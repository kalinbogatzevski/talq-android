package talq.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import talq.app.ui.room.RoomTab
import talq.app.ui.common.userMessage
import talq.core.model.ChatMessage
import talq.core.repo.ChatRepository
import talq.core.repo.ChatScope
import talq.core.repo.TalkSession
import talq.core.repo.TopicsRepository
import talq.core.repo.UnreadTracker

/**
 * State of one tab of an open conversation (whole chat, General or a topic) that is not
 * already held by the core repositories: the draft, the reply target, how far the user has
 * read. One per tab, keyed `chat-<token>-<tab key>` in the room's navigation entry, so each
 * tab keeps its own while the room is open, and across rotation.
 */
class ChatViewModel(
    val session: TalkSession,
    val token: String,
    val tab: RoomTab,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    val scope: ChatScope = tab.scope
    val repo: ChatRepository = session.chat(token, scope)
    val topics: TopicsRepository = session.topics(token)
    val unread: UnreadTracker = session.unread(token)

    /** Topic id for a topic view, 0 for General, null for the whole chat. */
    val bucket: Long? = tab.bucket

    /**
     * The last message that was read when this tab was first shown: the "New messages" divider
     * goes right after it. Fixed for the life of the tab so the divider does not jump around.
     */
    var dividerAfter by mutableLongStateOf(dividerFor(session.rooms.room(token)?.lastReadMessage ?: 0L))
        private set

    /**
     * The room (and so its read marker) is known. False only when the tab was opened before
     * the rooms list had answered (a notification tap on a cold start): the divider is set
     * when it does, and the pane waits for that before it settles on a position.
     */
    var roomKnown by mutableStateOf(session.rooms.room(token) != null)
        private set

    /** Newest message the user has had on screen; drives the jump-to-bottom count. */
    var seenUpTo by mutableLongStateOf(dividerAfter)
        private set

    private fun dividerFor(lastRead: Long): Long =
        if (lastRead == 0L) 0L else maxOf(lastRead, unread.state.value.seenUpTo(bucket ?: -1L))

    init {
        if (!roomKnown) {
            viewModelScope.launch {
                val room = session.rooms.rooms.mapNotNull { list -> list.firstOrNull { it.token == token } }.first()
                dividerAfter = dividerFor(room.lastReadMessage)
                if (seenUpTo < dividerAfter) seenUpTo = dividerAfter
                roomKnown = true
            }
        }
    }

    private var draftText by mutableStateOf(saved.get<String>(KEY_DRAFT).orEmpty())

    /** What is typed but not sent. Survives the app being killed in the background. */
    var draft: String
        get() = draftText
        set(value) {
            draftText = value
            saved[KEY_DRAFT] = value
        }

    var replyTo by mutableStateOf<ChatMessage?>(null)

    /** A one-line message for the snackbar; the screen clears it once shown. */
    var notice by mutableStateOf<String?>(null)

    val maxLength: Int = session.capabilities.maxMessageLength.takeIf { it > 0 } ?: 32_000

    fun isMe(m: ChatMessage): Boolean = session.isMe(m.actorType, m.actorId)

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        repo.send(text, replyTo)
        draft = ""
        replyTo = null
    }

    /** The newest message is on screen: count it read here and, as far as allowed, on the server. */
    fun onReachedBottom(newestId: Long) {
        if (newestId <= seenUpTo) return
        seenUpTo = newestId
        // On the session's lifetime, not this view's: leaving right after reading must still count.
        session.launch { unread.markSeen(scope, newestId) }
    }

    fun loadOlder() {
        viewModelScope.launch { repo.loadOlder() }
    }

    fun retryLoad() {
        viewModelScope.launch { repo.refresh() }
    }

    fun toggleReaction(messageId: Long, emoji: String) {
        viewModelScope.launch {
            repo.toggleReaction(messageId, emoji)?.let { notice = "The reaction wasn't saved. " + it.userMessage() }
        }
    }

    private companion object {
        const val KEY_DRAFT = "draft"
    }
}
