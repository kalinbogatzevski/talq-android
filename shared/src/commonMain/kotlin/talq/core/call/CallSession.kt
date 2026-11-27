package talq.core.call

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import talq.core.TalkClient
import talq.core.TalkError
import talq.core.net.EmptyJsonObject
import talq.core.net.int
import talq.core.net.obj
import talq.core.net.str
import kotlin.concurrent.Volatile

/**
 * One one-to-one call in the conversation [token] with the user [peerUserId], from [start]
 * (outgoing) or [answer] (incoming) to its end. The wire sequence is the one in the call
 * protocol map (docs/superpowers/specs/2026-11-22-call-protocol-map.md):
 *
 * signaling settings, signaling connect, join the conversation (`participants/active`), join its
 * room on the signaling server and wait for the answer, then join the call. Joining counts once
 * the server answers 200 OR the signaling server lists our own session in the call, whichever
 * comes first (the join request can hang for a long time on a server whose call notifications
 * are slow). Then the publisher's offer goes to our OWN session (the backend's media server
 * takes it, also in a one-to-one call), and once the other person is in the call (a
 * participants update, or found by polling `GET call/{token}`) their media is requested with
 * `requestoffer` and their offer answered.
 *
 * One coroutine (the loop) owns the call's state and takes everything in order: signaling
 * events, the media engine's candidates and connection state, REST answers, timers and the
 * user's actions. REST requests run beside it and post their answers back.
 *
 * A dropped signaling socket that resumes the same session changes nothing. A NEW session
 * (the old one was lost) means joining the call again (silently), a new publisher and a new
 * `requestoffer`; so does media that failed. 20 s without getting back ends the call.
 *
 * Every end (whoever ended it) leaves the call, the room and the conversation and closes the
 * signaling and the media; [hangUp] waits for that. Nothing logged carries an SDP, a ticket,
 * TURN credentials or a session id.
 *
 * [recordingConsent] is sent with the join (the user agreed to a possible recording). [log]
 * gets one line per step.
 */
class CallSession(
    private val client: TalkClient,
    private val http: HttpClient,
    val token: String,
    private val peerUserId: String,
    private val engine: MediaEngine,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val recordingConsent: Boolean = false,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<CallState>(CallState.Connecting)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _peer = MutableStateFlow<PeerMedia?>(null)

    /** The other person once they are in the call (null before). */
    val peer: StateFlow<PeerMedia?> = _peer.asStateFlow()

    private val _ownSessionId = MutableStateFlow("")

    /** This device's signaling session ("" before it has one). */
    val ownSessionId: StateFlow<String> = _ownSessionId.asStateFlow()

    private val _microphone = MutableStateFlow(true)

    /** Our microphone is on (a moderator can switch it off). */
    val microphone: StateFlow<Boolean> = _microphone.asStateFlow()

    private val _camera = MutableStateFlow(false)

    /** Our camera is on. */
    val camera: StateFlow<Boolean> = _camera.asStateFlow()

    private val _sent = MutableSharedFlow<SentSignal>(replay = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** What this call sent on the signaling socket, type and recipient only (the last 64). */
    val sent: SharedFlow<SentSignal> = _sent.asSharedFlow()

    private sealed interface Input {
        data class InRoom(val sessionId: String) : Input
        data class Hpb(val event: HpbInbound) : Input
        data class Signaling(val state: HpbState) : Input
        data class Local(val candidate: LocalCandidate) : Input
        data class Publisher(val connection: MediaConnection) : Input
        data class Subscriber(val connection: MediaConnection) : Input
        data object JoinOk : Input
        data class JoinError(val error: TalkError) : Input
        data object ReenteredRoom : Input
        data class JoinListed(val peers: List<CallPeer>) : Input
        data class OfferRetry(val generation: Int) : Input
        data class Peers(val peers: List<CallPeer>) : Input
        data class Timer(val kind: TimerKind) : Input
        data class Mic(val on: Boolean) : Input
        data class Camera(val on: Boolean) : Input
        data class End(val reason: CallEndReason, val why: String) : Input
    }

    private enum class TimerKind {
        NoAnswer, JoinDeadline, JoinCheck, Poll, RequestOffer, Reconnect, SubscriberStall, PublishDeadline, Republish, PeerGrace,
    }

    private val inbox = Channel<Input>(Channel.UNLIMITED)
    private val lifecycle = Mutex()
    private val done = Job()

    /** Outside [scope]'s job: leaving the call must finish even when the caller's scope ends. */
    private val teardownScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())
    @Volatile private var begun = false
    @Volatile private var work: Job? = null
    @Volatile private var callScope: CoroutineScope? = null
    @Volatile private var loopJob: Job? = null
    @Volatile private var setupJob: Job? = null
    @Volatile private var hpb: HpbConnection? = null
    @Volatile private var iceServers: List<IceServer> = emptyList()
    @Volatile private var ncSession: String? = null
    @Volatile private var joinSent = false
    @Volatile private var joinJob: Job? = null
    @Volatile private var reenterJob: Job? = null
    @Volatile private var peerSid: String? = null
    @Volatile private var superseded = false

    // Owned by the loop.
    private var outgoing = true
    private var ownId = ""
    private var inRoom = false
    private var joined = false
    private var lastJoinSilent = false
    private var reenteredRoom = false
    private var awaitingRoomAck = false
    private var signalingUp = true
    private var reconnecting = false
    private var pubState = MediaConnection.New
    private var subState = MediaConnection.New
    private var lastPublishAt = 0L
    private var pubAttempts = 0
    private var pubRebuildPending = false
    private var subRebuilds = 0
    private var postedFlags = 0
    private var signalingDropped = false

    /** The adopted peer's session went (left the call or the conversation): waiting for them back. */
    private var peerGone = false

    /** A requestoffer is out and its retry timer runs: a second one would make a second handle. */
    private var offerRequested = false
    private var offerGeneration = 0
    private var joinDeadlineExtended = false

    /** Sessions that left the room: never adopted again from a stale list. */
    private val goneSessions = HashSet<String>()
    private var activeSince: Long? = null
    private var micOn = true
    private var camOn = false
    private var pubSid: String? = null
    private var lastPubSid = 0L
    private var pubAnswered = false
    private var peerFlags = 0
    private var peerFlagsKnown = false
    private var subSid: String? = null
    private var offerRequests = 0
    private val timers = HashMap<TimerKind, Job>()
    private val inCall = HashMap<String, Int>()
    private val sessionUser = HashMap<String, String>()
    private val ncToHpb = HashMap<String, String>()
    private val earlyCandidates = LinkedHashMap<String, MutableList<JsonObject>>()

    /** Calls [peerUserId]: rings them and waits up to 60 s for them to join. */
    suspend fun start(video: Boolean) = begin(video, outgoing = true)

    /** Joins the call the other person started (the same path, without waiting for a ring). */
    suspend fun answer(video: Boolean) = begin(video, outgoing = false)

    suspend fun setMicrophone(on: Boolean) {
        inbox.send(Input.Mic(on))
    }

    suspend fun setCamera(on: Boolean) {
        inbox.send(Input.Camera(on))
    }

    /**
     * A newer call in this conversation started on this account. Leaving (the call, the
     * conversation) goes through the account's one server session, so a leave this session sends
     * late, once a slow join is answered, would take the newer call down: it is skipped.
     */
    fun supersede() {
        superseded = true
    }

    /** Returns once this session has left everything (after [hangUp] or any other end). */
    suspend fun awaitClosed() {
        done.join()
    }

    /** Ends the call from this side and returns once everything is left and closed. */
    suspend fun hangUp() {
        end(CallEndReason.HungUp, "hung up")
        done.join()
    }

    private suspend fun begin(video: Boolean, outgoing: Boolean) {
        val setup = lifecycle.withLock {
            check(!begun) { "a CallSession is started once" }
            begun = true
            if (_state.value is CallState.Ended) return
            this.outgoing = outgoing
            camOn = video
            _camera.value = video
            val job = SupervisorJob(scope.coroutineContext[Job])
            val cs = CoroutineScope(scope.coroutineContext + job)
            work = job
            callScope = cs
            loopJob = cs.launch { runLoop() }
            cs.launch(start = CoroutineStart.UNDISPATCHED) { engine.localCandidates.collect { inbox.send(Input.Local(it)) } }
            cs.launch(start = CoroutineStart.UNDISPATCHED) { engine.publisher.collect { inbox.send(Input.Publisher(it)) } }
            cs.launch(start = CoroutineStart.UNDISPATCHED) { engine.subscriber.collect { inbox.send(Input.Subscriber(it)) } }
            cs.launch { setup(cs) }.also { setupJob = it }
        }
        setup.join()
    }

    /** Signaling and the room; the call itself is joined by the loop once [Input.InRoom] arrives. */
    private suspend fun setup(cs: CoroutineScope) {
        try {
            val settings = client.signalingSettings(now())
            if (!settings.hasHpb) {
                inbox.send(Input.End(CallEndReason.NoHpb, "the server has no call backend"))
                return
            }
            iceServers = settings.iceServers
            val c = HpbConnection(http, settings, client.serverUrl, cs) { client.signalingSettings(now()) }
            hpb = c
            // The events have no replay: listen before connecting.
            cs.launch(start = CoroutineStart.UNDISPATCHED) { c.events.collect { inbox.send(Input.Hpb(it)) } }
            cs.launch(start = CoroutineStart.UNDISPATCHED) { c.state.collect { inbox.send(Input.Signaling(it)) } }
            val id = c.connect()
            val nc = client.joinRoomSession(token)
            ncSession = nc
            c.joinRoom(token, nc)
            c.announceClient()
            inbox.send(Input.InRoom(id))
        } catch (e: TalkError) {
            inbox.send(Input.End(CallEndReason.Failed, "setting up failed (${e::class.simpleName})"))
        }
    }

    private suspend fun runLoop() {
        for (input in inbox) {
            if (_state.value is CallState.Ended) return
            try {
                handle(input)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                end(CallEndReason.Failed, "unexpected ${e::class.simpleName}")
            }
        }
    }

    private suspend fun handle(input: Input) {
        when (input) {
            is Input.InRoom -> inRoom(input.sessionId)
            is Input.Hpb -> hpbEvent(input.event)
            is Input.Signaling -> signaling(input.state)
            is Input.Local -> localCandidate(input.candidate)
            is Input.Publisher -> publisherState(input.connection)
            is Input.Subscriber -> subscriberState(input.connection)
            Input.JoinOk -> joinConfirmed("the server answered")
            is Input.JoinError -> joinError(input.error)
            Input.ReenteredRoom -> postJoin(lastJoinSilent)
            is Input.JoinListed -> joinListed(input.peers)
            is Input.OfferRetry -> if (input.generation == offerGeneration && peerSid != null && subSid == null &&
                offerRequests < MAX_OFFER_REQUESTS
            ) sendRequestOffer() else if (input.generation == offerGeneration) offerRequested = false
            is Input.Peers -> peers(input.peers)
            is Input.Timer -> timer(input.kind)
            is Input.Mic -> microphone(input.on)
            is Input.Camera -> camera(input.on)
            is Input.End -> end(input.reason, input.why)
        }
    }

    // ---------------------------------------------------------------- joining

    private fun inRoom(sessionId: String) {
        if (ownId.isEmpty()) setOwn(sessionId)
        inRoom = true
        log("in the room; joining the call")
        startTimer(TimerKind.NoAnswer, NO_ANSWER_MS)
        postJoin(silent = false)
    }

    private fun postJoin(silent: Boolean) {
        if (callScope == null) return
        joinSent = true
        lastJoinSilent = silent
        joinDeadlineExtended = false
        val flags = flags()
        postedFlags = flags
        startTimer(TimerKind.JoinDeadline, JOIN_DEADLINE_MS)
        // The server writes the join before its slow part (telling the signaling server), so
        // `GET call/{token}` can show us in the call while the POST still waits.
        startTimer(TimerKind.JoinCheck, JOIN_CHECK_MS)
        // Outside the call's own jobs: a late leave in teardown waits for this answer.
        joinJob = teardownScope.launch {
            val answer = try {
                client.joinCall(token, flags, silent = silent, recordingConsent = recordingConsent)
                Input.JoinOk
            } catch (e: TalkError) {
                Input.JoinError(e)
            }
            inbox.send(answer)
        }
    }

    private fun joinError(e: TalkError) {
        if (joined) return
        when {
            // Not an answer: the join may still have gone through. Wait for the signaling server.
            e is TalkError.Network || e is TalkError.Server ->
                log("joining the call: no clear answer (${e::class.simpleName}); waiting for the signaling server")
            // Talk found no participant for this request's session. Seen when the conversation
            // was joined in a brand-new server session: that first join is not kept, a second
            // one is. Enter the conversation once more (a conversation that is really gone
            // answers 404 to that too) and join the call again.
            e is TalkError.NotFound && !reenteredRoom -> {
                reenteredRoom = true
                log("joining the call answered 404; entering the conversation again")
                reenterRoom()
            }
            else -> end(CallEndReason.Failed, "joining the call was refused (${e::class.simpleName})")
        }
    }

    /** Leaves the signaling room, joins the conversation again and the room with that session. */
    private fun reenterRoom() {
        val c = hpb ?: return
        reenterJob = callScope?.launch {
            try {
                c.leaveRoom()
                val nc = client.joinRoomSession(token)
                ncSession = nc
                c.joinRoom(token, nc)
                c.announceClient()
                inbox.send(Input.ReenteredRoom)
            } catch (e: TalkError) {
                inbox.send(Input.End(CallEndReason.Failed, "entering the conversation again failed (${e::class.simpleName})"))
            }
        }
    }

    private suspend fun joinConfirmed(how: String) {
        if (joined) return
        joined = true
        cancelTimer(TimerKind.JoinDeadline)
        cancelTimer(TimerKind.JoinCheck)
        log("in the call ($how)")
        // A mute or camera switch made while the join was on its way.
        if (flags() != postedFlags) updateFlags()
        publish()
        if (peerSid == null) startTimer(TimerKind.Poll, FIRST_POLL_MS) else requestOfferIfPublishing()
        refreshState()
    }

    private suspend fun publish() {
        val sid = maxOf(now(), lastPubSid + 1)
        lastPubSid = sid
        lastPublishAt = now()
        pubSid = sid.toString()
        pubAnswered = false
        pubAttempts++
        pubRebuildPending = false
        // An offer that gets no answer (refused, or lost with a dropped socket) leaves the
        // connection NEW for ever: give it a deadline.
        startTimer(TimerKind.PublishDeadline, PUBLISH_DEADLINE_MS)
        val sdp = engine.createPublisherOffer(iceServers, camOn)
        engine.setLocalAudio(micOn)
        engine.setLocalVideo(camOn)
        send(
            ownId, "offer",
            signal(ownId, "offer", sid.toString(), payload = sdpPayload("offer", sdp),
                extra = mapOf("audiocodec" to "opus", "videocodec" to VIDEO_CODECS)),
        )
    }

    // ---------------------------------------------------------------- the other person

    private suspend fun participants(users: List<HpbUser>) {
        for (u in users) participant(u)
    }

    /**
     * After an update whose `users` (everyone in the call) lists the other person on another
     * session but not on the adopted one: they moved (a second device, a new session), follow.
     */
    private fun followMovedPeer(users: List<HpbUser>) {
        val peer = peerSid ?: return
        if (users.isEmpty() || users.any { it.sessionId == peer }) return
        if (users.none { it.sessionId != peer && sessionUser[it.sessionId] == peerUserId && it.inCall and CallFlags.IN_CALL != 0 }) return
        peerLeft("the other person moved to another session")
    }

    private suspend fun participant(u: HpbUser) {
        run {
            if (u.sessionId.isEmpty()) return
            if (u.userId.isNotEmpty()) sessionUser[u.sessionId] = u.userId
            if (u.ncSessionId.isNotEmpty()) ncToHpb[u.ncSessionId] = u.sessionId
            inCall[u.sessionId] = u.inCall
            val inTheCall = u.inCall and CallFlags.IN_CALL != 0
            if (u.sessionId == ownId) {
                if (joinSent && inTheCall) joinConfirmed("the signaling server lists us")
                else if (joined && !inTheCall) end(CallEndReason.Failed, "the server took this phone out of the call")
                return
            }
            if (sessionUser[u.sessionId] != peerUserId) return
            val peer = peerSid
            when {
                peer == null -> if (inTheCall && u.sessionId !in goneSessions) adopt(u.sessionId, u.inCall, u.displayName, known = true)
                u.sessionId == peer -> if (inTheCall) peerFlagsChanged(u.inCall, known = true) else peerLeft("the other person left the call")
            }
        }
    }

    /**
     * The adopted peer's session left the call or the conversation. That is also what a client
     * does that starts a new signaling session (the web app does on many changes), so wait 28 s
     * for the same person's next session in the call, as TalQ desktop does. A hangup hint ends
     * the call at once instead.
     */
    private fun peerLeft(why: String) {
        val old = peerSid ?: return
        log("$why; waiting for them to come back")
        peerSid = null
        subSid = null
        offerRequested = false
        earlyCandidates.clear()
        cancelTimer(TimerKind.RequestOffer)
        cancelTimer(TimerKind.SubscriberStall)
        peerGone = true
        startTimer(TimerKind.PeerGrace, PEER_GRACE_MS)
        // Already in the call on another session (a second device): take that one at once.
        val other = inCall.entries.firstOrNull { (sid, flags) ->
            sid != old && sid !in goneSessions && sessionUser[sid] == peerUserId && flags and CallFlags.IN_CALL != 0
        }
        if (other != null) adopt(other.key, other.value, _peer.value?.displayName.orEmpty(), known = true) else refreshState()
    }

    /**
     * `GET call/{token}` while the join POST waits. Listed means the server wrote the join; it
     * may still be telling the signaling server (the slow part), and only that makes the call
     * work, so this is no confirmation: the wait for it is just extended once, to 95 s.
     */
    private fun joinListed(list: List<CallPeer>) {
        if (joined) return
        // The other person may be listed too (their join is as slow): take them from the rows,
        // so the 60 s no-answer timer does not end a call that is only slow to confirm.
        peers(list)
        if (joinDeadlineExtended) return
        val own = ncSession ?: return
        if (list.none { it.ncSessionId == own }) return
        joinDeadlineExtended = true
        cancelTimer(TimerKind.JoinCheck)
        log("listed in the call; waiting for the signaling server")
        startTimer(TimerKind.JoinDeadline, LONG_JOIN_DEADLINE_MS)
        if (peerSid == null) startTimer(TimerKind.Poll, POLL_MS)
    }

    /** The other person found by polling `GET call/{token}` (they were in the call before us). */
    private fun peers(list: List<CallPeer>) {
        if (peerSid != null) return
        val row = list.firstOrNull { it.actorType == "users" && it.actorId == peerUserId } ?: return
        val sid = ncToHpb[row.ncSessionId]?.takeIf { it !in goneSessions }
            ?: sessionUser.entries.filter { it.value == peerUserId && it.key !in goneSessions }.singleOrNull()?.key
            ?: return
        // The poll has no flags: assume audio until the signaling server says otherwise.
        val flags = inCall[sid]?.takeIf { it != 0 } ?: (CallFlags.IN_CALL or CallFlags.WITH_AUDIO)
        adopt(sid, flags, row.displayName, known = inCall[sid]?.let { it != 0 } ?: false)
    }

    private fun adopt(sid: String, flags: Int, displayName: String, known: Boolean) {
        peerSid = sid
        peerFlags = flags
        peerFlagsKnown = known
        if (peerGone) {
            peerGone = false
            cancelTimer(TimerKind.PeerGrace)
        }
        cancelTimer(TimerKind.NoAnswer)
        cancelTimer(TimerKind.Poll)
        // Their client learns what is off here from the flags and from these.
        if (!micOn) send(sid, "mute", signal(sid, "mute", "", payload = buildJsonObject { put("name", "audio") }))
        if (!camOn) send(sid, "mute", signal(sid, "mute", "", payload = buildJsonObject { put("name", "video") }))
        _peer.value = PeerMedia(
            audio = flags and CallFlags.WITH_AUDIO != 0,
            video = flags and CallFlags.WITH_VIDEO != 0,
            displayName = displayName.ifEmpty { peerUserId },
        )
        log("the other person is in the call")
        if (joined) requestOfferIfPublishing()
        refreshState()
    }

    private fun peerFlagsChanged(flags: Int, known: Boolean) {
        // Flags the REST poll assumed never overwrite flags the signaling server reported.
        if (!known && peerFlagsKnown) return
        peerFlagsKnown = peerFlagsKnown || known
        peerFlags = flags
        _peer.update {
            it?.copy(audio = flags and CallFlags.WITH_AUDIO != 0, video = flags and CallFlags.WITH_VIDEO != 0)
        }
        if (joined) requestOfferIfPublishing()
    }

    /** Asks for the other person's media unless it is already coming or they send none. */
    private fun requestOfferIfPublishing() {
        if (peerSid == null || subSid != null || offerRequested) return
        if (peerFlags and (CallFlags.WITH_AUDIO or CallFlags.WITH_VIDEO) == 0) return
        offerRequests = 0
        sendRequestOffer()
    }

    private fun sendRequestOffer() {
        val peer = peerSid ?: return
        offerRequests++
        offerRequested = true
        send(peer, "requestoffer", requestOffer(peer))
        // The backend refuses it until the other side publishes: ask again until an offer comes.
        // A retry from an earlier request (or peer) is recognised by its generation and dropped.
        val generation = ++offerGeneration
        val scope = callScope ?: return
        timers.remove(TimerKind.RequestOffer)?.cancel()
        timers[TimerKind.RequestOffer] = scope.launch {
            delay(OFFER_RETRY_MS)
            inbox.send(Input.OfferRetry(generation))
        }
    }

    private fun isPeerSession(sid: String): Boolean = sid.isNotEmpty() && (sid == peerSid || sessionUser[sid] == peerUserId)

    // ---------------------------------------------------------------- signaling in

    private suspend fun hpbEvent(e: HpbInbound) {
        when (e) {
            is HpbInbound.ParticipantsUpdate -> {
                // The edges first (a leaver is only there), then everyone in the call.
                participants(e.changed)
                participants(e.users)
                followMovedPeer(e.users)
            }
            is HpbInbound.RoomJoined -> for (j in e.entries) {
                goneSessions -= j.sessionId
                if (j.userId.isNotEmpty()) sessionUser[j.sessionId] = j.userId
                if (j.ncSessionId.isNotEmpty()) ncToHpb[j.ncSessionId] = j.sessionId
            }
            is HpbInbound.RoomLeft -> {
                if (peerSid != null && peerSid in e.sessionIds) peerLeft("the other person left the conversation")
                for (sid in e.sessionIds) {
                    inCall.remove(sid)
                    goneSessions += sid
                }
            }
            is HpbInbound.Signal -> signalIn(e)
            is HpbInbound.RoomAck -> if (awaitingRoomAck && e.roomId == token) {
                awaitingRoomAck = false
                log("back in the room; joining the call again")
                postJoin(silent = true)
            }
            is HpbInbound.Error -> log("signaling error: ${e.code}")
            is HpbInbound.ForceMute -> if (e.peerId == ownId && micOn) {
                log("a moderator muted the microphone")
                microphone(false)
            }
            is HpbInbound.CallEndedForEveryone -> if (e.roomId == token && joined) {
                end(CallEndReason.PeerHungUp, "the call was ended for everyone")
            }
            HpbInbound.Bye -> log("the signaling server ended the session")
            else -> Unit
        }
    }

    private suspend fun signalIn(s: HpbInbound.Signal) {
        val from = s.senderSessionId
        if (ownId.isNotEmpty() && from == ownId) {
            when (s.type) {
                // Any sid: a stock backend keeps answering a rebuilt publisher with the first sid.
                "answer" -> if (pubSid != null && !pubAnswered) {
                    pubAnswered = true
                    try {
                        engine.setPublisherAnswer(s.payload.str("sdp"))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        republish("the media server's answer could not be used")
                    }
                }
                "candidate" -> remoteCandidate(null, s.payload)
            }
            return
        }
        if (!isPeerSession(from)) return
        // Screen sharing (roomType "screen") is not part of this version.
        if (s.roomType != "video") return
        when (s.type) {
            "offer" -> if (from == peerSid) subscriberOffer(from, s)
            "candidate" -> if (from == peerSid) {
                if (s.sid == subSid) remoteCandidate(from, s.payload) else keepEarly(s.sid, s.payload)
            }
            "mute", "unmute" -> {
                val on = s.type == "unmute"
                when (s.payload.str("name")) {
                    "audio" -> _peer.update { it?.copy(audio = on) }
                    "video" -> _peer.update { it?.copy(video = on) }
                }
            }
            "hangup" -> {
                val elsewhere = inCall.entries.any { (sid, flags) ->
                    sid != from && sid !in goneSessions && sessionUser[sid] == peerUserId && flags and CallFlags.IN_CALL != 0
                }
                // They hung up on one device but are still in the call on another: follow them.
                if (elsewhere && from == peerSid) peerLeft("the other person left on one device") else if (!elsewhere) end(CallEndReason.PeerHungUp, "the other person hung up")
            }
        }
    }

    /** Every offer is a new media-server handle: a new subscriber, answered with that offer's sid. */
    private suspend fun subscriberOffer(from: String, s: HpbInbound.Signal) {
        subSid = s.sid
        offerRequested = false
        cancelTimer(TimerKind.RequestOffer)
        startTimer(TimerKind.SubscriberStall, SUBSCRIBER_STALL_MS)
        val answer = try {
            engine.answerSubscriber(from, s.payload.str("sdp"), iceServers)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            resubscribe("the other person's media could not be set up")
            return
        }
        send(from, "answer", signal(from, "answer", s.sid, payload = sdpPayload("answer", answer)))
        earlyCandidates.remove(s.sid)?.forEach { remoteCandidate(from, it) }
        earlyCandidates.clear()
    }

    /** The media server trickles a handle's candidates before its offer: keep a few until it comes. */
    private fun keepEarly(sid: String, payload: JsonObject) {
        if (sid.isEmpty()) return
        val list = earlyCandidates[sid] ?: run {
            if (earlyCandidates.size >= MAX_EARLY_SIDS) earlyCandidates.remove(earlyCandidates.keys.first())
            mutableListOf<JsonObject>().also { earlyCandidates[sid] = it }
        }
        if (list.size < MAX_EARLY_CANDIDATES) list += payload
    }

    private fun remoteCandidate(peer: String?, payload: JsonObject) {
        val c = payload.obj("candidate") ?: return
        val text = c.str("candidate")
        if (text.isEmpty()) return
        engine.addRemoteCandidate(peer, text, c.str("sdpMid").ifEmpty { null }, c.int("sdpMLineIndex"))
    }

    // ---------------------------------------------------------------- signaling and media state

    private suspend fun signaling(st: HpbState) {
        when (st) {
            is HpbState.Ready -> {
                signalingUp = true
                // Every call goes through the media server: without one, an offer to our own
                // session is dropped without an answer.
                if (st.features.isNotEmpty() && "mcu" !in st.features) {
                    end(CallEndReason.NoHpb, "the signaling server has no media server")
                    return
                }
                when {
                    ownId.isEmpty() -> setOwn(st.sessionId)
                    st.sessionId != ownId -> sessionReset(st.sessionId)
                    else -> resumed()
                }
            }
            HpbState.Resuming, HpbState.Connecting -> if (inRoom) {
                signalingUp = false
                signalingDropped = true
                enterReconnecting("the signaling connection dropped")
            }
            is HpbState.Failed -> end(CallEndReason.Failed, "signaling failed")
            HpbState.Disconnected -> Unit
        }
    }

    /**
     * The same signaling session is back: the room and the call are still joined. A drop is
     * usually a network change, which breaks the media paths too: whatever is not connected now
     * is built again at once instead of waiting for ICE to give up.
     */
    private suspend fun resumed() {
        if (signalingDropped && joined) {
            signalingDropped = false
            if (pubRebuildPending || pubState != MediaConnection.Connected) {
                pubAttempts = 0
                republish("the signaling connection came back")
            }
            if (subSid != null && subState != MediaConnection.Connected) resubscribe("the signaling connection came back")
        }
        refreshState()
    }

    /** A new signaling session: the connection joins the room again; then the call, silently. */
    private fun sessionReset(newId: String) {
        setOwn(newId)
        enterReconnecting("the signaling session was lost")
        inCall.clear()
        joined = false
        pubSid = null
        subSid = null
        offerRequested = false
        earlyCandidates.clear()
        signalingDropped = false
        pubRebuildPending = false
        pubAttempts = 0
        cancelTimer(TimerKind.SubscriberStall)
        cancelTimer(TimerKind.RequestOffer)
        cancelTimer(TimerKind.PublishDeadline)
        cancelTimer(TimerKind.Republish)
        engine.rebuild()
        awaitingRoomAck = true
    }

    /** What we send. A failed publisher is replaced (Reconnecting meanwhile, 20 s at most). */
    private suspend fun publisherState(c: MediaConnection) {
        pubState = c
        when (c) {
            MediaConnection.Connected -> {
                cancelTimer(TimerKind.PublishDeadline)
                pubAttempts = 0
                pubRebuildPending = false
                refreshState()
            }
            MediaConnection.Failed -> republish("sending media failed")
            else -> Unit
        }
    }

    /**
     * A new publisher. Not while the signaling is down (it is remembered and done on its
     * return), not twice within 5 s (done when the 5 s are up), and 4 tries in a row at most.
     */
    private suspend fun republish(why: String) {
        if (!joined) return
        if (!signalingUp) {
            pubRebuildPending = true
            enterReconnecting(why)
            return
        }
        val wait = REPUBLISH_GAP_MS - (now() - lastPublishAt)
        if (wait > 0) {
            pubRebuildPending = true
            startTimer(TimerKind.Republish, wait)
            return
        }
        if (pubAttempts >= MAX_PUBLISH_ATTEMPTS) {
            end(CallEndReason.Failed, "sending media did not connect")
            return
        }
        enterReconnecting(why)
        publish()
    }

    /**
     * What we receive. A failed subscriber is replaced on its own (a new `requestoffer`), never
     * the whole call; once it connects, ask the publisher for its best layer.
     */
    private fun subscriberState(c: MediaConnection) {
        subState = c
        when (c) {
            MediaConnection.Connected -> {
                cancelTimer(TimerKind.SubscriberStall)
                val peer = peerSid
                val sid = subSid
                if (peer != null && sid != null) {
                    send(peer, "selectStream", signal(peer, "selectStream", sid, payload = buildJsonObject {
                        put("substream", 2)
                        put("temporal", 2)
                    }))
                }
                refreshState()
            }
            MediaConnection.Failed -> resubscribe("receiving media failed")
            else -> Unit
        }
    }

    /** Drops the current subscriber and asks for a new offer (8 times per call at most). */
    private fun resubscribe(why: String) {
        cancelTimer(TimerKind.SubscriberStall)
        if (subSid == null || peerSid == null) return
        if (subRebuilds >= MAX_SUBSCRIBER_REBUILDS) {
            log("$why; no more attempts to receive")
            return
        }
        subRebuilds++
        log("$why; asking for the other person's media again")
        subSid = null
        offerRequested = false
        earlyCandidates.clear()
        requestOfferIfPublishing()
    }

    /**
     * The call counts as up once what we send is connected (the other person can hear us); the
     * call screen tells when nothing arrives from them.
     */
    private fun mediaUp(): Boolean = pubState == MediaConnection.Connected

    private fun enterReconnecting(why: String) {
        if (reconnecting) return
        reconnecting = true
        log("reconnecting: $why")
        setState(CallState.Reconnecting)
        startTimer(TimerKind.Reconnect, RECONNECT_MS)
    }

    private fun refreshState() {
        if (reconnecting && signalingUp && joined && mediaUp()) {
            reconnecting = false
            cancelTimer(TimerKind.Reconnect)
            log("reconnected")
        }
        if (reconnecting || peerGone) {
            setState(CallState.Reconnecting)
            return
        }
        setState(
            when {
                !joined -> CallState.Connecting
                peerSid == null -> if (outgoing) CallState.WaitingForPeer else CallState.Connecting
                mediaUp() ->
                    CallState.Active(activeSince ?: now().also { activeSince = it })
                else -> CallState.Connecting
            },
        )
    }

    // ---------------------------------------------------------------- local side

    private fun localCandidate(c: LocalCandidate) {
        val to: String
        val sid: String
        if (c.peerSessionId == null) {
            to = ownId
            sid = pubSid ?: return
        } else {
            to = c.peerSessionId ?: return
            if (to != peerSid) return
            sid = subSid ?: return
        }
        when (c) {
            is LocalCandidate.Candidate -> send(
                to, "candidate",
                signal(to, "candidate", sid, payload = buildJsonObject {
                    put("candidate", buildJsonObject {
                        put("candidate", c.candidate)
                        put("sdpMLineIndex", c.sdpMLineIndex)
                        put("sdpMid", c.sdpMid ?: "")
                    })
                }),
            )
            is LocalCandidate.End -> send(to, "endOfCandidates", signal(to, "endOfCandidates", sid, payload = EmptyJsonObject))
        }
    }

    private fun microphone(on: Boolean) {
        micOn = on
        _microphone.value = on
        engine.setLocalAudio(on)
        engine.sendStatus(if (on) "audioOn" else "audioOff")
        announce(on, "audio")
    }

    private fun camera(on: Boolean) {
        camOn = on
        _camera.value = on
        engine.setLocalVideo(on)
        engine.sendStatus(if (on) "videoOn" else "videoOff")
        announce(on, "video")
    }

    /** Tells the other person (signaling) and the server (the call flags). */
    private fun announce(on: Boolean, name: String) {
        val type = if (on) "unmute" else "mute"
        peerSid?.let { send(it, type, signal(it, type, "", payload = buildJsonObject { put("name", name) })) }
        if (joined) updateFlags()
    }

    private fun updateFlags() {
        val flags = flags()
        postedFlags = flags
        callScope?.launch {
            try {
                client.updateCallFlags(token, flags)
            } catch (e: TalkError) {
                log("updating the call flags failed (${e::class.simpleName})")
            }
        }
    }

    private fun flags(): Int = CallFlags.IN_CALL or
        (if (micOn) CallFlags.WITH_AUDIO else 0) or
        (if (camOn) CallFlags.WITH_VIDEO else 0)

    // ---------------------------------------------------------------- timers

    private suspend fun timer(kind: TimerKind) {
        when (kind) {
            TimerKind.NoAnswer -> if (peerSid == null) {
                if (outgoing) end(CallEndReason.NoAnswer, "nobody answered")
                else end(CallEndReason.PeerHungUp, "the caller is not in the call")
            }
            TimerKind.JoinDeadline -> if (!joined) end(CallEndReason.Failed, "joining the call got no answer")
            TimerKind.JoinCheck -> if (!joined) {
                callScope?.launch {
                    try {
                        inbox.send(Input.JoinListed(client.callPeers(token)))
                    } catch (e: TalkError) {
                        // The next check tries again.
                    }
                }
                startTimer(TimerKind.JoinCheck, POLL_MS)
            }
            TimerKind.Poll -> if (peerSid == null && (joined || joinDeadlineExtended)) {
                callScope?.launch {
                    try {
                        inbox.send(Input.Peers(client.callPeers(token)))
                    } catch (e: TalkError) {
                        // The next poll tries again.
                    }
                }
                startTimer(TimerKind.Poll, POLL_MS)
            }
            TimerKind.RequestOffer -> Unit // sent as Input.OfferRetry
            TimerKind.Reconnect -> if (reconnecting) end(CallEndReason.Failed, "could not reconnect")
            // An offer whose media never connects (the backend may trickle no candidates at all).
            TimerKind.SubscriberStall -> if (subState != MediaConnection.Connected) resubscribe("receiving media did not connect")
            TimerKind.PublishDeadline -> if (pubState != MediaConnection.Connected) republish("sending media did not connect in time")
            TimerKind.Republish -> if (pubRebuildPending) republish("sending media failed")
            TimerKind.PeerGrace -> if (peerGone) end(CallEndReason.PeerHungUp, "the other person did not come back")
        }
    }

    private fun startTimer(kind: TimerKind, millis: Long) {
        val cs = callScope ?: return
        timers.remove(kind)?.cancel()
        timers[kind] = cs.launch {
            delay(millis)
            inbox.send(Input.Timer(kind))
        }
    }

    private fun cancelTimer(kind: TimerKind) {
        timers.remove(kind)?.cancel()
    }

    // ---------------------------------------------------------------- helpers and the end

    private fun setOwn(id: String) {
        ownId = id
        _ownSessionId.value = id
    }

    private fun send(to: String, type: String, text: String) {
        val c = hpb ?: return
        if (to.isEmpty()) return
        c.enqueue(text)
        _sent.tryEmit(SentSignal(type, to))
    }

    private fun setState(s: CallState) {
        while (true) {
            val cur = _state.value
            if (cur is CallState.Ended || cur == s) return
            if (_state.compareAndSet(cur, s)) return
        }
    }

    /** Ends the call once (the first reason wins) and tears everything down beside the caller. */
    private fun end(reason: CallEndReason, why: String) {
        while (true) {
            val cur = _state.value
            if (cur is CallState.Ended) return
            if (_state.compareAndSet(cur, CallState.Ended(reason))) break
        }
        log("call ended: $reason ($why)")
        teardownScope.launch { teardown(hint = reason == CallEndReason.HungUp) }
    }

    /**
     * Leaves everything, in this order: the hangup hint to the other person, the media, the call
     * (3 tries, 2 s apart, also when the join was never confirmed), the room, the conversation,
     * the signaling session.
     */
    private suspend fun teardown(hint: Boolean) {
        try {
            lifecycle.withLock {
                setupJob?.cancelAndJoin()
                loopJob?.cancelAndJoin()
                reenterJob?.cancelAndJoin()
            }
            timers.values.forEach { it.cancel() }
            timers.clear()
            val c = hpb
            val me = sessionUser[ownId]
            // We moved the call to another device of ours: the other person must not hang up.
            val ownElsewhere = me != null && inCall.entries.any { (sid, flags) ->
                sid != ownId && sid !in goneSessions && sessionUser[sid] == me && flags and CallFlags.IN_CALL != 0
            }
            if (hint && c != null && !ownElsewhere) {
                // The adopted session, and any other the other person has in the room (one that
                // came back during the grace and was not adopted yet).
                val targets = buildSet {
                    peerSid?.let { add(it) }
                    sessionUser.forEach { (sid, user) -> if (user == peerUserId && sid !in goneSessions) add(sid) }
                }
                for (peer in targets) {
                    c.enqueue(hangupHint(peer))
                    _sent.tryEmit(SentSignal("hangup", peer))
                }
            }
            runCatching { engine.close() }
            // Once a newer call in this conversation started, the leaves below would reach it
            // (they name no session: the server takes the account's current one); the room leave
            // on the signaling socket and its goodbye still end exactly this session.
            if (joinSent && !superseded) {
                leaveCall()
                // A join still on its way may land after the leave: leave again once it is answered.
                val pending = joinJob?.takeIf { it.isActive }
                if (pending != null) {
                    teardownScope.launch {
                        withTimeoutOrNull(LATE_JOIN_MS) { pending.join() }
                        if (!superseded) runCatching { client.leaveCall(token) }
                    }
                }
            }
            if (c != null) runCatching { c.leaveRoom() }
            if (ncSession != null && !superseded) {
                try {
                    client.leaveRoomSession(token)
                } catch (e: TalkError) {
                    log("leaving the conversation failed (${e::class.simpleName})")
                }
            }
            c?.close(sayBye = true)
        } finally {
            work?.cancel()
            done.complete()
        }
    }

    private suspend fun leaveCall() {
        repeat(LEAVE_ATTEMPTS) { attempt ->
            if (superseded) return
            try {
                client.leaveCall(token)
                return
            } catch (e: TalkError) {
                if (e is TalkError.NotFound) return
                log("leaving the call failed (${e::class.simpleName})")
                if (attempt < LEAVE_ATTEMPTS - 1) delay(LEAVE_RETRY_MS)
            }
        }
    }

    private fun sdpPayload(type: String, sdp: String): JsonObject = buildJsonObject {
        put("type", type)
        put("sdp", sdp)
    }

    private companion object {
        /** H264 first, as TalQ desktop publishes (the media server picks from this list). */
        const val VIDEO_CODECS = "h264,vp8"
        const val NO_ANSWER_MS = 60_000L
        const val JOIN_DEADLINE_MS = 45_000L
        const val FIRST_POLL_MS = 1_200L
        const val POLL_MS = 3_000L
        const val OFFER_RETRY_MS = 8_000L
        const val MAX_OFFER_REQUESTS = 60
        const val RECONNECT_MS = 20_000L
        const val LEAVE_ATTEMPTS = 3
        const val LEAVE_RETRY_MS = 2_000L
        const val LATE_JOIN_MS = 100_000L
        const val MAX_EARLY_SIDS = 8
        const val JOIN_CHECK_MS = 5_000L
        const val LONG_JOIN_DEADLINE_MS = 95_000L
        const val PUBLISH_DEADLINE_MS = 15_000L
        const val MAX_PUBLISH_ATTEMPTS = 4
        const val PEER_GRACE_MS = 28_000L
        const val SUBSCRIBER_STALL_MS = 10_000L
        const val MAX_SUBSCRIBER_REBUILDS = 8
        const val REPUBLISH_GAP_MS = 5_000L
        const val MAX_EARLY_CANDIDATES = 16
    }
}
