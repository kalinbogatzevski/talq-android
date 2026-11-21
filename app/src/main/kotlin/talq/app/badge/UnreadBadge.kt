package talq.app.badge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import talq.app.SessionState
import talq.app.data.NotifyLevelStore
import talq.app.push.NotificationGate
import talq.core.model.NotificationLevel
import talq.core.model.Room
import talq.core.repo.TalkSession

/**
 * The unread number on the launcher icon: all unread messages, leaving out conversations on
 * "never" and, inside the others, topics on "never" (the desktop app's rule for its tray
 * number with "count muted" off; there is no such switch here).
 *
 * Per conversation:
 * - on "never": 0;
 * - no topic of it is known to be on "never": the server's unread number;
 * - otherwise the conversation's unread messages are read ([talq.core.repo.UnreadTracker],
 *   nothing is marked read) and those in "never" topics are left out. Until that has
 *   answered, the server's number stands (showing too much for a moment is safe, hiding a
 *   message that is not muted is not).
 *
 * Recomputed after every conversation list that loads, every change of a stored level,
 * every push, whenever the app leaves the screen, and by [BadgeRefreshWorker] now and then
 * (messages that send no push would never move it otherwise). Nothing is shown before the
 * first list has loaded (a process started by a push must not wipe the number).
 *
 * Everything here runs on the main thread, except the launcher call itself.
 */
class UnreadBadge(
    context: Context,
    private val levels: NotifyLevelStore,
    private val currentSession: () -> TalkSession?,
    private val updaters: List<BadgeUpdater> = listOf(LauncherProviderBadge.Honor, LauncherProviderBadge.Huawei),
) {
    private val appContext = context.applicationContext
    private val requests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val writes = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val computing = Mutex()

    /** Which updaters this phone has; found out once, off the main thread. */
    private var available: List<BadgeUpdater>? = null

    private fun updatersHere(): List<BadgeUpdater> = synchronized(this) {
        available ?: updaters.filter { it.isAvailable(appContext) }.also { available = it }
    }

    /** This phone's home screen takes a number at all. */
    suspend fun supported(): Boolean = withContext(Dispatchers.IO) { updatersHere().isNotEmpty() }

    /** The number that should be on the icon; -1 = not known yet. */
    @Volatile
    private var wanted = -1

    /** The number the launcher last accepted. Only the writer touches it. */
    private var applied = -1

    @Volatile
    private var perRoom: Map<String, Int> = emptyMap()

    /** What one conversation adds to the number (0 included), as last worked out; null when it has not been yet. */
    fun roomCount(token: String): Int? = perRoom[token]

    /** Work the number out again soon. Cheap; calls that pile up become one. */
    fun request() {
        requests.tryEmit(Unit)
    }

    /** Runs for as long as the process lives. */
    suspend fun run(sessionState: StateFlow<SessionState>): Unit = coroutineScope {
        launch { writes.collect { write() } }
        sessionState.collectLatest { state ->
            when (state) {
                is SessionState.SignedIn -> coroutineScope {
                    val session = state.session
                    launch { session.rooms.rooms.collect { request() } }
                    launch { levels.version.drop(1).collect { request() } }
                    launch {
                        requests.collect {
                            // A burst (several pushes, a list and a level at once) becomes one pass.
                            delay(DEBOUNCE_MS)
                            recompute(session)
                        }
                    }
                }
                SessionState.SignedOut -> {
                    // No suspension between this and a pass's own "still signed in?" check
                    // (same thread), so a pass that is still running cannot put a number back.
                    perRoom = emptyMap()
                    scanned.clear()
                    apply(0)
                }
                // Loading or unreachable: what the icon shows stays.
                else -> Unit
            }
        }
    }

    /** Count now and wait for it (the conversation list is taken as it is). Main thread. */
    suspend fun refreshNow(session: TalkSession) = recompute(session)

    private suspend fun recompute(session: TalkSession) {
        try {
            computing.withLock {
                if (session !== currentSession() || !session.rooms.state.value.loaded) return
                val account = NotificationGate.accountKey(session)
                val pass = Pass()
                val counts = HashMap<String, Int>()
                for (room in session.rooms.rooms.value) counts[room.token] = counted(session, account, room, pass)
                // Signed out (or another account) while conversations were being read.
                if (session !== currentSession()) return
                perRoom = counts
                apply(counts.values.sum())
                // Conversations left for later: go on, unless the server is not answering.
                if (pass.skipped > 0 && pass.failed == 0) request()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the unread number could not be worked out", e)
        }
    }

    private class Pass(var scans: Int = 0, var failed: Int = 0, var skipped: Int = 0)

    /** The reading each conversation was last given. */
    private class Scanned(val session: TalkSession, val stamp: String, val lastRead: Long, val unread: Int)

    private val scanned = HashMap<String, Scanned>()

    private suspend fun counted(session: TalkSession, account: String, room: Room, pass: Pass): Int {
        if (room.notificationLevel == NotificationLevel.NEVER || room.unreadMessages <= 0) return 0
        val silenced = levels.silencedTopics(account, room.token)
        if (silenced.isEmpty()) return room.unreadMessages

        val tracker = session.unread(room.token)
        // "newest message / read marker / unread": a reading holds for as long as these do.
        val stamp = "${room.lastMessage?.id ?: 0L}/${room.lastReadMessage}/${room.unreadMessages}"
        fun current(): Boolean {
            val last = scanned[room.token] ?: return false
            return last.session === session && last.stamp == stamp && tracker.state.value.loaded
        }
        if (!current()) {
            if (pass.scans < MAX_SCANS) {
                pass.scans++
                val ok = withTimeoutOrNull(SCAN_TIMEOUT_MS) { tracker.refresh() } == true
                if (ok) {
                    scanned[room.token] = Scanned(session, stamp, room.lastReadMessage, room.unreadMessages)
                } else {
                    pass.failed++
                }
            } else {
                pass.skipped++
            }
        }
        val state = tracker.state.value
        if (!current()) {
            // Not read (yet): the server's number. What an older reading knows to be muted is
            // only taken off while it is certainly still part of that number (same read
            // marker, unread not shrunk).
            val last = scanned[room.token]
            val known = state.loaded && last != null && last.session === session &&
                last.lastRead == room.lastReadMessage && room.unreadMessages >= last.unread
            return (room.unreadMessages - if (known) state.pendingIn(silenced) else 0).coerceAtLeast(0)
        }
        // What the conversation's own tabs show as unread, without the silenced topics.
        val shown = state.byTopic.filterKeys { it !in silenced }.values.sumOf { it.count }
        return if (state.complete) shown
        // Unread reaches further back than was read: the server's number is the better guess.
        else maxOf(shown, room.unreadMessages - state.pendingIn(silenced))
    }

    private fun apply(count: Int) {
        wanted = count
        writes.tryEmit(Unit)
    }

    /** Hand [wanted] to the launcher. A refusal is tried again with the next pass. */
    private suspend fun write() {
        val count = wanted
        if (count < 0 || count == applied) return
        val ok = withContext(Dispatchers.IO) {
            val here = updatersHere()
            here.isEmpty() || here.map { it.set(appContext, count) }.any { it }
        }
        if (ok) applied = count
    }

    private companion object {
        const val TAG = "TalqBadge"
        const val SCAN_TIMEOUT_MS = 8_000L
        const val DEBOUNCE_MS = 400L

        /** Conversations read per pass; any beyond that keep the server's number until the next pass. */
        const val MAX_SCANS = 8
    }
}
