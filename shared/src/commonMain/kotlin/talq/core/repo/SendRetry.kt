package talq.core.repo

import talq.core.TalkError
import talq.core.net.neverLeftThePhone

/**
 * The rules for sending an unsent text again: TalQ for desktop's `src/core/SendRetryPolicy.h` (which
 * also holds the evidence), used by [TextOutbox]. In short:
 *
 * - "Failed" means UNKNOWN, not "undelivered": the server stores a message before it answers, and it
 *   does NOT take the `referenceId` as a key, so the same request twice is two messages. A text that
 *   may have reached it is therefore looked for in the chat's history first ([provablyAbsent]).
 * - "Not on the page" is no proof. The server cuts its window to `limit` raw comments and only then
 *   drops what the user may not see. `X-Chat-Last-Given` names the oldest raw comment of the window;
 *   a message stored after we knew id N has an id above N, so the window covers it when it reaches
 *   back to N + 1.
 *
 * Decided differences from desktop: every 4xx but 408 and 429 stops at once (desktop tries a 400 once
 * more); the wait after a hung attempt ([SLOW_SETTLE_MILLIS]) is not cut short by a recovery hint.
 */
internal object SendRetry {
    /**
     * Worth trying again by itself: no answer, a server error, or an answer that is none (the outcome
     * is unknown, or the trouble passes). Everything else the server refused: the same again would
     * be refused again (and a 401 must not be hammered).
     */
    fun retryable(e: TalkError): Boolean = when (e) {
        is TalkError.Network, is TalkError.Server, is TalkError.Protocol, is TalkError.QuotaExceeded -> true
        is TalkError.Http -> e.status == 408 || e.status == 429 || e.status !in 400..499
        else -> false
    }

    /** The request failed before anything could leave the phone: it cannot be on the server. */
    fun neverSent(e: TalkError): Boolean = e is TalkError.Network && e.cause?.let { neverLeftThePhone(it) } == true

    /** Posts, the first included, before a text no longer goes by itself (a Retry or a new network starts over). */
    const val POST_BUDGET = 5

    /** Wait after the [posts]th failed post (1..), also the time a request that outlived us has to be stored. */
    fun retryDelay(posts: Int): Long = when {
        posts <= 1 -> 5_000L
        posts == 2 -> 15_000L
        posts == 3 -> 45_000L
        posts == 4 -> 120_000L
        else -> 300_000L
    }

    /** Wait after the [failures]th look at the server in a row that got nowhere: 5 s doubling to 60 s. Costs no post. */
    fun probeDelay(failures: Int): Long {
        var d = 5_000L
        repeat((failures - 1).coerceIn(0, 10)) { d = (d * 2).coerceAtMost(PROBE_MAX_MILLIS) }
        return d
    }

    private const val PROBE_MAX_MILLIS = 60_000L

    /** An attempt that hung this long before it failed may still be on its way (a busy server, a phone that repeats what it had sent). */
    const val SLOW_FAILURE_MILLIS = 25_000L

    /** Nothing looks for such a one, or sends it again, earlier than this after it failed. */
    const val SLOW_SETTLE_MILLIS = 120_000L

    /** A recovery hint pulls the next try forward, but never closer than this to the last activity (flapping links). */
    const val MIN_SPACING_MILLIS = 3_000L

    /** [nextTry] after a recovery hint: sooner, never later, and never before [settleUntil]. */
    fun kicked(nextTry: Long, now: Long, lastActivity: Long, settleUntil: Long): Long =
        minOf(nextTry, maxOf(now, lastActivity + MIN_SPACING_MILLIS, settleUntil))

    /** How much of a history a look has read: down to the raw comment [oldestRaw] (0 = unknown), or [whole]. */
    class Window(val oldestRaw: Long, val whole: Boolean)

    /**
     * A text that was not found in [window] is proved not to be on the server. [knownNewest]: the
     * newest message id of the room known before it was first posted, 0 = none. [possibleExpiry]:
     * the conversation's messages disappear, and one posted then could be gone by now.
     * [trusted] false (a conversation on another server, whose history is passed on to us): there
     * only finding it says something.
     */
    fun provablyAbsent(window: Window, knownNewest: Long, possibleExpiry: Boolean, trusted: Boolean): Boolean = when {
        !trusted || possibleExpiry -> false
        window.whole -> true
        else -> window.oldestRaw > 0 && knownNewest > 0 && window.oldestRaw <= knownNewest + 1
    }
}
