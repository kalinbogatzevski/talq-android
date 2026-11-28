package talq.core.call

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * One call in the conversation [token], from [start] (outgoing) or [answer] (incoming / joining)
 * to its end, in [mode]: a one-to-one call with one user, or a group call with whoever is in it.
 * The wire sequence is the one in the call protocol map
 * (docs/superpowers/specs/2026-11-22-call-protocol-map.md):
 *
 * signaling settings, signaling connect, join the conversation (`participants/active`), join its
 * room on the signaling server and wait for the answer, then join the call. Joining counts once
 * the server answers 200 OR the signaling server lists our own session in the call, whichever
 * comes first (the join request can hang for a long time on a server whose call notifications
 * are slow). Then the publisher's offer goes to our OWN session (the backend's media server
 * takes it, also in a one-to-one call), and the media of everyone else in the call (participants
 * updates, or found by polling `GET call/{token}`) is requested with `requestoffer` and their
 * offers answered: one camera subscriber per person, and one screen subscriber per person who
 * shares a screen.
 *
 * Only what [rights] allow is published ([PublishRights]: the signaling server refuses an offer
 * with a video section without the video permission); without any right the user listens. The
 * own row's permissions in a participants update replace [rights] when they change.
 *
 * One coroutine (the loop) owns the call's state and takes everything in order: signaling
 * events, the media engine's candidates and connection states, REST answers, timers and the
 * user's actions. REST requests run beside it and post their answers back. Per-person state is a
 * [PeerLink]: one person's broken media is rebuilt for that person only and never ends the call.
 *
 * A dropped signaling socket that resumes the same session changes nothing. A NEW session
 * (the old one was lost) means joining the call again (silently), a new publisher and new
 * `requestoffer`s; so does media that failed. 45 s without getting back ends the call.
 *
 * One-to-one rules (only in [CallMode.OneToOne]): 60 s without the other person ends an outgoing
 * call; the other person's session leaving waits 28 s for them to come back; a hang-up hint ends
 * the call at once and one is sent on hanging up. Group rules: nobody else in the call for 60 s
 * ends it ([CallEndReason.Alone]); a moderator ending it for everyone ends it
 * ([CallEndReason.EndedForEveryone]); people come and go without ending anything.
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
    val mode: CallMode,
    rights: PublishRights,
    private val engine: MediaEngine,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val recordingConsent: Boolean = false,
    /** Join without notifying the conversation (joining a call that runs, never starting one that rings). */
    private val joinSilently: Boolean = false,
    private val log: (String) -> Unit = {},
    /** Settings, and a connection that said hello, made ready before the call (see [PreparedSignaling]). */
    private val prepared: PreparedSignaling? = null,
    /** Which signaling node to use when the server lists several (see `HpbPick.kt`). */
    private val nodes: HpbNodes = HpbNodes.Named,
) {
    private val _state = MutableStateFlow<CallState>(CallState.Connecting)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _participants = MutableStateFlow<List<CallParticipant>>(emptyList())

    /** Everyone else in the call, in the order they were seen (empty before anyone is). */
    val participants: StateFlow<List<CallParticipant>> = _participants.asStateFlow()

    private val _ownSessionId = MutableStateFlow("")

    /** This device's signaling session ("" before it has one). */
    val ownSessionId: StateFlow<String> = _ownSessionId.asStateFlow()

    private val _microphone = MutableStateFlow(true)

    /** Our microphone is on (a moderator can switch it off). */
    val microphone: StateFlow<Boolean> = _microphone.asStateFlow()

    private val _camera = MutableStateFlow(false)

    /** Our camera is on. */
    val camera: StateFlow<Boolean> = _camera.asStateFlow()

    private val _rights = MutableStateFlow(rights)

    /** What may be published now; neither = the user only listens. */
    val rights: StateFlow<PublishRights> = _rights.asStateFlow()

    private val _notice = MutableStateFlow(noticeFor(rights))

    /** One line about what the user cannot send here, or null. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _sent = MutableSharedFlow<SentSignal>(replay = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** What this call sent on the signaling socket, type and recipient only (the last 64). */
    val sent: SharedFlow<SentSignal> = _sent.asSharedFlow()

    private sealed interface Input {
        data class InRoom(val sessionId: String) : Input
        data class Hpb(val event: HpbInbound) : Input
        data class Signaling(val state: HpbState) : Input
        data class Local(val candidate: LocalCandidate) : Input
        data class Publisher(val connection: MediaConnection) : Input
        data class Subscribers(val connections: Map<SubscriberKey, MediaConnection>) : Input
        data class Status(val status: RemoteStatus) : Input
        data object JoinOk : Input
        data class JoinError(val error: TalkError) : Input
        data object ReenteredRoom : Input
        data class JoinListed(val peers: List<CallPeer>) : Input
        data class OfferRetry(val sid: String, val generation: Int) : Input
        data class Stall(val sid: String, val kind: StreamKind, val handle: String) : Input
        data class Substream(val sid: String, val substream: Int) : Input
        data class Peers(val peers: List<CallPeer>) : Input
        data class Timer(val kind: TimerKind) : Input
        data class Mic(val on: Boolean) : Input
        data class Camera(val on: Boolean) : Input
        data class End(val reason: CallEndReason, val why: String) : Input
    }

    private enum class TimerKind {
        NoAnswer, Alone, JoinDeadline, JoinCheck, Poll, Reconnect, PublishDeadline, Republish, PeerGrace,
    }

    private val oneToOne: CallMode.OneToOne? = mode as? CallMode.OneToOne
    private val peerUserId: String = oneToOne?.peerUserId.orEmpty()

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
    private var lastPublishAt = 0L
    private var pubAttempts = 0
    private var pubRebuildPending = false

    /** Publishing gave up (refused, or failed too often) in a group call: listening only from then on. */
    private var pubGivenUp = false
    private var postedFlags = 0
    private var signalingDropped = false
    private var rightsNow = rights

    /** After a lost signaling session: the first full roster drops the people who went meanwhile. */
    private var reconcileRoster = false

    /** One-to-one: the adopted peer's session went (left the call or the conversation): waiting for them back. */
    private var peerGone = false
    private var joinDeadlineExtended = false

    /** Sessions that left the room: never taken again from a stale list. */
    private val goneSessions = HashSet<String>()
    private var activeSince: Long? = null
    private var micOn = rights.audio
    private var camOn = false
    private var pubSid: String? = null
    private var lastPubSid = 0L
    private var pubAnswered = false
    /**
     * Publisher offers replaced before any answer came (new rights, a deadline): the server may still
     * refuse them, and its refusal names no offer. Such a refusal must not end the offer that replaced it.
     */
    private var supersededOffers = 0
    private val timers = HashMap<TimerKind, Job>()
    private val inCall = HashMap<String, Int>()
    private val sessionUser = HashMap<String, String>()
    private val sessionName = HashMap<String, String>()
    private val ncToHpb = HashMap<String, String>()

    /** Everyone else in the call, by signaling session, in the order they were seen. */
    private val links = LinkedHashMap<String, PeerLink>()
    private var linksMade = 0

    /** Our publisher was offered before the join was confirmed and has no answer yet. */
    private var earlyOffer = false

    /** One-to-one: the adopted peer's session (at most one link there). */
    private val peerSid: String? get() = if (oneToOne != null) links.keys.firstOrNull() else null

    /** Calls: in a one-to-one call rings the other person and waits up to 60 s for them. */
    suspend fun start(video: Boolean) = begin(video, outgoing = true)

    /** Joins a call someone else started (the same path, without waiting for a ring). */
    suspend fun answer(video: Boolean) = begin(video, outgoing = false)

    suspend fun setMicrophone(on: Boolean) {
        inbox.send(Input.Mic(on))
    }

    suspend fun setCamera(on: Boolean) {
        inbox.send(Input.Camera(on))
    }

    /**
     * The camera substream [substream] (0-2, see [substreamFor]) wanted from [sid]: sent with
     * `selectStream` once that person's camera subscriber is connected and the value changed.
     */
    fun setSubstream(sid: String, substream: Int) {
        inbox.trySend(Input.Substream(sid, substream.coerceIn(0, 2)))
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
            camOn = video && rightsNow.video
            _camera.value = camOn
            _microphone.value = micOn
            val job = SupervisorJob(scope.coroutineContext[Job])
            val cs = CoroutineScope(scope.coroutineContext + job)
            work = job
            callScope = cs
            loopJob = cs.launch { runLoop() }
            cs.launch(start = CoroutineStart.UNDISPATCHED) { engine.localCandidates.collect { inbox.send(Input.Local(it)) } }
            cs.launch(start = CoroutineStart.UNDISPATCHED) { engine.publisher.collect { inbox.send(Input.Publisher(it)) } }
            cs.launch(start = CoroutineStart.UNDISPATCHED) { engine.subscribers.collect { inbox.send(Input.Subscribers(it)) } }
            cs.launch(start = CoroutineStart.UNDISPATCHED) { engine.remoteStatus.collect { inbox.send(Input.Status(it)) } }
            cs.launch { setup(cs) }.also { setupJob = it }
        }
        setup.join()
    }

    /**
     * Signaling and the room; the call itself is joined by the loop once [Input.InRoom] arrives.
     * The conversation is entered while the signaling connects: neither needs the other, and each
     * is a round trip or more to the server. With [prepared] the settings (and while a call rang,
     * the connection with its hello) are ready already.
     */
    private suspend fun setup(cs: CoroutineScope) {
        try {
            val ready = prepared
            val settings = ready?.settings ?: client.signalingSettings(now())
            log(if (ready != null) "signaling settings ready (prepared)" else "signaling settings read")
            if (!settings.hasHpb) {
                ready?.connection?.close(sayBye = true)
                inbox.send(Input.End(CallEndReason.NoHpb, "the server has no call backend"))
                return
            }
            iceServers = settings.iceServers
            // Not with the settings request: on a server session that is new, two requests at once
            // can end in two sessions, and the call must be joined in the conversation's.
            val entered = cs.async { client.joinRoomSession(token) }
            val c = ready?.connection ?: HpbConnection(http, settings, client.serverUrl, cs, nodes = nodes) { client.signalingSettings(now()) }
            hpb = c
            // The events have no replay: listen before connecting (a prepared connection has not
            // joined a room yet, and nothing comes before that).
            cs.launch(start = CoroutineStart.UNDISPATCHED) { c.events.collect { inbox.send(Input.Hpb(it)) } }
            cs.launch(start = CoroutineStart.UNDISPATCHED) { c.state.collect { inbox.send(Input.Signaling(it)) } }
            // A prepared connection that is still up answers at once.
            val id = c.connect()
            log((if (ready?.connection != null) "signaling connected (prepared)" else "signaling connected") + " on " + hpbHost(c.node))
            val nc = entered.await()
            log("in the conversation")
            ncSession = nc
            c.joinRoom(token, nc)
            log("in the signaling room")
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
            is Input.Subscribers -> subscriberStates(input.connections)
            is Input.Status -> remoteStatus(input.status)
            Input.JoinOk -> joinConfirmed("the server answered")
            is Input.JoinError -> joinError(input.error)
            Input.ReenteredRoom -> postJoin(lastJoinSilent)
            is Input.JoinListed -> joinListed(input.peers)
            is Input.OfferRetry -> offerRetry(input.sid, input.generation)
            is Input.Stall -> links[input.sid]?.let { l ->
                // An offer whose media never connects (the backend may trickle no candidates at all).
                if (l.handle[input.kind] == input.handle && l.state[input.kind] != MediaConnection.Connected) {
                    resubscribe(l, input.kind, "receiving media did not connect")
                }
            }
            is Input.Substream -> links[input.sid]?.let { l ->
                l.wantedSubstream = input.substream
                if (l.state[StreamKind.Camera] == MediaConnection.Connected) selectStream(l)
            }
            is Input.Peers -> peers(input.peers)
            is Input.Timer -> timer(input.kind)
            is Input.Mic -> microphone(input.on)
            is Input.Camera -> camera(input.on)
            is Input.End -> end(input.reason, input.why)
        }
    }

    // ---------------------------------------------------------------- joining

    private suspend fun inRoom(sessionId: String) {
        if (ownId.isEmpty()) setOwn(sessionId)
        inRoom = true
        log("in the room; joining the call")
        if (oneToOne != null) startTimer(TimerKind.NoAnswer, NO_ANSWER_MS)
        postJoin(silent = joinSilently)
        // Our media is offered while the join is on its way (its answer is the slowest step): the
        // signaling server takes a publisher from a session in the room. Refused that early, it is
        // offered again once the join is confirmed (see hpbError).
        if (!joined && pubSid == null) {
            earlyOffer = true
            publish()
        }
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
                // An early offer belonged to the room session that is left now: offered anew after the join.
                if (earlyOffer) {
                    earlyOffer = false
                    pubSid = null
                    cancelTimer(TimerKind.PublishDeadline)
                    engine.closePublisher()
                }
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
        // Offered already while the join was on its way (inRoom): not twice. A mute or camera switch
        // meanwhile went to that publisher directly (engine.setLocalAudio / setLocalVideo).
        if (pubSid == null) publish()
        for (l in links.values) requestCamera(l)
        if (links.isEmpty() || links.values.any { waitingForMedia(it) }) startTimer(TimerKind.Poll, FIRST_POLL_MS)
        // A group call: nobody else for a minute from the confirmed join (a slow join must not count).
        if (oneToOne == null && links.isEmpty()) startTimer(TimerKind.Alone, ALONE_MS)
        refreshState()
    }

    /**
     * A new publisher with what [rightsNow] allow; none without any right (listening). In a group
     * call a publisher that was refused or failed too often stays given up: listening.
     */
    private suspend fun publish() {
        if (pubSid != null && !pubAnswered) {
            supersededOffers++
            // A refusal of the replaced early offer is then "an earlier offer" (hpbError), not this one's.
            earlyOffer = false
        }
        if (!rightsNow.any || pubGivenUp) {
            pubSid = null
            pubRebuildPending = false
            cancelTimer(TimerKind.PublishDeadline)
            // Nothing is sent, and the microphone is let go (no recording indicator while listening).
            engine.closePublisher()
            log("listening only")
            return
        }
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
        val sdp = engine.createPublisherOffer(iceServers, rightsNow, camOn)
        engine.setLocalAudio(micOn)
        engine.setLocalVideo(camOn)
        val extra = buildMap {
            if (rightsNow.audio) put("audiocodec", "opus")
            if (rightsNow.video) put("videocodec", VIDEO_CODECS)
        }
        send(ownId, "offer", signal(ownId, "offer", sid.toString(), payload = sdpPayload("offer", sdp), extra = extra))
        log("sending: offer sent")
    }

    // ---------------------------------------------------------------- who is in the call

    private suspend fun roster(users: List<HpbUser>) {
        for (u in users) participant(u)
    }

    private suspend fun participant(u: HpbUser) {
        if (u.sessionId.isEmpty()) return
        if (u.userId.isNotEmpty()) sessionUser[u.sessionId] = u.userId
        if (u.ncSessionId.isNotEmpty()) ncToHpb[u.ncSessionId] = u.sessionId
        if (u.displayName.isNotEmpty()) sessionName[u.sessionId] = u.displayName
        inCall[u.sessionId] = u.inCall
        val inTheCall = u.inCall and CallFlags.IN_CALL != 0
        if (u.sessionId == ownId) {
            if (u.permissions != 0) ownPermissions(PublishRights.of(u.permissions))
            if (joinSent && inTheCall) joinConfirmed("the signaling server lists us")
            else if (joined && !inTheCall) end(CallEndReason.Failed, "the server took this phone out of the call")
            return
        }
        if (oneToOne != null) oneToOneRow(u, inTheCall) else groupRow(u, inTheCall)
    }

    private fun groupRow(u: HpbUser, inTheCall: Boolean) {
        val l = links[u.sessionId]
        if (!inTheCall) {
            if (l != null) dropLink(l, "someone left the call")
            return
        }
        if (u.sessionId in goneSessions) return
        val link = l ?: newLink(u.sessionId)
        flagsChanged(link, u.inCall, known = true)
    }

    /** Someone new in a group call (or the one-to-one peer adopted): a tile, our mute state, their media. */
    private fun newLink(sid: String): PeerLink {
        val l = PeerLink(sid)
        l.number = ++linksMade
        l.userId = sessionUser[sid].orEmpty()
        l.name = sessionName[sid].orEmpty()
        l.wantedSubstream = if (oneToOne != null) 2 else 1
        links[sid] = l
        cancelTimer(TimerKind.Alone)
        cancelTimer(TimerKind.NoAnswer)
        // Their client learns what is off here from the flags and from these.
        if (!micOn) send(sid, "mute", signal(sid, "mute", "", payload = buildJsonObject { put("name", "audio") }))
        if (!camOn) send(sid, "mute", signal(sid, "mute", "", payload = buildJsonObject { put("name", "video") }))
        log(if (oneToOne != null) "the other person is in the call" else "someone joined the call (${links.size} others)")
        publishParticipants()
        // Answered: the state says so now (the ringing tone stops), not only once their media is offered.
        refreshState()
        return l
    }

    /** [l] left the call or the conversation: their subscribers go; a group call goes on. */
    private fun dropLink(l: PeerLink, why: String) {
        log(why)
        l.cancelJobs()
        for (kind in l.handle.keys.toList()) engine.closeSubscriber(SubscriberKey(l.sid, kind))
        links.remove(l.sid)
        publishParticipants()
        if (oneToOne == null && links.isEmpty() && joined) startTimer(TimerKind.Alone, ALONE_MS)
        refreshState()
    }

    private fun flagsChanged(l: PeerLink, flags: Int, known: Boolean) {
        // Flags the REST poll assumed never overwrite flags the signaling server reported.
        if (!known && l.flagsKnown) return
        l.flagsKnown = l.flagsKnown || known
        l.flags = flags
        l.audio = flags and CallFlags.WITH_AUDIO != 0
        l.video = flags and CallFlags.WITH_VIDEO != 0
        publishParticipants()
        if (joined) requestCamera(l)
    }

    /** The REST rows of everyone in the call: people the signaling server has not reported yet. */
    private fun peers(list: List<CallPeer>) {
        if (oneToOne != null) {
            oneToOnePeers(list)
            return
        }
        for (row in list) {
            if (row.ncSessionId == ncSession) continue
            val sid = ncToHpb[row.ncSessionId]?.takeIf { it !in goneSessions && it != ownId } ?: continue
            if (row.displayName.isNotEmpty()) sessionName.getOrPut(sid) { row.displayName }
            if (row.actorType == "users" && row.actorId.isNotEmpty()) sessionUser.getOrPut(sid) { row.actorId }
            if (links.containsKey(sid)) continue
            val l = newLink(sid)
            // The poll has no flags: assume audio until the signaling server says otherwise.
            val flags = inCall[sid]?.takeIf { it != 0 } ?: (CallFlags.IN_CALL or CallFlags.WITH_AUDIO)
            flagsChanged(l, flags, known = inCall[sid]?.let { it != 0 } ?: false)
        }
        refreshState()
    }

    /** A person whose camera has not come yet although they send something. */
    private fun waitingForMedia(l: PeerLink): Boolean =
        l.handle[StreamKind.Camera] == null && l.flags and (CallFlags.WITH_AUDIO or CallFlags.WITH_VIDEO) != 0

    /**
     * The first full roster after a lost signaling session (this server sends everyone in the call
     * with each update): people who went meanwhile sent no leave this session could see.
     */
    private fun reconcileAfterReset(users: List<HpbUser>) {
        if (!reconcileRoster || !joined || users.isEmpty()) return
        reconcileRoster = false
        val present = users.filter { it.inCall and CallFlags.IN_CALL != 0 }.map { it.sessionId }.toSet()
        for (l in links.values.toList()) if (l.sid !in present) dropLink(l, "someone left while the connection was lost")
    }

    // ---------------------------------------------------------------- the one-to-one peer

    private fun oneToOneRow(u: HpbUser, inTheCall: Boolean) {
        if (sessionUser[u.sessionId] != peerUserId) return
        val peer = peerSid
        when {
            peer == null -> if (inTheCall && u.sessionId !in goneSessions) adopt(u.sessionId, u.inCall, known = true)
            u.sessionId == peer -> if (inTheCall) links[peer]?.let { flagsChanged(it, u.inCall, known = true) }
                else peerLeft("the other person left the call")
        }
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

    /**
     * The adopted peer's session left the call or the conversation. That is also what a client
     * does that starts a new signaling session (the web app does on many changes), so wait 28 s
     * for the same person's next session in the call, as TalQ desktop does. A hangup hint ends
     * the call at once instead.
     */
    private fun peerLeft(why: String) {
        val old = peerSid ?: return
        val name = links[old]?.name.orEmpty()
        log("$why; waiting for them to come back")
        links[old]?.let { l ->
            l.cancelJobs()
            for (kind in l.handle.keys.toList()) engine.closeSubscriber(SubscriberKey(l.sid, kind))
        }
        links.remove(old)
        peerGone = true
        startTimer(TimerKind.PeerGrace, PEER_GRACE_MS)
        // Already in the call on another session (a second device): take that one at once.
        val other = inCall.entries.firstOrNull { (sid, flags) ->
            sid != old && sid !in goneSessions && sessionUser[sid] == peerUserId && flags and CallFlags.IN_CALL != 0
        }
        if (other != null) {
            if (name.isNotEmpty()) sessionName.getOrPut(other.key) { name }
            adopt(other.key, other.value, known = true)
        } else {
            publishParticipants()
            refreshState()
        }
    }

    /**
     * `GET call/{token}` while the join POST waits. Listed means the server wrote the join; it
     * may still be telling the signaling server (the slow part), and only that makes the call
     * work, so this is no confirmation: the wait for it is just extended once, to 95 s.
     */
    private fun joinListed(list: List<CallPeer>) {
        if (joined) return
        // The others may be listed too (their join is as slow): take them from the rows,
        // so the no-answer timer does not end a call that is only slow to confirm.
        peers(list)
        if (joinDeadlineExtended) return
        val own = ncSession ?: return
        if (list.none { it.ncSessionId == own }) return
        joinDeadlineExtended = true
        cancelTimer(TimerKind.JoinCheck)
        log("listed in the call; waiting for the signaling server")
        startTimer(TimerKind.JoinDeadline, LONG_JOIN_DEADLINE_MS)
        if (links.isEmpty()) startTimer(TimerKind.Poll, POLL_MS)
    }

    /** The other person found by polling `GET call/{token}` (they were in the call before us). */
    private fun oneToOnePeers(list: List<CallPeer>) {
        if (peerSid != null) return
        val row = list.firstOrNull { it.actorType == "users" && it.actorId == peerUserId } ?: return
        val sid = ncToHpb[row.ncSessionId]?.takeIf { it !in goneSessions }
            ?: sessionUser.entries.filter { it.value == peerUserId && it.key !in goneSessions }.singleOrNull()?.key
            ?: return
        if (row.displayName.isNotEmpty()) sessionName.getOrPut(sid) { row.displayName }
        // The poll has no flags: assume audio until the signaling server says otherwise.
        val flags = inCall[sid]?.takeIf { it != 0 } ?: (CallFlags.IN_CALL or CallFlags.WITH_AUDIO)
        adopt(sid, flags, known = inCall[sid]?.let { it != 0 } ?: false)
    }

    private fun adopt(sid: String, flags: Int, known: Boolean) {
        if (peerGone) {
            peerGone = false
            cancelTimer(TimerKind.PeerGrace)
        }
        cancelTimer(TimerKind.Poll)
        val l = newLink(sid)
        if (l.name.isEmpty()) l.name = peerUserId
        flagsChanged(l, flags, known)
        refreshState()
    }

    // ---------------------------------------------------------------- receiving

    /** Asks for [l]'s camera and microphone unless they are already coming or [l] sends none. */
    private fun requestCamera(l: PeerLink) {
        if (!waitingForMedia(l) || l.offerRequested) return
        l.offerRequests = 0
        sendRequestOffer(l)
    }

    private fun sendRequestOffer(l: PeerLink) {
        l.offerRequests++
        l.offerRequested = true
        send(l.sid, "requestoffer", requestOffer(l.sid))
        log("asked for ${whose(l)} media (${l.offerRequests})")
        // The backend refuses it until the other side publishes: ask again until an offer comes.
        // A retry from an earlier request is recognised by its generation and dropped.
        val generation = ++l.offerGeneration
        val cs = callScope ?: return
        l.retryJob?.cancel()
        l.retryJob = cs.launch {
            delay(OFFER_RETRY_MS)
            inbox.send(Input.OfferRetry(l.sid, generation))
        }
    }

    private fun offerRetry(sid: String, generation: Int) {
        val l = links[sid] ?: return
        if (generation != l.offerGeneration) return
        if (l.handle[StreamKind.Camera] == null && l.offerRequests < MAX_OFFER_REQUESTS) sendRequestOffer(l) else l.offerRequested = false
    }

    /** Every offer is a new media-server handle: a new subscriber, answered with that offer's sid. */
    private suspend fun subscriberOffer(l: PeerLink, kind: StreamKind, s: HpbInbound.Signal) {
        val sdp = s.payload.str("sdp")
        if (!offerHasMedia(sdp)) {
            // Asked before their media was up: drop it and keep asking (the retry runs on).
            log("an offer without media; asking again")
            l.early.remove(s.sid)
            return
        }
        val key = SubscriberKey(l.sid, kind)
        l.handle[kind] = s.sid
        l.state[kind] = MediaConnection.New
        if (kind == StreamKind.Camera) {
            l.offerRequested = false
            l.retryJob?.cancel()
            l.retryJob = null
            l.sentSubstream = -1
        } else {
            l.sharing = true
        }
        startStall(l, kind, s.sid)
        publishParticipants()
        val answer = try {
            engine.answerSubscriber(key, sdp, iceServers)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            resubscribe(l, kind, "their media could not be set up")
            return
        }
        send(l.sid, "answer", signal(l.sid, "answer", s.sid, roomType = kind.roomType, payload = sdpPayload("answer", answer)))
        log("${whose(l)} ${kind.name.lowercase()} offered, answered")
        l.early.remove(s.sid)?.forEach { remoteCandidate(key, it) }
        // Desktop's rejoin lesson: a screen offer from someone whose camera is not here asks for it too.
        if (kind == StreamKind.Screen) requestCamera(l)
    }

    /** The media server trickles a handle's candidates before its offer: keep a few until it comes. */
    private fun keepEarly(l: PeerLink, sid: String, payload: JsonObject) {
        if (sid.isEmpty()) return
        val list = l.early[sid] ?: run {
            if (l.early.size >= MAX_EARLY_SIDS) l.early.remove(l.early.keys.first())
            mutableListOf<JsonObject>().also { l.early[sid] = it }
        }
        if (list.size < MAX_EARLY_CANDIDATES) list += payload
    }

    private fun remoteCandidate(key: SubscriberKey?, payload: JsonObject) {
        val c = payload.obj("candidate") ?: return
        val text = c.str("candidate")
        if (text.isEmpty()) return
        engine.addRemoteCandidate(key, text, c.str("sdpMid").ifEmpty { null }, c.int("sdpMLineIndex"))
    }

    /** [l]'s screen share ended: its subscriber goes. */
    private fun screenEnded(l: PeerLink) {
        l.sharing = false
        l.stallJobs.remove(StreamKind.Screen)?.cancel()
        if (l.handle.remove(StreamKind.Screen) != null) engine.closeSubscriber(SubscriberKey(l.sid, StreamKind.Screen))
        l.state.remove(StreamKind.Screen)
        publishParticipants()
    }

    private fun startStall(l: PeerLink, kind: StreamKind, handle: String) {
        val cs = callScope ?: return
        l.stallJobs.remove(kind)?.cancel()
        l.stallJobs[kind] = cs.launch {
            delay(SUBSCRIBER_STALL_MS)
            inbox.send(Input.Stall(l.sid, kind, handle))
        }
    }

    /** The engine's connection states; a failed subscriber is rebuilt for that person only. */
    private fun subscriberStates(map: Map<SubscriberKey, MediaConnection>) {
        var changed = false
        for (l in links.values.toList()) {
            for (kind in StreamKind.entries) {
                if (l.handle[kind] == null) continue
                val c = map[SubscriberKey(l.sid, kind)] ?: continue
                if (l.state[kind] == c) continue
                l.state[kind] = c
                changed = true
                log("receiving ${kind.name.lowercase()} from ${who(l)}: ${c.name.lowercase()}")
                when (c) {
                    MediaConnection.Connected -> {
                        l.stallJobs.remove(kind)?.cancel()
                        // A working connection earns a fresh budget (desktop does the same): a long
                        // meeting must not use up a flaky person's rebuilds for good.
                        l.rebuilds = 0
                        if (kind == StreamKind.Camera) selectStream(l)
                    }
                    MediaConnection.Failed -> resubscribe(l, kind, "receiving media failed")
                    else -> Unit
                }
            }
        }
        if (changed) {
            publishParticipants()
            refreshState()
        }
    }

    /** Asks [l]'s publisher for the layer the call screen wants, once per change. */
    private fun selectStream(l: PeerLink) {
        val handle = l.handle[StreamKind.Camera] ?: return
        if (l.sentSubstream == l.wantedSubstream) return
        l.sentSubstream = l.wantedSubstream
        send(l.sid, "selectStream", signal(l.sid, "selectStream", handle, payload = buildJsonObject {
            put("substream", l.wantedSubstream)
            put("temporal", 2)
        }))
    }

    /** Drops [l]'s [kind] subscriber and asks again (8 times per person at most). */
    private fun resubscribe(l: PeerLink, kind: StreamKind, why: String) {
        l.stallJobs.remove(kind)?.cancel()
        if (l.handle[kind] == null) return
        if (l.rebuilds >= MAX_SUBSCRIBER_REBUILDS) {
            log("$why; no more attempts to receive from them")
            return
        }
        l.rebuilds++
        log("$why; asking for their media again")
        l.handle.remove(kind)
        l.state.remove(kind)
        engine.closeSubscriber(SubscriberKey(l.sid, kind))
        publishParticipants()
        when (kind) {
            StreamKind.Camera -> {
                l.offerRequested = false
                requestCamera(l)
            }
            StreamKind.Screen -> if (l.sharing) send(l.sid, "requestoffer", requestOffer(l.sid, StreamKind.Screen.roomType))
        }
    }

    private fun remoteStatus(s: RemoteStatus) {
        val l = links[s.sid] ?: return
        when (s.type) {
            "audioOn" -> l.audio = true
            "audioOff" -> {
                l.audio = false
                l.speaking = false
            }
            "videoOn" -> l.video = true
            "videoOff" -> l.video = false
            "speaking" -> l.speaking = true
            "stoppedSpeaking" -> l.speaking = false
            else -> return
        }
        publishParticipants()
    }

    // ---------------------------------------------------------------- signaling in

    private suspend fun hpbEvent(e: HpbInbound) {
        when (e) {
            is HpbInbound.ParticipantsUpdate -> {
                // The edges first (a leaver is only there), then everyone in the call.
                roster(e.changed)
                roster(e.users)
                if (oneToOne != null) followMovedPeer(e.users) else reconcileAfterReset(e.users)
            }
            is HpbInbound.RoomJoined -> for (j in e.entries) {
                goneSessions -= j.sessionId
                if (j.userId.isNotEmpty()) sessionUser[j.sessionId] = j.userId
                if (j.ncSessionId.isNotEmpty()) ncToHpb[j.ncSessionId] = j.sessionId
                if (j.displayName.isNotEmpty()) sessionName[j.sessionId] = j.displayName
                links[j.sessionId]?.let { l ->
                    if (l.name.isEmpty() && j.displayName.isNotEmpty()) l.name = j.displayName
                    if (l.userId.isEmpty() && j.userId.isNotEmpty()) l.userId = j.userId
                    publishParticipants()
                }
            }
            is HpbInbound.RoomLeft -> {
                val peer = peerSid
                if (peer != null && peer in e.sessionIds) peerLeft("the other person left the conversation")
                for (sid in e.sessionIds) {
                    if (oneToOne == null) links[sid]?.let { dropLink(it, "someone left the conversation") }
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
            is HpbInbound.Error -> hpbError(e)
            is HpbInbound.ForceMute -> if (e.peerId == ownId && micOn) {
                log("a moderator muted the microphone")
                microphone(false)
            }
            is HpbInbound.CallEndedForEveryone -> if (e.roomId == token && joined) {
                if (oneToOne != null) end(CallEndReason.PeerHungUp, "the call was ended for everyone")
                else end(CallEndReason.EndedForEveryone, "the call was ended for everyone")
            }
            HpbInbound.Bye -> log("the signaling server ended the session")
            else -> Unit
        }
    }

    /**
     * `not_allowed` names no session: the pending requests are simply asked again by their own
     * retries (never counted against one person). A refused publisher in a group call leaves the
     * user listening, with a notice; it is never offered again in a loop.
     */
    private suspend fun hpbError(e: HpbInbound.Error) {
        log("signaling error: ${e.code}")
        if (e.message.contains("publish", ignoreCase = true) && pubSid != null && !pubAnswered) {
            // The server answers offers in order: this refuses the oldest one still open. Seen live: a call
            // started with the conversation's old rights (video), the server's own rights (audio only) came
            // and the phone offered audio only; then the refusal of the first offer arrived.
            if (supersededOffers > 0) {
                supersededOffers--
                log("an earlier offer was refused; the current one waits for its answer")
                return
            }
            if (earlyOffer) {
                // The offer made before the join was confirmed: not a refusal of this user's sending.
                earlyOffer = false
                pubSid = null
                cancelTimer(TimerKind.PublishDeadline)
                log("the early offer was refused; offering again in the call")
                if (joined) publish()
                return
            }
            _notice.value = REFUSED_NOTICE
            if (oneToOne == null) {
                pubGivenUp = true
                stopSending()
                engine.closePublisher()
                pubSid = null
                cancelTimer(TimerKind.PublishDeadline)
                cancelTimer(TimerKind.Republish)
                pubRebuildPending = false
                if (reconnecting && signalingUp) {
                    reconnecting = false
                    cancelTimer(TimerKind.Reconnect)
                }
                refreshState()
            }
        }
    }

    private suspend fun signalIn(s: HpbInbound.Signal) {
        val from = s.senderSessionId
        if (ownId.isNotEmpty() && from == ownId) {
            when (s.type) {
                // Any sid: a stock backend keeps answering a rebuilt publisher with the first sid.
                "answer" -> if (pubSid != null && !pubAnswered) {
                    pubAnswered = true
                    earlyOffer = false
                    log("sending: answer arrived")
                    supersededOffers = 0
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
        if (from.isEmpty()) return
        if (oneToOne != null && s.type == "hangup") {
            oneToOneHangup(from)
            return
        }
        val l = links[from] ?: run {
            // A group call: someone the roster has not shown yet but who is in the call (a screen
            // offer can come first).
            if (oneToOne != null || from in goneSessions) return
            val flags = inCall[from]?.takeIf { it and CallFlags.IN_CALL != 0 } ?: return
            newLink(from).also { flagsChanged(it, flags, known = true) }
        }
        val kind = StreamKind.of(s.roomType) ?: return
        when (s.type) {
            "offer" -> subscriberOffer(l, kind, s)
            "candidate" -> {
                if (s.sid.isNotEmpty() && s.sid == l.handle[kind]) remoteCandidate(SubscriberKey(l.sid, kind), s.payload)
                else keepEarly(l, s.sid, s.payload)
            }
            "mute", "unmute" -> {
                val on = s.type == "unmute"
                when (s.payload.str("name")) {
                    "audio" -> {
                        l.audio = on
                        if (!on) l.speaking = false
                    }
                    "video" -> l.video = on
                }
                publishParticipants()
            }
            "unshareScreen" -> screenEnded(l)
        }
    }

    private fun oneToOneHangup(from: String) {
        if (!isPeerSession(from)) return
        val elsewhere = inCall.entries.any { (sid, flags) ->
            sid != from && sid !in goneSessions && sessionUser[sid] == peerUserId && flags and CallFlags.IN_CALL != 0
        }
        // They hung up on one device but are still in the call on another: follow them.
        if (elsewhere && from == peerSid) peerLeft("the other person left on one device")
        else if (!elsewhere) end(CallEndReason.PeerHungUp, "the other person hung up")
    }

    private fun isPeerSession(sid: String): Boolean = sid.isNotEmpty() && (sid == peerSid || sessionUser[sid] == peerUserId)

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
            if (pubRebuildPending || (pubSid != null && pubState != MediaConnection.Connected)) {
                pubAttempts = 0
                republish("the signaling connection came back")
            }
            for (l in links.values.toList()) for (kind in StreamKind.entries) {
                if (l.handle[kind] != null && l.state[kind] != MediaConnection.Connected) resubscribe(l, kind, "the signaling connection came back")
            }
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
        // Refusals of the old session's offers never reach the new one.
        supersededOffers = 0
        // Every person stays as a tile; what was received from them is built again after the re-join.
        for (l in links.values) {
            l.cancelJobs()
            l.handle.clear()
            l.state.clear()
            l.early.clear()
            l.offerRequested = false
            l.sentSubstream = -1
        }
        publishParticipants()
        signalingDropped = false
        pubRebuildPending = false
        pubAttempts = 0
        cancelTimer(TimerKind.PublishDeadline)
        cancelTimer(TimerKind.Republish)
        engine.rebuild()
        awaitingRoomAck = true
        reconcileRoster = oneToOne == null
    }

    /** What we send. A failed publisher is replaced (Reconnecting meanwhile, 45 s at most). */
    private suspend fun publisherState(c: MediaConnection) {
        if (pubState != c) log("sending: ${c.name.lowercase()}")
        pubState = c
        when (c) {
            MediaConnection.Connected -> {
                cancelTimer(TimerKind.PublishDeadline)
                pubAttempts = 0
                pubRebuildPending = false
                refreshState()
            }
            MediaConnection.Failed -> if (pubSid != null) republish("sending media failed")
            else -> Unit
        }
    }

    /**
     * A new publisher. Not while the signaling is down (it is remembered and done on its
     * return), not twice within 5 s (done when the 5 s are up), and 4 tries in a row at most:
     * then a one-to-one call ends, a group call goes on with the user listening.
     */
    private suspend fun republish(why: String) {
        if (!joined) return
        if (!rightsNow.any || pubGivenUp) {
            publish()
            refreshState()
            return
        }
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
            if (oneToOne != null) {
                end(CallEndReason.Failed, "sending media did not connect")
            } else {
                log("$why; sending gave up, listening")
                pubGivenUp = true
                _notice.value = GAVE_UP_NOTICE
                stopSending()
                publish()
                if (reconnecting && signalingUp) {
                    reconnecting = false
                    cancelTimer(TimerKind.Reconnect)
                }
                refreshState()
            }
            return
        }
        enterReconnecting(why)
        publish()
    }

    /**
     * Own permissions from a participants update: when they change, publish again with what is
     * left (or stop sending), once.
     */
    private suspend fun ownPermissions(r: PublishRights) {
        if (r == rightsNow) return
        log("what this phone may send changed")
        rightsNow = r
        _rights.value = r
        _notice.value = noticeFor(r)
        if (!r.audio && micOn) {
            micOn = false
            _microphone.value = false
        }
        if (!r.video && camOn) {
            camOn = false
            _camera.value = false
        }
        pubGivenUp = false
        if (joined) {
            pubAttempts = 0
            updateFlags()
            publish()
            refreshState()
        }
    }

    /**
     * The call counts as up once what we send is connected (the others can hear us), or at once
     * when this phone only listens; the call screen tells when nothing arrives from someone.
     */
    private fun mediaUp(): Boolean = pubSid == null || pubState == MediaConnection.Connected

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
                links.isEmpty() -> if (outgoing || oneToOne == null) CallState.WaitingForPeer else CallState.Connecting
                mediaUp() -> CallState.Active(activeSince ?: now().also { activeSince = it })
                else -> CallState.Connecting
            },
        )
    }

    private fun publishParticipants() {
        _participants.value = links.values.map { it.participant() }
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
            val l = links[to] ?: return
            sid = l.handle[c.kind] ?: return
        }
        val roomType = if (c.peerSessionId == null) StreamKind.Camera.roomType else c.kind.roomType
        when (c) {
            is LocalCandidate.Candidate -> send(
                to, "candidate",
                signal(to, "candidate", sid, roomType = roomType, payload = buildJsonObject {
                    put("candidate", buildJsonObject {
                        put("candidate", c.candidate)
                        put("sdpMLineIndex", c.sdpMLineIndex)
                        put("sdpMid", c.sdpMid ?: "")
                    })
                }),
            )
            is LocalCandidate.End -> send(to, "endOfCandidates", signal(to, "endOfCandidates", sid, roomType = roomType, payload = EmptyJsonObject))
        }
    }

    /** Listening from now on: the microphone and camera show off and the server is told. */
    private fun stopSending() {
        micOn = false
        camOn = false
        _microphone.value = false
        _camera.value = false
        engine.setLocalAudio(false)
        engine.setLocalVideo(false)
        if (joined) updateFlags()
    }

    private fun microphone(on: Boolean) {
        if (on && (!rightsNow.audio || pubGivenUp)) {
            _notice.value = if (pubGivenUp) _notice.value else noticeFor(rightsNow)
            return
        }
        micOn = on
        _microphone.value = on
        engine.setLocalAudio(on)
        engine.sendStatus(if (on) "audioOn" else "audioOff")
        announce(on, "audio")
    }

    private fun camera(on: Boolean) {
        if (on && (!rightsNow.video || pubGivenUp)) {
            if (!pubGivenUp) _notice.value = VIDEO_NOT_ALLOWED
            return
        }
        camOn = on
        _camera.value = on
        engine.setLocalVideo(on)
        engine.sendStatus(if (on) "videoOn" else "videoOff")
        announce(on, "video")
    }

    /** Tells everyone in the call (signaling) and the server (the call flags). */
    private fun announce(on: Boolean, name: String) {
        val type = if (on) "unmute" else "mute"
        for (sid in links.keys) send(sid, type, signal(sid, type, "", payload = buildJsonObject { put("name", name) }))
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
        (if (micOn && rightsNow.audio) CallFlags.WITH_AUDIO else 0) or
        (if (camOn && rightsNow.video) CallFlags.WITH_VIDEO else 0)

    // ---------------------------------------------------------------- timers

    private suspend fun timer(kind: TimerKind) {
        when (kind) {
            TimerKind.NoAnswer -> if (peerSid == null) {
                if (outgoing) end(CallEndReason.NoAnswer, "nobody answered")
                else end(CallEndReason.PeerHungUp, "the caller is not in the call")
            }
            TimerKind.Alone -> if (links.isEmpty()) end(CallEndReason.Alone, "nobody else was in the call")
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
            TimerKind.Poll -> if ((joined || joinDeadlineExtended) && (links.isEmpty() || links.values.any { waitingForMedia(it) })) {
                callScope?.launch {
                    try {
                        inbox.send(Input.Peers(client.callPeers(token)))
                    } catch (e: TalkError) {
                        // The next poll tries again.
                    }
                }
                startTimer(TimerKind.Poll, POLL_MS)
            }
            TimerKind.Reconnect -> if (reconnecting) end(CallEndReason.Failed, "could not reconnect")
            TimerKind.PublishDeadline -> if (pubSid != null && pubState != MediaConnection.Connected) republish("sending media did not connect in time")
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
            if (_state.compareAndSet(cur, s)) {
                if (cur::class != s::class) log("state: " + stateName(s))
                return
            }
        }
    }

    /** Spelt out: a shrunk build renames the classes. */
    private fun stateName(s: CallState): String = when (s) {
        CallState.Connecting -> "connecting"
        CallState.WaitingForPeer -> "waiting for the other side"
        is CallState.Active -> "in the call with someone"
        CallState.Reconnecting -> "reconnecting"
        is CallState.Ended -> "ended"
    }

    /** Who a link is in the call log: never a name or an id. */
    private fun who(l: PeerLink): String = if (oneToOne != null) "them" else "person ${l.number}"
    private fun whose(l: PeerLink): String = if (oneToOne != null) "their" else "person ${l.number}'s"

    /** Ends the call once (the first reason wins) and tears everything down beside the caller. */
    private fun end(reason: CallEndReason, why: String) {
        while (true) {
            val cur = _state.value
            if (cur is CallState.Ended) return
            if (_state.compareAndSet(cur, CallState.Ended(reason))) break
        }
        log("call ended: $reason ($why)")
        teardownScope.launch { teardown(hint = reason == CallEndReason.HungUp && oneToOne != null) }
    }

    /**
     * Leaves everything, in this order: the hangup hint to the other person (one-to-one only), the
     * media, the call (3 tries, 2 s apart, also when the join was never confirmed), the room, the
     * conversation, the signaling session.
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
            links.values.forEach { it.cancelJobs() }
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
            // Ended before the setup took the prepared connection over: it is this call's all the same.
            if (c == null) prepared?.connection?.let { runCatching { it.close(sayBye = true) } }
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
        const val ALONE_MS = 60_000L
        const val JOIN_DEADLINE_MS = 45_000L
        const val FIRST_POLL_MS = 1_200L
        const val POLL_MS = 3_000L
        const val OFFER_RETRY_MS = 8_000L
        const val MAX_OFFER_REQUESTS = 60
        /** Long enough for a 30 s loss of network plus getting back in (resume or a new session). */
        const val RECONNECT_MS = 45_000L
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

        const val VIDEO_NOT_ALLOWED = "Video isn't allowed here"
        const val REFUSED_NOTICE = "The server refused your audio and video"
        const val GAVE_UP_NOTICE = "Your audio and video couldn't be sent"

        fun noticeFor(r: PublishRights): String? = when {
            !r.any -> "You can only listen here"
            !r.video -> VIDEO_NOT_ALLOWED
            !r.audio -> "Your microphone isn't allowed here"
            else -> null
        }
    }
}
