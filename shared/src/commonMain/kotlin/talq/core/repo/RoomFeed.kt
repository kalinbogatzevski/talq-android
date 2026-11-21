package talq.core.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import talq.core.TalkClient
import talq.core.TalkError
import talq.core.model.ChatMessage
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The single long-poll of one room. Every [ChatRepository] of the room (whole chat, General,
 * each topic) listens to this one feed and filters locally, so a room never has more than one
 * poll open no matter how many of its views are alive.
 */
internal class RoomFeed(
    private val client: TalkClient,
    private val token: String,
    private val scope: CoroutineScope,
) {
    private val _incoming = MutableSharedFlow<List<ChatMessage>>(extraBufferCapacity = 64)
    private val _error = MutableStateFlow<TalkError?>(null)
    private val cursor = MutableStateFlow<Long?>(null)
    private val mutex = Mutex()
    private var users = 0
    private var job: Job? = null

    /** Batches of new messages, oldest first, unfiltered (update signals included). */
    val incoming: SharedFlow<List<ChatMessage>> = _incoming.asSharedFlow()

    /** Non-null while the poll is failing (offline, signed out, removed from the room). */
    val error: StateFlow<TalkError?> = _error.asStateFlow()

    /**
     * Pin the poll's starting point to the room's newest message. A repository calls this
     * BEFORE it loads its first page: anything that arrives after this point comes through
     * the poll, anything before it is in the page, so nothing falls in between.
     */
    suspend fun ensureCursor() {
        if (cursor.value != null) return
        mutex.withLock {
            if (cursor.value != null) return
            cursor.value = client.history(token, limit = 1).messages.lastOrNull()?.id ?: 0L
        }
    }

    suspend fun acquire() = mutex.withLock {
        users++
        if (job?.isActive != true) {
            // A restart after a pause pins the cursor afresh (the repository that restarts
            // loads the newest page right after this), instead of replaying everything that
            // was posted while nobody was looking.
            cursor.value = null
            job = scope.launch { loop() }
        }
    }

    suspend fun release() = mutex.withLock {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            job?.cancel()
            job = null
        }
    }

    private suspend fun loop() {
        var from = cursor.filterNotNull().first()
        var backoff = MIN_BACKOFF
        while (scope.isActive) {
            try {
                val asked = TimeSource.Monotonic.markNow()
                val page = client.pollMessages(token, from)
                _error.value = null
                backoff = MIN_BACKOFF
                // The server's own cursor counts comments this user is not shown (an expired
                // message, a command reply). Following only the visible ones would leave the
                // cursor behind such a comment and every poll would answer at once.
                val next = maxOf(from, page.lastGiven ?: 0L, page.messages.lastOrNull()?.id ?: 0L)
                val moved = next > from
                if (moved) {
                    from = next
                    cursor.value = from
                }
                if (!page.isEmpty) _incoming.emit(page.messages)
                // Nothing new and no wait on the server's side: never spin.
                if (page.isEmpty && !moved && asked.elapsedNow() < QUICK_ANSWER) delay(MIN_BACKOFF)
            } catch (e: TalkError) {
                _error.value = e
                // A dead session or a room we were removed from will not heal by hammering it.
                val fatal = e is TalkError.Unauthorized || e is TalkError.NotFound || e is TalkError.Forbidden
                delay(if (fatal) MAX_BACKOFF else backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
            }
        }
    }

    private companion object {
        const val MIN_BACKOFF = 2_000L
        const val MAX_BACKOFF = 30_000L
        val QUICK_ANSWER = 3.seconds
    }
}
