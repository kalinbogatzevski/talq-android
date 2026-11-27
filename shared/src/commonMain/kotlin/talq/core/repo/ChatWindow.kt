package talq.core.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import talq.core.TalkError
import talq.core.model.ChatMessage
import kotlin.concurrent.Volatile

/** Where a message of the conversation is, seen from one view of it (see [ChatRepository.around]). */
sealed interface Around {
    /** Among the view's own messages, as [messageId] (what a reaction or an edit is about, when that was asked for). */
    data class Here(val messageId: Long) : Around

    /** Further back: [window] holds the messages around it. Hand it to [ChatRepository.close] when it is no longer shown. */
    class InWindow(val window: ChatWindow) : Around

    /** Not in this view: in topic [topicId], or in none (0). */
    class Elsewhere(val topicId: Long) : Around

    /** Deleted, expired, or not for this user. */
    data object Gone : Around

    /** Not loaded, and it may not be asked for (the server lacks the capability). */
    data object OutOfReach : Around

    /** The server could not be asked. */
    class Failed(val error: TalkError) : Around
}

/** What a [ChatWindow] shows. */
data class WindowState(
    /** Confirmed messages of the view's scope, oldest first, update signals already applied. */
    val messages: List<ChatMessage>,
    /** There may be more before these. */
    val hasOlder: Boolean = true,
    /** There may be more after these. */
    val hasNewer: Boolean = true,
    val loadingOlder: Boolean = false,
    val loadingNewer: Boolean = false,
    /** The last page on that side failed with this. It is not asked for again before it is tried again. */
    val olderError: TalkError? = null,
    val newerError: TalkError? = null,
    /** It has been read down to the view's own messages: nothing is missing between the two ([ChatRepository.join]). */
    val meets: Boolean = false,
    /** `lastKnownMessageId` for the next older page. */
    internal val olderCursor: Long = 0L,
    /** The same towards the newest. */
    internal val newerCursor: Long = 0L,
)

/**
 * A part of a conversation around one message that is further back than what its view
 * ([ChatRepository.state]) holds: read beside the view, never into it, so the view keeps its newest
 * page, its paging and its gap rule. Edits, deletions and reactions arrive here too while the room's
 * feed runs; new messages are the view's. Paged in both directions; once it meets the view's own
 * messages the two can become one list ([ChatRepository.join]).
 */
class ChatWindow internal constructor(
    private val repo: ChatRepository,
    /** The message it was opened for. */
    val target: Long,
    initial: WindowState,
) {
    private val _state = MutableStateFlow(initial)
    private val loadMutex = Mutex()

    /** Its messages are the view's own by now ([ChatRepository.join]). */
    @Volatile
    internal var joined = false

    val state: StateFlow<WindowState> = _state.asStateFlow()

    fun holds(messageId: Long): Boolean = find(messageId) != null

    internal fun find(messageId: Long): ChatMessage? = _state.value.messages.firstOrNull { it.id == messageId }

    /** The next older page. False when there is none, or it failed ([WindowState.olderError]). */
    suspend fun loadOlder(): Boolean = loadMutex.withLock {
        val s = _state.value
        if (!s.hasOlder) return@withLock false
        _state.update { it.copy(loadingOlder = true, olderError = null) }
        try {
            val page = repo.fetch(s.olderCursor)
            val batch = repo.arrived(page.raw)
            _state.update {
                it.copy(
                    messages = repo.withBatch(it.messages, batch),
                    hasOlder = !page.exhausted,
                    loadingOlder = false,
                    olderCursor = if (page.nextCursor > 0) page.nextCursor else it.olderCursor,
                )
            }
            true
        } catch (e: TalkError) {
            _state.update { it.copy(loadingOlder = false, olderError = e) }
            false
        } finally {
            // Given up halfway.
            _state.update { if (it.loadingOlder) it.copy(loadingOlder = false) else it }
        }
    }

    /** The next page towards the newest. As [loadOlder]. */
    suspend fun loadNewer(): Boolean = loadMutex.withLock {
        val s = _state.value
        if (!s.hasNewer || s.meets) return@withLock false
        _state.update { it.copy(loadingNewer = true, newerError = null) }
        try {
            val page = repo.fetchNewer(s.newerCursor)
            val batch = repo.arrived(page.raw)
            _state.update {
                it.copy(
                    messages = repo.withBatch(it.messages, batch),
                    hasNewer = !page.exhausted,
                    loadingNewer = false,
                    newerCursor = page.nextCursor,
                    meets = repo.meets(page.nextCursor),
                )
            }
            true
        } catch (e: TalkError) {
            _state.update { it.copy(loadingNewer = false, newerError = e) }
            false
        } finally {
            _state.update { if (it.loadingNewer) it.copy(loadingNewer = false) else it }
        }
    }

    /** A failed page may be asked for again. */
    fun forgetErrors() = _state.update { it.copy(olderError = null, newerError = null) }

    /** The view's own messages moved away meanwhile (a reload left a gap): it is paged on. */
    internal fun apart() = _state.update { it.copy(meets = false, hasNewer = true) }

    /**
     * The room's feed starts again after a pause: what was edited, deleted or reacted to meanwhile did not
     * arrive here. The messages around [target] are read again, as they were at first; a failure leaves
     * them as they are.
     */
    internal suspend fun reload() = loadMutex.withLock {
        try {
            val near = repo.near(target)
            val batch = repo.arrived(near.raw)
            _state.update {
                // It can only have grown at its newest end, where it follows on.
                val newer = maxOf(it.newerCursor, near.newerCursor)
                it.copy(
                    messages = repo.withBatch(it.messages, batch),
                    hasNewer = it.hasNewer || newer > it.newerCursor,
                    newerCursor = newer,
                    meets = repo.meets(newer),
                )
            }
        } catch (_: TalkError) {
        }
    }

    /** What the feed brought: changes of messages held here. A new message only says that there is more. */
    internal fun onLive(batch: List<ChatMessage>) {
        val signals = batch.filter { it.isUpdateSignal }
        _state.update { s ->
            val more = !s.hasNewer && !s.meets && batch.any { it.id > s.newerCursor && repo.shows(it) }
            if (signals.isEmpty() && !more) s
            else s.copy(messages = repo.withBatch(s.messages, signals), hasNewer = s.hasNewer || more)
        }
    }

    /** One message as [change] makes it, when it is held here. */
    internal fun change(messageId: Long, change: (ChatMessage) -> ChatMessage) = _state.update { s ->
        if (s.messages.none { it.id == messageId }) s
        else s.copy(messages = s.messages.map { if (it.id == messageId) change(it) else it })
    }

    /** What has disappeared for everyone by now. */
    internal fun dropExpired(nowSeconds: Long) = _state.update { s ->
        val left = s.messages.unexpired(nowSeconds)
        if (left === s.messages) s else s.copy(messages = left)
    }

    /** The history was deleted, as message [id] says. */
    internal fun dropBefore(id: Long) = _state.update { s ->
        if (s.messages.none { it.id < id }) s else s.copy(messages = s.messages.filter { it.id >= id }, hasOlder = false)
    }
}
