package talq.core.folders

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.TimeSource

// One folder-sync cycle and the capability probe, ported from TalQ for desktop
// (src/core/FolderSyncController.cpp: startProbe .. probePassed, takeSnapshot .. recordSuccess) as
// two suspend functions. What desktop's controller does around them (the switch, the triggers and
// their gaps, the backoff timer, the pause rules, the probe marker, the sidecar writes, the notices)
// is the app's, which calls these with what they need through the interfaces below.
//
// The cycle: 1. guards (the caller's, plus "account known" here); 2. snapshot (generation, local
// state, base choice); 3. GET; 4. merge, then the shrink brake; 5. PUT only when the merged STATE
// differs from the remote's (never by bytes), conditional (If-Match with the GET's ETag, or
// If-None-Match: * when the file is absent), a replace no sooner than replaceGapMs after the GET's
// answer, a 412 or 404 back to step 3 at most MAX_PUT_ATTEMPTS times; 6. apply, through the store's
// generation check (a user edit meanwhile is Stale: back to step 2, at most MAX_STALE_RESTARTS
// times). Step 7, recording the merged state as the new base, is the caller's (CycleEnd.Synced).
// Write order: upload, then local apply, then the caller's sidecar; a crash anywhere is safe
// because the merge is idempotent.
//
// Cancelling the calling coroutine cancels the request in flight and ends everything: sign-out
// must cancel the sync job BEFORE the session's app password is revoked.

/** The shared file at the root of the user's own WebDAV home. One per Nextcloud user, shared by every device. */
const val REMOTE_FOLDERS_FILE = ".talq-folders.json"

/** The probe's scratch file: the shared file's dot-name pattern plus 8 random lowercase hex digits. */
const val PROBE_FILE_PREFIX = ".talq-folders.probe-"

/** Step 4's If-Match: an ETag no server has handed out (the quotes are part of it). */
const val PROBE_BOGUS_ETAG = "\"talq-probe-bogus\""

/** Every probe PUT's body: small, but not empty, so every PUT really sends one. */
internal val PROBE_BODY: ByteArray = "{}".encodeToByteArray()

private val NO_BYTES = ByteArray(0)

/**
 * A GET's answer. [status] 0 means no HTTP answer, or a 2xx whose transfer broke off (a cut-short
 * body must never read as "nobody wrote yet"). [etag] is OC-ETag if sent, else ETag, exactly as
 * sent (quotes and any W/ kept), empty when there is none. [oversize]: the body passed
 * MAX_FOLDER_FILE_BYTES (declared or received), whatever the status, and was not read; [body] is
 * then empty. The cycle reads it before the status (desktop: RemoteUnreadable); the probe never does.
 */
class DavGet(val status: Int, val etag: String = "", val body: ByteArray = NO_BYTES, val oversize: Boolean = false)

/**
 * The account's WebDAV home as the sync sees it ([FolderDav]; a check uses a fake). Every call is
 * ONE request; a status is returned, never thrown; a transport failure is status 0; cancellation of
 * the caller is rethrown. No redirect is ever followed (a 3xx comes back as the answer).
 */
interface FolderRemote {
    /** False while the account's canonical user id is not known: nothing may be sent then. */
    val accountKnown: Boolean

    suspend fun get(name: String): DavGet

    /** [ifMatch] null: create, with If-None-Match: *. Otherwise replace, with If-Match: [ifMatch] (non-empty). Content-Type application/json. */
    suspend fun put(name: String, body: ByteArray, ifMatch: String?): Int

    suspend fun delete(name: String): Int
}

/** What the cycle takes from the store at a snapshot: the generation and the state, read together. */
data class LocalSnapshot(val generation: Long, val state: FolderState)

/** desktop's FolderStore::ApplyResult. */
enum class ApplyResult {
    /** Set and saved. */
    Applied,
    /** Set in memory; the write failed (the store is dirty: the caller must not write the sidecar). */
    AppliedNotSaved,
    /** Already that state. */
    Unchanged,
    /** The generation moved (the user edited meanwhile) or the account changed: the base must NOT move. */
    Stale,
    NotAttached,
    ReadOnly,
    /** Over a cap or not sanitised: refused. */
    Invalid,
}

/**
 * The account's folder store as the cycle sees it: one attachment (one account). Called on the
 * caller's dispatcher only.
 */
interface FolderLocal {
    /** Still attached to the account this cycle started for. Checked after every wait. */
    fun isAttached(): Boolean

    fun snapshot(): LocalSnapshot

    /** May a remote change be applied now? Desktop holds it during a drag or an open folder dialog. */
    fun applyAllowed(): Boolean = true

    /**
     * Replace the state with [merged] if the generation is still [expectedGeneration]. A remote
     * apply is NOT a user edit: it must never arm the edit debounce or count as an edit, or two
     * devices would upload each other's changes back and forth. Before a change that
     * removesFoldersOrFilings, the store keeps its backup copy.
     */
    suspend fun applyMerged(expectedGeneration: Long, merged: FolderState): ApplyResult
}

/** Monotonic time only (never the wall clock), and a sleep a check can make instant. */
interface FolderSyncClock {
    fun nowMs(): Long

    suspend fun sleep(ms: Long)
}

object MonotonicFolderSyncClock : FolderSyncClock {
    private val origin = TimeSource.Monotonic.markNow()

    override fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds

    override suspend fun sleep(ms: Long) {
        if (ms > 0) delay(ms)
    }
}

// ---------------------------------------------------------------------------------------------
// The capability probe

enum class ProbeVerdict {
    /** All five steps answered as a server that honours conditional writes does. */
    Pass,
    /** The server answered wrongly: a precondition ignored, no ETag, a 404/409/413, a stray 3xx, ... */
    Refused,
    /** 401/403. */
    AuthRefused,
    /** No answer, a 423 or a 507: try again later. */
    CouldNotRun,
}

/** [stoppedAtStep] is 0 on a pass, else the step (1..5) whose answer decided. [lastStatus] -1: no answer. */
class ProbeResult(val verdict: ProbeVerdict, val stoppedAtStep: Int, val lastStatus: Int, val requests: Int)

/**
 * The capability probe, before the real file is ever touched on a server (desktop D17). On a scratch
 * file `PROBE_FILE_PREFIX + 8 random hex digits`, body `{}`:
 *   1. PUT If-None-Match: *          must answer 2xx (a 412 is Refused: the name is not ours)
 *   2. GET                           must answer 2xx WITH an ETag (e1)
 *   3. PUT If-None-Match: *          must answer 412
 *   4. PUT If-Match: "talq-probe-bogus"  must answer 412
 *   5. PUT If-Match: e1              must answer 2xx
 *   6. DELETE, best effort (awaited, its answer ignored), after a pass, and after any failure once
 *      step 1 answered 2xx or transient (it may have made the file), never after a 401/403.
 * Each answer: 2xx passes (Refused at steps 3 and 4); 412 passes at steps 3 and 4 (Refused
 * elsewhere); 401/403 AuthRefused; transient, 423, 507 CouldNotRun; 404, 409, 413 and every other
 * status Refused. The caller checks [FolderRemote.accountKnown] first (without it this answers
 * CouldNotRun and sends nothing), writes the probe marker after a pass (a marker it cannot write
 * makes the pass a CouldNotRun) and never runs a cycle without one.
 */
suspend fun runFolderProbe(remote: FolderRemote, random: Random = Random.Default): ProbeResult {
    if (!remote.accountKnown) return ProbeResult(ProbeVerdict.CouldNotRun, 0, -1, 0)
    val suffix = (random.nextInt().toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
    val name = PROBE_FILE_PREFIX + suffix
    var requests = 0
    var lastStatus = -1
    var created = false
    var e1 = ""
    for (step in 1..5) {
        val answer: DavGet = when (step) {
            1, 3 -> DavGet(remote.put(name, PROBE_BODY, null))
            2 -> remote.get(name)
            4 -> DavGet(remote.put(name, PROBE_BODY, PROBE_BOGUS_ETAG))
            else -> DavGet(remote.put(name, PROBE_BODY, e1))
        }
        requests++
        lastStatus = answer.status
        val outcome = classifyDav(answer.status, false)
        // Step 1 may have made the file even without a clean 2xx (a transfer that broke off after
        // the server stored it, a 5xx from a proxy after the server wrote): the DELETE is owed.
        if (step == 1 && (outcome == DavOutcome.Ok || outcome == DavOutcome.Created || outcome == DavOutcome.Transient)) {
            created = true
        }
        val expect412 = step == 3 || step == 4
        var verdict = when (outcome) {
            DavOutcome.Ok, DavOutcome.Created -> if (expect412) ProbeVerdict.Refused else ProbeVerdict.Pass
            DavOutcome.PreconditionFailed -> if (expect412) ProbeVerdict.Pass else ProbeVerdict.Refused
            DavOutcome.AuthRefused -> ProbeVerdict.AuthRefused
            DavOutcome.Transient, DavOutcome.Locked, DavOutcome.QuotaFull -> ProbeVerdict.CouldNotRun
            DavOutcome.NotFound, DavOutcome.Conflict, DavOutcome.PayloadTooLarge, DavOutcome.Fatal -> ProbeVerdict.Refused
            DavOutcome.Cancelled -> ProbeVerdict.CouldNotRun   // never: classifyDav is asked with aborted = false
        }
        if (verdict == ProbeVerdict.Pass && step == 2) {
            e1 = answer.etag   // byte for byte, as the cycle takes it
            if (e1.isEmpty()) verdict = ProbeVerdict.Refused   // the server sends no file version
        }
        if (verdict != ProbeVerdict.Pass) {
            if (created && verdict != ProbeVerdict.AuthRefused) {
                remote.delete(name)
                requests++
            }
            return ProbeResult(verdict, step, lastStatus, requests)
        }
    }
    remote.delete(name)   // step 6
    requests++
    return ProbeResult(ProbeVerdict.Pass, 0, lastStatus, requests)
}

// ---------------------------------------------------------------------------------------------
// One cycle

/** How a cycle ended. */
sealed interface CycleEnd {
    /**
     * Success. The caller records [merged] as the new base (the sidecar only while the store is
     * clean), clears the failure count and sets lastSynced. [local] is the snapshot the last merge
     * was made from; [diff] is what the user would see change (RemoteApplied when visible, unless
     * this was the first cycle after the switch). A device's own upload is never a notice.
     */
    class Synced(val local: FolderState, val merged: FolderState, val uploaded: Boolean) : CycleEnd {
        val applied: Boolean get() = merged != local
        val diff: StateDiff get() = diffStates(local, merged)
    }

    /**
     * Needs attention. For [FolderSyncPause.RemoteShrank], [shrinkDropped] of [shrinkOf] folders
     * would go ("Another device removed N of your M folders"). For ServerUnsupported the caller also
     * deletes the probe marker. A 401/403 in the first cycle after the switch was turned on turns
     * the switch back off instead (the caller's rule).
     */
    class Paused(val pause: FolderSyncPause, val shrinkDropped: Int = 0, val shrinkOf: Int = 0) : CycleEnd

    /** A transient failure: back off on the ladder. */
    class Failed(val why: String) : CycleEnd

    /** Neither success nor failure (nothing applied or recorded): the next trigger starts over. */
    class Ended(val why: String) : CycleEnd

    /** The account's user id is not known: nothing was sent, no attempt counted ("Waiting"). */
    data object AccountNotReady : CycleEnd
}

/** [lastStatus] -1: no answer arrived. [requests] counts every request this cycle sent. */
class CycleResult(val end: CycleEnd, val requests: Int, val lastStatus: Int, val elapsedMs: Long) {
    /** The `outcome=` of the per-cycle log line. */
    val outcomeName: String
        get() = when (end) {
            is CycleEnd.Synced -> when {
                end.uploaded && end.applied -> "uploaded+applied"
                end.uploaded -> "uploaded"
                end.applied -> "applied"
                else -> "no-change"
            }
            is CycleEnd.Paused -> "paused:" + folderSyncPauseName(end.pause)
            is CycleEnd.Failed -> "failed"
            is CycleEnd.Ended -> "ended"
            CycleEnd.AccountNotReady -> "account-not-ready"
        }
}

/** The class of a cycle's last HTTP answer for its log line: "-" none, net-error for 0, "other" outside 100..599, else "Nxx". */
fun folderSyncHttpClass(status: Int): String = when {
    status < 0 -> "-"
    status == 0 -> "net-error"
    status < 100 || status > 599 -> "other"
    else -> "${status / 100}xx"
}

/**
 * Desktop's one summary line per cycle: enums and counts only, never a folder, chat, path, account
 * or server. [origin]: sync-now, switch-on, or the trigger (login, activation, deactivation, edit,
 * reachability, poll, backoff, slow-step).
 */
fun folderSyncCycleLine(outcome: String, origin: String, lastStatus: Int, requests: Int, ms: Long): String =
    "FolderSync: cycle outcome=$outcome origin=$origin http=${folderSyncHttpClass(lastStatus)} requests=$requests ms=$ms"

private enum class RemoteSeen { Absent, ZeroBytes, Present }

/**
 * One cycle, steps 2 to 6 (see the top of this file). The caller has made desktop's guards: the
 * switch is on, the store is attached and writable, nothing else is in flight, the pause rules allow
 * it, and the probe marker is present.
 *
 * [baseChoice] is asked at every snapshot (also after a Stale restart): chooseBase over the attach's
 * inputs, or the session's own base once a cycle of this attach has succeeded, with rule 4 judged
 * against the store as it is now. [tracker] outlives the cycle (the caller owns one per account).
 * [acceptShownShrink]: "Sync now" pressed while Paused(RemoteShrank) showed "N of M"; it skips the
 * brake for the FIRST GET's answer only. [collator] orders names as createFolder and renameFolder
 * do (null: the device's language).
 */
suspend fun runFolderSyncCycle(
    remote: FolderRemote,
    local: FolderLocal,
    baseChoice: () -> BaseChoice,
    tracker: SelfInconsistent412Tracker,
    acceptShownShrink: Boolean = false,
    timing: FolderSyncTiming = FolderSyncTiming(),
    clock: FolderSyncClock = MonotonicFolderSyncClock,
    random: Random = Random.Default,
    collator: NameCollator? = null,
): CycleResult {
    val started = clock.nowMs()
    var requests = 0
    var lastStatus = -1
    fun done(end: CycleEnd) = CycleResult(end, requests, lastStatus, clock.nowMs() - started)

    // Desktop D38: never a path built from a guess at whose home it is. Not an attempt.
    if (!remote.accountKnown) return done(CycleEnd.AccountNotReady)

    var firstGet = true
    var staleRestarts = 0
    var uploaded = false   // a PUT of this cycle went up (kept across a Stale restart, as desktop)
    while (true) {
        // Step 2, the snapshot: the generation and the local state together, and the base judged now.
        if (!local.isAttached()) return done(CycleEnd.Ended("the folder store left this account; nothing more is sent"))
        val snap = local.snapshot()
        val localState = snap.state
        val choice = baseChoice()
        var putAttempts = 0

        getLoop@ while (true) {
            // Step 3, the GET. Whatever an earlier GET of this cycle found is forgotten.
            val got = remote.get(REMOTE_FOLDERS_FILE)
            val answeredAt = clock.nowMs()   // the replace gap counts from THIS answer
            requests++
            lastStatus = got.status
            if (!local.isAttached()) return done(CycleEnd.Ended("the folder store left this account while the download was out; nothing is used"))
            // The brake skip "Sync now" was pressed for belongs to the FIRST GET's answer only: a GET
            // after a 412 or a Stale restart may find a different file, which nobody was shown.
            val skipBrake = firstGet && acceptShownShrink
            firstGet = false
            if (got.oversize) return done(CycleEnd.Paused(FolderSyncPause.RemoteUnreadable))

            var remoteState = FolderState()
            var etag = ""
            val seen: RemoteSeen = when (classifyDav(got.status, false)) {
                DavOutcome.Ok, DavOutcome.Created -> {
                    // After a 412 this GET says whether the file changed (a race) or not (the server
                    // refused the ETag it had just handed out). A GET with no 412 before it counts
                    // for nothing.
                    tracker.nextGet(got.etag)
                    if (tracker.shouldPause) return done(CycleEnd.Paused(FolderSyncPause.ServerUnsupported))
                    val d = withContext(Dispatchers.Default) { decodeRemote(got.body) }
                    etag = got.etag
                    when (d.kind) {
                        RemoteKind.Present -> {
                            remoteState = d.state
                            RemoteSeen.Present
                        }
                        RemoteKind.Absent -> RemoteSeen.ZeroBytes   // nobody wrote yet, never "everyone deleted everything"
                        RemoteKind.Newer -> return done(CycleEnd.Paused(FolderSyncPause.RemoteNewer))
                        RemoteKind.Unreadable -> return done(CycleEnd.Paused(FolderSyncPause.RemoteUnreadable))
                    }
                }
                DavOutcome.NotFound -> {
                    tracker.nextGet("")   // the file went: a race, never a broken server
                    RemoteSeen.Absent     // not a deletion: it is recreated from local
                }
                DavOutcome.AuthRefused -> return done(CycleEnd.Paused(FolderSyncPause.AuthRefused))
                DavOutcome.Transient, DavOutcome.Locked -> return done(CycleEnd.Failed("the download did not complete"))
                DavOutcome.QuotaFull -> return done(CycleEnd.Paused(FolderSyncPause.QuotaFull))
                DavOutcome.PreconditionFailed, DavOutcome.Conflict, DavOutcome.PayloadTooLarge,
                DavOutcome.Fatal, DavOutcome.Cancelled -> return done(CycleEnd.Paused(FolderSyncPause.Fatal))
            }

            // Step 4, the merge, and 4b, the shrink brake BEFORE any PUT or apply.
            val merged: FolderState
            if (seen == RemoteSeen.Present) {
                // An untrusted choice carries the EMPTY base, merged with prefer Remote; a trusted one
                // its base with prefer Local.
                val prefer = if (choice.trusted) MergePrefer.Local else MergePrefer.Remote
                val mr = withContext(Dispatchers.Default) { mergeFolderStates(choice.base, localState, remoteState, prefer, collator) }
                when (mr.status) {
                    MergeStatus.Ok -> {}
                    MergeStatus.TooManyFolders, MergeStatus.TooManyAssignments ->
                        return done(CycleEnd.Paused(FolderSyncPause.TooLarge))
                    MergeStatus.Invalid -> return done(CycleEnd.Paused(FolderSyncPause.Fatal))
                }
                val localFolders = localState.folders.size
                if (remoteShrinkBrake(localFolders, mr.foldersDropped) && !skipBrake) {
                    return done(CycleEnd.Paused(FolderSyncPause.RemoteShrank, mr.foldersDropped, localFolders))
                }
                merged = mr.merged
            } else {
                merged = localState   // absent or zero bytes: the local state is the whole truth
            }

            // Step 5, the upload decision: by STATE, never by bytes, and never an empty state over
            // an absent or zero-byte remote (then success with nothing sent).
            val absentOrZeroBytes = seen != RemoteSeen.Present
            var upload = absentOrZeroBytes || merged != remoteState
            if (upload && !uploadAllowed(isEmptyFolderState(merged), absentOrZeroBytes)) upload = false
            if (upload) {
                // Not in desktop's cycle, which encodes the merged (or, over an absent remote, the
                // local) state as it is. Kept on purpose, because both refusals are exactly a body
                // desktop could not take as written: its decodeFolders calls more than MAX_ASSIGNMENTS
                // filings corrupt, and its decodeRemote reads any state sanitiseFolderState changes as
                // repaired, so Unreadable, a pause no trigger wakes, on every desktop of the user.
                // It never fires while this port keeps its invariants (the store's load, mutations
                // and applies, and the merge's own self-check, all yield capped, sanitised states);
                // if it ever does, sending nothing (Fatal) is the safe side.
                val body = withContext(Dispatchers.Default) {
                    if (merged.assign.size > MAX_ASSIGNMENTS || sanitiseFolderState(merged).changed) null
                    else encodeFolders(merged, REMOTE_VERSION)
                } ?: return done(CycleEnd.Paused(FolderSyncPause.Fatal))
                if (body.size > MAX_FOLDER_FILE_BYTES) return done(CycleEnd.Paused(FolderSyncPause.Fatal))
                // Never an unconditional write over a file someone else may have changed.
                if (seen != RemoteSeen.Absent && etag.isEmpty()) return done(CycleEnd.Paused(FolderSyncPause.Fatal))
                if (seen != RemoteSeen.Absent) {
                    // The replace gap: only a REPLACE waits; a create replaces nothing.
                    val wait = timing.replaceGapMs - (clock.nowMs() - answeredAt)
                    if (wait > 0) {
                        clock.sleep(wait)
                        if (!local.isAttached()) return done(CycleEnd.Ended("the folder store left this account during the wait before the upload; nothing is sent"))
                    }
                }
                val ifMatch: String? = if (seen == RemoteSeen.Absent) null else etag
                putAttempts++
                val status = remote.put(REMOTE_FOLDERS_FILE, body, ifMatch)
                requests++
                lastStatus = status
                if (!local.isAttached()) return done(CycleEnd.Ended("the folder store left this account while the upload was out; nothing is applied"))
                when (val outcome = classifyDav(status, false)) {
                    DavOutcome.Ok, DavOutcome.Created -> {
                        uploaded = true
                        tracker.reset()
                    }
                    DavOutcome.PreconditionFailed, DavOutcome.NotFound -> {
                        // The file changed (or went) between the GET and the PUT: nothing is applied,
                        // and the cycle goes back to step 3 with the same snapshot. A 404 is no 412:
                        // it never feeds the 412 count.
                        if (outcome == DavOutcome.PreconditionFailed) tracker.putFailed412(ifMatch ?: "")
                        if (putAttempts >= MAX_PUT_ATTEMPTS) {
                            return done(CycleEnd.Failed("the shared file kept changing on the server; the next cycle reads it again"))
                        }
                        // Jitter, so two devices that refused each other do not collide again in lockstep.
                        val lo = timing.conflictRetryMinMs.coerceIn(0L, 60_000L)
                        val hi = timing.conflictRetryMaxMs.coerceIn(lo, 60_000L)
                        clock.sleep(random.nextLong(lo, hi + 1))
                        if (!local.isAttached()) return done(CycleEnd.Ended("the folder store left this account during a conflict wait; nothing more is sent"))
                        continue@getLoop
                    }
                    DavOutcome.PayloadTooLarge -> return done(CycleEnd.Paused(FolderSyncPause.ServerSizeLimit))
                    DavOutcome.AuthRefused -> return done(CycleEnd.Paused(FolderSyncPause.AuthRefused))
                    DavOutcome.Transient, DavOutcome.Locked -> return done(CycleEnd.Failed("the upload did not complete"))
                    DavOutcome.QuotaFull -> return done(CycleEnd.Paused(FolderSyncPause.QuotaFull))
                    DavOutcome.Conflict, DavOutcome.Fatal, DavOutcome.Cancelled -> return done(CycleEnd.Paused(FolderSyncPause.Fatal))
                }
            }

            // Step 6, the apply: only after the upload (or when none was needed), and through the gate.
            if (merged == localState) return done(CycleEnd.Synced(localState, merged, uploaded))
            if (!local.applyAllowed()) {
                val heldAt = clock.nowMs()
                while (true) {
                    clock.sleep(timing.applyGatePollMs)
                    if (!local.isAttached()) return done(CycleEnd.Ended("the folder store left this account while an apply was held"))
                    if (local.applyAllowed()) break
                    if (clock.nowMs() - heldAt >= timing.applyGateGiveUpMs) {
                        // Nothing is lost: whatever went up is on the server, and the next cycle merges it again.
                        return done(CycleEnd.Ended("a remote change waited too long to be applied; the next cycle applies it"))
                    }
                }
            }
            when (local.applyMerged(snap.generation, merged)) {
                ApplyResult.Applied, ApplyResult.AppliedNotSaved, ApplyResult.Unchanged ->
                    return done(CycleEnd.Synced(localState, merged, uploaded))
                ApplyResult.Stale -> {
                    // The user changed the folders while the cycle was out. The base does NOT move (M
                    // was merged from a state the store no longer holds): back to step 2 instead.
                    if (staleRestarts >= MAX_STALE_RESTARTS) {
                        return done(CycleEnd.Ended("the folders kept changing here during the cycle; the next trigger merges again"))
                    }
                    staleRestarts++
                    if (!remote.accountKnown) return done(CycleEnd.Ended("the account changed during the cycle; nothing more is sent"))
                    break@getLoop
                }
                ApplyResult.NotAttached, ApplyResult.ReadOnly ->
                    return done(CycleEnd.Ended("the folder store can no longer take the change"))
                ApplyResult.Invalid -> return done(CycleEnd.Paused(FolderSyncPause.Fatal))
            }
        }
    }
}
