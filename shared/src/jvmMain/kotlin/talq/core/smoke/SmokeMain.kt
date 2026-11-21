package talq.core.smoke

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
        val code = try {
            runBlocking { run(write) }
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

    private suspend fun run(write: Boolean) {
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
        check("bad password gives Unauthorized", try {
            TalkSession.open(credsA.copy(appPassword = "definitely-wrong")).close(); false
        } catch (e: TalkError.Unauthorized) { true } catch (e: TalkError) { println("    got $e"); false })

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
