package talq.app.folders

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import talq.app.Brand
import talq.app.data.FolderStore
import talq.core.folders.ApplyResult
import talq.core.folders.BaseChoice
import talq.core.folders.BaseInputs
import talq.core.folders.CycleEnd
import talq.core.folders.FolderLoadOutcome
import talq.core.folders.FolderLocal
import talq.core.folders.FolderRemote
import talq.core.folders.FolderState
import talq.core.folders.FolderSyncPause
import talq.core.folders.FolderSyncState
import talq.core.folders.FolderSyncTiming
import talq.core.folders.LocalSnapshot
import talq.core.folders.MAX_FOLDERS
import talq.core.folders.ProbeVerdict
import talq.core.folders.SelfInconsistent412Tracker
import talq.core.folders.Sidecar
import talq.core.folders.SidecarStatus
import talq.core.folders.StateDiff
import talq.core.folders.chooseBase
import talq.core.folders.folderSyncCycleLine
import talq.core.folders.folderSyncPauseName
import talq.core.folders.isEmptyFolderState
import talq.core.folders.isScopeKey
import talq.core.folders.nextBackoffMs
import talq.core.folders.runFolderProbe
import talq.core.folders.runFolderSyncCycle
import talq.core.folders.transientNoticeDue
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Keeps this account's folders in step with ONE small file in the user's own Nextcloud Files
 * (/.talq-folders.json in the WebDAV home, shared with TalQ for desktop and every other device of
 * the user), ported from desktop's FolderSyncController (src/core/FolderSyncController.h/.cpp). The
 * cycle itself (GET, merge, brakes, conditional PUT, apply) and the capability probe are the shared
 * core's (talq.core.folders.runFolderSyncCycle / runFolderProbe); this class is everything around
 * them: the switch, the triggers and their gaps, the backoff, the pause rules, the probe marker,
 * the sidecar, the status for Settings and the facts a notice is made from. It never shows anything.
 *
 * MAIN THREAD ONLY ([scope] is the container's). One chain (probe, then cycle) at a time; [gen] is
 * bumped by attach, detach, disable and enable, and everything that comes back from a chain checks
 * it, so an answer that outlived its session changes nothing.
 *
 * OFF BY DEFAULT, PER ACCOUNT. The switch is SharedPreferences `folder_sync`, key
 * `<scopeKey>.enabled`. It is written only when the user turns it off (before anything stops), when
 * the server is proved after turning it on, and when turning it on was refused: never at attach.
 *
 * THE TRIGGERS (desktop's, with the platform's limits: nothing runs this app in the background, and
 * no request is made just because time passed while it is not on screen):
 *  - Login: 3 s after attach with the switch on, when the app is in front. Attached in the
 *    background, the next coming to the front pulls instead.
 *  - Activation (the app comes to the front): a pull, more than 2 min after the last attempt.
 *  - Deactivation (the app leaves the front): a push, only when one is pending (the state differs
 *    from the base, or the base is untrusted) and the last attempt is over 15 s old.
 *  - Edit: 10 s after the last local edit, at most 60 s after the first unsent one; an edit made
 *    while a cycle is out starts the debounce when it ends.
 *  - Poll: every 10 min, only while the app is in front.
 *  - Reachability: the device's default network is usable again (internet, validated) after it was
 *    not; watched only while an account is attached with the switch on. While backing off it is a
 *    retry, no sooner than 30 s after the last attempt (the activation's override gap: the phone's
 *    network can flap, where desktop's edge needs two misses to flip), and one inside that gap is
 *    judged again when it has passed. Otherwise only while Waiting or with a push pending, and no
 *    sooner than 15 s after the last attempt (the push gap).
 *  - Backoff: after a transient failure, the ladder (30 s .. 15 min). While it waits only its own
 *    timer, the reachability edge, "Sync now" and an activation more than 30 s after the last
 *    attempt start a cycle.
 *  - SlowStep: Paused(QuotaFull) tries again on the slowest step.
 *  - "Sync now" and the switch.
 * A Login, Backoff, SlowStep or Reachability trigger that fires while the app is not in front is
 * kept and judged when it comes back. While Paused only the pause's own trigger starts a cycle (an
 * edit ends TooLarge, the slow step retries QuotaFull); "Sync now" clears every pause but
 * AuthRefused; the switch clears every pause.
 *
 * SIGN-OUT. [signedOut] cancels the chain and waits for it, BEFORE the app password is revoked, and
 * sends nothing: a change not yet uploaded is a difference from the base and goes up at the next
 * sign-in. The files stay.
 */
class FolderSyncController(
    context: Context,
    private val scope: CoroutineScope,
    private val store: FolderStore,
    /** Organisation mode: sync starts on for an account whose switch was never set (0.11.0). */
    private val onByDefault: () -> Boolean = { false },
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("folder_sync", Context.MODE_PRIVATE)
    private val timing = FolderSyncTiming()

    /** What started a cycle: its log origin and its rules about pauses and the shrink brake. */
    private enum class Origin { Button, Automatic, SwitchOn }

    private enum class Trigger(val logName: String) {
        Login("login"),
        Activation("activation"),
        Deactivation("deactivation"),
        Edit("edit"),
        Reachability("reachability"),
        Poll("poll"),
        Backoff("backoff"),
        SlowStep("slow-step"),
    }

    /** Why turning the switch on did not work (the toggle path only). */
    enum class EnableRefusal(val logName: String) {
        ServerUnsupported("server-unsupported"),
        SignInRefused("sign-in-refused"),
        CouldNotReach("could-not-reach"),
    }

    /** The cycle summary line the chain in flight owes (one per chain, whatever ends it). */
    private class CycleLog {
        var open = false
        var origin = Origin.Automatic
        var trigger = Trigger.Poll
        var startedAt = 0L
        var requests = 0
        var lastStatus = -1
    }

    /** A single-shot (or repeating) timer that fires [trigger] on the main thread. */
    private inner class Timer(private val trigger: Trigger, private val repeating: Boolean = false) {
        private var armedJob: Job? = null

        fun start(ms: Long) {
            armedJob?.cancel()
            // At least 1 ms, so the body always suspends before it fires (never inside start()).
            val wait = ms.coerceIn(1L, Int.MAX_VALUE.toLong())
            armedJob = scope.launch(Dispatchers.Main) {
                while (true) {
                    delay(wait)
                    if (!repeating) armedJob = null
                    fire(trigger)
                    if (!repeating) break
                }
            }
        }

        fun stop() {
            armedJob?.cancel()
            armedJob = null
        }
    }

    /**
     * Desktop's reachability edge (MainWindow -> onReachabilityRestored), from the device's default
     * network: [Trigger.Reachability] fires when it is usable (internet, validated) after it was not.
     * A hand-over that keeps a usable network (Wi-Fi to mobile) is no edge, and neither is the report
     * the system makes right after [start]. Reports arrive on the main thread.
     */
    private inner class NetworkWatch {
        private val connectivity: ConnectivityManager? = appContext.getSystemService(ConnectivityManager::class.java)
        private var callback: ConnectivityManager.NetworkCallback? = null
        private var usable = false

        fun start() {
            val cm = connectivity ?: return
            if (callback != null) return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                    report(this, isUsable(networkCapabilities))

                override fun onLost(network: Network) = report(this, false)
            }
            try {
                usable = isUsable(cm.getNetworkCapabilities(cm.activeNetwork))
                cm.registerDefaultNetworkCallback(cb, Handler(Looper.getMainLooper()))
                callback = cb
            } catch (e: RuntimeException) {
                // Too many callbacks in this process, or no permission: the other triggers still run.
                Log.w(TAG, "FolderSync: the network cannot be watched; a change waits for the next trigger")
            }
        }

        fun stop() {
            val cb = callback ?: return
            callback = null
            try {
                connectivity?.unregisterNetworkCallback(cb)
            } catch (_: RuntimeException) {
            }
        }

        private fun report(cb: ConnectivityManager.NetworkCallback, now: Boolean) {
            if (cb !== callback) return   // queued before stop(): this watch is over
            val was = usable
            usable = now
            if (now && !was) fire(Trigger.Reachability)
        }

        private fun isUsable(caps: NetworkCapabilities?): Boolean =
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ------------------------------------------------------------------------------ state

    private val _status = MutableStateFlow(FolderSyncStatus())

    /** What Settings shows (the switch, the state, the pause and its numbers). */
    val status: StateFlow<FolderSyncStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<FolderSyncEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Facts a notice is made from ([noticeForCycle], [noticeForPause], [noticeForFailedAttempt]). */
    val events: SharedFlow<FolderSyncEvent> = _events.asSharedFlow()

    private var attached = false
    private var enabled = false
    private var scopeKey = ""
    /** The account's WebDAV client, made on first use (see [attach]). */
    private var remote: (() -> FolderRemote)? = null
    private var gen = 0L
    private var chain: Job? = null
    private var inFlight = false

    // The base. Rules 1 to 3 of chooseBase hold for a whole attach, so their inputs are read once;
    // rule 4 is judged every cycle against the store as it is then.
    private var attachInputs = BaseInputs()
    private var attachGeneration = 0L
    private var sessionTrusted = false
    private var base = FolderState()
    private var savedSha = ""
    private var sidecarOwed = false
    private var applying = false
    private var repairRewriteDue = false
    private var revertOnAuthRefused = false

    private var state = FolderSyncState.Off
    private var pause = FolderSyncPause.None
    private var accountNotReady = false
    private var togglePending = false

    /** The switch-on under way is the organisation's default, not the person's: a failure stores nothing. */
    private var automaticEnable = false
    private var firstAfterEnable = false
    private var lastSyncedMs = 0L
    private var failures = 0
    private var failureSpanMs = 0L
    private var failureStartedAt: Long? = null
    private var shrinkDropped = 0
    private var shrinkOf = 0
    private var refusalText = ""
    private val tracker = SelfInconsistent412Tracker()
    private var log = CycleLog()

    private val loginTimer = Timer(Trigger.Login)
    private val pollTimer = Timer(Trigger.Poll, repeating = true)
    private val editTimer = Timer(Trigger.Edit)
    private val backoffTimer = Timer(Trigger.Backoff)
    private val slowStepTimer = Timer(Trigger.SlowStep)
    /** A reachability edge that came inside the backoff override gap, judged again when it has passed. */
    private val reachabilityTimer = Timer(Trigger.Reachability)
    private val networkWatch = NetworkWatch()
    private var backoff = false
    private var lastAttemptAt: Long? = null
    private var editClockStartedAt: Long? = null
    private var editDuringFlight = false
    private var foreground = false
    private val dueInFront = LinkedHashSet<Trigger>()
    private var applyHolds = 0

    init {
        // For the controller's whole life; both check that the store is attached to OUR account.
        // The debounce listens to user edits only: a sync apply never schedules an upload of what it
        // just took in.
        store.onSaved = { key, sha -> onStoreSaved(key, sha) }
        store.onLocalEdit = { onLocalEdited() }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun appName(): String = Brand.appName(appContext)

    // ------------------------------------------------------------------------------ the switch key

    /** Whether the switch is on for [key] on this device. Read at attach; off by default. */
    fun isEnabledFor(key: String): Boolean = isScopeKey(key) && prefs.getBoolean("$key.enabled", false)

    private fun writeEnabled(on: Boolean) {
        if (!isScopeKey(scopeKey)) return
        prefs.edit().putBoolean("$scopeKey.enabled", on).apply()
    }

    // ------------------------------------------------------------------------------ attach / detach

    /**
     * The store is already attached; the account is its scope key. Reads the sidecar, judges the
     * trust rules that hold for the whole attach, reads the switch. Sends nothing itself: with the
     * switch on (and the store writable) the login timer starts the session's first cycle. [remote]
     * is asked only when a chain starts, so an account that never turns sync on never builds one.
     */
    fun attach(remote: () -> FolderRemote) {
        resetSession()
        if (!store.isAttached || !isScopeKey(store.scopeKey)) {
            Log.w(TAG, "FolderSync: attach without an attached folder store; sync stays off")
            setState(FolderSyncState.Off)
            return
        }
        attached = true
        scopeKey = store.scopeKey
        this.remote = remote
        enabled = isEnabledFor(scopeKey)

        val read = store.readSidecar(scopeKey)
        attachInputs = BaseInputs(
            sidecarStatus = read.status,
            sidecar = read.sidecar,   // EMPTY unless Ok
            outcome = store.loadOutcome,
            loadedSha = store.loadedSha,
        )
        attachGeneration = store.generation
        // The base chooseBase hands out now: the sidecar's when trusted, else EMPTY, so nothing that
        // reads it (the saved hook, "push pending") can act on a base the rules distrust.
        base = baseChoiceNow().base
        savedSha = store.loadedSha   // until the store reports a write (empty: no file)
        repairRewriteDue = store.loadOutcome == FolderLoadOutcome.Repaired || store.loadOutcome == FolderLoadOutcome.SetAside

        if (!enabled) {
            setState(FolderSyncState.Off)
        } else if (!storeWritable()) {
            setState(FolderSyncState.Paused, FolderSyncPause.StoreUnavailable)   // status line only
            _events.tryEmit(FolderSyncEvent.ProblemRaised(FolderSyncPause.StoreUnavailable))
        } else {
            // A pull before any push (every cycle GETs first), and the poll while in front.
            if (foreground) loginTimer.start(timing.loginDelayMs)
            armPoll()
            networkWatch.start()
            setState(FolderSyncState.Idle)
        }
        // Organisation mode starts with sync on, through the switch's own way on (a probe, then the
        // switch written once the server is proved); a choice the person made, on or off, stays theirs.
        if (!enabled && onByDefault() && !prefs.contains("$scopeKey.enabled")) enable(automatic = true)
    }

    /** Sign-out, an account switch: stop everything, forget the account. No request, no sidecar write. */
    fun detach() {
        resetSession()
        setState(FolderSyncState.Off)
    }

    /** [detach], then wait until the chain that was running has really ended (before the password is revoked). */
    suspend fun signedOut() {
        val running = chain
        detach()
        running?.join()
    }

    private fun resetSession() {
        logCycleEnd("cancelled")
        gen++
        chain?.cancel()
        chain = null
        stopTimers()
        lastAttemptAt = null
        inFlight = false
        attached = false
        enabled = false
        scopeKey = ""
        remote = null
        attachInputs = BaseInputs()
        attachGeneration = 0
        sessionTrusted = false
        base = FolderState()
        savedSha = ""
        sidecarOwed = false
        applying = false
        repairRewriteDue = false
        revertOnAuthRefused = false
        togglePending = false
        firstAfterEnable = false
        lastSyncedMs = 0
        failures = 0
        failureSpanMs = 0
        failureStartedAt = null
        shrinkDropped = 0
        shrinkOf = 0
        refusalText = ""
        tracker.reset()
    }

    /** Every timer, and the network watch: what ends the switch or the session ends all of them. */
    private fun stopTimers() {
        networkWatch.stop()
        loginTimer.stop()
        pollTimer.stop()
        editTimer.stop()
        backoffTimer.stop()
        slowStepTimer.stop()
        reachabilityTimer.stop()
        backoff = false
        editClockStartedAt = null
        editDuringFlight = false
        dueInFront.clear()
    }

    private fun storeIsOurs(): Boolean = attached && store.isAttached && store.scopeKey == scopeKey

    private fun storeWritable(): Boolean = !store.isReadOnly && store.loadOutcome != FolderLoadOutcome.ReadOnlyLoad

    private fun armPoll() {
        if (attached && enabled && foreground && storeWritable()) pollTimer.start(timing.pollMs)
    }

    // ------------------------------------------------------------------------------ the user's controls

    /**
     * The user turned the switch ON. With no probe marker the probe runs first; the switch is kept
     * on (written) only once the server is proved, and turned back off with [FolderSyncStatus.refusalText]
     * when it is refused or cannot be reached. The first cycle then merges: a union with the remote
     * the first time, a trusted three-way merge when a sidecar from an earlier time is still believed.
     */
    /**
     * [automatic]: the organisation's default at attach, not a tap. If it fails nothing is written and
     * no refusal is shown, so the next attach tries again; only the person's own choice is stored.
     */
    fun enable(automatic: Boolean = false) {
        if (attached && enabled) return   // already on: no second probe
        refusalText = ""
        automaticEnable = automatic
        if (!storeIsOurs() || remote == null || !storeWritable()) {
            // The Settings row is disabled in this case; this is the defensive answer.
            Log.w(TAG, "FolderSync: the switch cannot be turned on: this account's folders are not available here")
            if (!automatic) {
                writeEnabled(false)
                refusalText = enableRefusalText(EnableRefusal.CouldNotReach, appName())
            }
            publish()
            return
        }
        gen++
        enabled = true
        togglePending = true
        firstAfterEnable = true
        tracker.reset()
        armPoll()   // no login timer: this cycle is the first pull
        networkWatch.start()
        publish()
        startCycle(Origin.SwitchOn)
    }

    /** The user turned the switch OFF: nothing more is sent. The shared file and the sidecar stay. */
    fun disable() {
        writeEnabled(false)   // first: whatever happens next, the choice is kept
        refusalText = ""
        turnOff()
        setState(FolderSyncState.Off)
    }

    /** "Sync now": overrides every pause but AuthRefused; from Paused(RemoteShrank) it accepts the shown shrink once. */
    fun syncNow() {
        startCycle(Origin.Button)
    }

    /** The app came to the front, or left it. */
    fun setForeground(on: Boolean) {
        if (foreground == on) return
        foreground = on
        if (on) {
            armPoll()
            // What fired while the app was away is judged now, then the activation pull.
            val due = dueInFront.toList()
            dueInFront.clear()
            for (t in due) startFromTrigger(t)
            startFromTrigger(Trigger.Activation)
        } else {
            pollTimer.stop()
            startFromTrigger(Trigger.Deactivation)
        }
    }

    /**
     * Hold remote changes while a folder dialog is open, so a merge never lands under the user's
     * typing (desktop's apply gate). Every [holdApplies] needs one [releaseApplies]. A held apply is
     * asked again every 500 ms and given up after 30 s (the next cycle applies it).
     */
    fun holdApplies() {
        applyHolds++
    }

    fun releaseApplies() {
        if (applyHolds > 0) applyHolds--
    }

    private fun turnOff() {
        logCycleEnd("cancelled")
        gen++
        chain?.cancel()
        chain = null
        stopTimers()
        inFlight = false
        enabled = false
        togglePending = false
        firstAfterEnable = false
        revertOnAuthRefused = false
        tracker.reset()
        failures = 0
        failureSpanMs = 0
        failureStartedAt = null
        shrinkDropped = 0
        shrinkOf = 0
    }

    // ------------------------------------------------------------------------------ the base

    private fun baseInputsNow(): BaseInputs {
        val inputs = if (sessionTrusted) {
            // A cycle of this attach succeeded: memory and the file descend from its result, so rules
            // 1 to 3 hold by construction; a fingerprint that matches itself says so.
            BaseInputs(
                sidecarStatus = SidecarStatus.Ok,
                sidecar = Sidecar(SESSION_FINGERPRINT, base),
                outcome = FolderLoadOutcome.Ok,
                loadedSha = SESSION_FINGERPRINT,
            )
        } else {
            attachInputs
        }
        // Rule 4 judges the store as it is NOW.
        return inputs.copy(
            localEmpty = isEmptyFolderState(store.state.value),
            localEditCount = store.localEditCount,
            stateUnchangedSinceAttach = store.generation == attachGeneration,
        )
    }

    private fun baseChoiceNow(): BaseChoice = chooseBase(baseInputsNow())

    /** Derived, never stored: the base is untrusted (a cycle is always due) or the state moved away from it. */
    private fun pushPending(): Boolean = !baseChoiceNow().trusted || store.state.value != base

    // ------------------------------------------------------------------------------ triggers

    private fun fire(trigger: Trigger) {
        if (!foreground) {
            when (trigger) {
                // Never a pull because time passed while the app is away, nor a request because the
                // network came back meanwhile: judged when it comes back.
                Trigger.Login, Trigger.Backoff, Trigger.SlowStep, Trigger.Reachability -> {
                    dueInFront.add(trigger)
                    return
                }
                Trigger.Poll -> return
                Trigger.Edit, Trigger.Activation, Trigger.Deactivation -> Unit
            }
        }
        startFromTrigger(trigger)
    }

    private fun lastAttemptOlderThan(ms: Long): Boolean {
        val at = lastAttemptAt ?: return true
        return now() - at > ms
    }

    /** The ONE place an automatic trigger is judged: the backoff and the gaps here, the pause rule in startCycle. */
    private fun startFromTrigger(trigger: Trigger) {
        if (!storeIsOurs()) return
        if (state != FolderSyncState.Paused) {
            if (backoff) {
                when (trigger) {
                    Trigger.Backoff -> Unit
                    Trigger.Activation -> if (!lastAttemptOlderThan(timing.backoffOverrideGapMs)) return
                    // The network is back: a retry now, as on desktop, held to the activation's
                    // override gap; inside it, judged again once the gap has passed.
                    Trigger.Reachability -> {
                        val at = lastAttemptAt
                        if (at != null && now() - at <= timing.backoffOverrideGapMs) {
                            reachabilityTimer.start(at + timing.backoffOverrideGapMs + 1 - now())
                            return
                        }
                    }
                    Trigger.Login, Trigger.Deactivation, Trigger.Edit, Trigger.Poll, Trigger.SlowStep -> return
                }
            } else {
                when (trigger) {
                    Trigger.Activation -> if (!lastAttemptOlderThan(timing.activationGapMs)) return
                    Trigger.Deactivation -> if (!lastAttemptOlderThan(timing.pushGapMs) || !pushPending()) return
                    // A retry (Waiting: the account was not ready) or a push pending, as on desktop;
                    // held to the push gap, as the push on leaving the front is.
                    Trigger.Reachability ->
                        if ((state != FolderSyncState.Waiting && !pushPending()) || !lastAttemptOlderThan(timing.pushGapMs)) return
                    Trigger.Backoff, Trigger.SlowStep -> return   // their state is gone: a cycle ran since
                    Trigger.Login, Trigger.Edit, Trigger.Poll -> Unit
                }
            }
        }
        startCycle(Origin.Automatic, trigger)
    }

    private fun onLocalEdited() {
        if (!attached || !enabled || !storeIsOurs()) return
        if (editClockStartedAt == null) editClockStartedAt = now()
        if (inFlight) {
            editDuringFlight = true
            return
        }
        armEditDebounce()
    }

    private fun armEditDebounce() {
        val sinceFirst = editClockStartedAt?.let { now() - it } ?: 0L
        editTimer.start(minOf(timing.editDebounceMs, timing.editMaxWaitMs - sinceFirst))
    }

    /** The cycle's snapshot took every edit made so far: the debounce starts again with the next one. */
    private fun tookSnapshot() {
        editTimer.stop()
        editClockStartedAt = null
        editDuringFlight = false
    }

    private fun endFlight() {
        inFlight = false
        if (editDuringFlight) {
            editDuringFlight = false
            armEditDebounce()
        }
    }

    private fun ladderWaitMs(failureCount: Int): Long {
        val ms = nextBackoffMs(failureCount, Random.nextDouble()).toDouble() * timing.backoffScale
        if (!(ms >= 1.0)) return 1   // also NaN
        if (ms >= Int.MAX_VALUE.toDouble()) return Int.MAX_VALUE.toLong()
        return ms.roundToLong()
    }

    private fun wakesPause(trigger: Trigger, p: FolderSyncPause): Boolean =
        (trigger == Trigger.Edit && p == FolderSyncPause.TooLarge) || (trigger == Trigger.SlowStep && p == FolderSyncPause.QuotaFull)

    // ------------------------------------------------------------------------------ one chain

    /** The guards, then the probe when the marker is absent, else the cycle. The one in-flight guard. */
    private fun startCycle(origin: Origin, trigger: Trigger = Trigger.Poll) {
        if (!attached || !enabled || inFlight) return
        if (!storeIsOurs() || !storeWritable()) return
        val remote = remote?.invoke() ?: return
        if (state == FolderSyncState.Paused) {
            when (origin) {
                Origin.Automatic -> if (!wakesPause(trigger, pause)) return
                Origin.Button -> {
                    if (pause == FolderSyncPause.AuthRefused) return   // only a sign-in again or the switch
                    if (pause == FolderSyncPause.ServerUnsupported) tracker.reset()
                }
                Origin.SwitchOn -> Unit   // the toggle clears every pause
            }
        }
        // "Sync now" accepts a remote shrink only when the pause has already said "N of M".
        val acceptShownShrink = origin == Origin.Button && state == FolderSyncState.Paused && pause == FolderSyncPause.RemoteShrank
        val markerPresent = store.probeMarkerPresent(scopeKey)
        // This attempt answers whatever was waiting to start one.
        backoff = false
        backoffTimer.stop()
        slowStepTimer.stop()
        loginTimer.stop()
        reachabilityTimer.stop()
        dueInFront.clear()
        revertOnAuthRefused = false
        inFlight = true
        log = CycleLog().also {
            it.open = true
            it.origin = origin
            it.trigger = trigger
            it.startedAt = now()
        }

        // The toggle with the marker present: the switch is kept on at once, before the first cycle.
        if (togglePending && markerPresent) {
            setState(FolderSyncState.SettingUp)
            if (!reportEnabled()) return
        }
        // Never a path built from a guess at whose home it is. Not an attempt: no clock, no timer;
        // the next trigger looks again.
        if (!remote.accountKnown) {
            endFlight()
            if (togglePending) {
                refuseEnable(EnableRefusal.CouldNotReach)
                return
            }
            if (state == FolderSyncState.Waiting && accountNotReady) log.open = false else logCycleEnd("account-not-ready")
            setState(FolderSyncState.Waiting, FolderSyncPause.None, accountNotReady = true)
            return
        }
        lastAttemptAt = now()   // a probe-only attempt counts, and so does one that fails
        val g = gen
        val key = scopeKey
        // Dispatched, never run inside this call: everything above has settled before the chain starts.
        chain = scope.launch(Dispatchers.Main) { runChain(g, key, remote, origin, acceptShownShrink, markerPresent) }
    }

    private suspend fun runChain(g: Long, key: String, remote: FolderRemote, origin: Origin, acceptShownShrink: Boolean, markerPresent: Boolean) {
        if (g != gen) return
        if (!markerPresent) {
            // No cycle ever touches the real file on a server whose capability probe has not passed.
            setState(FolderSyncState.SettingUp)
            Log.i(TAG, "FolderSync: this device has not proved the server yet; the capability probe runs before any sync")
            val probe = runFolderProbe(remote)
            if (g != gen) return
            log.requests += probe.requests
            if (probe.requests > 0) log.lastStatus = probe.lastStatus
            if (!storeIsOurs()) {
                probeEndedQuietly("the folder store left this account during the capability probe; nothing more is sent")
                return
            }
            var verdict = probe.verdict
            if (verdict != ProbeVerdict.Pass) {
                Log.w(TAG, "FolderSync: the capability probe stopped at step ${probe.stoppedAtStep} of 5, HTTP status ${probe.lastStatus}")
            } else if (!store.writeProbeMarker(key)) {
                // A pass that is not on disk is not a pass: no cycle runs on it.
                Log.w(TAG, "FolderSync: the server passed the capability probe, but its marker could not be written; nothing is synced")
                verdict = ProbeVerdict.CouldNotRun
            }
            if (verdict != ProbeVerdict.Pass) {
                probeFailed(verdict)
                return
            }
            Log.i(TAG, "FolderSync: the server passed the capability probe")
            if (togglePending && !reportEnabled()) return
        }

        // The toggle's own cycle shows SettingUp, and a 401/403 in it turns the switch back off.
        val firstAfterToggle = origin == Origin.SwitchOn
        revertOnAuthRefused = firstAfterToggle
        setState(if (firstAfterToggle) FolderSyncState.SettingUp else FolderSyncState.Syncing)
        val result = runFolderSyncCycle(
            remote = remote,
            local = cycleLocal(key, g),
            baseChoice = { baseChoiceNow() },
            tracker = tracker,
            acceptShownShrink = acceptShownShrink,
            timing = timing,
        )
        if (g != gen) return
        log.requests += result.requests
        if (result.requests > 0) log.lastStatus = result.lastStatus
        when (val end = result.end) {
            is CycleEnd.Synced -> recordSuccess(end, result.outcomeName)
            is CycleEnd.Paused -> when (end.pause) {
                FolderSyncPause.ServerUnsupported -> pauseServerUnsupported()
                FolderSyncPause.RemoteShrank -> {
                    shrinkDropped = end.shrinkDropped
                    shrinkOf = end.shrinkOf
                    endPaused(end.pause)
                }
                else -> endPaused(end.pause)
            }
            is CycleEnd.Failed -> endTransient(end.why)
            is CycleEnd.Ended -> endQuietly(end.why)
            CycleEnd.AccountNotReady -> {
                endFlight()
                logCycleEnd("account-not-ready")
                setState(FolderSyncState.Waiting, FolderSyncPause.None, accountNotReady = true)
            }
        }
    }

    /**
     * The store as this chain sees it: attached to [key] AND still this session's chain; the
     * snapshot restarts the edit debounce; an apply waits while a dialog holds it; and the save
     * inside the chain's own apply never writes the sidecar from the saved hook (the record step
     * writes it, with the merged base).
     */
    private fun cycleLocal(key: String, g: Long): FolderLocal {
        val inner = store.localFor(key)
        return object : FolderLocal {
            override fun isAttached(): Boolean = g == gen && inner.isAttached()

            override fun snapshot(): LocalSnapshot {
                tookSnapshot()
                return inner.snapshot()
            }

            override fun applyAllowed(): Boolean = applyHolds == 0

            override suspend fun applyMerged(expectedGeneration: Long, merged: FolderState): ApplyResult {
                applying = true
                try {
                    return inner.applyMerged(expectedGeneration, merged)
                } finally {
                    applying = false
                }
            }
        }
    }

    // ------------------------------------------------------------------------------ the probe's ends

    private fun probeFailed(verdict: ProbeVerdict) {
        endFlight()
        if (togglePending) {
            // The user is looking at the switch: it goes back off with the reason, never a pause.
            refuseEnable(
                when (verdict) {
                    ProbeVerdict.Refused -> EnableRefusal.ServerUnsupported
                    ProbeVerdict.AuthRefused -> EnableRefusal.SignInRefused
                    ProbeVerdict.CouldNotRun, ProbeVerdict.Pass -> EnableRefusal.CouldNotReach
                },
            )
            return
        }
        when (verdict) {
            ProbeVerdict.Refused -> endPaused(FolderSyncPause.ServerUnsupported)
            ProbeVerdict.AuthRefused -> endPaused(FolderSyncPause.AuthRefused)
            ProbeVerdict.CouldNotRun, ProbeVerdict.Pass -> endTransient("the capability probe could not run; the next trigger runs it again")
        }
    }

    private fun probeEndedQuietly(why: String) {
        if (togglePending) {
            refuseEnable(EnableRefusal.CouldNotReach)
            return
        }
        endQuietly(why)
    }

    /** The switch is proved: kept on before the first cycle. False when the account went meanwhile. */
    private fun reportEnabled(): Boolean {
        togglePending = false
        writeEnabled(true)
        refusalText = ""
        publish()
        if (!storeIsOurs()) {
            endQuietly("the folder store left this account as the switch was turned on")
            return false
        }
        return true
    }

    private fun refuseEnable(why: EnableRefusal) {
        Log.w(
            TAG,
            "FolderSync: the switch was not turned on: " + when (why) {
                EnableRefusal.ServerUnsupported -> "the server does not honour conditional saves"
                EnableRefusal.SignInRefused -> "the saved sign-in was refused"
                EnableRefusal.CouldNotReach -> "the server could not be reached"
            },
        )
        logCycleEnd("switch-refused", why.logName)
        turnOff()
        // The organisation's default tried and failed: stored nowhere, tried again at the next attach.
        if (!automaticEnable) {
            writeEnabled(false)
            refusalText = enableRefusalText(why, appName())
        }
        automaticEnable = false
        setState(FolderSyncState.Off)
    }

    // ------------------------------------------------------------------------------ a cycle's ends

    /**
     * Step 7. The base moves in memory; the sidecar describes only what is on disk, so it is written
     * only while the store is clean, with the sha the store last reported. A sidecar that cannot be
     * written does not undo the cycle: it is owed, and the saved hook or the next record pays it.
     */
    private fun recordSuccess(end: CycleEnd.Synced, outcomeName: String) {
        val diff = end.diff
        val uploaded = end.uploaded
        logCycleEnd(outcomeName)
        base = end.merged
        sessionTrusted = true
        lastSyncedMs = System.currentTimeMillis()   // display only
        failures = 0
        failureSpanMs = 0
        failureStartedAt = null

        if (store.isDirty) {
            sidecarOwed = true
        } else if (store.writeSidecar(scopeKey, base, savedSha)) {
            sidecarOwed = false
        } else {
            Log.w(TAG, "FolderSync: the sync record could not be written; it is written at the next save or cycle")
            sidecarOwed = true
        }

        if (repairRewriteDue) {
            // A file that loaded Repaired or SetAside is not rewritten by a merge that changes
            // nothing; rewritten once, its write reaches the saved hook with the base now trusted.
            repairRewriteDue = false
            val g = gen
            if (!store.forceSave()) Log.w(TAG, "FolderSync: the repaired folders file could not be rewritten; the next start tries again")
            if (g != gen) return
            if (!storeIsOurs()) {
                endQuietly("the folder store left this account during the rewrite")
                return
            }
        }

        val first = firstAfterEnable
        firstAfterEnable = false
        endFlight()
        setState(FolderSyncState.Idle)
        _events.tryEmit(FolderSyncEvent.CycleFinished(diff, first, uploaded))
    }

    /** Two self-inconsistent 412s: the marker goes, so "Sync now" proves the server again first. */
    private fun pauseServerUnsupported() {
        Log.w(TAG, "FolderSync: the server refused the file version it had just handed out, twice; sync stops until Sync now")
        if (!store.removeProbeMarker(scopeKey)) Log.w(TAG, "FolderSync: the capability probe marker could not be deleted")
        endPaused(FolderSyncPause.ServerUnsupported)
    }

    private fun endPaused(reason: FolderSyncPause) {
        endFlight()
        if (reason == FolderSyncPause.AuthRefused && revertOnAuthRefused) {
            // The first cycle after the user turned the switch on met 401/403: the switch goes back off.
            refuseEnable(EnableRefusal.SignInRefused)
            return
        }
        if (reason == FolderSyncPause.QuotaFull) slowStepTimer.start(ladderWaitMs(PAST_THE_LADDER))
        val name = folderSyncPauseName(reason)
        Log.w(TAG, "FolderSync: paused: $name")
        logCycleEnd("paused", name)
        setState(FolderSyncState.Paused, reason)
        _events.tryEmit(FolderSyncEvent.ProblemRaised(reason))
    }

    private fun endTransient(why: String) {
        endFlight()
        failures++
        val startedAt = failureStartedAt ?: now().also { failureStartedAt = it }
        failureSpanMs = now() - startedAt
        Log.w(TAG, "FolderSync: attempt failed: $why - failures in a row: $failures")
        backoff = true
        backoffTimer.start(ladderWaitMs(failures))
        logCycleEnd("failed")
        setState(FolderSyncState.Waiting)
        _events.tryEmit(FolderSyncEvent.AttemptFailed(failures, failureSpanMs))
    }

    /** Neither success nor failure: nothing applied or recorded, the next cycle starts over. */
    private fun endQuietly(why: String) {
        endFlight()
        Log.i(TAG, "FolderSync: $why")
        logCycleEnd("ended")
        setState(FolderSyncState.Idle)
    }

    /** One summary line per chain: enums and counts only, never a folder, chat, path, account or server. */
    private fun logCycleEnd(outcome: String, detail: String? = null) {
        if (!log.open) return
        log.open = false
        val origin = when (log.origin) {
            Origin.Button -> "sync-now"
            Origin.SwitchOn -> "switch-on"
            Origin.Automatic -> log.trigger.logName
        }
        val o = if (detail != null) "$outcome:$detail" else outcome
        Log.i(TAG, folderSyncCycleLine(o, origin, log.lastStatus, log.requests, now() - log.startedAt))
    }

    private fun setState(s: FolderSyncState, p: FolderSyncPause = FolderSyncPause.None, accountNotReady: Boolean = false) {
        state = s
        pause = if (s == FolderSyncState.Paused) p else FolderSyncPause.None
        this.accountNotReady = s == FolderSyncState.Waiting && accountNotReady
        publish()
    }

    private fun publish() {
        _status.value = FolderSyncStatus(
            switchOn = attached && enabled,
            state = state,
            pause = pause,
            accountNotReady = accountNotReady,
            lastSyncedMs = lastSyncedMs,
            shrinkDropped = shrinkDropped,
            shrinkOf = shrinkOf,
            refusalText = refusalText,
        )
    }

    // ------------------------------------------------------------------------------ the saved hook

    /**
     * After every successful write of the folders file, while attached, the switch off included. It
     * only RECORDS: the sha is kept for the record step, and the sidecar is rewritten as
     * {base, sha} only while that base is TRUSTED now and the store is clean (an untrusted base
     * written next to the new file's sha would be believed at the next start, and every folder it
     * holds that the file lacks would read as a deletion made here). Not for the save inside this
     * controller's own apply: the record step that follows writes the merged base itself.
     */
    private fun onStoreSaved(key: String, sha: String) {
        if (key != scopeKey || !storeIsOurs()) return
        savedSha = sha
        if (applying) return
        if (store.isDirty || !baseChoiceNow().trusted) return
        if (store.writeSidecar(scopeKey, base, sha)) {
            sidecarOwed = false
        } else {
            Log.w(TAG, "FolderSync: the sync record could not be updated after a save; the next start merges as a union")
            sidecarOwed = true
        }
    }

    private companion object {
        const val TAG = "FolderSync"

        /** What a base this session's own cycle produced hands chooseBase; only compared with itself. */
        const val SESSION_FINGERPRINT = "merged-in-this-session"

        /** Past the end of the ladder: its slowest step, what Paused(QuotaFull) waits. */
        const val PAST_THE_LADDER = 1000
    }
}

/** What the sync is doing, for Settings. */
data class FolderSyncStatus(
    /** The switch as it should SHOW: on from the moment it is turned on (the probe included) until it is off again. */
    val switchOn: Boolean = false,
    val state: FolderSyncState = FolderSyncState.Off,
    val pause: FolderSyncPause = FolderSyncPause.None,
    /** Waiting because the account's user id is not known yet. */
    val accountNotReady: Boolean = false,
    /** Wall clock, display only; 0 = not synced in this session. */
    val lastSyncedMs: Long = 0,
    /** Paused(RemoteShrank): "Another device removed [shrinkDropped] of your [shrinkOf] folders." */
    val shrinkDropped: Int = 0,
    val shrinkOf: Int = 0,
    /** Why the last attempt to turn the switch on did not work; empty when it did or nobody tried. */
    val refusalText: String = "",
)

/** Facts a notice is made from. */
sealed interface FolderSyncEvent {
    /** A cycle ended well. [diff] is what changed here (nothing visible when only this device's change went up). */
    data class CycleFinished(val diff: StateDiff, val firstAfterEnable: Boolean, val uploaded: Boolean) : FolderSyncEvent

    data class ProblemRaised(val pause: FolderSyncPause) : FolderSyncEvent

    /** A transient failure; [failures] in a row over [spanMs]. */
    data class AttemptFailed(val failures: Int, val spanMs: Long) : FolderSyncEvent
}

// ---------------------------------------------------------------------------------------------
// What folder sync tells the user, ported from desktop's FolderSyncNotices.h and FolderSyncUiLogic.h
// with "device" where desktop says "computer", and the app's own name where desktop says "TalQ".
// The texts are pure; showing them (and the spacing between two of them) is the screens'.

/** The notices. PausedServerUnsupported also serves Fatal. None for StoreUnavailable or the account-not-ready wait. */
enum class FolderSyncNotice {
    RemoteApplied,
    FirstSync,
    FirstSyncHint,
    ProblemTransient,
    PausedAuthRefused,
    PausedRemoteNewer,
    PausedRemoteUnreadable,
    PausedTooLarge,
    PausedServerSizeLimit,
    PausedQuotaFull,
    PausedServerUnsupported,
    PausedRemoteShrank,
}

data class FolderSyncNoticeText(val title: String, val body: String)

fun folderSyncNoticeText(id: FolderSyncNotice, appName: String): FolderSyncNoticeText = when (id) {
    FolderSyncNotice.RemoteApplied -> FolderSyncNoticeText("Folders updated", "Your folders were updated from another device.")
    FolderSyncNotice.FirstSync -> FolderSyncNoticeText("Folder sync is on", "Your folders now stay the same on your other devices.")
    FolderSyncNotice.FirstSyncHint -> FolderSyncNoticeText("Folder sync is on", "Your folders are now in sync. To see them, turn on Show folders.")
    FolderSyncNotice.ProblemTransient -> FolderSyncNoticeText("Folders not syncing", "$appName could not save your folders to Nextcloud. It will keep trying.")
    FolderSyncNotice.PausedAuthRefused -> FolderSyncNoticeText("Folder sync paused", "Nextcloud no longer accepts the saved sign-in. Sign in again to resume.")
    FolderSyncNotice.PausedRemoteNewer -> FolderSyncNoticeText("Folder sync paused", "The synced folders were saved by a newer $appName. Update $appName to resume.")
    FolderSyncNotice.PausedRemoteUnreadable -> FolderSyncNoticeText("Folder sync paused", "The shared folders file looks damaged, so $appName left it alone.")
    FolderSyncNotice.PausedTooLarge -> FolderSyncNoticeText("Folder sync paused", "Your devices together have too many folders. Delete some to resume.")
    FolderSyncNotice.PausedServerSizeLimit -> FolderSyncNoticeText("Folder sync paused", "Nextcloud refused the folders file as too large, so syncing is paused.")
    FolderSyncNotice.PausedQuotaFull -> FolderSyncNoticeText("Folders not syncing", "Your Nextcloud storage is full, so folders could not be saved.")
    FolderSyncNotice.PausedServerUnsupported -> FolderSyncNoticeText("Folder sync paused", "Nextcloud did not accept $appName's saves, so folder sync is paused.")
    FolderSyncNotice.PausedRemoteShrank -> FolderSyncNoticeText("Folder sync paused", "Folders were removed on another device. Press Sync now in Settings.")
}

/**
 * The notice a finished cycle offers: the first after the switch was turned on says so (with the
 * hint when folders exist but are not shown); otherwise a change the user can see (not a collapse
 * alone); never this device's own upload.
 */
fun noticeForCycle(diff: StateDiff, firstAfterEnable: Boolean, foldersExist: Boolean, showingFolders: Boolean): FolderSyncNotice? = when {
    firstAfterEnable -> if (foldersExist && !showingFolders) FolderSyncNotice.FirstSyncHint else FolderSyncNotice.FirstSync
    diff.visible -> FolderSyncNotice.RemoteApplied
    else -> null
}

fun noticeForPause(pause: FolderSyncPause): FolderSyncNotice? = when (pause) {
    FolderSyncPause.AuthRefused -> FolderSyncNotice.PausedAuthRefused
    FolderSyncPause.RemoteNewer -> FolderSyncNotice.PausedRemoteNewer
    FolderSyncPause.RemoteUnreadable -> FolderSyncNotice.PausedRemoteUnreadable
    FolderSyncPause.TooLarge -> FolderSyncNotice.PausedTooLarge
    FolderSyncPause.ServerSizeLimit -> FolderSyncNotice.PausedServerSizeLimit
    FolderSyncPause.QuotaFull -> FolderSyncNotice.PausedQuotaFull
    FolderSyncPause.RemoteShrank -> FolderSyncNotice.PausedRemoteShrank
    FolderSyncPause.ServerUnsupported, FolderSyncPause.Fatal -> FolderSyncNotice.PausedServerUnsupported
    FolderSyncPause.StoreUnavailable, FolderSyncPause.None -> null
}

/** At least 3 failures in a row over at least 10 minutes, with the server answering other requests. */
fun noticeForFailedAttempt(failures: Int, spanMs: Long, serverReachable: Boolean): FolderSyncNotice? =
    if (transientNoticeDue(failures, spanMs, serverReachable)) FolderSyncNotice.ProblemTransient else null

fun enableRefusalText(why: FolderSyncController.EnableRefusal, appName: String): String = when (why) {
    FolderSyncController.EnableRefusal.ServerUnsupported ->
        "Not turned on: your server can't store it safely."
    FolderSyncController.EnableRefusal.SignInRefused -> "Not turned on: Nextcloud no longer accepts the saved sign-in."
    FolderSyncController.EnableRefusal.CouldNotReach -> "Not turned on: $appName could not reach your Nextcloud files."
}

/** The description under "Keep my folders in sync" (desktop's, for a device). */
fun folderSyncDescription(): String = "Same folders on all your devices, TalQ for desktop included."

private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/**
 * "today 14:32", "yesterday 09:10" or "3 Nov 14:32", in the device's time zone (wall clock, display
 * only). A [lastMs] on a later day than [nowMs] (the clock was set back) prints the date.
 */
fun formatLastSynced(lastMs: Long, nowMs: Long): String {
    val zone = ZoneId.systemDefault()
    val last = Instant.ofEpochMilli(lastMs).atZone(zone)
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val hm = String.format(Locale.US, "%02d:%02d", last.hour, last.minute)
    return when (ChronoUnit.DAYS.between(last.toLocalDate(), now.toLocalDate())) {
        0L -> "today $hm"
        1L -> "yesterday $hm"
        else -> "${last.dayOfMonth} ${MONTHS[last.monthValue - 1]} $hm"
    }
}

/** The line under the Settings row while the switch is on. Off is empty. */
fun folderSyncStatusLine(s: FolderSyncStatus, appName: String, nowMs: Long): String = when (s.state) {
    FolderSyncState.Off -> ""
    FolderSyncState.SettingUp -> "Setting up..."
    FolderSyncState.Syncing -> "Syncing..."
    FolderSyncState.Idle -> if (s.lastSyncedMs <= 0) "Not synced yet." else "Last synced: " + formatLastSynced(s.lastSyncedMs, nowMs)
    FolderSyncState.Waiting ->
        if (s.accountNotReady) "Waiting for your Nextcloud account to finish loading."
        else "Can't reach your Nextcloud. Will retry."
    FolderSyncState.Paused -> when (s.pause) {
        FolderSyncPause.AuthRefused -> "Paused: sign in again."
        FolderSyncPause.RemoteNewer -> "Paused: update $appName."
        FolderSyncPause.RemoteUnreadable ->
            "Paused: the shared file .talq-folders.json looks damaged."
        FolderSyncPause.TooLarge ->
            "Paused: more than $MAX_FOLDERS folders. Delete some."
        FolderSyncPause.ServerSizeLimit ->
            "Paused: the server refused the file as too large."
        FolderSyncPause.QuotaFull -> "Paused: your Nextcloud storage is full."
        FolderSyncPause.StoreUnavailable -> "Folders are not available right now."
        FolderSyncPause.RemoteShrank ->
            "Another device removed ${s.shrinkDropped} of ${s.shrinkOf} folders. Sync now to accept."
        FolderSyncPause.ServerUnsupported, FolderSyncPause.Fatal, FolderSyncPause.None ->
            "Paused: unexpected answer from the server."
    }
}

/** "Sync now" is offered in Idle, Waiting and every pause but AuthRefused (which waits for a sign-in or the switch). */
fun syncNowOffered(state: FolderSyncState, pause: FolderSyncPause): Boolean = when (state) {
    FolderSyncState.Idle, FolderSyncState.Waiting -> true
    FolderSyncState.Paused -> pause != FolderSyncPause.AuthRefused
    FolderSyncState.Off, FolderSyncState.SettingUp, FolderSyncState.Syncing -> false
}

/**
 * The Settings row. [enabled] is what the switch shows; [available] false disables it with
 * [whyNot] (never empty then); [statusText] is the line under it; [canSyncNow] offers "Sync now".
 */
data class FolderSyncRow(
    val enabled: Boolean = false,
    val available: Boolean = false,
    val canSyncNow: Boolean = false,
    val whyNot: String = "",
    val statusText: String = "",
)

/**
 * Desktop's folderSyncRow: the first cause that holds disables the row (not signed in, the folders
 * file could not be read, no store attached, a read-only store); available with the switch off it
 * shows the last refusal; on, the status line and "Sync now" where it can act.
 */
fun folderSyncRow(
    signedIn: Boolean,
    loadFailed: Boolean,
    store: FolderStore.Status,
    sync: FolderSyncStatus,
    appName: String,
    nowMs: Long,
): FolderSyncRow {
    val whyNot = when {
        !signedIn -> "Sign in to keep your folders in sync."
        loadFailed -> "Folders could not be loaded. Restart $appName to try again."
        !store.attached -> "Folders are unavailable on this device right now, so they cannot be synced."
        store.readOnly == FolderStore.ReadOnlyReason.NewerFormat -> "Folders are hidden: saved by a newer version of $appName."
        store.readOnly == FolderStore.ReadOnlyReason.CouldNotSetAside -> "Folders are read-only: the folders file could not be updated."
        else -> ""
    }
    if (whyNot.isNotEmpty()) return FolderSyncRow(enabled = sync.switchOn, whyNot = whyNot)
    if (!sync.switchOn) return FolderSyncRow(available = true, statusText = sync.refusalText)
    return FolderSyncRow(
        enabled = true,
        available = true,
        canSyncNow = syncNowOffered(sync.state, sync.pause),
        statusText = folderSyncStatusLine(sync, appName, nowMs),
    )
}
