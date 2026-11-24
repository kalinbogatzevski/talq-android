package talq.core.folders

import kotlin.math.roundToLong

// The numbers and small decisions of folder sync, ported from TalQ for desktop
// (src/core/FolderSyncPolicy.h and src/core/DavSyncPolicy.h): the controller's states and pauses,
// every timing it uses, the retry ladder, the two brakes that stop a sync from losing folders, the
// counter for a server that rejects the ETag it just handed out, and how an HTTP status of a
// WebDAV request is read. No clock, no I/O: callers feed monotonic millisecond readings, never the
// wall clock.

/** What the sync controller is doing. Off is the zero value: a controller that does nothing. */
enum class FolderSyncState {
    /** The switch is off, or no account is attached: no request is ever made. */
    Off,
    /** The capability probe, or the first cycle after the switch was turned on. */
    SettingUp,
    /** On; the last attempt succeeded (or nothing has run yet); nothing in flight. */
    Idle,
    /** A cycle (or the probe) is in flight. */
    Syncing,
    /** A transient failure, backing off; or the account is not ready (status line only). */
    Waiting,
    /** Needs attention; [FolderSyncPause] says why. */
    Paused,
}

/** Why the controller is Paused. None is what "not paused" reads as. */
enum class FolderSyncPause {
    None,
    /** 401/403; clears on sign-in again or a switch toggle, never retried. */
    AuthRefused,
    /** The shared file was written by a newer version. */
    RemoteNewer,
    /** Not JSON, over the cap, needs repair, or a download stopped for size. */
    RemoteUnreadable,
    /** The MERGE is over the 50-folder / 20000-filing cap; a local edit can end it. */
    TooLarge,
    /** HTTP 413 on the upload: the server refused the file as too large. */
    ServerSizeLimit,
    /** The probe failed, or two self-inconsistent 412s: the server rejects its own ETag. */
    ServerUnsupported,
    /** HTTP 507; retried on the slowest backoff step. */
    QuotaFull,
    /** The local store is read-only or detached (status line only, no notice). */
    StoreUnavailable,
    /** The shrink brake fired; clears only on "Sync now". */
    RemoteShrank,
    /** A status the table does not name, or a 409. */
    Fatal,
}

/** The pause's name in the per-cycle log line (desktop's names; "none" when not paused). */
fun folderSyncPauseName(p: FolderSyncPause): String = if (p == FolderSyncPause.None) "none" else p.name

/**
 * Every gap and interval of the controller, in milliseconds. The defaults are desktop's; a check
 * shortens them instead of waiting.
 */
data class FolderSyncTiming(
    /** After attach with the switch on, before the first cycle. */
    val loginDelayMs: Long = 3_000,
    /** Coming to the front pulls at most this often. */
    val activationGapMs: Long = 120_000,
    /** A push on leaving the front waits this long since the last attempt. */
    val pushGapMs: Long = 15_000,
    /** Coming to the front may cut a backoff short after this gap. */
    val backoffOverrideGapMs: Long = 30_000,
    /** Quiet time after the last local edit before a cycle ... */
    val editDebounceMs: Long = 10_000,
    /** ... but never more than this after the FIRST unsent edit. */
    val editMaxWaitMs: Long = 60_000,
    /** The plain-GET poll (on this platform only while the app is in front). */
    val pollMs: Long = 600_000,
    /** Transfer timeout of the GET. */
    val getTimeoutMs: Long = 20_000,
    /** Transfer timeout of the conditional PUT (and the probe's DELETE). */
    val putTimeoutMs: Long = 30_000,
    /** How often a held remote apply asks the gate again ... */
    val applyGatePollMs: Long = 500,
    /** ... and when it gives up (the next cycle applies it). */
    val applyGateGiveUpMs: Long = 30_000,
    /** A 412 re-GET waits a random time in [min, max]. */
    val conflictRetryMinMs: Long = 200,
    val conflictRetryMaxMs: Long = 800,
    /**
     * The replace gap: a PUT that REPLACES the file (If-Match) goes out no sooner than this after
     * the answer of the GET it was merged from; a create (If-None-Match: *) never waits. The server
     * keeps a file's ETag for a second write in the same whole second, so without the gap a stale
     * If-Match could be accepted (a lost update). Over 1000 on purpose.
     */
    val replaceGapMs: Long = 1_200,
    /** Every ladder wait is multiplied by this (floored at 1 ms). A check seam only; production keeps 1.0. */
    val backoffScale: Double = 1.0,
)

/** Conditional-write attempts in one cycle (a 412 or a 404 on a PUT goes back to the GET). */
const val MAX_PUT_ATTEMPTS = 3

/** Consecutive Stale restarts (the user edited while a cycle was out) before the cycle stops and waits for the next trigger. */
const val MAX_STALE_RESTARTS = 5

/** Does a device's OWN upload raise a "folders updated" notice? No (a notice per tap would be noise). */
const val NOTIFY_ON_UPLOAD = false

private val LADDER_MS = longArrayOf(30_000, 60_000, 120_000, 300_000, 600_000, 900_000)

/**
 * The wait before the next attempt after [failures] consecutive transient failures (1 after the
 * first): 30 s, 1 min, 2 min, 5 min, 10 min, then 15 min for every later failure; a count of 1 or
 * less (0 and negatives included) is the first step. [unit] is a draw in [0,1) made by the caller;
 * the wait is the step times 0.8 + 0.4 * unit (plus or minus 20 %), rounded to the millisecond (ties
 * away from zero, as desktop's llround; every value here is positive). A unit outside [0,1], and
 * NaN, is clamped, so the result is always within 24 s .. 18 min.
 */
fun nextBackoffMs(failures: Int, unit: Double): Long {
    val steps = LADDER_MS.size
    val step = if (failures <= 1) 0 else if (failures > steps) steps - 1 else failures - 1
    val u = if (!(unit >= 0.0)) 0.0 else if (unit > 1.0) 1.0 else unit   // !(>=) is also true for NaN
    return (LADDER_MS[step].toDouble() * (0.8 + 0.4 * u)).roundToLong()
}

/**
 * The remote shrink brake. [foldersDropped] is the merge's count of folders in the base AND in this
 * device's local state that the remote lacks: a device's own deletions are never counted, and an
 * untrusted base counts none, so a first enable never brakes. Fires when the remote would remove
 * EVERY folder this device has (at least one), or MORE THAN HALF of them with at least 3. Exactly
 * half does not fire. Argument order is (local, dropped).
 */
fun remoteShrinkBrake(localFolderCount: Int, foldersDropped: Int): Boolean {
    if (foldersDropped >= 1 && foldersDropped == localFolderCount) return true
    return foldersDropped >= 3 && 2 * foldersDropped > localFolderCount
}

/**
 * The minimum upload brake: a cycle never uploads an EMPTY state over a remote that is absent (404)
 * or zero bytes. That would turn "nobody wrote anything yet" into "everyone deleted everything" for
 * every device that trusts its base. Over a readable remote an empty merge is a deliberate
 * delete-everything and goes up.
 */
fun uploadAllowed(mergedIsEmpty: Boolean, remoteAbsentOrZeroBytes: Boolean): Boolean =
    !(mergedIsEmpty && remoteAbsentOrZeroBytes)

/**
 * "A self-inconsistent 412". A race changes the file, so after a 412 the next GET returns a
 * DIFFERENT ETag. When it returns the very ETag the failed PUT was conditioned on, nobody wrote in
 * between: the server (or a proxy) rejected an ETag it had just handed out. Two of those, in one
 * cycle or in two consecutive cycles, pause sync as ServerUnsupported instead of backing off for
 * ever.
 *
 * [putFailed412] (with the If-Match value) ARMS it; [nextGet] (with the re-GET's ETag) CONSUMES the
 * arm. A GET with nothing armed counts for nothing. ETags are compared exactly (quotes and any W/
 * prefix kept); an EMPTY ETag on either side is never "the same" and reads as a race. The count
 * outlives a cycle, so the caller owns one tracker per attached account; a successful PUT, a
 * switch toggle, a sign-out and "Sync now" from ServerUnsupported call [reset].
 */
class SelfInconsistent412Tracker {
    private var failedEtag = ""
    private var armed = false

    var count: Int = 0
        private set

    val shouldPause: Boolean get() = count >= PAUSE_AT

    fun putFailed412(ifMatchEtag: String) {
        failedEtag = ifMatchEtag
        armed = true
    }

    fun nextGet(etag: String) {
        if (!armed) return
        armed = false
        if (failedEtag.isNotEmpty() && etag == failedEtag) count++ else count = 0
    }

    fun reset() {
        failedEtag = ""
        armed = false
        count = 0
    }

    private companion object {
        const val PAUSE_AT = 2
    }
}

/**
 * The "Folders not syncing" notice for transient failures: at least 3 consecutive failed attempts
 * spanning at least 10 minutes (from the first failure of the run to the latest), and the server
 * reachable (an offline period never notifies). Both thresholds are inclusive.
 */
const val TRANSIENT_NOTICE_MIN_FAILURES = 3
const val TRANSIENT_NOTICE_MIN_SPAN_MS = 10L * 60 * 1000

fun transientNoticeDue(consecutiveFailures: Int, spanMs: Long, serverReachable: Boolean): Boolean =
    serverReachable && consecutiveFailures >= TRANSIENT_NOTICE_MIN_FAILURES && spanMs >= TRANSIENT_NOTICE_MIN_SPAN_MS

// ---------------------------------------------------------------------------------------------
// How the status of a folder-sync WebDAV request is read (desktop's DavSyncPolicy.h). The probe
// and the cycle read every answer through classifyDav, so they agree on what a status means. A
// sync failure is never fed to the app's own "offline" state.
//
//   200, 204 and every other 2xx but 201 ... Ok
//   201 .................................... Created
//   404 .................................... NotFound           GET: remote absent, recreate it
//                                                               from local; PUT: re-GET
//   412 .................................... PreconditionFailed not an error: re-GET and re-merge
//   401, 403 ............................... AuthRefused        pause; never retry, never sign out
//   409 .................................... Conflict           unexpected for a root file: pause
//   413 .................................... PayloadTooLarge    its own pause
//   423 .................................... Locked             transient
//   507 .................................... QuotaFull          pause, retry on the slow step
//   0, 408, 429, 500, 502, 503, 504 ........ Transient          back off (0 = no HTTP answer)
//   aborted ................................ Cancelled          silent
//   anything else (1xx, 3xx, 600, -1, ...) . Fatal              pause
// A 2xx whose transfer broke off is read as status 0 before it gets here (FolderDav), so a cut-short
// body never reads as "nobody wrote yet".

enum class DavOutcome {
    Ok,
    Created,
    PreconditionFailed,
    NotFound,
    Conflict,
    Locked,
    PayloadTooLarge,
    QuotaFull,
    AuthRefused,
    Transient,
    Cancelled,
    Fatal,
}

/** [aborted] wins over any status: a request this app cancelled on purpose is not a server answer. */
fun classifyDav(httpStatus: Int, aborted: Boolean): DavOutcome {
    if (aborted) return DavOutcome.Cancelled
    return when (httpStatus) {
        0, 408, 429, 500, 502, 503, 504 -> DavOutcome.Transient
        201 -> DavOutcome.Created
        404 -> DavOutcome.NotFound
        412 -> DavOutcome.PreconditionFailed
        401, 403 -> DavOutcome.AuthRefused
        409 -> DavOutcome.Conflict
        413 -> DavOutcome.PayloadTooLarge
        423 -> DavOutcome.Locked
        507 -> DavOutcome.QuotaFull
        in 200..299 -> DavOutcome.Ok
        else -> DavOutcome.Fatal
    }
}
