package talq.core.call

import talq.core.TalqCore
import talq.core.typing.clientHello
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import talq.core.TalkError
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/** Where the signaling socket is. */
sealed interface HpbState {
    data object Disconnected : HpbState
    data object Connecting : HpbState

    /** Signed in as [sessionId]. A new id after a drop means the old session was lost. */
    data class Ready(val sessionId: String, val features: Set<String>) : HpbState

    /** The socket dropped; reconnecting, resuming the old session if the server still has it. */
    data object Resuming : HpbState

    /** Given up: [reason] says why (a rejected hello with no way to get a new ticket). */
    data class Failed(val reason: String) : HpbState
}

/**
 * One signaling session with the call backend (HPB), from [connect] to [close].
 *
 * One coroutine owns the socket: it says hello, then reads every frame into [events] while a
 * writer sends what [send] queued. A dropped socket is reopened at once with a resume of the
 * same session (the room and the call stay joined; the server sends no new room answer). If
 * the server no longer knows the session, a new one is started on the same socket and the room
 * is joined again; what was queued for the old session is dropped then, since offers and
 * candidates never carry over to another session. Further attempts back off from 2 s to 60 s.
 *
 * [refreshSettings] fetches a fresh ticket for a new session after the first (the ticket in
 * [settings] may have expired by then). The ticket is never logged. [reconnect] false: a lost
 * connection is not reopened (nor the room joined again); the state is then [HpbState.Failed].
 *
 * The node: the first [connect] asks [nodes] (see `HpbPick.kt`). A node other than the named one
 * ([SignalingSettings.server]) gets [TRY_NODE_MS] to say hello; when it fails or refuses, it is
 * reported to [nodes] and the named node is used from then on, with the whole [CONNECT_TIMEOUT_MS]
 * (so the worst case is their sum). A resume always goes to the node that said hello (a session
 * lives on one node); once the resume window has passed, the new session goes to the named node:
 * the chosen one may be gone, and the named one always takes the ticket.
 */
class HpbConnection(
    private val http: HttpClient,
    settings: SignalingSettings,
    ncServerUrl: String,
    private val scope: CoroutineScope,
    private val reconnect: Boolean = true,
    private val nodes: HpbNodes = HpbNodes.Named,
    private val refreshSettings: (suspend () -> SignalingSettings)? = null,
) {
    private val backendUrl = ncServerUrl.trimEnd('/') + "/ocs/v2.php/apps/spreed/api/v3/signaling/backend"

    @Volatile private var settings = settings

    /** Asked [nodes] already (once per connection). */
    @Volatile private var nodeAsked = false

    /** A node other than the named one, while it is to be used; null = the named node. */
    @Volatile private var chosen: String? = null

    /** The node that said the last hello: where a resume must go. */
    @Volatile private var helloNode: String? = null

    /** The node in use, or about to be. */
    val node: String get() = helloNode ?: chosen ?: settings.server

    private val _state = MutableStateFlow<HpbState>(HpbState.Disconnected)
    val state: StateFlow<HpbState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HpbInbound>(extraBufferCapacity = 64)

    /** Everything the server sends after hello. No replay: start collecting before you ask. */
    val events: SharedFlow<HpbInbound> = _events.asSharedFlow()

    private val outbox = Channel<String>(Channel.UNLIMITED)
    private val lifecycle = Mutex()
    private val requestIds = MutableStateFlow(0)
    private var loop: Job? = null

    @Volatile private var socket: DefaultClientWebSocketSession? = null
    @Volatile private var sessionId = ""
    @Volatile private var resumeId: String? = null
    @Volatile private var features: Set<String> = emptySet()
    @Volatile private var room: Pair<String, String>? = null
    @Volatile private var closing = false

    /**
     * Opens the socket and says hello; returns this session's id. Throws [TalkError.Network] when
     * the server cannot be reached in time and [TalkError.Protocol] when it refuses the hello.
     */
    suspend fun connect(): String = lifecycle.withLock {
        (state.value as? HpbState.Ready)?.let { return@withLock it.sessionId }
        if (!nodeAsked) {
            nodeAsked = true
            val home = hpbHost(settings.server)
            chosen = nodes.choose(settings).takeIf { hpbHost(it).let { h -> h.isNotEmpty() && h != home } }
        }
        chosen?.let { trial ->
            try {
                return@withLock open(TRY_NODE_MS).also { nodes.connected(trial) }
            } catch (e: Throwable) {
                // Our own time limit, not the caller giving up (that cancels this coroutine).
                val ours = e is TimeoutCancellationException && currentCoroutineContext().isActive
                if (e is kotlinx.coroutines.CancellationException && !ours) {
                    _state.value = HpbState.Disconnected
                    throw e
                }
                // Not reached, refused, or too slow: the named node takes over.
                chosen = null
                helloNode = null
                nodes.failed(trial)
            }
        }
        try {
            open(CONNECT_TIMEOUT_MS).also { nodes.connected(settings.server) }
        } catch (e: Throwable) {
            when (e) {
                is TimeoutCancellationException -> {
                    _state.value = HpbState.Failed("no answer from the signaling server")
                    throw TalkError.Network(e)
                }
                is TalkError -> {
                    _state.value = HpbState.Failed(e.message ?: "signaling failed")
                    throw e
                }
                else -> {
                    _state.value = HpbState.Disconnected
                    throw e
                }
            }
        }
    }

    /** Starts the socket's owner and waits up to [timeoutMs] for its first hello; stops it on failure. */
    private suspend fun open(timeoutMs: Long): String {
        loop?.cancelAndJoin()
        closing = false
        val first = CompletableDeferred<String>()
        loop = scope.launch { run(first) }
        try {
            return withTimeout(timeoutMs) { first.await() }
        } catch (e: Throwable) {
            withContext(NonCancellable) { loop?.cancelAndJoin() }
            loop = null
            throw e
        }
    }

    /**
     * Joins the conversation [token] with the Nextcloud room session [ncSessionId] (from
     * `participants/active`); returns once the server confirms, within 15 s.
     */
    suspend fun joinRoom(token: String, ncSessionId: String) {
        room = token to ncSessionId
        val id = nextRequestId()
        val answer = coroutineScope {
            val wait = async(start = CoroutineStart.UNDISPATCHED) {
                events.first {
                    (it is HpbInbound.RoomAck && (it.id == id || (it.id == null && it.roomId == token))) ||
                        (it is HpbInbound.Error && it.id == id)
                }
            }
            send(roomJoin(token, ncSessionId, id))
            withTimeoutOrNull(ROOM_TIMEOUT_MS) { wait.await() }.also { if (it == null) wait.cancel() }
        }
        when (answer) {
            null -> throw TalkError.Network(IllegalStateException("no answer to joining the room"))
            is HpbInbound.Error -> {
                room = null
                throw TalkError.Protocol("joining the room was refused: ${answer.code} ${answer.message}".trim())
            }
            else -> Unit
        }
    }

    /** Leaves the room (waits up to 5 s for the server to confirm; never throws for that). */
    suspend fun leaveRoom() {
        room = null
        if (state.value !is HpbState.Ready) return
        val id = nextRequestId()
        coroutineScope {
            val wait = async(start = CoroutineStart.UNDISPATCHED) {
                events.first { it is HpbInbound.RoomAck && (it.id == id || (it.id == null && it.roomId.isEmpty())) }
            }
            send(roomJoin("", "", id))
            if (withTimeoutOrNull(LEAVE_TIMEOUT_MS) { wait.await() } == null) wait.cancel()
        }
    }

    /** Queues one message (built with [signal], [requestOffer] ...) for the current session. */
    suspend fun send(text: String) {
        outbox.send(text)
    }

    /** [send] without suspending (the queue is unbounded), so messages leave in the order queued. */
    fun enqueue(text: String) {
        outbox.trySend(text)
    }

    /** Tells the room's other TalQ apps that this is TalQ Android and its version (after [joinRoom]). */
    fun announceClient() {
        enqueue(clientHello(TalqCore.version, settings.helloUserId))
    }

    /** Ends the session. [sayBye] tells the server it is over (else it would wait for a resume). */
    suspend fun close(sayBye: Boolean) = withContext(NonCancellable) {
        lifecycle.withLock {
            closing = true
            socket?.let { s ->
                if (sayBye) {
                    try {
                        withTimeout(BYE_TIMEOUT_MS) { s.outgoing.send(Frame.Text(bye())) }
                    } catch (e: Exception) {
                        // Gone already: nothing to say goodbye to.
                    }
                }
                try {
                    s.close()
                } catch (e: Exception) {
                    // Already closed.
                }
            }
            loop?.cancelAndJoin()
            loop = null
            while (outbox.tryReceive().isSuccess) { }
            sessionId = ""
            resumeId = null
            helloNode = null
            room = null
            features = emptySet()
            _state.value = HpbState.Disconnected
        }
    }

    private fun nextRequestId(): String = "talq-" + requestIds.updateAndGet { it + 1 }

    /** The socket's owner: connect, hello, read; again after a drop until [close]. */
    private suspend fun run(first: CompletableDeferred<String>) {
        var failures = 0
        var droppedAt: TimeSource.Monotonic.ValueTimeMark? = null
        while (!closing) {
            val resuming = resumeId != null
            _state.value = if (resuming) HpbState.Resuming else HpbState.Connecting
            var rejected: String? = null
            // A session lives on the node that made it: a resume goes there, whatever settings say now.
            val target = (if (resuming) helloNode else null) ?: chosen ?: settings.server
            try {
                http.webSocket(urlString = hpbWebSocketUrl(target)) {
                    socket = this
                    try {
                        handshake(resuming)
                        helloNode = target
                        failures = 0
                        droppedAt = null
                        if (!first.isCompleted) first.complete(sessionId)
                        _state.value = HpbState.Ready(sessionId, features)
                        coroutineScope {
                            val writer = launch { for (text in outbox) outgoing.send(Frame.Text(text)) }
                            try {
                                read()
                            } finally {
                                writer.cancel()
                            }
                        }
                    } finally {
                        socket = null
                    }
                }
            } catch (e: HelloRejected) {
                rejected = e.code
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!first.isCompleted) {
                    first.completeExceptionally(TalkError.Network(e))
                    return
                }
            }
            if (closing) return
            if (!reconnect && first.isCompleted) {
                _state.value = HpbState.Failed("the signaling connection was lost")
                return
            }
            if (rejected != null) {
                val why = "the signaling server refused the hello ($rejected)"
                if (!first.isCompleted) {
                    first.completeExceptionally(TalkError.Protocol(why))
                    return
                }
                if (!refreshTicket()) {
                    _state.value = HpbState.Failed(why)
                    return
                }
            }
            failures++
            val since = droppedAt ?: TimeSource.Monotonic.markNow().also { droppedAt = it }
            // The server notices a dead socket only when its pings go unanswered (up to a minute)
            // and keeps the session about 30 s after that: keep resuming it, every second, for
            // 45 s, the call's reconnect window. A refused resume starts a new session on the
            // same socket at once, so trying longer costs nothing; after that, new sessions.
            if (resumeId != null && since.elapsedNow().inWholeMilliseconds >= RESUME_WINDOW_MS) {
                resumeId = null
                failures = 1
                // The session is lost anyway: the new one goes to the named node (the chosen one
                // may be what went away). Not reported as failed: the phone's own network may have been.
                chosen = null
            }
            delay(
                when {
                    resumeId == null -> backoffMillis(failures)
                    failures == 1 -> RESUME_DELAY_MS
                    else -> RESUME_RETRY_MS
                },
            )
        }
    }

    /** Hello (or resume) on a fresh socket; returns once the server has answered it. */
    private suspend fun DefaultClientWebSocketSession.handshake(resuming: Boolean) {
        var welcome: Set<String> = emptySet()
        var tryingResume = resuming
        outgoing.send(Frame.Text(if (tryingResume) helloResume(resumeId!!) else freshHello()))
        val answered = withTimeoutOrNull(HELLO_TIMEOUT_MS) {
            while (true) {
                val frame = incoming.receive()
                if (frame !is Frame.Text) continue
                when (val msg = parseHpb(frame.readText())) {
                    is HpbInbound.Welcome -> welcome = msg.features
                    is HpbInbound.HelloOk -> {
                        if (tryingResume && msg.sessionId == sessionId) {
                            msg.resumeId?.let { resumeId = it }
                            if (welcome.isNotEmpty()) features = welcome
                        } else {
                            val lost = sessionId.isNotEmpty()
                            sessionId = msg.sessionId
                            resumeId = msg.resumeId
                            features = welcome.ifEmpty { msg.features }
                            if (lost) sessionLost()
                        }
                        break
                    }
                    is HpbInbound.Error -> {
                        if (!tryingResume) throw HelloRejected(msg.code)
                        // The old session is gone: start a new one on this socket.
                        tryingResume = false
                        resumeId = null
                        refreshTicket()
                        outgoing.send(Frame.Text(freshHello()))
                    }
                    else -> _events.emit(msg)
                }
            }
            true
        }
        if (answered == null) throw IllegalStateException("no answer to hello")
    }

    /** Reads until the socket closes (or the server ends the session). */
    private suspend fun DefaultClientWebSocketSession.read() {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val msg = parseHpb(frame.readText())
            _events.emit(msg)
            if (msg is HpbInbound.Bye) {
                // The server ended this session: the next connect starts a new one.
                resumeId = null
                return
            }
        }
    }

    /**
     * A new session replaced the old one: what was queued for the old one is dropped, and the
     * room is joined again (its answer arrives in [events] as a [HpbInbound.RoomAck]).
     */
    private fun sessionLost() {
        while (outbox.tryReceive().isSuccess) { }
        room?.let { (token, ncSession) -> outbox.trySend(roomJoin(token, ncSession, nextRequestId())) }
    }

    private suspend fun refreshTicket(): Boolean {
        val refresh = refreshSettings ?: return false
        return try {
            settings = refresh()
            true
        } catch (e: TalkError) {
            false
        }
    }

    private fun freshHello(): String = helloV1(backendUrl, settings.helloUserId, settings.helloTicket)

    private class HelloRejected(val code: String) : Exception("hello rejected: $code")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L

        /** A chosen node that is not the named one (desktop's verify bound: slower is not worth it). */
        const val TRY_NODE_MS = 5_000L
        const val HELLO_TIMEOUT_MS = 10_000L
        const val ROOM_TIMEOUT_MS = 15_000L
        const val LEAVE_TIMEOUT_MS = 5_000L
        const val BYE_TIMEOUT_MS = 2_000L
        const val RESUME_DELAY_MS = 250L
        const val RESUME_RETRY_MS = 1_000L
        const val RESUME_WINDOW_MS = 45_000L

        /** 2 s, 4 s, 8 s ... up to 60 s. */
        fun backoffMillis(failures: Int): Long {
            var ms = 2_000L
            repeat((failures - 2).coerceAtLeast(0)) { ms = (ms * 2).coerceAtMost(60_000L) }
            return ms
        }
    }
}
