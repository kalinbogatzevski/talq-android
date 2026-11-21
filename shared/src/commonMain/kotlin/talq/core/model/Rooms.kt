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

object NotificationLevel {
    const val DEFAULT = 0
    const val ALWAYS = 1
    const val MENTION = 2
    const val NEVER = 3
}

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
) {
    val isOneToOne: Boolean get() = type == RoomType.ONE_TO_ONE
    val isReadOnly: Boolean get() = readOnly != 0

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
