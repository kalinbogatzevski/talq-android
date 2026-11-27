package talq.core.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import talq.core.TalkError
import talq.core.model.Room
import talq.core.model.RoomType
import talq.core.model.TalkCapabilities

/** One thing the user does to a conversation from its row in the list. */
sealed interface RoomAction {
    data object MarkRead : RoomAction
    data object MarkUnread : RoomAction
    data class Favourite(val on: Boolean) : RoomAction

    /** [level]: a [talq.core.model.NotificationLevel]. */
    data class Notify(val level: Int) : RoomAction
    data class RingForCalls(val on: Boolean) : RoomAction
    data class Archive(val on: Boolean) : RoomAction
    data class Important(val on: Boolean) : RoomAction
    data object Leave : RoomAction
}

/** Something is unread: "Mark as read" (capability `chat-read-marker`). */
fun Room.canBeMarkedRead(capabilities: TalkCapabilities): Boolean =
    capabilities.readMarker && (unreadMessages > 0 || unreadMention)

/** Nothing is unread and there is a message that can be: "Mark as unread" (capability `chat-unread`). */
fun Room.canBeMarkedUnread(capabilities: TalkCapabilities): Boolean =
    capabilities.markUnread && unreadMessages == 0 && !unreadMention && lastMessage != null

/**
 * "Leave" is offered: the server's own word (desktop's `leaveBlock` in Talk25Rooms.h: not the last
 * moderator, not an announcement), and only in groups. Leaving a one-to-one can delete it for both
 * (a server setting), leaving Note to self always does.
 */
val Room.canBeLeft: Boolean get() = canLeave && (type == RoomType.GROUP || type == RoomType.PUBLIC)

/** Why the server refused a [RoomAction], in the manner of desktop's TalkRefusalPolicy.h (which has no arm for these routes). */
enum class RoomRefusal {
    /** Unrecognised, or no usable reason came back. */
    Unknown,

    /** Never reached the server. */
    NoConnection,

    /** The user is not in the conversation any more (404). */
    Gone,

    /** Leave: a moderator, and the only one (400 last-moderator). */
    LastModerator,

    /** Leave: an announcement (400 announcement). */
    Announcement,
}

fun classifyRoomRefusal(action: RoomAction, status: Int, error: String): RoomRefusal = when {
    status == 0 -> RoomRefusal.NoConnection
    status == 404 -> RoomRefusal.Gone
    action == RoomAction.Leave && status == 400 && error == "last-moderator" -> RoomRefusal.LastModerator
    action == RoomAction.Leave && status == 400 && error == "announcement" -> RoomRefusal.Announcement
    else -> RoomRefusal.Unknown
}

/**
 * Carries out what the user picks in a conversation's menu (`session.roomActions`). As TalQ for desktop
 * (`ConversationListModel::setFavorite`, `setArchived`, `setImportant`, `setNotificationLevel`,
 * `setNotificationCalls`, `ApiClient::leaveRoom`) the row changes once the server has taken it, never
 * before: a refusal leaves it as it was. One request at a time per conversation ([running]), on the
 * session's lifetime: closing the menu, turning the phone or leaving the list does not cut it off.
 */
class RoomActions internal constructor(private val session: TalkSession) {
    private val _running = MutableStateFlow<Map<String, RoomAction>>(emptyMap())

    /** What runs now, by conversation token. */
    val running: StateFlow<Map<String, RoomAction>> = _running.asStateFlow()

    private val _left = MutableStateFlow<String?>(null)

    /** A conversation just left: whoever has it open closes it, then calls [leftHandled]. */
    val left: StateFlow<String?> = _left.asStateFlow()

    fun leftHandled(token: String) {
        _left.compareAndSet(token, null)
    }

    /**
     * Do [action] to conversation [token]. False when something runs for it already (nothing is started).
     * [done] gets null, or why it did not work; not called when the session ends under it.
     */
    fun run(token: String, action: RoomAction, done: suspend (TalkError?) -> Unit): Boolean {
        var mine = false
        _running.update { all ->
            mine = token !in all
            if (mine) all + (token to action) else all
        }
        if (!mine) return false
        session.launch {
            var failure: TalkError? = null
            try {
                carryOut(token, action)
            } catch (e: TalkError) {
                failure = e
            } finally {
                _running.update { it - token }
            }
            // Left, or refused for a reason the list does not show yet: what it shows is not what the server has.
            if (action == RoomAction.Leave || (failure != null && failure !is TalkError.Network)) {
                session.launch { session.rooms.refresh() }
            }
            done(failure)
        }
        return true
    }

    private suspend fun carryOut(token: String, action: RoomAction) {
        val client = session.client
        val rooms = session.rooms
        when (action) {
            RoomAction.MarkRead -> session.unread(token).markRoomRead()
            RoomAction.MarkUnread -> session.unread(token).markRoomUnread()
            is RoomAction.Favourite -> {
                client.setFavorite(token, action.on)
                rooms.applyOwn(token) { it.copy(isFavorite = action.on) }
            }
            is RoomAction.Notify -> {
                client.setNotificationLevel(token, action.level)
                rooms.applyOwn(token) { it.copy(notificationLevel = action.level) }
            }
            is RoomAction.RingForCalls -> {
                client.setNotificationCalls(token, action.on)
                rooms.applyOwn(token) { it.copy(notificationCalls = action.on) }
            }
            is RoomAction.Archive -> {
                client.setArchived(token, action.on)
                rooms.applyOwn(token) { it.copy(isArchived = action.on) }
            }
            is RoomAction.Important -> {
                client.setImportant(token, action.on)
                rooms.applyOwn(token) { it.copy(isImportant = action.on) }
            }
            RoomAction.Leave -> {
                try {
                    client.leaveRoom(token)
                } catch (e: TalkError.NotFound) {
                    // Not in it any more (left elsewhere, or an earlier try landed): as asked.
                }
                rooms.applyOwn(token) { null }
                _left.value = token
            }
        }
    }

    /**
     * The user writes in [token]: an archived conversation comes back out of the archive, at once and
     * before the server has said so (desktop's `unarchiveIfArchived`, the one change it makes ahead). If
     * the server keeps it archived, the list says so again.
     */
    fun wroteIn(token: String) {
        val rooms = session.rooms
        if (rooms.room(token)?.isArchived != true) return
        rooms.applyOwn(token) { it.copy(isArchived = false) }
        session.launch {
            val kept = try {
                session.client.setArchived(token, false)
                false
            } catch (e: TalkError) {
                true
            }
            rooms.applyOwn(token) { it.copy(isArchived = kept) }
        }
    }
}
