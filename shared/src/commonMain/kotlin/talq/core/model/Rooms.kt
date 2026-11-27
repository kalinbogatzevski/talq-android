package talq.core.model

import kotlinx.serialization.json.JsonObject
import talq.core.net.bool
import talq.core.net.int
import talq.core.net.long
import talq.core.net.obj
import talq.core.net.str

object RoomType {
    const val ONE_TO_ONE = 1
    const val GROUP = 2
    const val PUBLIC = 3
    const val CHANGELOG = 4
    const val FORMER_ONE_TO_ONE = 5
    const val NOTE_TO_SELF = 6
}

/** What the user is in a conversation (Talk's participant types). */
object ParticipantType {
    const val OWNER = 1
    const val MODERATOR = 2
    const val USER = 3
    const val GUEST = 4

    /** Somebody with an account who followed the link of a public conversation and was not added to it. */
    const val USER_SELF_JOINED = 5
    const val GUEST_MODERATOR = 6
}

/** Bits of [Room.permissions] (Talk's attendee permissions). */
object RoomPermission {
    const val LOBBY_IGNORE = 8
    const val CHAT = 128
}

object NotificationLevel {
    const val DEFAULT = 0
    const val ALWAYS = 1
    const val MENTION = 2
    const val NEVER = 3
}

/** Conversation attribute "Classified" (constants.md), a bit of the room's `attributes`. */
private const val ATTRIBUTE_CLASSIFIED = 4

/** A conversation as listed by `GET /room` (Talk API v4). */
data class Room(
    val id: Long,
    val token: String,
    /** See [RoomType]. */
    val type: Int,
    val name: String,
    val displayName: String,
    val description: String,
    val unreadMessages: Int,
    val unreadMention: Boolean,
    val unreadMentionDirect: Boolean,
    /** Seconds, UTC. */
    val lastActivity: Long,
    /** Null for an empty room. Never carries `parent` or `reactionsSelf`. */
    val lastMessage: ChatMessage?,
    val lastReadMessage: Long,
    val lastCommonReadMessage: Long,
    /** 0 = read-write, 1 = read-only. */
    val readOnly: Int,
    /** See [NotificationLevel]. */
    val notificationLevel: Int,
    val avatarVersion: String,
    val isCustomAvatar: Boolean,
    val isFavorite: Boolean,
    val isArchived: Boolean,
    val isImportant: Boolean,
    val hasCall: Boolean,
    val participantType: Int,
    val permissions: Int,
    val actorType: String,
    val actorId: String,
    val objectType: String,
    val objectId: String,
    /**
     * The other person's presence: `online`, `away`, `dnd`, `busy`, `invisible`, `offline`.
     * One-to-one rooms only, when the list was requested with status; else empty.
     */
    val status: String,
    /** The emoji of their status message, or empty. */
    val statusIcon: String,
    val statusMessage: String,
    /** Seconds, UTC; 0 = the status message does not expire. */
    val statusClearAt: Long,
    val raw: JsonObject,
    /** The server that hosts the conversation, and its token there; empty for one on our own server. */
    val remoteServer: String = "",
    val remoteToken: String = "",
    /** 0 = no lobby, 1 = a lobby for everyone but moderators; [lobbyTimer] = when the server lifts it (seconds, UTC), 0 = never. */
    val lobbyState: Int = 0,
    val lobbyTimer: Long = 0L,
    /** The server's word on leaving (not the last moderator, not an announcement); absent = yes, as TalQ for desktop reads it. */
    val canLeave: Boolean = true,
    /** The server's word on starting a call here; absent = yes. */
    val canStartCall: Boolean = true,
    /** The user is rung for calls here (a switch of its own beside [notificationLevel]); absent = yes. */
    val notificationCalls: Boolean = true,
) {
    /**
     * The conversation lives on another server. Ours passes only some requests on to it; the rest
     * answer 406 there (Talk's `checkFederationSupport`), so what is local only is not offered.
     */
    val isFederated: Boolean get() = remoteServer.isNotEmpty() || remoteToken.isNotEmpty()

    /**
     * The user marked it sensitive (`isSensitive`), or it is a classified one, which the server forces sensitive for
     * everyone: nothing of what is said in it is shown or kept outside it.
     */
    val isSensitive: Boolean get() = raw.bool("isSensitive") || raw.int("attributes") and ATTRIBUTE_CLASSIFIED != 0

    val isOneToOne: Boolean get() = type == RoomType.ONE_TO_ONE
    val isReadOnly: Boolean get() = readOnly != 0

    /** Owner or moderator (a guest with moderator rights is not one here: Talk's `hasModeratorPermissions(false)`). */
    val isModerator: Boolean get() = participantType == ParticipantType.OWNER || participantType == ParticipantType.MODERATOR

    /**
     * The server takes the user's posts, edits and deletes in this conversation: the guards of those
     * routes (Talk 25.0.7 `ChatController`, `InjectionMiddleware`). Not read-only (the changelog room
     * always is); the chat permission, as desktop's `postBlock` (only where the server asks for it,
     * `chat-permission`; [permissions] 0 = not sent, counts as allowed); and no lobby in the way
     * (moderators, guest moderators and who may ignore the lobby pass; a past [lobbyTimer] lifts it).
     */
    fun takesPosts(capabilities: TalkCapabilities, nowSeconds: Long): Boolean {
        if (isReadOnly || type == RoomType.CHANGELOG) return false
        if (capabilities.has("chat-permission") && permissions != 0 && permissions and RoomPermission.CHAT == 0) return false
        val inLobby = lobbyState != 0 && lobbyTimer !in 1..nowSeconds
        return !inLobby || isModerator || participantType == ParticipantType.GUEST_MODERATOR ||
            permissions and RoomPermission.LOBBY_IGNORE != 0
    }

    /**
     * The other person's Nextcloud user id: a one-to-one room's `name` is exactly that.
     * Empty for every other kind of room.
     */
    val partnerUserId: String get() = if (isOneToOne) name else ""

    /** Topic id of the last message, 0 when it is in no topic. */
    val lastMessageTopicId: Long get() = lastMessage?.topicId ?: 0L

    companion object {
        fun fromJson(o: JsonObject): Room = Room(
            id = o.long("id"),
            token = o.str("token"),
            type = o.int("type"),
            name = o.str("name"),
            displayName = o.str("displayName").ifEmpty { o.str("name") },
            description = o.str("description"),
            unreadMessages = o.int("unreadMessages"),
            unreadMention = o.bool("unreadMention"),
            unreadMentionDirect = o.bool("unreadMentionDirect"),
            lastActivity = o.long("lastActivity"),
            lastMessage = ChatMessage.fromJsonOrNull(o.obj("lastMessage")),
            lastReadMessage = o.long("lastReadMessage"),
            lastCommonReadMessage = o.long("lastCommonReadMessage"),
            readOnly = o.int("readOnly"),
            notificationLevel = o.int("notificationLevel"),
            avatarVersion = o.str("avatarVersion"),
            isCustomAvatar = o.bool("isCustomAvatar"),
            isFavorite = o.bool("isFavorite"),
            isArchived = o.bool("isArchived"),
            isImportant = o.bool("isImportant"),
            hasCall = o.bool("hasCall"),
            participantType = o.int("participantType"),
            permissions = o.int("permissions"),
            actorType = o.str("actorType"),
            actorId = o.str("actorId"),
            objectType = o.str("objectType"),
            objectId = o.str("objectId"),
            status = o.str("status"),
            statusIcon = o.str("statusIcon"),
            statusMessage = o.str("statusMessage"),
            statusClearAt = o.long("statusClearAt"),
            raw = o,
            remoteServer = o.str("remoteServer"),
            remoteToken = o.str("remoteToken"),
            lobbyState = o.int("lobbyState"),
            lobbyTimer = o.long("lobbyTimer"),
            canLeave = o.bool("canLeaveConversation", true),
            canStartCall = o.bool("canStartCall", true),
            notificationCalls = o.bool("notificationCalls", true),
        )
    }
}

/** `TalkThread`: a topic. Its id is the id of its root message. */
data class TalkThread(
    val id: Long,
    val roomToken: String,
    val title: String,
    val lastMessageId: Long,
    /** Seconds, UTC. */
    val lastActivity: Long,
    val numReplies: Int,
)

/** `TalkThreadAttendee`: the current user's settings for one topic. */
data class TalkThreadAttendee(
    /** See [NotificationLevel]. */
    val notificationLevel: Int,
)

/** `TalkThreadInfo`: a topic plus its first and last message, as the threads endpoints return it. */
data class TalkThreadInfo(
    val thread: TalkThread,
    val attendee: TalkThreadAttendee,
    /** Root message; null when it has expired. */
    val first: ChatMessage?,
    /** Newest message; null when the topic has no replies yet or it has expired. */
    val last: ChatMessage?,
) {
    val id: Long get() = thread.id
    val title: String get() = thread.title

    /** The message to show as the topic's preview line. */
    val preview: ChatMessage? get() = last ?: first

    /**
     * The server keeps ONE read marker per room, not per topic. A topic "has unread" when its
     * newest message is past that marker.
     */
    fun hasUnread(roomLastReadMessage: Long): Boolean =
        maxOf(thread.lastMessageId, thread.id) > roomLastReadMessage

    companion object {
        /** A topic entry built from its just-posted root message, before the server listed it. */
        fun local(root: ChatMessage, title: String): TalkThreadInfo = TalkThreadInfo(
            thread = TalkThread(root.id, root.token, title, root.id, root.timestamp, 0),
            attendee = TalkThreadAttendee(NotificationLevel.DEFAULT),
            first = root,
            last = null,
        )

        fun fromJson(o: JsonObject): TalkThreadInfo {
            val t = o.obj("thread") ?: JsonObject(emptyMap())
            return TalkThreadInfo(
                thread = TalkThread(
                    id = t.long("id"),
                    roomToken = t.str("roomToken"),
                    title = t.str("title"),
                    lastMessageId = t.long("lastMessageId"),
                    lastActivity = t.long("lastActivity"),
                    numReplies = t.int("numReplies"),
                ),
                attendee = TalkThreadAttendee(o.obj("attendee")?.int("notificationLevel") ?: 0),
                first = ChatMessage.fromJsonOrNull(o.obj("first")),
                last = ChatMessage.fromJsonOrNull(o.obj("last")),
            )
        }
    }
}
