package talq.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import talq.core.call.CallPeer
import talq.core.call.IceServer
import talq.core.call.NotificationState
import talq.core.call.SignalingSettings
import talq.core.model.ChatMessage
import talq.core.model.ChatPage
import talq.core.model.Credentials
import talq.core.model.MENTION_FETCH_LIMIT
import talq.core.model.MentionCandidate
import talq.core.model.isMentionable
import talq.core.model.OwnStatus
import talq.core.model.PersonMatch
import talq.core.model.PredefinedStatus
import talq.core.model.ReactionActor
import talq.core.model.Room
import talq.core.model.RoomBot
import talq.core.model.RoomParticipant
import talq.core.model.ScheduledMessage
import talq.core.model.TalkCapabilities
import talq.core.model.TalkNotification
import talq.core.model.TalkThreadInfo
import talq.core.model.UserInfo
import talq.core.net.TalkJson
import talq.core.net.asArray
import talq.core.net.asObject
import talq.core.net.arr
import talq.core.net.strings
import talq.core.net.int
import talq.core.net.long
import talq.core.net.newTalkHttpClient
import talq.core.net.obj
import talq.core.net.str

/** Result of the web push calls. "Disabled by the admin" is an expected state, not an error. */
sealed interface WebPushResult<out T> {
    data class Ok<T>(val value: T) : WebPushResult<T>

    /** 403 `WEBPUSH_DISABLED`: the admin setting `webpush_enabled` is off. */
    data object Disabled : WebPushResult<Nothing>

    /** The notifications app on this server has no web push endpoints (404). */
    data object Unsupported : WebPushResult<Nothing>

    /** The server refused the request: `INVALID_ENDPOINT`, `INVALID_ACTIVATION_TOKEN`, ... */
    data class Rejected(val status: Int, val reason: String) : WebPushResult<Nothing>
}

enum class WebPushRegistration {
    /** 201: stored, the server now pushes an `activationToken` to the endpoint. */
    CREATED_NEEDS_ACTIVATION,

    /** 200: this exact subscription was already registered and active. */
    ALREADY_ACTIVE,
}

/** A raw OCS answer: HTTP status, the `ocs.data` element and the response headers. */
class OcsResponse(val status: Int, val data: JsonElement, val message: String, val headers: Headers) {
    val isSuccess: Boolean get() = status in 200..299

    /** `data.error` / `data.message` / `meta.message`, whichever the server filled in. */
    val detail: String
        get() = data.asObject()?.let { it.str("error").ifEmpty { it.str("message") } }?.ifEmpty { null } ?: message
}

/**
 * The Talk HTTP API for one account on one server. Stateless apart from the credentials: every
 * function is one request. Throws [TalkError]; never returns partially parsed data.
 *
 * API versions are not interchangeable: rooms are v4, chat/threads/reactions are v1,
 * notifications are v2.
 */
class TalkClient(
    serverUrl: String,
    val loginName: String,
    private val appPassword: String,
    /** Null: this client sends nothing, every request is a [TalkError.Network] (see `TalkSession.unchecked`). */
    private val engine: HttpClient? = newTalkHttpClient(),
) {
    constructor(credentials: Credentials, engine: HttpClient? = newTalkHttpClient()) :
        this(credentials.server, credentials.loginName, credentials.appPassword, engine)

    private val http: HttpClient
        get() = engine ?: throw TalkError.Network(IllegalStateException("the account is not checked yet"))

    val serverUrl: String = normalizeServerUrl(serverUrl)

    /** `Authorization` header value, for image loaders that fetch avatars and previews. */
    val authorizationHeader: String =
        "Basic " + kotlin.io.encoding.Base64.Default.encode("$loginName:$appPassword".encodeToByteArray())

    fun close() {
        engine?.close()
    }

    // ---------------------------------------------------------------- transport

    /**
     * One OCS request. Returns the answer for ANY status; only transport failures throw.
     * Use [ocs] unless non-2xx statuses carry meaning for the caller. [once]: the HTTP engine must not
     * put the request on the wire a second time by itself (see [OnceJson]).
     */
    suspend fun ocsRaw(
        method: HttpMethod,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: JsonObject? = null,
        timeoutMillis: Long? = null,
        once: Boolean = false,
    ): OcsResponse {
        val response = try {
            http.request("$serverUrl/ocs/v2.php/${path.trimStart('/')}") {
                this.method = method
                header("OCS-APIRequest", "true")
                header("Accept", "application/json")
                header("Authorization", authorizationHeader)
                // A list repeats its key (`shareTypes[]=0&shareTypes[]=1`).
                for ((k, v) in query) {
                    if (v is Iterable<*>) v.forEach { if (it != null) parameter(k, it) } else if (v != null) parameter(k, v)
                }
                if (body != null) setBody(if (once) OnceJson(body.toString()) else TextContent(body.toString(), ContentType.Application.Json))
                if (timeoutMillis != null) timeout {
                    requestTimeoutMillis = timeoutMillis
                    socketTimeoutMillis = timeoutMillis
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw TalkError.Network(e)
        }
        val status = response.status.value
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw TalkError.Network(e)
        }
        var data: JsonElement = JsonNull
        var message = ""
        if (text.isNotBlank()) {
            val root = try {
                TalkJson.parseToJsonElement(text).asObject()
            } catch (e: Exception) {
                if (status in 200..299) throw TalkError.Protocol("not JSON (HTTP $status)", e)
                null
            }
            val ocs = root?.obj("ocs")
            if (ocs != null) {
                data = ocs["data"] ?: JsonNull
                message = ocs.obj("meta")?.str("message") ?: ""
            } else if (root != null) {
                message = root.str("message")
            }
        }
        return OcsResponse(status, data, message, response.headers)
    }

    /** One OCS request; any status outside 2xx (and 304 when [allowNotModified]) throws. */
    suspend fun ocs(
        method: HttpMethod,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: JsonObject? = null,
        timeoutMillis: Long? = null,
        allowNotModified: Boolean = false,
        once: Boolean = false,
    ): OcsResponse {
        val r = ocsRaw(method, path, query, body, timeoutMillis, once)
        if (r.isSuccess || (allowNotModified && r.status == 304)) return r
        throw errorFor(r)
    }

    private fun errorFor(r: OcsResponse): TalkError = errorFor(r.status, r.detail)

    /** The HTTP client, for the file transfers in [TalkFiles] (same engine, same user agent). */
    internal val httpClient: HttpClient get() = http

    private fun OcsResponse.objectData(what: String): JsonObject =
        data.asObject() ?: throw TalkError.Protocol("$what: expected an object")

    /**
     * The list in a 2xx answer. An empty body or somebody else's JSON (a proxy, a gateway) is NOT an empty
     * list: read as one, an unsent text counts as provably not in the chat and goes a second time, and the
     * drafts of every conversation "no longer listed" go (desktop's `probeToken` asks for the same).
     */
    private fun OcsResponse.list(what: String): JsonArray =
        data as? JsonArray ?: throw TalkError.Protocol("$what: expected a list")

    private fun OcsResponse.messages(): List<ChatMessage> =
        list("messages").mapNotNull { it.asObject() }.map { ChatMessage.fromJson(it) }.sortedBy { it.id }

    private fun OcsResponse.threads(): List<TalkThreadInfo> =
        list("topics").mapNotNull { it.asObject() }.map { TalkThreadInfo.fromJson(it) }

    // ---------------------------------------------------------------- calls

    /**
     * The signaling server (HPB), hello v1.0 credentials and STUN/TURN servers for this user.
     * The STUN fallback is Nextcloud's public one, as the Talk clients use.
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun signalingSettings(nowMillis: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()): SignalingSettings {
        val o = ocs(HttpMethod.Get, "apps/spreed/api/v3/signaling/settings").objectData("signaling settings")
        val hello = o.obj("helloAuthParams")?.obj("1.0")
        fun servers(key: String): List<IceServer> = o.arr(key).mapNotNull { it.asObject() }.mapNotNull { s ->
            // `urls` is a list (or, on old servers, one string); some send `url`.
            val urls = s.strings("urls").ifEmpty { listOfNotNull(s.str("urls").ifEmpty { null } ?: s.str("url").ifEmpty { null }) }
            if (urls.isEmpty()) null else IceServer(urls, s.str("username"), s.str("credential"))
        }
        val stun = servers("stunservers").ifEmpty { listOf(IceServer(listOf("stun:stun.nextcloud.com:443"))) }
        return SignalingSettings(
            server = o.str("server"),
            servers = o.arr("servers").mapNotNull { it.asObject()?.str("server")?.ifEmpty { null } },
            helloUserId = hello?.str("userid").orEmpty(),
            helloTicket = hello?.str("ticket").orEmpty(),
            iceServers = stun + servers("turnservers"),
            fetchedAtMillis = nowMillis,
        )
    }

    /** Join the conversation (not the call) and get this client's Nextcloud session id for it. */
    suspend fun joinRoomSession(token: String): String =
        ocs(HttpMethod.Post, "apps/spreed/api/v4/room/${seg(token)}/participants/active", body = buildJsonObject {})
            .objectData("join room").str("sessionId")
            .ifEmpty { throw TalkError.Protocol("join room: no sessionId") }

    /** Leave the conversation's session (the counterpart of [joinRoomSession]). */
    suspend fun leaveRoomSession(token: String) {
        ocs(HttpMethod.Delete, "apps/spreed/api/v4/room/${seg(token)}/participants/active")
    }

    /** Join the conversation's call with [flags] ([talq.core.call.CallFlags]). */
    suspend fun joinCall(token: String, flags: Int, silent: Boolean = false, recordingConsent: Boolean = false) {
        ocs(
            HttpMethod.Post, "apps/spreed/api/v4/call/${seg(token)}",
            body = buildJsonObject {
                put("flags", flags)
                put("silent", silent)
                put("recordingConsent", recordingConsent)
            },
        )
    }

    /** Change this participant's call flags (microphone or camera switched). */
    suspend fun updateCallFlags(token: String, flags: Int) {
        ocs(HttpMethod.Put, "apps/spreed/api/v4/call/${seg(token)}", body = buildJsonObject { put("flags", flags) })
    }

    /** Leave the call, for this session only. */
    suspend fun leaveCall(token: String) {
        ocs(HttpMethod.Delete, "apps/spreed/api/v4/call/${seg(token)}", body = buildJsonObject { put("all", false) })
    }

    /** Who is in the conversation's call now, with their Nextcloud session ids. */
    suspend fun callPeers(token: String): List<CallPeer> =
        ocs(HttpMethod.Get, "apps/spreed/api/v4/call/${seg(token)}").data.asArray().mapNotNull { it.asObject() }.map { p ->
            CallPeer(
                actorType = p.str("actorType"),
                actorId = p.str("actorId"),
                displayName = p.str("displayName"),
                ncSessionId = p.str("sessionId"),
                lastPing = p.long("lastPing"),
            )
        }

    /** Whether a phone ringing for the call in [token] should keep ringing. Other statuses throw. */
    suspend fun callNotificationState(token: String): NotificationState {
        val r = ocsRaw(HttpMethod.Get, "apps/spreed/api/v4/call/${seg(token)}/notification-state")
        return when (r.status) {
            200 -> NotificationState.Ringing
            201 -> NotificationState.Missed
            404 -> NotificationState.Gone
            403 -> NotificationState.Forbidden
            else -> throw errorFor(r)
        }
    }

    /** Dismiss one notification on the server (all of this user's devices). */
    suspend fun dismissNotification(id: Long) {
        ocs(HttpMethod.Delete, "apps/notifications/api/v2/notifications/$id")
    }

    // ---------------------------------------------------------------- account

    suspend fun capabilities(): TalkCapabilities =
        TalkCapabilities.fromOcsData(ocs(HttpMethod.Get, "cloud/capabilities").objectData("capabilities"))

    suspend fun currentUser(): UserInfo {
        val o = ocs(HttpMethod.Get, "cloud/user").objectData("user")
        return UserInfo(userId = o.str("id"), displayName = o.str("display-name").ifEmpty { o.str("displayname") })
    }

    /** Deletes the app password this client is using. Every later request answers 401. */
    suspend fun revokeAppPassword() {
        ocs(HttpMethod.Delete, "core/apppassword")
    }

    /**
     * Signing out: the server forgets this phone's push subscription ([pushMillis] at most, best effort) and then the
     * app password. True: the password is dead, also when the server did not know it any more. Throws nothing but
     * a cancellation.
     */
    suspend fun signOff(pushMillis: Long): Boolean = try {
        try {
            withTimeoutOrNull(pushMillis) { webPushUnregister() }
        } catch (e: TalkError.Unauthorized) {
            throw e
        } catch (_: TalkError) {
        }
        revokeAppPassword()
        true
    } catch (e: TalkError) {
        e is TalkError.Unauthorized
    }

    // ---------------------------------------------------------------- own status (user_status app, capability `user_status`)

    /** The user's own status; null when the server has none for them yet (404). */
    suspend fun ownStatus(): OwnStatus? {
        val r = ocsRaw(HttpMethod.Get, USER_STATUS)
        if (r.status == 404) return null
        if (!r.isSuccess) throw errorFor(r)
        return OwnStatus.fromJson(r.objectData("user status"))
    }

    /** The server's predefined status messages, in its order. */
    suspend fun predefinedStatuses(): List<PredefinedStatus> =
        ocs(HttpMethod.Get, "apps/user_status/api/v1/predefined_statuses")
            .data.asArray().mapNotNull { it.asObject() }.mapNotNull { PredefinedStatus.fromJson(it) }

    /** Set the status type ([talq.core.model.StatusType]). */
    suspend fun setStatusType(statusType: String) {
        ocs(HttpMethod.Put, "$USER_STATUS/status", body = buildJsonObject { put("statusType", statusType) })
    }

    /** Use the predefined message [messageId]; [clearAt] in Unix seconds, 0 = never (sent as null). */
    suspend fun setPredefinedStatusMessage(messageId: String, clearAt: Long) {
        ocs(
            HttpMethod.Put, "$USER_STATUS/message/predefined",
            body = buildJsonObject {
                put("messageId", messageId)
                put("clearAt", if (clearAt > 0) JsonPrimitive(clearAt) else JsonNull)
            },
        )
    }

    /** A message of the user's own: [icon] one emoji or empty (then not sent), [clearAt] as for the predefined one. */
    suspend fun setCustomStatusMessage(icon: String, message: String, clearAt: Long) {
        ocs(
            HttpMethod.Put, "$USER_STATUS/message/custom",
            body = buildJsonObject {
                if (icon.isNotEmpty()) put("statusIcon", icon)
                put("message", message)
                put("clearAt", if (clearAt > 0) JsonPrimitive(clearAt) else JsonNull)
            },
        )
    }

    /** Remove the status message (and its emoji); the status type stays. */
    suspend fun clearStatusMessage() {
        ocs(HttpMethod.Delete, "$USER_STATUS/message")
    }

    // ---------------------------------------------------------------- rooms (v4)

    suspend fun rooms(includeStatus: Boolean = false, noStatusUpdate: Boolean = true): List<Room> =
        ocs(
            HttpMethod.Get, "apps/spreed/api/v4/room",
            mapOf("noStatusUpdate" to if (noStatusUpdate) 1 else 0, "includeStatus" to if (includeStatus) "true" else null),
        ).list("conversations").mapNotNull { it.asObject() }.map { Room.fromJson(it) }

    suspend fun room(token: String): Room =
        Room.fromJson(ocs(HttpMethod.Get, "apps/spreed/api/v4/room/${seg(token)}").objectData("room"))

    /**
     * People to start a conversation with, as TalQ for desktop asks Nextcloud's autocomplete
     * (ApiClient::searchNcUsers): `itemType=call` with `itemId=new`, share types 0 user, 1 group
     * and 7 circle, at most 25. Only users, groups and circles are kept. [itemId] a conversation's
     * token: people to add to it, and the server leaves out who is in it already (Talk's autocomplete
     * listener; whoever only followed its public link is listed, to be added for good).
     */
    suspend fun searchPeople(search: String, itemId: String = "new"): List<PersonMatch> =
        ocs(
            HttpMethod.Get, "core/autocomplete/get",
            mapOf(
                "search" to search,
                "itemType" to "call",
                "itemId" to itemId,
                "shareTypes[]" to listOf(0, 1, 7),
                "limit" to 25,
            ),
        ).data.asArray().mapNotNull { it.asObject() }.mapNotNull { o ->
            val id = o.str("id")
            val source = o.str("source")
            if (id.isEmpty() || source !in PersonMatch.SOURCES) null else PersonMatch(id, o.str("label"), source)
        }.distinctBy { it.key }

    /**
     * Who can be mentioned in [token] for what is typed after "@" ([search] may be empty: the
     * conversation's people), as TalQ for desktop's ApiClient::fetchMentions asks (`chat` is v1,
     * limit 20), plus `includeStatus` for the presence disc. Rows without a usable id are left out.
     * Talk 25.0.7 answers 403 for a read-only conversation and 412 behind a lobby.
     */
    suspend fun mentions(token: String, search: String, limit: Int = MENTION_FETCH_LIMIT): List<MentionCandidate> =
        ocs(
            HttpMethod.Get, "apps/spreed/api/v1/chat/${seg(token)}/mentions",
            mapOf("search" to search, "limit" to limit, "includeStatus" to "true"),
        ).data.asArray().mapNotNull { it.asObject() }.map { o ->
            MentionCandidate(
                id = o.str("id"),
                mentionId = o.str("mentionId"),
                label = o.str("label"),
                source = o.str("source"),
                status = o.str("status"),
                statusIcon = o.str("statusIcon"),
                statusMessage = o.str("statusMessage"),
                details = o.str("details"),
            )
        }.filter { isMentionable(it) }.distinctBy { it.key }

    /**
     * The bots known in [token], enabled or not (capability `bots-v1`), as TalQ for desktop's
     * ApiClient::fetchEnabledBots asks. Local conversations only; the server may refuse whoever may
     * not manage the conversation.
     */
    suspend fun bots(token: String): List<RoomBot> =
        ocs(HttpMethod.Get, "apps/spreed/api/v1/bot/${seg(token)}")
            .data.asArray().mapNotNull { it.asObject() }
            .map { RoomBot(it.long("id"), it.str("name"), it.int("state")) }
            .filter { it.id != 0L }

    /**
     * Who is in [token], as TalQ for desktop's ApiClient::fetchRoomParticipants asks; [includeStatus]
     * adds the users' presence (not in a very large conversation). 403: a channel's list, for anyone but
     * its moderators; 412: behind a lobby; 404: the user is not in the conversation any more.
     */
    suspend fun participants(token: String, includeStatus: Boolean = false): List<RoomParticipant> =
        // A large answer, asked for from screens: not read on their thread.
        withContext(Dispatchers.Default) {
            ocs(
                HttpMethod.Get, "apps/spreed/api/v4/room/${seg(token)}/participants",
                mapOf("includeStatus" to if (includeStatus) "true" else null),
            ).data.asArray().mapNotNull { it.asObject() }.map { o ->
                RoomParticipant(
                    actorType = o.str("actorType"),
                    actorId = o.str("actorId"),
                    displayName = o.str("displayName"),
                    attendeeId = o.long("attendeeId"),
                    participantType = o.int("participantType"),
                    status = o.str("status"),
                )
            }
        }

    /**
     * Take [attendeeId] ([RoomParticipant.attendeeId]) out of a conversation: moderators only, never an
     * owner (403 `owner`); 404 `participant` when they are not in it any more. A group or team takes its
     * members along (not the moderators, nor who is in another group or team of the conversation). Local
     * conversations only, like the two below; the id goes in the body, as TalQ for desktop sends it
     * (ApiClient::removeRoomParticipant, promoteModerator, demoteModerator).
     */
    suspend fun removeAttendee(token: String, attendeeId: Long) {
        ocs(
            HttpMethod.Delete, "apps/spreed/api/v4/room/${seg(token)}/attendees",
            body = buildJsonObject { put("attendeeId", attendeeId) },
        )
    }

    /** Make a user or guest a moderator. 400 when they are one already, 403 for oneself or without the right. */
    suspend fun promoteModerator(token: String, attendeeId: Long) {
        ocs(
            HttpMethod.Post, "apps/spreed/api/v4/room/${seg(token)}/moderators",
            body = buildJsonObject { put("attendeeId", attendeeId) },
        )
    }

    /** Make a moderator (never an owner) an ordinary member again. Refused like [promoteModerator]. */
    suspend fun demoteModerator(token: String, attendeeId: Long) {
        ocs(
            HttpMethod.Delete, "apps/spreed/api/v4/room/${seg(token)}/moderators",
            body = buildJsonObject { put("attendeeId", attendeeId) },
        )
    }

    /**
     * Make a conversation and return its token (TalQ for desktop's ApiClient::createRoom, nothing
     * else in the body): [roomType] 1 with [invite] a user id opens the one-to-one conversation with
     * that person (an existing one answers 200), 2 with [roomName] makes a group of one, you.
     */
    suspend fun createRoom(roomType: Int, roomName: String = "", invite: String = ""): String =
        ocs(
            HttpMethod.Post, "apps/spreed/api/v4/room",
            body = buildJsonObject {
                put("roomType", roomType)
                if (roomName.isNotEmpty()) put("roomName", roomName)
                if (invite.isNotEmpty()) put("invite", invite)
            },
        ).objectData("new conversation").str("token").ifEmpty { throw TalkError.Protocol("new conversation: no token") }

    /**
     * Add [newParticipant] to a group conversation; [source] is where the id came from (`users`,
     * `groups`, `circles`, as the autocomplete said), as TalQ for desktop's addRoomParticipant sends it.
     */
    suspend fun addParticipant(token: String, newParticipant: String, source: String = PersonMatch.USERS) {
        ocs(
            HttpMethod.Post, "apps/spreed/api/v4/room/${seg(token)}/participants",
            body = buildJsonObject {
                put("newParticipant", newParticipant)
                put("source", source.ifEmpty { PersonMatch.USERS })
            },
        )
    }

    /** The user's Note to self conversation (capability `note-to-self`); the server makes it on the first ask. */
    suspend fun noteToSelf(): Room =
        Room.fromJson(ocs(HttpMethod.Get, "apps/spreed/api/v4/room/note-to-self").objectData("note to self"))

    /** Favourite or not (capability `favorites`): the conversation itself is the whole request (desktop's ApiClient::setFavorite). */
    suspend fun setFavorite(token: String, on: Boolean) = roomFlag(token, "favorite", on)

    /** Archived or not (capability `archived-conversations-v2`; desktop's ApiClient::setArchived). */
    suspend fun setArchived(token: String, on: Boolean) = roomFlag(token, "archive", on)

    /** Important or not (capability `important-conversations`; desktop's ApiClient::setImportant). */
    suspend fun setImportant(token: String, on: Boolean) = roomFlag(token, "important", on)

    /** POST sets, DELETE takes back. The answer (the room) is not used. */
    private suspend fun roomFlag(token: String, flag: String, on: Boolean) {
        val path = "apps/spreed/api/v4/room/${seg(token)}/$flag"
        if (on) ocs(HttpMethod.Post, path, body = buildJsonObject {}) else ocs(HttpMethod.Delete, path)
    }

    /**
     * What the conversation notifies the user of, a [talq.core.model.NotificationLevel] (capability
     * `notification-levels`). POST, as conversation.md says (desktop sends PUT).
     */
    suspend fun setNotificationLevel(token: String, level: Int) {
        ocs(HttpMethod.Post, "apps/spreed/api/v4/room/${seg(token)}/notify", body = buildJsonObject { put("level", level) })
    }

    /** Ring for calls here or not (capability `notification-calls`; level 1 or 0). */
    suspend fun setNotificationCalls(token: String, on: Boolean) {
        ocs(
            HttpMethod.Post, "apps/spreed/api/v4/room/${seg(token)}/notify-calls",
            body = buildJsonObject { put("level", if (on) 1 else 0) },
        )
    }

    /** Take the user out of the conversation for good. A refusal (400) names its reason: `last-moderator`, `announcement`. */
    suspend fun leaveRoom(token: String) {
        ocs(HttpMethod.Delete, "apps/spreed/api/v4/room/${seg(token)}/participants/self")
    }

    // ---------------------------------------------------------------- chat (v1)

    /**
     * Older messages. [lastKnownMessageId] 0 means "the newest page". With [threadId] > 0 only
     * that topic's messages (root included) are returned. Never moves the read marker and never
     * dismisses notifications: reading is explicit ([markRead]).
     */
    suspend fun history(
        token: String,
        lastKnownMessageId: Long = 0,
        limit: Int = 100,
        threadId: Long = 0,
        includeLastKnown: Boolean = false,
    ): ChatPage = chatPage(
        ocs(
            HttpMethod.Get, "apps/spreed/api/v1/chat/${seg(token)}",
            mapOf(
                "lookIntoFuture" to 0,
                "limit" to limit,
                "setReadMarker" to 0,
                "markNotificationsAsRead" to 0,
                "noStatusUpdate" to 1,
                "lastKnownMessageId" to lastKnownMessageId.takeIf { it > 0 },
                "includeLastKnown" to if (includeLastKnown) 1 else null,
                "threadId" to threadId.takeIf { it > 0 },
            ),
            allowNotModified = true,
        ),
    )

    /**
     * The messages right after [lastKnownMessageId], oldest first, at most [limit]: a page of the history
     * read towards the newest, not the live poll ([pollMessages]): it answers at once. With [threadId] > 0
     * only that topic's. `lastGiven` is null when there is nothing newer. Like [history] it never moves the
     * read marker and never dismisses notifications.
     *
     * Together with [history] (`includeLastKnown`) this is what `GET chat/{token}/{id}/context` gives. That
     * route is not called: it leaves the read marker alone too, but its second half runs with
     * `markNotificationsAsRead` on and no way to turn it off (`ChatController::getMessageContext` ->
     * `ChatManager::waitForNewMessages` -> `Notifier::markMentionNotificationsRead`), which takes down every
     * notification of the conversation on all the user's devices, also of messages nobody has seen.
     */
    suspend fun newer(token: String, lastKnownMessageId: Long, limit: Int = 100, threadId: Long = 0): ChatPage = chatPage(
        ocs(
            HttpMethod.Get, "apps/spreed/api/v1/chat/${seg(token)}",
            mapOf(
                "lookIntoFuture" to 1,
                "timeout" to 0,
                "limit" to limit,
                "setReadMarker" to 0,
                "markNotificationsAsRead" to 0,
                "noStatusUpdate" to 1,
                "includeLastKnown" to 0,
                "lastKnownMessageId" to lastKnownMessageId,
                "threadId" to threadId.takeIf { it > 0 },
            ),
            allowNotModified = true,
        ),
    )

    /**
     * Long-poll for messages newer than [lastKnownMessageId]; returns an empty page when the
     * server's wait ([timeoutSeconds], at most 60) ran out. Deliberately has no topic filter:
     * poll the whole room and sort messages into topics locally, otherwise messages that a
     * peer posted without a topic id are silently missed.
     */
    suspend fun pollMessages(
        token: String,
        lastKnownMessageId: Long,
        timeoutSeconds: Int = 30,
        limit: Int = 100,
        /**
         * The last `X-Chat-Last-Common-Read` received, 0 = not interested. With it the server
         * answers early (an empty page with the new value) when that point moves, so the read
         * status of own messages follows live. Always send the LAST value received: a stale
         * one makes every poll answer at once.
         */
        lastCommonReadId: Long = 0,
    ): ChatPage = chatPage(
        ocs(
            HttpMethod.Get, "apps/spreed/api/v1/chat/${seg(token)}",
            mapOf(
                "lookIntoFuture" to 1,
                "timeout" to timeoutSeconds,
                "limit" to limit,
                "setReadMarker" to 0,
                "markNotificationsAsRead" to 0,
                "includeLastKnown" to 0,
                "lastKnownMessageId" to lastKnownMessageId,
                "lastCommonReadId" to lastCommonReadId.takeIf { it > 0 },
            ),
            timeoutMillis = (timeoutSeconds + 20) * 1000L,
            allowNotModified = true,
        ),
    )

    private fun chatPage(r: OcsResponse): ChatPage = ChatPage(
        messages = if (r.status == 304) emptyList() else r.messages(),
        lastGiven = r.headers["X-Chat-Last-Given"]?.toLongOrNull(),
        lastCommonRead = r.headers["X-Chat-Last-Common-Read"]?.toLongOrNull(),
    )

    /**
     * Post a message.
     * - [threadTitle] non-empty: this message STARTS a new topic with that title (there is no
     *   separate "create topic" call). The returned message's id is the topic id.
     * - [threadId] > 0: the message is posted INTO that topic.
     * - [replyTo] > 0: the message quotes that message. A quote alone does not put the message
     *   in a topic; inside a topic send both.
     * - [referenceId]: echoed back on the message, used to match an outbox entry to its result. The
     *   server does NOT take it as a key: the same request twice stores two messages, so the engine
     *   sends this one once (a connection that breaks after it went out is a failure, not a replay;
     *   desktop's `ApiClient::isRetryableTransportError` is the same rule).
     */
    suspend fun sendMessage(
        token: String,
        message: String,
        replyTo: Long = 0,
        threadId: Long = 0,
        threadTitle: String = "",
        referenceId: String = "",
        silent: Boolean = false,
    ): ChatMessage {
        val body = buildJsonObject {
            put("message", message)
            if (referenceId.isNotEmpty()) put("referenceId", referenceId)
            if (replyTo > 0) put("replyTo", replyTo)
            if (threadTitle.isNotEmpty()) put("threadTitle", threadTitle) else if (threadId > 0) put("threadId", threadId)
            if (silent) put("silent", true)
        }
        val r = ocs(HttpMethod.Post, "apps/spreed/api/v1/chat/${seg(token)}", body = body, once = true)
        return ChatMessage.fromJson(r.objectData("sent message"))
    }

    /**
     * Change the text of a sent message (capability `edit-messages`; 24 hours, unlimited in
     * note-to-self with `edit-messages-note-to-self`). Answers the message as it is now: the
     * server returns the "message_edited" system message with the edited one as its `parent`.
     * Null when the answer carries none. A refusal throws with the reason word in its detail
     * (`age`, `message`, `permission`; 413 too long).
     */
    suspend fun editMessage(token: String, messageId: Long, message: String): ChatMessage? {
        val r = ocs(
            HttpMethod.Put, "apps/spreed/api/v1/chat/${seg(token)}/$messageId",
            body = buildJsonObject { put("message", message) },
        )
        return changedMessage(r)
    }

    /**
     * Delete a sent message (capability `delete-messages`; a file or shared object needs
     * `rich-object-delete`). Answers the message as it is now ("Message deleted", `deleted`),
     * from the answer's `parent`, or null.
     */
    suspend fun deleteMessage(token: String, messageId: Long): ChatMessage? =
        changedMessage(ocs(HttpMethod.Delete, "apps/spreed/api/v1/chat/${seg(token)}/$messageId"))

    /** The message an edit or delete answer is about: its `parent`, or the answer itself when it is no notice. */
    private fun changedMessage(r: OcsResponse): ChatMessage? {
        val answer = r.data.asObject() ?: return null
        val changed = ChatMessage.fromJsonOrNull(answer.obj("parent"))
        if (changed != null) return changed
        return ChatMessage.fromJsonOrNull(answer)?.takeIf { !it.isSystem }
    }

    // ---------------------------------------------------------------- scheduled messages (v1, capability `scheduled-messages`)

    /** The user's own messages waiting to be sent in this room. 404 `actor` for guests and self-joined users. */
    suspend fun scheduledMessages(token: String): List<ScheduledMessage> =
        ocs(HttpMethod.Get, "apps/spreed/api/v1/chat/${seg(token)}/schedule")
            .data.asArray().mapNotNull { it.asObject() }.map { ScheduledMessage.fromJson(it) }

    /**
     * Have the server post [message] at [sendAt] (Unix seconds, in the future). [replyTo] > 0
     * quotes that message; [threadId] > 0 puts it into that topic. A refusal throws with the
     * reason word in its detail (`send-at`, `message`, `reply-to`, `actor`; 413 too long).
     * The answer (the stored message) is not used.
     */
    suspend fun scheduleMessage(
        token: String,
        message: String,
        sendAt: Long,
        replyTo: Long = 0,
        threadId: Long = 0,
        silent: Boolean = false,
    ) {
        ocs(
            HttpMethod.Post, "apps/spreed/api/v1/chat/${seg(token)}/schedule",
            body = buildJsonObject {
                put("message", message)
                put("sendAt", sendAt)
                if (replyTo > 0) put("replyTo", replyTo)
                if (silent) put("silent", true)
                if (threadId > 0) put("threadId", threadId)
            },
            // The server has no key for it either: the same request twice is two messages.
            once = true,
        )
    }

    /**
     * Change a scheduled message's text and time. [silent] is always sent, false included: the
     * server replaces the stored flag with whatever arrives. No `threadTitle`: an empty one keeps
     * the stored title, and a non-empty one on a message that starts no topic is refused.
     */
    suspend fun editScheduledMessage(token: String, id: String, message: String, sendAt: Long, silent: Boolean) {
        ocs(
            HttpMethod.Post, "apps/spreed/api/v1/chat/${seg(token)}/schedule/${seg(id)}",
            body = buildJsonObject {
                put("message", message)
                put("sendAt", sendAt)
                put("silent", silent)
            },
        )
    }

    /** Take a scheduled message back. 404 `message` when it was sent or deleted already. */
    suspend fun deleteScheduledMessage(token: String, id: String) {
        ocs(HttpMethod.Delete, "apps/spreed/api/v1/chat/${seg(token)}/schedule/${seg(id)}")
    }

    /**
     * Set the room's read marker. Null [lastReadMessage] means "everything" (`chat-read-last`).
     * The server writes the id as given, also an older one: never send two of these at once for a room.
     * Answers the room as it is after the move (its unread numbers) where the server sends it
     * (`federation-v1`, chat.md "Mark chat as read"), else null.
     */
    suspend fun markRead(token: String, lastReadMessage: Long? = null): Room? {
        val body = buildJsonObject { if (lastReadMessage != null) put("lastReadMessage", lastReadMessage) }
        return answeredRoom(token, ocs(HttpMethod.Post, "apps/spreed/api/v1/chat/${seg(token)}/read", body = body))
    }

    /**
     * Mark the chat as unread (capability `chat-unread`): the server sets the marker to the message before
     * the conversation's newest one. Answers the room as it is then, like [markRead], else null.
     */
    suspend fun markUnread(token: String): Room? =
        answeredRoom(token, ocs(HttpMethod.Delete, "apps/spreed/api/v1/chat/${seg(token)}/read"))

    private fun answeredRoom(token: String, r: OcsResponse): Room? {
        val room = r.data.asObject()?.takeIf { it.str("token") == token } ?: return null
        return runCatching { Room.fromJson(room) }.getOrNull()
    }

    /** Who reacted to a message: emoji -> actors. The counts are already on [ChatMessage.reactions]. */
    suspend fun reactions(token: String, messageId: Long): Map<String, List<ReactionActor>> {
        val r = ocs(HttpMethod.Get, "apps/spreed/api/v1/reaction/${seg(token)}/$messageId")
        return r.data.asObject()?.mapValues { (_, list) ->
            list.asArray().mapNotNull { it.asObject() }.map {
                ReactionActor(it.str("actorType"), it.str("actorId"), it.str("actorDisplayName"), it.long("timestamp"))
            }
        } ?: emptyMap()
    }

    /** React to a message with [emoji] (capability `reactions`). Reacting twice is not an error. */
    suspend fun addReaction(token: String, messageId: Long, emoji: String) {
        ocs(
            HttpMethod.Post, "apps/spreed/api/v1/reaction/${seg(token)}/$messageId",
            body = buildJsonObject { put("reaction", emoji) },
        )
    }

    /** Take back the user's own [emoji] reaction. */
    suspend fun removeReaction(token: String, messageId: Long, emoji: String) {
        ocs(HttpMethod.Delete, "apps/spreed/api/v1/reaction/${seg(token)}/$messageId", query = mapOf("reaction" to emoji))
    }

    // ---------------------------------------------------------------- topics (v1, capability `threads`)

    /** Recently active topics of a room, newest activity first. [limit] 1..50. */
    suspend fun recentThreads(token: String, limit: Int = 50): List<TalkThreadInfo> =
        ocs(HttpMethod.Get, "apps/spreed/api/v1/chat/${seg(token)}/threads/recent", mapOf("limit" to limit)).threads()

    suspend fun thread(token: String, threadId: Long): TalkThreadInfo =
        TalkThreadInfo.fromJson(
            ocs(HttpMethod.Get, "apps/spreed/api/v1/chat/${seg(token)}/threads/$threadId").objectData("thread"),
        )

    /** Topics the user follows, across all rooms. [limit] 1..100. */
    suspend fun subscribedThreads(limit: Int = 100, offset: Int = 0): List<TalkThreadInfo> =
        ocs(
            HttpMethod.Get, "apps/spreed/api/v1/chat/subscribed-threads",
            mapOf("limit" to limit, "offset" to offset),
        ).threads()

    /** Allowed for the topic's author and for moderators (403 otherwise; 400 for an empty title). */
    suspend fun renameThread(token: String, threadId: Long, title: String): TalkThreadInfo =
        TalkThreadInfo.fromJson(
            ocs(
                HttpMethod.Put, "apps/spreed/api/v1/chat/${seg(token)}/threads/$threadId",
                body = buildJsonObject { put("threadTitle", title) },
            ).objectData("thread"),
        )

    /** [level] is a [talq.core.model.NotificationLevel]. */
    suspend fun setThreadNotificationLevel(token: String, threadId: Long, level: Int): TalkThreadInfo =
        TalkThreadInfo.fromJson(
            ocs(
                HttpMethod.Post, "apps/spreed/api/v1/chat/${seg(token)}/threads/$threadId/notify",
                body = buildJsonObject { put("level", level) },
            ).objectData("thread"),
        )

    /** Messages of one topic, root included, oldest first. Page back with [lastKnownMessageId]. */
    suspend fun threadMessages(token: String, threadId: Long, lastKnownMessageId: Long = 0, limit: Int = 100): ChatPage =
        history(token, lastKnownMessageId, limit, threadId)

    // ---------------------------------------------------------------- notifications (v2)

    suspend fun notifications(): List<TalkNotification> =
        ocs(HttpMethod.Get, "apps/notifications/api/v2/notifications")
            .data.asArray().mapNotNull { it.asObject() }.map { TalkNotification.fromJson(it) }

    suspend fun notification(id: Long): TalkNotification =
        TalkNotification.fromJson(
            ocs(HttpMethod.Get, "apps/notifications/api/v2/notifications/$id").objectData("notification"),
        )

    // ---------------------------------------------------------------- web push (notifications v2)

    private fun <T> webPushFailure(r: OcsResponse): WebPushResult<T> {
        val reason = r.detail
        return when {
            r.status == 401 -> throw TalkError.Unauthorized(reason)
            r.status == 403 && (reason == "WEBPUSH_DISABLED" || reason.isEmpty()) -> WebPushResult.Disabled
            r.status == 404 && reason != "NO_PUSH_SUBSCRIPTION" -> WebPushResult.Unsupported
            r.status in 500..599 -> throw TalkError.Server(r.status, reason)
            else -> WebPushResult.Rejected(r.status, reason)
        }
    }

    /** The server's VAPID public key: pass it to the push distributor when subscribing. */
    suspend fun webPushVapidKey(): WebPushResult<String> {
        val r = ocsRaw(HttpMethod.Get, "apps/notifications/api/v2/webpush/vapid")
        if (!r.isSuccess) return webPushFailure(r)
        val key = r.data.asObject()?.str("vapid").orEmpty()
        return if (key.isEmpty()) WebPushResult.Rejected(r.status, "empty vapid key") else WebPushResult.Ok(key)
    }

    /**
     * Register this app password's push subscription.
     * @param endpoint push server URL (https, max 765 chars)
     * @param uaPublicKey the device's P-256 public key, uncompressed, base64url
     * @param auth the 16 byte auth secret, base64url
     * @param appTypes comma separated filter; `talk` = Talk notifications only, `all` = everything
     */
    suspend fun webPushRegister(
        endpoint: String,
        uaPublicKey: String,
        auth: String,
        appTypes: String = "talk",
    ): WebPushResult<WebPushRegistration> {
        val r = ocsRaw(
            HttpMethod.Post, "apps/notifications/api/v2/webpush",
            body = buildJsonObject {
                put("endpoint", endpoint)
                put("uaPublicKey", uaPublicKey)
                put("auth", auth)
                put("appTypes", appTypes)
            },
        )
        return when (r.status) {
            201 -> WebPushResult.Ok(WebPushRegistration.CREATED_NEEDS_ACTIVATION)
            200 -> WebPushResult.Ok(WebPushRegistration.ALREADY_ACTIVE)
            else -> webPushFailure(r)
        }
    }

    /**
     * Confirm a subscription with the `activationToken` that arrived as the first push.
     * Ok(true) = activated now, Ok(false) = was already active.
     */
    suspend fun webPushActivate(activationToken: String): WebPushResult<Boolean> {
        val r = ocsRaw(
            HttpMethod.Post, "apps/notifications/api/v2/webpush/activate",
            body = buildJsonObject { put("activationToken", activationToken) },
        )
        return when (r.status) {
            202 -> WebPushResult.Ok(true)
            200 -> WebPushResult.Ok(false)
            else -> webPushFailure(r)
        }
    }

    /** Remove this app password's subscription. Ok(true) = removed, Ok(false) = there was none. */
    suspend fun webPushUnregister(): WebPushResult<Boolean> {
        val r = ocsRaw(HttpMethod.Delete, "apps/notifications/api/v2/webpush")
        return when (r.status) {
            202 -> WebPushResult.Ok(true)
            200 -> WebPushResult.Ok(false)
            else -> webPushFailure(r)
        }
    }

    // ---------------------------------------------------------------- image URLs (send [authorizationHeader])

    /** Conversation avatar. [avatarVersion] busts the 24 h cache when the avatar changes. */
    fun roomAvatarUrl(token: String, avatarVersion: String = "", dark: Boolean = false): String =
        "$serverUrl/ocs/v2.php/apps/spreed/api/v1/room/${seg(token)}/avatar" + (if (dark) "/dark" else "") +
            (if (avatarVersion.isNotEmpty()) "?v=${avatarVersion.encodeURLParameter()}" else "")

    fun userAvatarUrl(userId: String, size: Int = 64, dark: Boolean = false): String =
        "$serverUrl/index.php/avatar/${seg(userId)}/$size" + if (dark) "/dark" else ""

    /** Preview of a shared file; [fileId] is the `id` of a `file` rich object. */
    fun filePreviewUrl(fileId: String, width: Int = 480, height: Int = 480): String =
        "$serverUrl/index.php/core/preview?fileId=${fileId.encodeURLParameter()}&x=$width&y=$height&a=1"

    /** Avatar for a message author: users get their avatar, everything else has none (empty). */
    fun actorAvatarUrl(actorType: String, actorId: String, size: Int = 64, dark: Boolean = false): String =
        if (actorType == "users") userAvatarUrl(actorId, size, dark) else ""

    private fun seg(s: String): String = s.encodeURLPathPart()

    /**
     * A JSON body the engine can send one time only. A body it holds as bytes is put on the wire again
     * by itself when the connection breaks after the request went out (OkHttp's `recover`; Ktor leaves
     * `retryOnConnectionFailure` on); a streamed one is not, unless the request provably never left.
     */
    private class OnceJson(text: String) : OutgoingContent.WriteChannelContent() {
        private val bytes = text.encodeToByteArray()
        override val contentLength: Long = bytes.size.toLong()
        override val contentType: ContentType = ContentType.Application.Json

        override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeFully(bytes)
    }

    companion object {
        private const val USER_STATUS = "apps/user_status/api/v1/user_status"

        /** "cloud.example.com/index.php/apps/..." -> "https://cloud.example.com" */
        fun normalizeServerUrl(input: String): String {
            var s = input.trim()
            if (s.isEmpty()) return s
            if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) s = "https://$s"
            s = s.substringBefore("/index.php").substringBefore("/ocs/").substringBefore("?").substringBefore("#")
            return s.trimEnd('/')
        }

        internal fun errorFor(status: Int, detail: String): TalkError = when (status) {
            401 -> TalkError.Unauthorized(detail)
            403 -> TalkError.Forbidden(detail)
            404 -> TalkError.NotFound(detail)
            507 -> TalkError.QuotaExceeded(detail)
            in 500..599 -> TalkError.Server(status, detail)
            else -> TalkError.Http(status, detail)
        }

        internal fun primitiveText(e: JsonElement?): String = (e as? JsonPrimitive)?.content ?: ""
    }
}
