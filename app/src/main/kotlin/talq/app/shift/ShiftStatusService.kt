package talq.app.shift

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import talq.app.Brand
import talq.app.SessionState
import talq.app.data.ErpStore
import talq.app.data.StoredPairing
import talq.app.push.NotificationGate
import talq.core.shift.ApproveTarget
import talq.core.shift.ErpBase
import talq.core.shift.ErpClient
import talq.core.shift.PairPollResult
import talq.core.shift.PairStartResult
import talq.core.shift.SHIFT_TTL_MS
import talq.core.shift.ShiftEntry
import talq.core.shift.ShiftState
import talq.core.shift.ShiftStatusResult
import talq.core.shift.approveTarget
import talq.core.shift.buildShiftBatch
import talq.core.shift.shiftEntryIsStale

/** Where this phone stands with the organisation's ERP. Settings shows exactly this. */
sealed interface ErpLink {
    data object NotPaired : ErpLink

    /**
     * Pairing is under way. [approve] is null until the ERP has answered with its approval
     * page; [opened] turns true once that page was handed to the browser.
     */
    data class Pairing(val approve: ApproveTarget?, val opened: Boolean) : ErpLink

    data class Paired(val displayName: String) : ErpLink

    /** The ERP answered 401/403 to the stored key: nothing more is asked until paired again. */
    data object Rejected : ErpLink
}

/**
 * Shift status of colleagues, from the organisation's ERP, and the pairing that makes it
 * possible. One instance for the app (`appContainer.shift`); everything here runs on the
 * main thread (the container's scope), the requests themselves suspend in [ErpClient].
 *
 * The rules, each in one place:
 * - Who is asked about: the one-to-one partners of the conversation list ([observe], fed by [run]).
 * - When: whatever is older than [SHIFT_TTL_MS], looked at every [TICK_MS] while the app is in
 *   front ([setForeground]), and at once when a partner appears that was never asked about.
 *   One request at a time ([fetchDue]). An answer is stamped with the time it was asked.
 * - After a failed request nothing is asked for [SHIFT_TTL_MS] ([lastFailureAt]).
 * - Every name asked is stamped, also those the ERP left out (they become Unknown), so
 *   nobody is asked about again before the stamp is old.
 * - 401/403 latches [rejected] until a new pairing and removes the stored key (it is dead);
 *   any other failure keeps what is known.
 * - No ERP address or no key: nothing is sent at all.
 * - The key is only ever sent to the address it was issued by ([pairedBase]).
 * - Unknown is not drawn anywhere; [statuses] is emptied on sign-out, Unpair and rejection.
 */
class ShiftStatusService(
    context: Context,
    private val scope: CoroutineScope,
    /** Bring the app back over the browser once the approval has arrived. */
    private val bringToFront: () -> Unit,
) {
    private val store = ErpStore(context)
    private val client by lazy { ErpClient() }

    private val _link = MutableStateFlow<ErpLink>(ErpLink.NotPaired)
    val link: StateFlow<ErpLink> = _link.asStateFlow()

    private val _linkKnown = MutableStateFlow(false)

    /**
     * True once the signed-in account's stored pairing has been read. Until then [link] says
     * NotPaired without meaning it, so nothing may claim "not paired" before this is true.
     */
    val linkKnown: StateFlow<Boolean> = _linkKnown.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** Why the last pairing attempt ended without a pairing, for Settings. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _address = MutableStateFlow("")

    /** The ERP address typed in the generic build (a branded build has its own built in). */
    val address: StateFlow<String> = _address.asStateFlow()

    private val _statuses = MutableStateFlow<Map<String, ShiftEntry>>(emptyMap())

    /** Nextcloud user id -> what the ERP said. An id that is missing, or Unknown, draws nothing. */
    val statuses: StateFlow<Map<String, ShiftEntry>> = _statuses.asStateFlow()

    /** `server|user` of the signed-in account, null while nobody is signed in. */
    private var account: String? = null

    /** Bumped whenever answers still on their way must be dropped (sign-out, unpair, new pairing). */
    private var generation = 0
    private var apiKey: String? = null
    private var pairedBase: ErpBase? = null
    private var pairedName = ""
    private var rejected = false
    private var observed: List<String> = emptyList()
    private var inFlight = false

    /** elapsedRealtime of the last failed shift request, null when the last one was answered. */
    private var lastFailureAt: Long? = null
    private var foreground = false
    private var tick: Job? = null
    private var pairJob: Job? = null

    /** The ERP address in force: built in (branded) or typed (generic). Null = unusable, nothing is sent. */
    private fun currentBase(): ErpBase? = ErpBase.parse(if (Brand.isBranded) Brand.erpBaseUrl else _address.value)

    // ---------------------------------------------------------------- account

    /** Follows the session: loads the account's ERP link and watches its conversation list. */
    suspend fun run(states: StateFlow<SessionState>) {
        states.map { (it as? SessionState.SignedIn)?.session }.distinctUntilChanged().collectLatest { session ->
            forgetInMemory()
            if (session == null) return@collectLatest
            val key = NotificationGate.accountKey(session)
            // Read apart: a pairing that cannot be read just now must not lose the typed address.
            val typed = try {
                store.address(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the ERP address could not be read", e)
                ""
            }
            var storedRead = true
            val stored = try {
                store.pairing(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A busy Keystore: no shift light this run, the pairing itself is untouched.
                Log.w(TAG, "the ERP pairing could not be read", e)
                storedRead = false
                null
            }
            account = key
            _address.value = typed
            // A key belongs to the address that issued it. Another address in force now
            // (retyped, or a new build) means: not paired with that one, and the key is of
            // no use any more.
            val base = currentBase()
            if (stored != null && base != null && base.url == stored.base) {
                apiKey = stored.apiKey
                pairedBase = base
                pairedName = stored.displayName
                _link.value = ErpLink.Paired(stored.displayName)
            } else if (stored != null && base != null) {
                forgetStored(key)
            }
            // An unreadable store says nothing about the pairing: leave it unknown.
            _linkKnown.value = storedRead
            session.rooms.rooms.collect { rooms -> observe(rooms.map { it.partnerUserId }) }
        }
    }

    /** Remove [account]'s stored key. Never throws. */
    private suspend fun forgetStored(account: String) {
        try {
            store.clearPairing(account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the stored ERP pairing could not be cleared", e)
        }
    }

    private fun forgetInMemory() {
        generation++
        lastFailureAt = null
        pairJob?.cancel()
        pairJob = null
        account = null
        apiKey = null
        pairedBase = null
        pairedName = ""
        rejected = false
        observed = emptyList()
        _statuses.value = emptyMap()
        _linkKnown.value = false
        _link.value = ErpLink.NotPaired
        _notice.value = null
        _address.value = ""
    }

    /** Sign-out (either kind): the key leaves the phone. Never throws. */
    suspend fun signedOut() {
        forgetInMemory()
        try {
            store.clearAllPairings()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the stored ERP pairing could not be cleared", e)
        }
    }

    // ---------------------------------------------------------------- shift lookup

    /** The colleagues on screen: the one-to-one partners of the conversation list, newest first. */
    private fun observe(userIds: List<String>) {
        val ids = userIds.filter { it.isNotEmpty() }.distinct()
        val known = _statuses.value
        val someoneNew = ids.any { it !in known }
        observed = ids
        // Not after up to two minutes; but a list of already known colleagues asks nothing.
        if (someoneNew) fetchDue()
    }

    /** The app came to the front or left it. Asking happens only in front. */
    fun setForeground(on: Boolean) {
        foreground = on
        tick?.cancel()
        tick = if (!on) null else scope.launch {
            // Looked at more often than the answers age, so nobody waits a whole extra round.
            while (true) {
                fetchDue()
                delay(TICK_MS)
            }
        }
    }

    private fun fetchDue() {
        if (inFlight || rejected || !foreground) return
        // Not configured or not paired: dormant, not broken.
        val base = pairedBase ?: return
        val key = apiKey ?: return
        // The ERP did not answer a moment ago: quiet until the answers would be old anyway.
        lastFailureAt?.let { if (SystemClock.elapsedRealtime() - it < SHIFT_TTL_MS) return }
        val now = System.currentTimeMillis()
        val known = _statuses.value
        val batch = buildShiftBatch(observed.filter { id -> known[id]?.let { shiftEntryIsStale(it.fetchedAtMs, now) } ?: true })
        if (batch.isEmpty()) return
        val asked = generation
        inFlight = true
        scope.launch {
            val result = try {
                client.shiftStatus(base, key, batch)
            } finally {
                inFlight = false
            }
            if (asked != generation) {
                // Another account or pairing meanwhile: the answer is nobody's. Ask for the new one.
                fetchDue()
                return@launch
            }
            when (result) {
                is ShiftStatusResult.Ok -> {
                    lastFailureAt = null
                    // The time of asking: an answer is as old as its question.
                    val stamp = now
                    val next = HashMap(_statuses.value)
                    for (id in batch) {
                        val answer = result.statuses[id]
                        next[id] = ShiftEntry(answer?.state ?: ShiftState.Unknown, answer?.label.orEmpty(), stamp)
                    }
                    _statuses.value = next
                    // More than one request's worth of colleagues: the rest right away.
                    if (observed.any { it !in next }) fetchDue()
                }
                ShiftStatusResult.Rejected -> {
                    Log.w(TAG, "the ERP no longer accepts this device; not asking again until it is paired again")
                    rejected = true
                    _statuses.value = emptyMap()
                    if (_link.value is ErpLink.Paired) _link.value = ErpLink.Rejected
                    // The key is dead: it is not kept and never sent again. Settings says
                    // "Not accepted" for this run; after a restart it is simply "Not paired".
                    account?.let { forgetStored(it) }
                }
                is ShiftStatusResult.Failed -> {
                    lastFailureAt = SystemClock.elapsedRealtime()
                    Log.d(TAG, "shift lookup failed: ${result.reason}")
                }
            }
        }
    }

    // ---------------------------------------------------------------- settings

    /** The generic build's "ERP address" field. Only while not paired: a key never moves to another address. */
    fun setAddress(text: String) {
        val account = account ?: return
        if (Brand.isBranded || _link.value != ErpLink.NotPaired) return
        val clean = text.filter { it.code >= 0x20 }.take(MAX_ADDRESS_CHARS)
        _address.value = clean
        _notice.value = null
        scope.launch {
            try {
                store.setAddress(account, clean.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the ERP address could not be stored", e)
            }
        }
    }

    /** What [link] is when no pairing is under way. */
    private fun resting(): ErpLink = when {
        apiKey == null -> ErpLink.NotPaired
        rejected -> ErpLink.Rejected
        else -> ErpLink.Paired(pairedName)
    }

    /**
     * "Pair with ERP": ask the ERP for a pairing, then poll until the person has approved in
     * the browser. Runs on the app's scope: leaving the app for the browser does not stop it.
     * Opening the approval page is the screen's part (see [ErpLink.Pairing], [approveOpened]).
     */
    fun startPairing() {
        val account = account ?: return
        if (_link.value is ErpLink.Pairing) return
        val base = currentBase()
        if (base == null) {
            _notice.value =
                if (Brand.isBranded) "This build has no usable ERP address."
                else "Enter the ERP address first. It must start with https://"
            return
        }
        pairJob?.cancel()
        _notice.value = null
        _link.value = ErpLink.Pairing(approve = null, opened = false)
        val started = generation
        pairJob = scope.launch {
            fun end(why: String) {
                _notice.value = why
                _link.value = resting()
            }
            val offer = when (val r = client.pairStart(base, deviceName())) {
                is PairStartResult.Failed -> return@launch end("Could not start pairing: ${r.reason}.")
                is PairStartResult.Offered -> r.offer
            }
            val target = approveTarget(base, offer.approveUrl)
                ?: return@launch end("The ERP offered an approval page that cannot be opened safely.")
            _link.value = ErpLink.Pairing(target, opened = false)
            val interval = offer.pollIntervalSeconds.coerceIn(1, MAX_POLL_INTERVAL_SECONDS)
            // A budget of waiting, spent by the app itself and not read off the clock: a phone
            // that froze the app while the browser was in front asks again on return instead
            // of having "timed out" unseen. A question that got no answer costs what it took.
            var leftMs = MAX_PAIR_SECONDS * 1000L
            while (true) {
                delay(interval * 1000L)
                leftMs -= interval * 1000L
                if (leftMs < 0) return@launch end("Pairing timed out. Try again.")
                val askedAt = SystemClock.elapsedRealtime()
                when (val r = client.pairPoll(base, offer.pairToken)) {
                    PairPollResult.Waiting -> Unit
                    // No network just now (the browser is in front): not an answer, ask again.
                    is PairPollResult.NoAnswer ->
                        leftMs -= (SystemClock.elapsedRealtime() - askedAt).coerceIn(0L, MAX_POLL_COST_MS)
                    is PairPollResult.Gone -> return@launch end("Pairing was declined or expired.")
                    is PairPollResult.Paired -> {
                        if (started != generation) return@launch
                        try {
                            store.savePairing(account, StoredPairing(base.url, r.apiKey, r.displayName))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "the ERP key could not be stored", e)
                            return@launch end("Paired, but the key could not be stored on this phone. Try again.")
                        }
                        generation++
                        apiKey = r.apiKey
                        pairedBase = base
                        pairedName = r.displayName
                        rejected = false
                        lastFailureAt = null
                        _statuses.value = emptyMap()
                        _notice.value = null
                        _link.value = ErpLink.Paired(r.displayName)
                        val wasBehind = !foreground
                        fetchDue()
                        if (wasBehind) bringToFront()
                        return@launch
                    }
                }
            }
        }
    }

    /** The screen handed the approval page to the browser. */
    fun approveOpened() {
        val pairing = _link.value as? ErpLink.Pairing ?: return
        _link.value = pairing.copy(opened = true)
    }

    /** "Cancel", or the approval page could not be shown ([why]). The ERP's offer just expires. */
    fun cancelPairing(why: String? = null) {
        if (_link.value !is ErpLink.Pairing) return
        pairJob?.cancel()
        pairJob = null
        _notice.value = why
        _link.value = resting()
    }

    /** "Unpair": the key is forgotten on this phone. */
    fun unpair() {
        val account = account ?: return
        generation++
        pairJob?.cancel()
        pairJob = null
        apiKey = null
        pairedBase = null
        pairedName = ""
        rejected = false
        lastFailureAt = null
        _statuses.value = emptyMap()
        _notice.value = null
        _link.value = ErpLink.NotPaired
        scope.launch { forgetStored(account) }
    }

    /** What the person sees on the ERP's approval page: "<model> (Android)". */
    private fun deviceName(): String {
        val model = Build.MODEL.orEmpty()
            .map { if (it.code < 0x20 || it.code == 0x7f) ' ' else it }.joinToString("").trim().take(64)
        return "${model.ifEmpty { "Phone" }} (Android)"
    }

    private companion object {
        const val TAG = "TalqShift"
        const val MAX_PAIR_SECONDS = 180

        /** The most one unanswered poll takes off the pairing budget (the request timeout). */
        const val MAX_POLL_COST_MS = 15_000L

        /** How often the foreground loop looks for answers older than [SHIFT_TTL_MS]. */
        const val TICK_MS = 30_000L
        const val MAX_POLL_INTERVAL_SECONDS = 30
        const val MAX_ADDRESS_CHARS = 300
    }
}
