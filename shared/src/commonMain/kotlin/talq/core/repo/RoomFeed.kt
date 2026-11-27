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
import kotlin.concurrent.Volatile
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
    /** The session's [Waker]: the network is usable again, a failed poll goes now. */
    private val waker: Waker,
    /** The poll works again after it had failed. */
    private val onRecovered: () -> Unit,
    /** The server refuses the chat to the user from now on ([closesChat]). */
    private val onRefused: (TalkError) -> Unit,
) {
    private val _incoming = MutableSharedFlow<List<ChatMessage>>(extraBufferCapacity = 64)
    private val _error = MutableStateFlow<TalkError?>(null)
    private val cursor = MutableStateFlow<Long?>(null)
    private val _commonRead = MutableStateFlow(0L)
    private val mutex = Mutex()
    private var users = 0
    private var job: Job? = null

    /** Where the poll that runs began: every message after this one comes through [incoming]. */
    @Volatile
    var began: Long = Long.MAX_VALUE
        private set

    /** Batches of new messages, oldest first, unfiltered (update signals included). */
    val incoming: SharedFlow<List<ChatMessage>> = _incoming.asSharedFlow()

    /** Non-null while the poll is failing (offline, signed out, removed from the room). */
    val error: StateFlow<TalkError?> = _error.asStateFlow()

    /**
     * The newest message every participant who shares their read status has read, as the
     * server last said (0 = unknown, or the user does not share theirs). Taken as given, not
     * as a maximum: it can move back when someone joins, and the poll must send the server's
     * own value back or it answers at once forever.
     */
    val commonRead: StateFlow<Long> = _commonRead.asStateFlow()

    private fun noteCommonRead(value: Long?) {
        if (value != null) _commonRead.value = value
    }

    /**
     * Pin the poll's starting point to the room's newest message. A repository calls this
     * BEFORE it loads its first page: anything that arrives after this point comes through
     * the poll, anything before it is in the page, so nothing falls in between. Returns it:
     * every message up to it was stored before the page is asked for.
     */
    suspend fun ensureCursor(): Long {
        cursor.value?.let { return it }
        return mutex.withLock {
            cursor.value ?: run {
                val newest = client.history(token, limit = 1)
                noteCommonRead(newest.lastCommonRead)
                (newest.messages.lastOrNull()?.id ?: 0L).also { cursor.value = it }
            }
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
        began = from
        var backoff = MIN_BACKOFF
        while (scope.isActive) {
            val wakes = waker.mark()
            try {
                val asked = TimeSource.Monotonic.markNow()
                // After a failure the server is asked to answer at once (no 30 s wait), so a
                // working connection clears the error, and its line, without delay.
                val wait = if (_error.value != null) RECOVERY_WAIT_SECONDS else POLL_WAIT_SECONDS
                // The network came back meanwhile: this poll may be on a dead connection. Asked
                // again at once from the same cursor (it only moves on an answer: nothing is lost).
                val page = waker.unlessWoken {
                    client.pollMessages(token, from, timeoutSeconds = wait, lastCommonReadId = _commonRead.value)
                } ?: continue
                if (_error.value != null) {
                    _error.value = null
                    onRecovered()
                }
                noteCommonRead(page.lastCommonRead)
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
                val before = _error.value
                _error.value = e
                if (e.closesChat && before?.closesChat != true) onRefused(e)
                // A dead session or a room we were removed from will not heal by hammering it.
                val fatal = e is TalkError.Unauthorized || e is TalkError.NotFound || e is TalkError.Forbidden
                if (fatal) {
                    delay(MAX_BACKOFF)
                } else {
                    // The network usable again ends the wait, and the backoff starts over.
                    val woken = waker.pause(backoff, since = wakes)
                    backoff = if (woken) MIN_BACKOFF else (backoff * 2).coerceAtMost(MAX_BACKOFF)
                }
            }
        }
    }

    private companion object {
        const val MIN_BACKOFF = 2_000L
        const val MAX_BACKOFF = 30_000L
        const val POLL_WAIT_SECONDS = 30
        const val RECOVERY_WAIT_SECONDS = 1
        val QUICK_ANSWER = 3.seconds
    }
}
