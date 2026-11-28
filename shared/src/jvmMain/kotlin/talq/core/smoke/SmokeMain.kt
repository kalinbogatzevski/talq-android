package talq.core.smoke

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import talq.core.net.asObject
import talq.core.call.NotificationState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import talq.core.call.CallEndReason
import talq.core.call.CallSession
import talq.core.call.CallState
import talq.core.call.IceServer
import talq.core.call.LocalCandidate
import talq.core.call.MediaConnection
import talq.core.call.MediaEngine
import talq.core.call.PublishRights
import talq.core.call.RemoteStatus
import talq.core.call.SubscriberKey
import talq.core.call.CallMode
import talq.core.call.SentSignal
import talq.core.call.HpbConnection
import talq.core.call.HpbInbound
import talq.core.call.HpbState
import talq.core.call.helloV1
import talq.core.call.hpbWebSocketUrl
import talq.core.call.parseHpb
import talq.core.net.newHpbHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import talq.core.SharedItemType
import talq.core.TalkError
import talq.core.WebPushResult
import talq.core.files.MemorySink
import talq.core.files.MemorySource
import talq.core.model.ChatMessage
import talq.core.model.Credentials
import talq.core.repo.ChatScope
import talq.core.repo.FileSendState
import talq.core.repo.FileToSend
import talq.core.repo.TalkSession
import talq.core.shift.ErpBase
import talq.core.shift.ShiftState
import talq.core.shift.approveTarget
import talq.core.shift.buildShiftBatch
import talq.core.shift.sanitizeShiftLabel
import talq.core.shift.shiftEntryIsStale
import java.io.File
import kotlin.system.exitProcess

/**
 * Runs the shared core against a Nextcloud test server from a desktop JVM.
 *
 *   ./gradlew :shared:smoke                  checks that make no lasting changes (one of them is a
 *                                            deliberate wrong-password login, which counts toward the
 *                                            server's brute-force throttle for this IP)
 *   ./gradlew :shared:smoke --args=--write   also writes into the test room: ONE topic (through the
 *                                            outbox) with a reply, a General message, a reaction added
 *                                            and removed, ONE tiny file, a chunked upload that is
 *                                            removed again, and the room marked read
 *
 * Reads ~/.talq-test.env: TALQ_TEST_SERVER, TALQ_TEST_USERA / TALQ_TEST_USERA_PASS and
 * TALQ_TEST_USERB / TALQ_TEST_USERB_PASS (two accounts, app passwords), TALQ_TEST_TOKEN (a room
 * both are in). Passwords are never printed.
 */
object SmokeMain {
    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val write = "--write" in args
        val call = "--call" in args
        val code = try {
            runBlocking { run(write, call) }
            if (failures == 0) 0 else 1
        } catch (e: Throwable) {
            println("SMOKE ABORTED: ${e::class.simpleName}: ${e.message}")
            2
        }
        println(if (code == 0) "\nSMOKE OK" else "\nSMOKE FAILED ($failures failed checks)")
        exitProcess(code)
    }

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("  [${if (ok) "ok" else "FAIL"}] $name${if (detail.isNotEmpty()) "  -- $detail" else ""}")
    }

    private fun env(): Map<String, String> {
        val file = File(System.getProperty("user.home"), ".talq-test.env")
        require(file.exists()) { "missing ${file.path}" }
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .associate { it.substringBefore("=").trim().removePrefix("export ") to it.substringAfter("=").trim().trim('"', '\'') }
    }

    /** The pure rules of the shift light and of the ERP addresses. Nothing here asks any server. */
    private fun shiftRules() {
        check("state parsing is exact", ShiftState.fromWire("on_shift") == ShiftState.OnShift &&
            ShiftState.fromWire("on_break") == ShiftState.OnBreak && ShiftState.fromWire("off_shift") == ShiftState.OffShift &&
            ShiftState.fromWire("On_shift") == ShiftState.Unknown && ShiftState.fromWire(" on_shift") == ShiftState.Unknown &&
            ShiftState.fromWire(null) == ShiftState.Unknown && ShiftState.fromWire("") == ShiftState.Unknown)
        check("label: control characters become spaces, 24 at most",
            sanitizeShiftLabel("On\nshift\u007f!") == "On shift !" && sanitizeShiftLabel("x".repeat(40)).length == 24 &&
                sanitizeShiftLabel(null) == "")
        val many = listOf("b", "", "a", "b") + (1..150).map { "u$it" }
        val batch = buildShiftBatch(many)
        check("batch: no empties, no repeats, order kept, 100 at most",
            batch.size == 100 && batch.take(3) == listOf("b", "a", "u1") && batch.toSet().size == 100)
        check("staleness: 2 minutes, a future stamp is stale",
            shiftEntryIsStale(0, 1000) && shiftEntryIsStale(5000, 1000) && !shiftEntryIsStale(1000, 1000 + 119_999) &&
                shiftEntryIsStale(1000, 1000 + 120_000))
        check("ERP address: https only, loopback excepted",
            ErpBase.parse("https://erp.example/en/")?.url == "https://erp.example/en" &&
                ErpBase.parse("https://ERP.example:8443/en")?.host == "erp.example" &&
                ErpBase.parse("https://erp.example:8443/en")?.origin == "https://erp.example:8443" &&
                ErpBase.parse("http://erp.example") == null && ErpBase.parse("http://localhost:8080") != null &&
                ErpBase.parse("http://127.0.0.1/x") != null && ErpBase.parse("erp.example") == null &&
                ErpBase.parse("") == null && ErpBase.parse("https://erp.example@other.example/") == null &&
                ErpBase.parse("ftp://erp.example") == null)
        val base = ErpBase.parse("https://erp.example/en")!!
        val rel = approveTarget(base, "/en/cti/pair/abc")
        val abs = approveTarget(base, "https://ERP.example/en/cti/pair/abc")
        check("approval page: relative joins the ERP host; another host, another port and http are refused",
            rel?.url == "https://erp.example/en/cti/pair/abc" && abs?.host == "erp.example" &&
                approveTarget(base, "https://erp.example:443/x") != null &&
                approveTarget(base, "https://login.example/x") == null &&
                approveTarget(base, "https://erp.example:8443/x") == null &&
                approveTarget(ErpBase.parse("https://erp.example:8443/en")!!, "/x")?.url == "https://erp.example:8443/x" &&
                approveTarget(base, "http://erp.example/x") == null && approveTarget(base, "javascript:alert(1)") == null &&
                approveTarget(base, "https://erp.example@other.example/x") == null && approveTarget(base, "") == null &&
                approveTarget(base, "//other.example/x") == null)
    }

    private fun brief(m: ChatMessage): String =
        "#${m.id} thread=${m.threadId} isThread=${m.isThread} topicId=${m.topicId}" +
            (if (m.threadTitle.isNotEmpty()) " title='${m.threadTitle}'" else "") +
            (if (m.systemMessage.isNotEmpty()) " sys=${m.systemMessage}" else "") +
            (m.parent?.let { " parent=#${it.id}" } ?: "") +
            " ${m.actorId}: ${m.plainText.replace('\n', ' ').take(40)}"

    private suspend fun run(write: Boolean, call: Boolean) {
        val env = env()
        val server = env["TALQ_TEST_SERVER"]?.takeIf { it.isNotBlank() }
            ?: error("TALQ_TEST_SERVER is not set in ~/.talq-test.env (the test server's https address)")
        val token = env.getValue("TALQ_TEST_TOKEN")
        val credsA = Credentials(server, env.getValue("TALQ_TEST_USERA"), env.getValue("TALQ_TEST_USERA_PASS"))
        val credsB = Credentials(server, env.getValue("TALQ_TEST_USERB"), env.getValue("TALQ_TEST_USERB_PASS"))
        println("server=$server room=$token write=$write")

        println("\n== session (bot A)")
        val a = TalkSession.open(credsA)
        val caps = a.capabilities
        println("  user=${a.user.userId} '${a.user.displayName}'  nextcloud=${caps.serverVersion} talk=${caps.talkVersion}")
        println("  talk features=${caps.features.size} maxMessageLength=${caps.maxMessageLength}")
        println("  notifications ocs-endpoints=${caps.notificationFeatures} push=${caps.notificationPush}")
        check("talk installed", caps.hasTalk)
        check("threads capability", caps.threads)
        check("read status capability", caps.readStatus)

        println("\n== calls: settings and REST (read-only)")
        val sig = a.client.signalingSettings()
        check("signaling settings name an HPB", sig.server.startsWith("https://"))
        check("hello v1.0 ticket present", sig.helloTicket.isNotEmpty() && sig.helloUserId.isNotEmpty())
        check("ICE servers include TURN", sig.iceServers.any { s -> s.urls.any { it.startsWith("turn") } })
        check("settings toString hides the ticket", sig.helloTicket !in sig.toString())
        check("ICE server toString hides the TURN secret",
            sig.iceServers.filter { it.credential.isNotEmpty() }.all { it.credential !in it.toString() && it.username !in it.toString() })
        check("notification-state capability", caps.callNotificationState)
        println("  recording-consent = ${caps.recordingConsent}  calls enabled = ${caps.callsEnabled}  HPB nodes = ${1 + sig.servers.size}")
        check("no call running in the test room",
            a.client.callNotificationState(token) in setOf(NotificationState.Missed, NotificationState.Gone))
        check("bot A shares its read status", caps.sharesReadStatus)
        check("bad password gives Unauthorized", try {
            TalkSession.open(credsA.copy(appPassword = "definitely-wrong")).close(); false
        } catch (e: TalkError.Unauthorized) { true } catch (e: TalkError) { println("    got $e"); false })
        if (call) {
            hpbChecks(a, credsB, token)
            callSessionChecks(a, credsB, token)
            a.close()
            return
        }

        println("\n== rooms")
        check("rooms refresh", a.rooms.refresh(), a.rooms.state.value.error?.message ?: "")
        val rooms = a.rooms.rooms.value
        println("  ${rooms.size} rooms")
        for (r in rooms.take(8)) {
            println("  - ${r.token} type=${r.type} '${r.displayName}' unread=${r.unreadMessages} mention=${r.unreadMention}" +
                " lastRead=${r.lastReadMessage} last=#${r.lastMessage?.id ?: 0} lastTopic=${r.lastMessageTopicId}")
        }
        val room = a.rooms.room(token)
        check("test room is listed", room != null)

        println("\n== presence (one-to-one rooms of bot A)")
        val direct = rooms.filter { it.isOneToOne }
        println("  ${direct.size} one-to-one rooms; raw status keys of the first: " +
            "${direct.firstOrNull()?.raw?.keys?.filter { it.startsWith("status") }?.sorted()}")
        for (r in direct.take(10)) {
            println("  - ${r.token} partner='${r.partnerUserId}' status='${r.status}' icon='${r.statusIcon}'" +
                " message='${r.statusMessage.take(40)}' clearAt=${r.statusClearAt}")
        }
        check("one-to-one rooms name their partner", direct.all { it.partnerUserId.isNotEmpty() && it.partnerUserId == it.name })
        check("other rooms have no partner", rooms.filter { !it.isOneToOne }.all { it.partnerUserId.isEmpty() })
        // The status keys are optional: the server sends them only for a person who has a status.
        val b0 = TalkSession.open(credsB)
        check("rooms refresh (bot B)", b0.rooms.refresh(), b0.rooms.state.value.error?.message ?: "")
        val directB = b0.rooms.rooms.value.filter { it.isOneToOne }
        println("  bot B: ${directB.size} one-to-one rooms")
        for (r in directB.take(10)) {
            println("  - ${r.token} partner='${r.partnerUserId}' status='${r.status}' icon='${r.statusIcon}'" +
                " message='${r.statusMessage.take(40)}' clearAt=${r.statusClearAt}" +
                " statusKeys=${r.raw.keys.filter { it.startsWith("status") }.sorted()}")
        }
        check("bot B: one-to-one rooms name their partner", directB.all { it.partnerUserId.isNotEmpty() })
        b0.close()

        println("\n== shift rules (no network)")
        shiftRules()
        println("  avatar url: ${a.client.roomAvatarUrl(token, room?.avatarVersion ?: "")}")

        println("\n== history (raw client, newest 30)")
        val page = a.client.history(token, limit = 30)
        println("  ${page.messages.size} messages, lastGiven=${page.lastGiven}")
        page.messages.takeLast(12).forEach { println("  ${brief(it)}") }
        check("history is oldest-first", page.messages.zipWithNext().all { (x, y) -> x.id < y.id })
        println("  raw keys of newest: ${page.messages.lastOrNull()?.raw?.keys?.sorted()}")

        println("\n== topics")
        val topics = a.topics(token)
        check("topics refresh", topics.refresh(), topics.state.value.error?.message ?: "")
        println("  ${topics.topics.value.size} recent topics")
        for (t in topics.topics.value.take(6)) {
            println("  - #${t.id} '${t.title}' replies=${t.thread.numReplies} lastMessageId=${t.thread.lastMessageId}" +
                " notify=${t.attendee.notificationLevel} first=${t.first != null} last=${t.last != null}")
        }
        topics.topics.value.firstOrNull()?.let { t ->
            val one = a.client.thread(token, t.id)
            check("get one topic", one.id == t.id && one.title == t.title)
            val msgs = a.client.threadMessages(token, t.id, limit = 20)
            println("  topic #${t.id} messages (${msgs.messages.size}):")
            msgs.messages.takeLast(5).forEach { println("    ${brief(it)}") }
            check("topic fetch returns only that topic", msgs.messages.all { it.topicId == t.id || it.id == t.id })
        }
        val subscribed = a.client.subscribedThreads(limit = 20)
        println("  subscribed topics (all rooms): ${subscribed.size}")

        println("\n== repositories")
        val general = a.chat(token, ChatScope.General)
        check("general refresh", general.refresh(), general.state.value.error?.message ?: "")
        val gs = general.state.value
        println("  general: ${gs.messages.size} messages, hasOlder=${gs.hasOlder}")
        check("general holds no topic messages", gs.messages.none { it.topicId != 0L })
        val all = a.chat(token, ChatScope.All)
        check("whole chat refresh", all.refresh())
        println("  whole chat: ${all.state.value.messages.size} messages, hasOlder=${all.state.value.hasOlder}")
        if (all.state.value.hasOlder) {
            val before = all.state.value.messages.size
            check("load older", all.loadOlder())
            println("  whole chat after loadOlder: ${all.state.value.messages.size} (was $before)")
        }
        all.state.value.messages.lastOrNull { it.reactions.isNotEmpty() }?.let { m ->
            println("  reactions on #${m.id}: ${m.reactions} detail=${a.client.reactions(token, m.id).mapValues { it.value.size }}")
        }

        println("\n== notifications + web push")
        val notifications = a.client.notifications()
        println("  ${notifications.size} notifications")
        notifications.take(3).forEach { println("  - #${it.id} app=${it.app} type=${it.objectType} object=${it.objectId} '${it.subject.take(50)}'") }
        notifications.firstOrNull()?.let { check("get notification by id", a.client.notification(it.id).id == it.id) }
        when (val v = a.client.webPushVapidKey()) {
            is WebPushResult.Ok -> check("vapid key", v.value.length > 40, "${v.value.length} chars")
            WebPushResult.Disabled -> check("vapid key: web push disabled by admin (typed)", true)
            WebPushResult.Unsupported -> check("vapid key: server has no web push (typed)", true)
            is WebPushResult.Rejected -> check("vapid key", false, "${v.status} ${v.reason}")
        }
        // Registering with a deliberately invalid key: proves the typed error path without
        // creating a subscription. Disabled / Rejected are both fine; a crash is not.
        val reg = a.client.webPushRegister("https://push.invalid/endpoint", "not-a-key", "not-auth")
        println("  register with invalid key -> $reg")
        check("register failure is typed", reg is WebPushResult.Disabled || reg is WebPushResult.Rejected || reg is WebPushResult.Unsupported)

        println("\n== files (read-only)")
        val att = caps.attachments
        println("  attachments: allowed=${att.allowed} folder='${att.folder}' conversationSubfolders=${att.conversationSubfolders}" +
            " mediaCaption=${caps.mediaCaption} sharedItems=${caps.sharedItems}")
        check("auth header only for the own server",
            a.files.authorizationFor("$server/remote.php/dav/files/x") != null &&
                a.files.authorizationFor("https://example.org/remote.php/dav/files/x") == null &&
                a.files.authorizationFor(server.replace("https://", "http://") + "/x") == null)
        val overview = a.files.sharedOverview(token)
        println("  shared items overview: ${overview.byType.map { "${it.key.key}=${it.value.size}" }}")
        check("shared items overview", true)
        for (type in listOf(SharedItemType.MEDIA, SharedItemType.FILE, SharedItemType.VOICE)) {
            val first = a.files.sharedItems(token, type, limit = 5)
            println("  ${type.key}: ${first.messages.size} on page 1, nextCursor=${first.nextCursor}  " +
                first.messages.take(3).map { "#${it.id} ${it.fileName} ${it.fileMimetype} ${it.fileSize}B voice=${it.isVoiceMessage}" })
            check("${type.key} page is newest first", first.messages.zipWithNext().all { (x, y) -> x.id > y.id })
            if (first.nextCursor != null) {
                val second = a.files.sharedItems(token, type, first.nextCursor, limit = 5)
                check("${type.key} page 2 is older than page 1",
                    second.messages.isEmpty() || second.messages.first().id < first.messages.last().id,
                    "${second.messages.size} items, nextCursor=${second.nextCursor}")
            }
        }

        if (write) writeChecks(a, credsB, token)

        a.close()
    }

    /**
     * In a ONE-TO-ONE room Talk turns a reaction into the room's last message and counts it as
     * unread (a group room does neither; seen live 2026-11-22). Reading up to the newest visible
     * message must still clear it. Uses the bots' own one-to-one conversation.
     */
    private suspend fun directReactionCheck(a: TalkSession, b: TalkSession) {
        println("\n== WRITE: a reaction in a one-to-one room does not stay unread")
        val dm = a.client.ocs(
            HttpMethod.Post, "apps/spreed/api/v4/room",
            body = buildJsonObject { put("roomType", 1); put("invite", b.user.userId) },
        ).data.asObject()?.get("token")?.jsonPrimitive?.content.orEmpty()
        check("bots' one-to-one room", dm.isNotEmpty())
        if (dm.isEmpty()) return
        val mine = a.client.sendMessage(dm, "Smoke run: a message to react to.")
        a.client.markRead(dm, mine.id)
        b.client.addReaction(dm, mine.id, "🎉")
        delay(1000)
        a.rooms.refresh()
        val before = a.rooms.room(dm)
        check("the reaction counts as unread in a one-to-one room (the server's behaviour)",
            before?.unreadMessages == 1 && before.lastMessage?.isSystem == true,
            "unread=${before?.unreadMessages} last=${before?.lastMessage?.systemMessage}")
        // What the app does when the chat is open at the bottom: the newest VISIBLE message is
        // A's own one (the reaction is folded into it).
        val tracker = a.unread(dm)
        tracker.refresh()
        tracker.markSeen(ChatScope.All, mine.id)
        a.rooms.refresh()
        val after = a.rooms.room(dm)
        check("reading to the bottom clears a reaction-only unread", after?.unreadMessages == 0,
            "unread=${after?.unreadMessages} lastRead=${after?.lastReadMessage} last=${after?.lastMessage?.id}")
    }

    /**
     * The call backend's signaling socket (HPB), with both bots in their own room: hello, the
     * room join and the join/leave events. Joins the room's session (as the app does before a
     * call) and leaves it again; starts no call.
     */
    private suspend fun hpbChecks(a: TalkSession, credsB: Credentials, token: String) = coroutineScope {
        println("\n== calls: HPB signaling (bots' room, no call)")
        check("ws url", hpbWebSocketUrl("https://h.example/standalone-signaling/") == "wss://h.example/standalone-signaling/spreed")
        check("ws url keeps one /spreed", hpbWebSocketUrl("https://h.example/sig/spreed") == "wss://h.example/sig/spreed")
        check("ws url from http", hpbWebSocketUrl("http://h.example/sig") == "ws://h.example/sig/spreed")
        check("parse error", parseHpb("""{"type":"error","error":{"code":"no_such_session","message":"gone"}}""") ==
            HpbInbound.Error("no_such_session", "gone"))
        check("parse bye", parseHpb("""{"type":"bye","bye":{}}""") == HpbInbound.Bye)
        check("parse garbage does not throw", parseHpb("not json") is HpbInbound.Other)
        // JSON the reader itself takes (it refuses at 400 levels): only parseHpb's own limit turns it down.
        val deep = "[".repeat(100) + "]".repeat(100)
        check("parse: a frame nested too deep", parseHpb("""{"type":"bye","bye":{},"x":$deep}""") == HpbInbound.Other("invalid"))
        check("parse: a text nested too deep",
            parseHpb("""{"type":"message","message":{"data":"{\"type\":\"offer\",\"x\":$deep}"}}""") == HpbInbound.Other("message"))
        val brackets = "[".repeat(200)
        check("parse: brackets and a quote in a text are no nesting",
            parseHpb("""{"type":"message","message":{"data":{"type":"offer","payload":{"sdp":"a\"$brackets"}}}}""") is HpbInbound.Signal)
        val users = """[{"sessionId":"s","inCall":"\"5"}]"""
        check("parse: a text with a quote for a number",
            parseHpb("""{"type":"event","event":{"target":"participants","type":"update","update":{"all":"\"1","users":$users}}}""") is
                HpbInbound.ParticipantsUpdate)
        check("hello carries the ticket", "\"ticket\":\"t1\"" in helloV1("https://nc/backend", "u1", "t1"))

        val b = TalkSession.open(credsB)
        val http = newHpbHttpClient()
        val hpbA = HpbConnection(http, a.client.signalingSettings(), a.client.serverUrl, this)
        val hpbB = HpbConnection(http, b.client.signalingSettings(), b.client.serverUrl, this)
        try {
            val idA = hpbA.connect()
            val idB = hpbB.connect()
            check("hello gives session ids", idA.isNotEmpty() && idB.isNotEmpty() && idA != idB)
            val ready = hpbA.state.value as? HpbState.Ready
            println("  HPB features = ${ready?.features?.sorted()}")
            check("HPB advertises mcu", ready != null && "mcu" in ready.features)

            hpbA.joinRoom(token, a.client.joinRoomSession(token))
            // events has no replay: listen before B joins.
            val seesJoin = async(start = CoroutineStart.UNDISPATCHED) {
                awaitEvent(hpbA, 10_000) { it is HpbInbound.RoomJoined && it.entries.any { e -> e.sessionId == idB } }
            }
            hpbB.joinRoom(token, b.client.joinRoomSession(token))
            check("A sees B join the room", seesJoin.await())

            val seesLeave = async(start = CoroutineStart.UNDISPATCHED) {
                awaitEvent(hpbA, 10_000) { it is HpbInbound.RoomLeft && idB in it.sessionIds }
            }
            hpbB.leaveRoom()
            check("A sees B leave the room", seesLeave.await())

            // A quiet socket must stay up: no request timeout, the pings keep it alive.
            val dropped = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(40_000) { hpbA.state.first { it !is HpbState.Ready } }
            }
            check("stays connected while idle (40 s)",
                dropped.await() == null && (hpbA.state.value as? HpbState.Ready)?.sessionId == idA,
                "state=${hpbA.state.value::class.simpleName}")
        } finally {
            hpbA.close(sayBye = true)
            hpbB.close(sayBye = true)
            runCatching { a.client.leaveRoomSession(token) }
            runCatching { b.client.leaveRoomSession(token) }
            http.close()
            b.close()
        }
        check("closed", hpbA.state.value == HpbState.Disconnected && hpbB.state.value == HpbState.Disconnected)
    }

    private suspend fun awaitEvent(hpb: HpbConnection, millis: Long, match: (HpbInbound) -> Boolean): Boolean =
        withTimeoutOrNull(millis) { hpb.events.first { match(it) } } != null

    /**
     * A one-to-one call between the bots in their own room, signaling only: A calls, B answers,
     * B hangs up. The media is a stand-in whose canned SDP the media server refuses (its errors
     * are logged, not checked). Rings nobody but the two bots.
     */
    private suspend fun callSessionChecks(a: TalkSession, credsB: Credentials, token: String) = coroutineScope {
        println("\n== calls: call session (stand-in media, bots' room)")
        val b = TalkSession.open(credsB)
        val http = newHpbHttpClient()
        val before = runCatching { a.client.callPeers(token) }.getOrNull()
        check("nobody in the room's call before", before?.isEmpty() == true, "in call: ${before?.size}")
        if (before?.isNotEmpty() == true) {
            runCatching { a.client.leaveCall(token) }
            runCatching { b.client.leaveCall(token) }
        }
        val clock = { System.currentTimeMillis() }
        val sessionA = CallSession(a.client, http, token, CallMode.OneToOne(b.user.userId), PublishRights.ALL, StubEngine(), this, clock, log = { println("    A: $it") })
        val sessionB = CallSession(b.client, http, token, CallMode.OneToOne(a.user.userId), PublishRights.ALL, StubEngine(), this, clock, log = { println("    B: $it") })
        try {
            launch { sessionA.start(video = false) }
            check("A waits for the peer", awaitState(sessionA, 15_000) { it is CallState.WaitingForPeer },
                "state=${sessionA.state.value}")
            val idA = sessionA.ownSessionId.value
            check("A's publisher offer went to A's own session",
                idA.isNotEmpty() && sessionA.sent.replayCache.filter { it.type == "offer" }.map { it.to } == listOf(idA))
            launch { sessionB.answer(video = false) }
            check("A requested B's stream", awaitSent(sessionA, 20_000) {
                it.type == "requestoffer" && it.to.isNotEmpty() && it.to == sessionB.ownSessionId.value
            })
            sessionB.hangUp()
            check("A sees B hang up", awaitState(sessionA, 10_000) { it == CallState.Ended(CallEndReason.PeerHungUp) },
                "state=${sessionA.state.value}")
            sessionA.hangUp() // waits for A's own teardown
            val emptied = withTimeoutOrNull(10_000) {
                while (runCatching { a.client.callPeers(token) }.getOrNull()?.isEmpty() != true) delay(1_000)
                true
            } == true
            check("no call left in the room", emptied && a.client.callNotificationState(token) != NotificationState.Ringing)
        } finally {
            sessionA.hangUp()
            sessionB.hangUp()
        }
        groupCallChecks(a, b, http, token)
        http.close()
        b.close()
    }

    /**
     * The same two bots in GROUP mode: A starts, B joins; A lists B as its one participant and
     * asks for B's media; B leaves and A stays in the call, waiting for others; then A leaves.
     */
    private suspend fun groupCallChecks(a: TalkSession, b: TalkSession, http: io.ktor.client.HttpClient, token: String) = coroutineScope {
        println("\n== calls: group call session (stand-in media, bots' room)")
        val clock = { System.currentTimeMillis() }
        val sessionA = CallSession(a.client, http, token, CallMode.Group, PublishRights.ALL, StubEngine(), this, clock, log = { println("    A: $it") })
        val sessionB = CallSession(b.client, http, token, CallMode.Group, PublishRights.ALL, StubEngine(), this, clock, log = { println("    B: $it") })
        try {
            launch { sessionA.start(video = false) }
            check("group: A waits for others", awaitState(sessionA, 15_000) { it is CallState.WaitingForPeer },
                "state=${sessionA.state.value}")
            launch { sessionB.answer(video = false) }
            val listed = withTimeoutOrNull(20_000) {
                sessionA.participants.first { list -> list.any { it.sid == sessionB.ownSessionId.value && it.sid.isNotEmpty() } }
            } != null
            check("group: A lists B as a participant", listed, "participants=${sessionA.participants.value.size}")
            check("group: A requested B's stream", awaitSent(sessionA, 20_000) {
                it.type == "requestoffer" && it.to.isNotEmpty() && it.to == sessionB.ownSessionId.value
            })
            check("group: no hang-up hint is sent", sessionB.sent.replayCache.none { it.type == "hangup" })
            sessionB.hangUp()
            val gone = withTimeoutOrNull(15_000) { sessionA.participants.first { it.isEmpty() } } != null
            check("group: B's tile goes when B leaves", gone, "participants=${sessionA.participants.value.size}")
            check("group: A stays in the call", sessionA.state.value !is CallState.Ended, "state=${sessionA.state.value}")
            sessionA.hangUp()
            val emptied = withTimeoutOrNull(10_000) {
                while (runCatching { a.client.callPeers(token) }.getOrNull()?.isEmpty() != true) delay(1_000)
                true
            } == true
            check("group: no call left in the room", emptied)
        } finally {
            sessionA.hangUp()
            sessionB.hangUp()
        }
    }

    private suspend fun awaitState(s: CallSession, millis: Long, match: (CallState) -> Boolean): Boolean =
        withTimeoutOrNull(millis) { s.state.first { match(it) } } != null

    private suspend fun awaitSent(s: CallSession, millis: Long, match: (SentSignal) -> Boolean): Boolean =
        withTimeoutOrNull(millis) { s.sent.first { match(it) } } != null

    /** Media for signaling checks: a canned SDP that the media server refuses, no candidates. */
    private class StubEngine : MediaEngine {
        override suspend fun createPublisherOffer(iceServers: List<IceServer>, rights: PublishRights, video: Boolean): String = CANNED_SDP
        override suspend fun setPublisherAnswer(sdp: String) {}
        override fun closePublisher() {}
        override suspend fun answerSubscriber(key: SubscriberKey, offerSdp: String, iceServers: List<IceServer>): String = CANNED_SDP
        override fun closeSubscriber(key: SubscriberKey) {}
        override fun addRemoteCandidate(key: SubscriberKey?, candidate: String, sdpMid: String?, sdpMLineIndex: Int) {}
        override fun setLocalAudio(enabled: Boolean) {}
        override fun setLocalVideo(enabled: Boolean) {}
        override fun sendStatus(type: String) {}
        override val localCandidates: Flow<LocalCandidate> = emptyFlow()
        override val publisher = MutableStateFlow(MediaConnection.New)
        override val subscribers = MutableStateFlow<Map<SubscriberKey, MediaConnection>>(emptyMap())
        override val remoteStatus: Flow<RemoteStatus> = emptyFlow()
        override fun rebuild() {}
        override fun close() {}

        companion object {
            const val CANNED_SDP = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"
        }
    }

    private suspend fun writeChecks(a: TalkSession, credsB: Credentials, token: String) {
        println("\n== WRITE: topic round trip in $token")
        val b = TalkSession.open(credsB)
        val title = "smoke " + (1000..9999).random()

        // Bot A watches the whole room and General live while the writes happen.
        val allA = a.chat(token, ChatScope.All)
        val generalA = a.chat(token, ChatScope.General)
        allA.start(); generalA.start()
        withTimeoutOrNull(15_000) { generalA.state.first { it.loaded } }
        delay(1500)

        // The draft-topic path: the first message goes through the outbox with a title, and
        // the outbox reports the id of the topic the server made.
        val draftKey = allA.send("Smoke run: first post of the topic.", threadTitle = title)
        check("draft send is queued with its title", allA.state.value.outbox.any { it.referenceId == draftKey && it.threadTitle == title })
        val rootId = withTimeoutOrNull(20_000) { allA.createdTopics.first { draftKey in it } }?.get(draftKey) ?: 0L
        check("outbox reported the created topic id", rootId > 0,
            allA.state.value.outbox.firstOrNull { it.referenceId == draftKey }?.error?.message ?: "")
        if (rootId == 0L) { allA.stop(); generalA.stop(); b.close(); return }
        check("draft send left the outbox", allA.state.value.outbox.none { it.referenceId == draftKey })
        val info = a.topics(token).topic(rootId) ?: a.client.thread(token, rootId)
        check("the new topic is listed locally at once", a.topics(token).topic(rootId) != null)
        check("the root is in the whole chat as a topic message",
            allA.state.value.messages.firstOrNull { it.id == rootId }?.topicId == rootId)
        println("  A created topic #$rootId '${info.title}'  first=${info.first?.let { brief(it) }}")
        println("  root raw: ${info.first?.raw?.filterKeys { it in setOf("id", "threadId", "isThread", "threadTitle", "threadReplies", "metaData") }}")
        check("create returns the new topic", info.title == title && rootId > 0)

        // Read status: nobody but A has seen the new root yet.
        check("A's root is not read by everyone before B saw it", allA.commonRead.value < rootId,
            "commonRead=${allA.commonRead.value} root=$rootId")

        val topicB = b.chat(token, ChatScope.Topic(rootId))
        check("B loads the topic", topicB.refresh())
        val key = topicB.send("Smoke run: reply inside the topic.")
        val confirmed = withTimeoutOrNull(20_000) {
            topicB.state.first { s -> s.outbox.none { it.referenceId == key } || s.outbox.any { it.referenceId == key && it.error != null } }
        }
        val reply = topicB.state.value.messages.lastOrNull { it.referenceId == key }
        check("B's reply was confirmed and left the outbox", confirmed != null && reply != null,
            topicB.state.value.outbox.firstOrNull()?.error?.message ?: "")
        println("  B reply: ${reply?.let { brief(it) }}")
        println("  reply raw: ${reply?.raw?.filterKeys { it in setOf("id", "threadId", "isThread", "threadTitle", "metaData", "parent") }?.mapValues { if (it.key == "parent") "<object>" else it.value.toString() }}")
        val replyId = reply?.id ?: 0L

        delay(4000) // let A's long-poll deliver both messages

        // B's reply moved B's read marker past A's root: the point everyone has read follows,
        // and A's live feed (the long-poll with lastCommonReadId) carries it.
        check("the read-by-everyone point passed A's root once B replied", allA.commonRead.value >= rootId,
            "commonRead=${allA.commonRead.value} root=$rootId")

        val topicMsgs = a.client.threadMessages(token, rootId)
        println("  topic fetch (threadId=$rootId): ${topicMsgs.messages.map { brief(it) }}")
        check("topic fetch has root and reply", topicMsgs.messages.any { it.id == rootId } && topicMsgs.messages.any { it.id == replyId })

        val main = a.client.history(token, limit = 30)
        val rootInMain = main.messages.firstOrNull { it.id == rootId }
        val replyInMain = main.messages.firstOrNull { it.id == replyId }
        println("  main chat fetch (no threadId), newest 6:")
        main.messages.takeLast(6).forEach { println("    ${brief(it)}") }
        println("  ANSWER topic root appears in main chat fetch: ${rootInMain != null}")
        println("  ANSWER topic reply appears in main chat fetch: ${replyInMain != null}")
        println("  ANSWER root markers:  isThread=${rootInMain?.isThread} threadId=${rootInMain?.threadId} threadTitle='${rootInMain?.threadTitle}' threadReplies=${rootInMain?.threadReplies}")
        println("  ANSWER reply markers: isThread=${replyInMain?.isThread} threadId=${replyInMain?.threadId} parent=${replyInMain?.parent?.id}")
        val plain = main.messages.lastOrNull { !it.isThread && it.systemMessage.isEmpty() }
        println("  ANSWER plain (no topic) message markers: ${plain?.let { "id=${it.id} isThread=${it.isThread} threadId=${it.threadId} hasThreadIdKey=${it.raw.containsKey("threadId")}" }}")

        val recent = a.client.recentThreads(token)
        val listed = recent.firstOrNull { it.id == rootId }
        check("new topic is in threads/recent", listed != null)
        println("  recent entry: replies=${listed?.thread?.numReplies} lastMessageId=${listed?.thread?.lastMessageId} last=${listed?.last?.id}")

        // What the live feed did with it.
        val liveAll = allA.state.value.messages
        val liveGeneral = generalA.state.value.messages
        check("A's live whole-chat view received root and reply", liveAll.any { it.id == rootId } && liveAll.any { it.id == replyId })
        check("A's live General view excluded root and reply", liveGeneral.none { it.id == rootId || it.id == replyId })
        val liveTopic = a.topics(token).topic(rootId)
        println("  A's live topic entry: replies=${liveTopic?.thread?.numReplies} last=#${liveTopic?.last?.id}")
        check("A's topic list followed the reply live", liveTopic?.thread?.lastMessageId == replyId)
        println("  system messages seen live: ${liveAll.filter { it.id >= rootId && it.isSystem }.map { brief(it) }}")

        println("\n== WRITE: per-topic unread + reactions")
        a.rooms.refresh()
        val unread = a.unread(token)
        check("unread scan", unread.refresh())
        println("  A unread by topic after B's reply: ${unread.state.value.byTopic} complete=${unread.state.value.complete}")
        check("B's reply counts as unread in its topic only",
            unread.state.value.of(rootId).count == 1 && unread.state.value.of(0L).count == 0)
        val generalKey = b.chat(token, ChatScope.General).send("Smoke run: message outside any topic.")
        delay(5000)
        val generalId = allA.state.value.messages.lastOrNull { it.referenceId == generalKey }?.id ?: 0L
        check("live feed counted B's General message", generalId > 0 && unread.state.value.of(0L).count == 1)
        unread.markSeen(ChatScope.General, generalId)
        val afterGeneral = a.rooms.room(token)?.lastReadMessage ?: 0L
        println("  after reading General: byTopic=${unread.state.value.byTopic} marker=$afterGeneral (reply=#$replyId general=#$generalId)")
        check("reading General cleared General but kept the topic unread, marker stayed before the topic's reply",
            unread.state.value.of(0L).count == 0 && unread.state.value.of(rootId).count == 1 && afterGeneral < replyId)
        unread.markSeen(ChatScope.Topic(rootId), replyId)
        a.rooms.refresh()
        val afterTopic = a.rooms.room(token)?.lastReadMessage ?: 0L
        println("  after reading the topic: byTopic=${unread.state.value.byTopic} server marker=$afterTopic")
        check("with nothing unread left the server marker moved to the newest read message", afterTopic == generalId)

        val thumb = String(Character.toChars(0x1F44D))
        val reactErr = allA.toggleReaction(replyId, thumb)
        val reacted = a.client.reactions(token, replyId)
        check("add reaction", reactErr == null && reacted.keys.any { it == thumb }, reactErr?.message ?: "")
        check("reaction shows on the message as own",
            allA.state.value.messages.firstOrNull { it.id == replyId }?.reactionsSelf?.contains(thumb) == true)
        val unreactErr = allA.toggleReaction(replyId, thumb)
        check("remove reaction", unreactErr == null && a.client.reactions(token, replyId).isEmpty(), unreactErr?.message ?: "")

        fileChecks(a, b, token, rootId)

        check("mark read", allA.markRead())
        allA.stop(); generalA.stop()
        directReactionCheck(a, b)
        b.close()
    }

    /** ONE tiny file through the outbox into the smoke topic, read back by both bots. */
    private suspend fun fileChecks(a: TalkSession, b: TalkSession, token: String, topicId: Long) {
        println("\n== WRITE: one file into topic #$topicId")
        val bytes = "TalQ smoke file ${System.currentTimeMillis()}\n".encodeToByteArray()
        val caption = "Smoke run: a file with a caption."
        val allA = a.chat(token, ChatScope.All)
        val outbox = a.fileOutbox(token)
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        val watcher = a.launch {
            outbox.entries.collect { list -> list.firstOrNull()?.let { seen.add(it.state::class.simpleName ?: "?") } }
        }
        val key = outbox.send(listOf(FileToSend(MemorySource(bytes), "talq-smoke.txt", "text/plain")), caption = caption, threadId = topicId).single()
        check("file is queued in the outbox", outbox.entries.value.any { it.referenceId == key && it.threadId == topicId && it.caption == caption })
        withTimeoutOrNull(60_000) { outbox.entries.first { list -> list.none { it.referenceId == key } || list.any { it.referenceId == key && it.failed } } }
        watcher.cancel()
        val left = outbox.entries.value.firstOrNull { it.referenceId == key }
        println("  outbox states seen: ${seen.distinct()}")
        check("file left the outbox", left == null, (left?.state as? FileSendState.Failed)?.error?.message ?: left?.state.toString())
        if (left != null) return

        val msg = allA.state.value.messages.lastOrNull { it.referenceId == key }
        check("the file message is in the chat with our reference id", msg != null)
        val fetched = a.client.threadMessages(token, topicId).messages.lastOrNull { it.referenceId == key }
        check("the topic fetch has the file message", fetched != null)
        val m = fetched ?: msg ?: return
        println("  ${brief(m)}")
        println("  message='${m.message}' type=${m.messageType} sys=${m.systemMessage}")
        println("  file object: ${m.file?.raw}")
        check("message has a file parameter", m.file != null)
        check("file message is in the topic", m.isThread && m.threadId == topicId && m.topicId == topicId, "isThread=${m.isThread} threadId=${m.threadId}")
        check("caption came through", m.caption == caption, "'${m.caption}'")
        check("file helpers", m.fileName.startsWith("talq-smoke") && m.fileSize == bytes.size.toLong() && !m.isImage && !m.isVideo && !m.isVoiceMessage,
            "name=${m.fileName} size=${m.fileSize} mime=${m.fileMimetype}")
        val file = m.file ?: return

        val sinkA = MemorySink()
        var progress = 0L
        a.files.download(file, sinkA) { received, _ -> progress = received }
        check("A downloads the same bytes", sinkA.toByteArray().contentEquals(bytes), "${sinkA.toByteArray().size} bytes, progress=$progress")
        val byId = a.files.pathOfFile(file.fileId)
        check("lookup by file id finds a path", byId?.endsWith(m.fileName) == true, byId ?: "null")

        val forB = b.client.threadMessages(token, topicId).messages.lastOrNull { it.referenceId == key }?.file
        println("  as B: path='${forB?.path}' link='${forB?.link}'")
        if (forB != null) {
            val sinkB = MemorySink()
            val err = try { b.files.download(forB, sinkB); null } catch (e: TalkError) { e }
            check("B (a recipient) downloads the same bytes", err == null && sinkB.toByteArray().contentEquals(bytes), err?.message ?: "")
        } else check("B sees the file message", false)

        val overview = a.files.sharedOverview(token)
        println("  shared items overview: ${overview.byType.map { "${it.key.key}=${it.value.size}" }}")
        check("the file is in the shared items", overview.byType.values.any { list -> list.any { it.id == m.id } })

        // The chunked path, without posting anything: a few bytes in three chunks into the
        // upload folder, read back, removed again.
        println("\n== WRITE: chunked upload (not posted, removed again)")
        val big = ByteArray(40) { (97 + it % 26).toByte() }
        try {
            val up = a.files.upload(token, MemorySource(big), "talq-smoke-chunked.txt", chunkThreshold = 0, chunkSize = 16)
            println("  uploaded to '${up.path}' draft=${up.draft}")
            val back = MemorySink()
            a.files.downloadUrl(a.files.davUrl(up.path), back)
            check("chunked upload assembles to the same bytes", back.toByteArray().contentEquals(big), "${back.toByteArray().size} bytes")
            a.files.discardUpload(up)
            val gone = try { a.files.downloadUrl(a.files.davUrl(up.path), MemorySink()); false } catch (e: TalkError.NotFound) { true }
            check("discarded upload is gone", gone)
        } catch (e: TalkError) {
            check("chunked upload", false, e.message ?: "")
        }
    }
}
