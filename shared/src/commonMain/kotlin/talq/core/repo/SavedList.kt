package talq.core.repo

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import talq.core.TalkClient
import talq.core.model.ChatMessage
import talq.core.model.Credentials
import talq.core.model.Room
import talq.core.model.TalkCapabilities
import talq.core.model.UserInfo
import talq.core.net.EmptyJsonObject
import talq.core.net.TalkJson
import talq.core.net.arr
import talq.core.net.asObject
import talq.core.net.int
import talq.core.net.obj
import talq.core.net.str

/**
 * One account's conversation list as the last run of the app left it, with the account's names and the
 * server's capabilities: what the first frame of the next run is drawn from, while the session behind it is
 * checked (`TalkSession.unchecked`). The app keeps it as one text ([encode], [decode]).
 *
 *  - It is something to look at, never a source: no repository is filled from it, nothing is counted, marked
 *    as read, dropped or sent because of it. The server's first answer takes its place whole, so a room that
 *    was left elsewhere, or a capability the server lost, goes with that answer.
 *  - Only what the rows show. Not kept: session ids, the dial-in PIN, the presence of other people (it is
 *    only true now), what is shared besides its name, messages that disappear, and notices of calls (the app keeps
 *    no history of calls). A long text is cut. The unread numbers are kept for the order and for "Unread"; they are never drawn from here.
 *  - As it stands here: what the user changed of a room since the server's last answer is in it.
 */
class SavedList internal constructor(
    val server: String,
    val loginName: String,
    val user: UserInfo,
    val capabilities: TalkCapabilities,
    /** Newest activity first; never empty, every token once. */
    val rooms: List<Room>,
) {
    /** It is the list of the account [credentials] sign in. */
    fun isOf(credentials: Credentials): Boolean =
        server == TalkClient.normalizeServerUrl(credentials.server) && loginName == credentials.loginName

    companion object {
        private const val VERSION = 1

        /** Favourites first, then the newest: what is past it is not in the first frame. */
        private const val MAX_ROOMS = 300
        private const val MAX_CHARS = 1_000_000
        private const val MAX_TEXT = 300

        /** [rooms], the list of [session], as text; null when it is empty. */
        fun encode(session: TalkSession, rooms: List<Room>): String? {
            if (rooms.isEmpty()) return null
            val head = buildJsonObject {
                put("v", VERSION)
                put("server", session.credentials.server)
                put("login", session.credentials.loginName)
                put("userId", session.user.userId)
                put("name", session.user.displayName)
                put("capabilities", kept(session.capabilities))
            }.toString()
            return buildString {
                append(head, 0, head.length - 1).append(",\"rooms\":[")
                var count = 0
                for (room in rooms.sortedByDescending { it.isFavorite }) {
                    if (count == MAX_ROOMS) break
                    val text = kept(room).toString()
                    if (length + text.length > MAX_CHARS) break
                    if (count++ > 0) append(',')
                    append(text)
                }
                append("]}")
            }
        }

        /** Null for anything but a list [encode] of this version made. */
        fun decode(text: String): SavedList? {
            val o = try {
                TalkJson.parseToJsonElement(text).asObject()
            } catch (_: Exception) {
                null
            }
            if (o == null || o.int("v") != VERSION) return null
            val rooms = o.arr("rooms").mapNotNull { it.asObject() }.map { Room.fromJson(it) }
                .filter { it.token.isNotEmpty() }.distinctBy { it.token }.sortedByDescending { it.lastActivity }
            val user = UserInfo(o.str("userId"), o.str("name"))
            val list = SavedList(
                server = o.str("server"),
                loginName = o.str("login"),
                user = user,
                capabilities = TalkCapabilities.fromOcsData(o.obj("capabilities") ?: return null),
                rooms = rooms,
            )
            return list.takeIf { it.server.isNotEmpty() && it.loginName.isNotEmpty() && user.userId.isNotEmpty() && rooms.isNotEmpty() }
        }

        /** The parts of the server's answer that [TalkCapabilities] reads. */
        private fun kept(capabilities: TalkCapabilities): JsonObject = buildJsonObject {
            put("version", capabilities.raw.obj("version") ?: EmptyJsonObject)
            putJsonObject("capabilities") {
                val all = capabilities.raw.obj("capabilities") ?: EmptyJsonObject
                for (app in listOf("spreed", "notifications", "user_status")) all[app]?.let { put(app, it) }
            }
        }

        private val ROOM = setOf(
            "id", "token", "type", "name", "displayName", "lastActivity", "readOnly", "avatarVersion", "isCustomAvatar",
            "participantType", "objectType", "remoteServer", "remoteToken",
        )

        private fun kept(room: Room): JsonObject = buildJsonObject {
            for ((key, value) in room.raw) if (key in ROOM) put(key, value)
            put("lastReadMessage", room.lastReadMessage)
            put("unreadMessages", room.unreadMessages)
            put("unreadMention", room.unreadMention)
            put("unreadMentionDirect", room.unreadMentionDirect)
            put("notificationLevel", room.notificationLevel)
            put("isFavorite", room.isFavorite)
            put("isArchived", room.isArchived)
            room.lastMessage?.let { m -> if (m.expirationTimestamp == 0L && !m.isCallNotice) put("lastMessage", kept(m)) }
        }

        private val MESSAGE = setOf(
            "id", "token", "actorType", "actorId", "actorDisplayName", "timestamp", "messageType", "systemMessage",
            "markdown", "deleted", "threadId", "isThread",
        )

        private fun kept(message: ChatMessage): JsonObject = buildJsonObject {
            for ((key, value) in message.raw) if (key in MESSAGE) put(key, value)
            put("threadTitle", message.threadTitle)
            put("message", cut(message.message))
            putJsonObject("messageParameters") {
                for ((key, value) in message.parameters) put(key, kept(value.raw))
            }
        }

        private fun kept(parameter: JsonObject): JsonElement =
            JsonObject(parameter.filterKeys { it == "type" || it == "id" || it == "name" })

        /** The row shows one line of it. Never half a character, nor half a placeholder. */
        private fun cut(text: String): String {
            if (text.length <= MAX_TEXT) return text
            var end = MAX_TEXT
            if (text[end - 1].isHighSurrogate()) end--
            val open = text.lastIndexOf('{', end - 1)
            if (open >= 0 && text.indexOf('}', open) >= end) end = open
            return text.substring(0, end)
        }
    }
}
