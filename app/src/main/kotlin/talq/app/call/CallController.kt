package talq.app.call

import android.content.Context
import android.os.PowerManager
import android.telecom.DisconnectCause
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import talq.app.AppContainer
import talq.app.SessionState
import talq.core.TalkError
import talq.core.call.CallEndReason
import talq.core.call.CallSession
import talq.core.call.CallMode
import talq.core.call.PublishRights
import talq.core.call.callOffered
import talq.core.call.CallState
import talq.core.call.NotificationState
import talq.core.model.Room
import talq.core.net.newHpbHttpClient
import talq.core.repo.TalkSession
import java.util.WeakHashMap

/**
 * The one call this phone can be in (`appContainer.calls`). Main thread.
 *
 * A call is started here ([begin]), in the controller's own scope: the call screen only asks
 * and watches ([starting], [lastStart], [current]), so leaving or replacing the screen never
 * cuts a start half-way, and [cancelStart] (Cancel, Back, Hang up during "Connecting…") is
 * checked right before the call is placed. Starting starts the call's foreground service first
 * (the screen is in front then: Android gives a call the microphone in the background only
 * through that service), then builds the media ([WebRtcEngine]) and the [CallSession], and tells
 * the system about the call ([CallTelecom]). Every end of the call, whoever ends it, releases all
 * of it: the system's call, the proximity lock, the microphone and camera (the session closes the
 * media); the service stops itself. [current] keeps an ended call a little longer, so the call
 * screen can show how it ended.
 */
class CallController(context: Context, private val container: AppContainer) {
    /**
     * The running (or just ended) call. [title] = the other person's name in a one-to-one call,
     * the conversation's name in a group call; [peerUserId] = the other person (one-to-one only).
     */
    class ActiveCall(
        val token: String,
        val title: String,
        val peerUserId: String?,
        val isGroup: Boolean,
        val incoming: Boolean,
        val session: CallSession,
        val engine: WebRtcEngine,
        /** The account the call belongs to (for avatars and the server's address). */
        val account: TalkSession,
        internal val scope: CoroutineScope,
    )

    /** One request to place or answer a call; [launch] tells the screen which result is its own. */
    data class StartRequest(
        val launch: Long,
        val token: String,
        val video: Boolean,
        val incoming: Boolean,
        val nid: Long,
        val recordingConsent: Boolean,
        /** Join the call that runs (the Join bar or notification): never start a new one. */
        val join: Boolean = false,
    )

    sealed interface StartResult {
        data object Started : StartResult

        /** That call is already running (or being set up) here: just show it. */
        data object Running : StartResult

        /** Another call is running or being set up. */
        data object Busy : StartResult

        /** The call to answer has ended, or was [answeredElsewhere] (on another device of the user). */
        data class Gone(val answeredElsewhere: Boolean) : StartResult

        /** Cancelled before the call was placed. */
        data object Cancelled : StartResult

        data class Failed(val message: String) : StartResult
    }

    private val appContext = context.applicationContext
    private val scope = container.scope
    private val telecom = CallTelecom(appContext, scope)
    private val ringback = Ringback()

    private val _current = MutableStateFlow<ActiveCall?>(null)
    val current: StateFlow<ActiveCall?> = _current.asStateFlow()

    private val _starting = MutableStateFlow(false)

    /** A call is being set up (before [current] has it). */
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    private val _lastStart = MutableStateFlow<Pair<Long, StartResult>?>(null)

    /** The outcome of the latest [begin], with its request's launch id. */
    val lastStart: StateFlow<Pair<Long, StartResult>?> = _lastStart.asStateFlow()

    val endpoints: StateFlow<List<AudioOutput>> get() = telecom.endpoints
    val endpoint: StateFlow<AudioOutput?> get() = telecom.endpoint

    private val availability = WeakHashMap<TalkSession, Boolean>()
    private var proximity: PowerManager.WakeLock? = null
    private var held = false
    private var heldMicrophone = false
    private var startingToken: String? = null
    private var startCancelled = false
    private var startJob: kotlinx.coroutines.Job? = null
    private var lastRestore = 0L

    /** The last call per conversation, for waiting until it has left before the next one. */
    private val previous = HashMap<String, ActiveCall>()

    /** True when a call is running or being set up (not just ended). */
    val busy: Boolean
        get() = _starting.value || _current.value?.let { it.session.state.value !is CallState.Ended } == true

    /**
     * Whether [session]'s server can do calls: calls are on for the user and the server has a
     * call backend (HPB). Asked once per session; a failed request is asked again next time.
     */
    suspend fun available(session: TalkSession): Boolean {
        availability[session]?.let { return it }
        if (!session.capabilities.callsEnabled) return false.also { availability[session] = false }
        return try {
            val hpb = session.client.signalingSettings().hasHpb
            availability[session] = hpb
            hpb
        } catch (e: TalkError) {
            false
        }
    }

    /**
     * The server wants the user's consent to a recording before joining (capability 1 always,
     * 2 when the conversation asks for it).
     */
    fun recordingConsentNeeded(session: TalkSession, token: String): Boolean = when (session.capabilities.recordingConsent) {
        1 -> true
        2 -> session.rooms.room(token)?.raw?.get("recordingConsent")?.jsonPrimitive?.let { runCatching { it.int }.getOrNull() } == 1
        else -> false
    }

    /**
     * The signed-in account, waiting while it is still being opened (a cold start from a
     * notification) or could not reach the server yet (it is asked again meanwhile).
     */
    suspend fun awaitAccount(): TalkSession? {
        container.session?.let { return it }
        return withTimeoutOrNull(SESSION_WAIT_MS) {
            var result: TalkSession? = null
            while (true) {
                val state = container.sessionState.value
                when (state) {
                    is SessionState.SignedIn -> { result = state.session; break }
                    SessionState.SignedOut -> break
                    // Asked again now and then, not in a tight loop (an offline restore fails at once).
                    is SessionState.Unreachable -> if (System.currentTimeMillis() - lastRestore >= RESTORE_GAP_MS) {
                        lastRestore = System.currentTimeMillis()
                        container.restoreIfUnreachable()
                    }
                    SessionState.Loading -> Unit
                }
                withTimeoutOrNull(STATE_POLL_MS) { container.sessionState.first { it !== state } }
            }
            result
        }
    }

    /** Places or answers a call for [r]; the outcome arrives in [lastStart]. */
    fun begin(r: StartRequest) {
        _current.value?.let { running ->
            if (running.session.state.value !is CallState.Ended) {
                settle(r, if (running.token == r.token) StartResult.Running else StartResult.Busy)
                return
            }
        }
        if (_starting.value) {
            if (startCancelled) {
                // A start the user gave up on is still unwinding: start afresh once it has.
                val old = startJob
                scope.launch {
                    old?.join()
                    begin(r)
                }
                return
            }
            settle(r, if (startingToken == r.token) StartResult.Running else StartResult.Busy)
            return
        }
        // A new call replaces the previous call's end screen.
        if (_current.value != null) _current.value = null
        _starting.value = true
        startingToken = r.token
        startCancelled = false
        // Now, while the call screen is surely in front: a microphone service can only be
        // started from the foreground. It stops itself if this start comes to nothing.
        TalqCallService.start(appContext)
        startJob = scope.launch {
            val result = try {
                start(r)
            } catch (e: CancellationException) {
                StartResult.Cancelled
            } finally {
                _starting.value = false
                startingToken = null
            }
            settle(r, result)
        }
    }

    /** Publishes [result] for [r]; an answer that did not go through is settled with the ringer. */
    private fun settle(r: StartRequest, result: StartResult) {
        if (r.incoming) {
            val answered = result == StartResult.Started || result == StartResult.Running ||
                (result is StartResult.Gone && result.answeredElsewhere)
            container.ringer.answerSettled(r.nid, answered)
        }
        _lastStart.value = r.launch to result
    }

    /** Cancel / Back / Hang up while "Connecting…": nothing is placed after this. */
    fun cancelStart() {
        if (!_starting.value) return
        startCancelled = true
        // Waiting steps end at once; the media build finishes and is closed again.
        startJob?.cancel()
    }

    private suspend fun start(r: StartRequest): StartResult {
        val account = awaitAccount() ?: return StartResult.Failed(
            if (container.sessionState.value is SessionState.Unreachable) "The server can't be reached right now." else "You are not signed in.",
        )
        if (startCancelled) return StartResult.Cancelled
        val room = roomOf(account, r.token) ?: return StartResult.Failed("This conversation could not be found.")
        val joining = r.incoming || r.join
        if (!callOffered(room, hasHpb = true, nowSeconds = System.currentTimeMillis() / 1000, joining = joining)) {
            return StartResult.Failed("Calls aren't possible in this conversation.")
        }
        if (r.join) {
            // A Join that comes late must not start a new call (it would ring everyone): the list is
            // asked again, and the server's word on whether this user is called counts.
            account.rooms.refresh()
            if (account.rooms.room(r.token)?.hasCall == false) return StartResult.Gone(answeredElsewhere = false)
            if (account.capabilities.callNotificationState) {
                val state = try {
                    account.client.callNotificationState(r.token)
                } catch (e: TalkError) {
                    null
                }
                if (state == NotificationState.Missed) return StartResult.Gone(answeredElsewhere = false)
            }
        }
        if (r.incoming && account.capabilities.callNotificationState) {
            // Joining a call that is over would start a new one that rings the caller.
            val state = try {
                account.client.callNotificationState(r.token)
            } catch (e: TalkError) {
                null
            }
            if (state == NotificationState.Missed) return StartResult.Gone(answeredElsewhere = false)
            if (state == NotificationState.Gone) return StartResult.Gone(answeredElsewhere = true)
        }
        if (startCancelled) return StartResult.Cancelled
        // The last call in this conversation leaves through the same server session: let it
        // finish leaving first (what it has not sent by then it leaves out, see supersede()).
        previous[r.token]?.let { old -> withTimeoutOrNull(PREVIOUS_WAIT_MS) { old.session.awaitClosed() } }
        if (startCancelled) return StartResult.Cancelled
        // The open conversation's "typing…" session says goodbye before the call enters one (a second at most),
        // while the media is set up. A start that comes to nothing leaves it to end by itself.
        val goodbye = scope.launch { container.typing.leaveForCall() }
        var built: WebRtcEngine? = null
        try {
            val withCamera = r.video && PublishRights.of(room.permissions).video
            withContext(NonCancellable + Dispatchers.Default) { built = WebRtcEngine(appContext, WebRtcEngine.egl(), withCamera) }
            goodbye.join()
        } catch (e: CancellationException) {
            built?.let { engine -> withContext(NonCancellable + Dispatchers.Default) { engine.close() } }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the media could not be set up (${e::class.simpleName})")
            return StartResult.Failed("The call could not be set up on this phone.")
        }
        val engine = built ?: return StartResult.Failed("The call could not be set up on this phone.")
        // Checked on the main thread right before the call is placed: nothing can slip in between.
        if (startCancelled || container.session !== account) {
            withContext(NonCancellable + Dispatchers.Default) { engine.close() }
            return StartResult.Cancelled
        }
        launchCall(account, room, r, engine)
        return StartResult.Started
    }

    /** Everything after the media exists; nothing here waits, so it cannot be cut half-way. */
    private fun launchCall(account: TalkSession, room: Room, r: StartRequest, engine: WebRtcEngine) {
        // Placing a call ends a ring that came meanwhile (as a missed call, unless it is from the
        // very person being called: then both join the same call).
        container.ringer.missCurrent(exceptToken = r.token)
        previous.remove(r.token)?.session?.supersede()
        // The call's own coroutines run off the main thread; teardown outlives this scope.
        val callScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = newHpbHttpClient()
        val oneToOne = room.isOneToOne && room.partnerUserId.isNotEmpty()
        val session = CallSession(
            client = account.client,
            http = http,
            token = r.token,
            mode = if (oneToOne) CallMode.OneToOne(room.partnerUserId) else CallMode.Group,
            rights = PublishRights.of(room.permissions),
            engine = engine,
            // Joining a call that runs notifies nobody; should it have ended a moment ago, the new
            // one rings nobody either.
            joinSilently = r.join || (r.incoming && !oneToOne),
            scope = callScope,
            now = { System.currentTimeMillis() },
            recordingConsent = r.recordingConsent,
            log = { Log.i(TAG, it) },
        )
        val title = room.displayName.ifBlank { room.partnerUserId.ifBlank { "Call" } }
        val call = ActiveCall(
            token = r.token,
            title = title,
            peerUserId = room.partnerUserId.takeIf { oneToOne },
            isGroup = !oneToOne,
            incoming = r.incoming,
            session = session,
            engine = engine,
            account = account,
            scope = callScope,
        )
        _current.value = call
        previous[r.token] = call
        held = false
        heldMicrophone = false
        telecom.begin(title, r.token, r.incoming, r.video, object : CallTelecom.Listener {
            override fun onSystemHangUp() = hangUp()

            override fun onHold(held: Boolean) = hold(call, held)

            override fun onSystemMute(muted: Boolean) {
                if (_current.value === call && call.session.microphone.value == muted) {
                    call.scope.launch { call.session.setMicrophone(!muted) }
                }
            }
        })
        // The caller hears ringing until someone joins: one-to-one calls placed here, and group
        // calls started here (joining a call that runs already rings nothing).
        val withRingback = !r.incoming && (oneToOne || !room.hasCall)
        // start()/answer() return once set up; the call goes on in the session.
        callScope.launch { if (r.incoming) session.answer(r.video) else session.start(r.video) }
        // The signaling client lives until the session has left everything.
        callScope.launch {
            http.use {
                session.state.first { it is CallState.Ended }
                session.awaitClosed()
            }
        }
        watch(call, withRingback)
    }

    /** Follows the call until it ends: the system's call, the proximity lock, the cleanup. */
    private fun watch(call: ActiveCall, withRingback: Boolean) {
        scope.launch {
            var activeReported = false
            // The ringing tone is for the wait until someone answers, not for everyone leaving later.
            var answered = false
            val (last, _, _) = combine(call.session.state, call.session.camera, telecom.endpoint) { state, camera, out ->
                Triple(state, camera, out)
            }.first { (state, camera, out) ->
                if (state is CallState.Active && !activeReported) {
                    activeReported = true
                    telecom.setActive()
                }
                if (_current.value === call) {
                    setProximity(state !is CallState.Ended && !camera && out?.kind == AudioOutput.Kind.Earpiece)
                    if (state is CallState.Active) answered = true
                    if (withRingback && !answered && state == CallState.WaitingForPeer) ringback.start() else ringback.stop()
                }
                state is CallState.Ended
            }
            finished(call, (last as CallState.Ended).reason)
        }
    }

    private fun finished(call: ActiveCall, reason: CallEndReason) {
        Log.i(TAG, "call over: $reason")
        if (_current.value === call) {
            ringback.stop()
            telecom.end(
                when (reason) {
                    CallEndReason.PeerHungUp, CallEndReason.EndedForEveryone -> DisconnectCause.REMOTE
                    CallEndReason.Declined -> DisconnectCause.REJECTED
                    CallEndReason.Failed, CallEndReason.NoHpb -> DisconnectCause.ERROR
                    else -> DisconnectCause.LOCAL
                },
            )
            setProximity(false)
        }
        // The call service sees the end itself and stops (stopping it from here could come
        // before it went to the foreground, which Android punishes by ending the app).
        scope.launch {
            // The call screen shows how it ended for a moment.
            delay(ENDED_SHOWN_MS)
            if (_current.value === call) _current.value = null
            // The session's teardown has its own scope; this one only held the call's helpers.
            call.session.awaitClosed()
            call.scope.cancel()
        }
    }

    fun hangUp() {
        val call = _current.value ?: return
        call.scope.launch { call.session.hangUp() }
    }

    fun setMicrophone(on: Boolean) {
        val call = _current.value ?: return
        heldMicrophone = false
        if (on && held) resume(call)
        call.scope.launch { call.session.setMicrophone(on) }
    }

    fun setCamera(on: Boolean) {
        val call = _current.value ?: return
        call.scope.launch { call.session.setCamera(on) }
        // Video is watched held out in front: the earpiece would be inaudible.
        if (on && telecom.endpoint.value?.kind == AudioOutput.Kind.Earpiece) {
            telecom.endpoints.value.firstOrNull { it.kind == AudioOutput.Kind.Speaker }?.let { telecom.select(it) }
        }
    }

    fun selectEndpoint(output: AudioOutput) = telecom.select(output)

    /**
     * On hold (a regular phone call came in) the microphone goes off and the other person is no
     * longer played. Off hold, both come back; the microphone only if the hold switched it off.
     */
    private fun hold(call: ActiveCall, on: Boolean) {
        if (_current.value !== call) return
        if (on) {
            held = true
            call.engine.setRemoteAudio(false)
            if (call.session.microphone.value) {
                heldMicrophone = true
                call.scope.launch { call.session.setMicrophone(false) }
            }
        } else {
            val restoreMic = heldMicrophone
            resume(call)
            if (restoreMic) call.scope.launch { call.session.setMicrophone(true) }
        }
    }

    /** Off hold: play the other person again and tell the system the call is active. */
    private fun resume(call: ActiveCall) {
        if (!held) return
        held = false
        heldMicrophone = false
        call.engine.setRemoteAudio(true)
        telecom.setActive()
    }

    /** Signing out: leave the call properly while the account still works (8 s at most). */
    suspend fun signedOut() {
        cancelStart()
        withTimeoutOrNull(SIGN_OUT_WAIT_MS) {
            if (_starting.value) _starting.first { !it }
            _current.value?.session?.hangUp()
        }
        previous.clear()
    }

    /** The account is gone already (401): end the call without waiting for the server. */
    fun abort() {
        cancelStart()
        hangUp()
        previous.clear()
    }

    private suspend fun roomOf(session: TalkSession, token: String): Room? {
        session.rooms.room(token)?.let { return it }
        session.rooms.refresh()
        return session.rooms.room(token)
    }

    private fun setProximity(on: Boolean) {
        if (on) {
            if (proximity?.isHeld == true) return
            val pm = appContext.getSystemService(PowerManager::class.java) ?: return
            if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "talq:call-proximity").also {
                it.setReferenceCounted(false)
                it.acquire(MAX_CALL_MS)
            }
        } else {
            proximity?.let { if (it.isHeld) it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
            proximity = null
        }
    }

    private companion object {
        const val TAG = "TalqCall"
        const val ENDED_SHOWN_MS = 2_500L
        const val SIGN_OUT_WAIT_MS = 8_000L
        const val SESSION_WAIT_MS = 15_000L
        const val STATE_POLL_MS = 3_000L
        const val RESTORE_GAP_MS = 10_000L
        const val PREVIOUS_WAIT_MS = 10_000L
        const val MAX_CALL_MS = 6 * 60 * 60 * 1000L
    }
}
