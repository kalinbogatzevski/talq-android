package talq.core.typing

import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import talq.core.TalkClient
import talq.core.TalkError
import talq.core.call.CallFlags
import talq.core.call.HpbConnection
import talq.core.call.HpbInbound
import talq.core.call.HpbState
import talq.core.call.HpbUser
import talq.core.call.SignalingSettings
import talq.core.model.Credentials
import talq.core.net.asObject
import talq.core.net.bool
import talq.core.net.newHpbHttpClient
import talq.core.net.str
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Why a stay in a conversation ended by itself. */
enum class TypingEnd {
    /** The connection broke, or could not be made. */
    Lost,

    /** A call runs in the conversation: nothing but a call's own session joins it then. */
    CallRunning,

    /** The server does not take it: not again for this conversation now. */
    Refused,
}

/**
 * The account's presence in conversations for the typing indicator, one [stay] at a time (the caller's
 * rule). Typing only travels between sessions that are in a conversation on the call backend, and three
 * things make such a session harmless:
 *
 * - **Notifications.** The server sends none to a user with an ACTIVE session in the conversation
 *   (Talk 25.0.7 `Chat/Notifier.php`, last ping within 30 s), so the session is made INACTIVE
 *   (`participants/state` 0, capability `session-state`: "Notifications should still be sent", constants.md)
 *   before it enters the room on the call backend; the pings the backend sends for it, also for a session
 *   whose phone is gone (30 s after the socket closed, up to 90 s when the network just vanished), then hold
 *   nothing back. Only between the two requests is it active, and nothing pings it there: 30 s at most, when
 *   the phone dies exactly then.
 * - **Calls.** The requests go through a client of their own, with cookies of their own: Talk keeps one
 *   session per conversation in the SERVER session (cookie), and joining or leaving names no other. The
 *   call's session (in the app's client) and those of the user's other devices can so never be the one that
 *   is replaced or left; `force` = false on top. With a call running the conversation is not entered, and a
 *   call that starts ends the stay.
 * - **Nothing rejoins by itself**: a lost connection ends the stay ([HpbConnection] with `reconnect` false).
 *
 * It is an extra: whatever goes wrong in a stay, also in what others send, ends that stay and nothing else.
 *
 * A brand-new server session does not keep the first conversation it joins unless it listed the
 * conversations before (measured, cause unknown; CallSession and desktop know it too). A state request
 * would then reach ANY session of the user, so a new server session lists first and is asked which session
 * it has before its state is touched.
 *
 * Never logged: tickets, session ids.
 */
class TypingServer(credentials: Credentials, private val log: (String) -> Unit = {}) {
    private val client = TalkClient(credentials)
    private val socketClient = lazy { newHpbHttpClient() }

    /** When the server session was last seen to keep what it joined. */
    @Volatile private var keptAt: TimeMark? = null
    @Volatile private var settings: SignalingSettings? = null
    @Volatile private var settingsAt: TimeMark? = null

    /** This server cannot do it: nothing is asked any more. */
    @Volatile private var off = false

    /**
     * Enters [token] and stays until the connection ends, a call starts or the caller is cancelled, then
     * leaves (also after a failure half-way; cancelling waits for that). [onJoining] runs right before the
     * room is entered on the call backend, [onJoined] gets what sends a message to it (null: the user does
     * not share that they type); [onLeft] runs once the signaling session is closed, before the last request.
     */
    suspend fun stay(
        token: String,
        onJoining: () -> Unit,
        onJoined: suspend (send: ((String) -> Unit)?) -> Unit,
        onEvent: suspend (HpbInbound) -> Unit,
        onLeft: () -> Unit,
    ): TypingEnd = withContext(Dispatchers.Default) {
        if (off) return@withContext TypingEnd.Refused
        val ended = CompletableDeferred<TypingEnd>()
        val failed = CoroutineExceptionHandler { _, e ->
            log("lost (${e::class.simpleName})")
            ended.complete(TypingEnd.Lost)
        }
        // Not the caller's job: a cancelled stay still says goodbye on the open socket.
        val own = CoroutineScope(SupervisorJob() + Dispatchers.Default + failed)
        var connection: HpbConnection? = null
        var entered = false
        try {
            listOnce()
            val backend = signaling()
            if (!backend.hasHpb) {
                off = true
                return@withContext TypingEnd.Refused
            }
            val askedAt = TimeSource.Monotonic.markNow()
            val shared = sharing() ?: return@withContext TypingEnd.Refused
            val c = HpbConnection(socketClient.value, backend, client.serverUrl, own, reconnect = false)
            connection = c
            // The events have no replay: listen before connecting.
            own.launch(start = CoroutineStart.UNDISPATCHED) {
                c.events.collect { e ->
                    if (e is HpbInbound.ParticipantsUpdate && (e.users.any { inCall(it) } || e.changed.any { inCall(it) })) {
                        ended.complete(TypingEnd.CallRunning)
                    }
                    onEvent(e)
                }
            }
            c.connect()
            own.launch(start = CoroutineStart.UNDISPATCHED) {
                c.state.first { it is HpbState.Failed || it == HpbState.Disconnected }
                ended.complete(TypingEnd.Lost)
            }
            currentCoroutineContext().ensureActive()
            // Not cut between the requests: an entered session is made inactive, or left.
            val entry = withContext(NonCancellable) {
                entered = true
                enter(token)
            }
            currentCoroutineContext().ensureActive()
            when (entry) {
                is Entry.Out -> return@withContext entry.end
                is Entry.In -> {
                    onJoining()
                    c.joinRoom(token, entry.session)
                }
            }
            // Once the answer is old this stay ends instead: the one after it asks again.
            val send: (String) -> Unit = { text ->
                if (askedAt.elapsedNow() < FRESH) c.enqueue(text) else ended.complete(TypingEnd.Lost)
            }
            onJoined(send.takeIf { shared })
            ended.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Whatever it was: the next stay asks for everything afresh.
            keptAt = null
            settings = null
            log("not joined (${e::class.simpleName})")
            when (e) {
                is TalkError.Network, is TalkError.Server, is TalkError.Protocol -> TypingEnd.Lost
                is TalkError -> TypingEnd.Refused
                else -> TypingEnd.Lost
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    connection?.close(sayBye = true)
                    onLeft()
                    if (entered) leave(token)
                } finally {
                    own.cancel()
                }
            }
        }
    }

    /** Releases the HTTP clients, once no [stay] runs. */
    fun close() {
        client.close()
        if (socketClient.isInitialized()) socketClient.value.close()
    }

    private fun inCall(u: HpbUser): Boolean = u.inCall and CallFlags.IN_CALL != 0

    private fun kept(): Boolean = keptAt?.let { it.elapsedNow() < FRESH } == true

    /** The first request of a server session (see the class): the conversation list, with next to nothing in it. */
    private suspend fun listOnce() {
        if (kept()) return
        client.ocs(
            HttpMethod.Get, "apps/spreed/api/v4/room",
            query = mapOf(
                "noStatusUpdate" to 1,
                "includeLastMessage" to false,
                "modifiedSince" to Clock.System.now().toEpochMilliseconds() / 1000,
            ),
            timeoutMillis = REQUEST_MS,
        )
    }

    private suspend fun signaling(): SignalingSettings {
        val known = settings
        val at = settingsAt
        if (known != null && at != null && at.elapsedNow() < FRESH) return known
        return client.signalingSettings().also {
            settings = it
            settingsAt = TimeSource.Monotonic.markNow()
        }
    }

    /**
     * Whether the user lets others see that they type. Read again with every stay, and nothing is sent before, nor
     * once it is [FRESH] old: they may have changed it on another device meanwhile. Null: the server has no
     * indicator any more.
     */
    private suspend fun sharing(): Boolean? {
        val now = client.capabilities()
        return now.sharesTyping.takeIf { now.typingIndicator }
    }

    private sealed interface Entry {
        class In(val session: String) : Entry
        class Out(val end: TypingEnd) : Entry
    }

    /** Joins the conversation and makes the session inactive; [Entry.Out] when it must be left again. */
    private suspend fun enter(token: String): Entry {
        val room = roomPath(token)
        repeat(ENTER_TRIES) {
            val joined = ask(HttpMethod.Post, "$room/participants/active", buildJsonObject { put("force", false) })
            val session = joined.str("sessionId")
            if (session.isEmpty() || session == NO_SESSION) return Entry.Out(TypingEnd.Refused)
            if (joined.bool("hasCall")) return Entry.Out(TypingEnd.CallRunning)
            if (kept() || ask(HttpMethod.Get, room).str("sessionId") == session) {
                val told = setState(room, INACTIVE)
                if (told == session) {
                    keptAt = TimeSource.Monotonic.markNow()
                    return Entry.In(session)
                }
                // The server session was lost meanwhile and another device's session answered: put it back.
                keptAt = null
                if (told.isNotEmpty() && told != NO_SESSION) setState(room, ACTIVE)
                return Entry.Out(TypingEnd.Refused)
            }
            // Not kept (the trap above): what is joined second is.
        }
        off = true
        log("the server keeps no session; typing is off")
        return Entry.Out(TypingEnd.Refused)
    }

    /** Answers the session the server changed. */
    private suspend fun setState(room: String, state: Int): String =
        ask(HttpMethod.Put, "$room/participants/state", buildJsonObject { put("state", state) }).str("sessionId")

    private suspend fun leave(token: String) {
        try {
            client.ocs(HttpMethod.Delete, "${roomPath(token)}/participants/active", timeoutMillis = REQUEST_MS)
        } catch (e: Throwable) {
            log("not left (${e::class.simpleName}); the server drops it by itself")
        }
    }

    private suspend fun ask(method: HttpMethod, path: String, body: JsonObject? = null): JsonObject =
        client.ocs(method, path, body = body, timeoutMillis = REQUEST_MS).data.asObject()
            ?: throw TalkError.Protocol("typing: expected a conversation")

    private fun roomPath(token: String): String = "apps/spreed/api/v4/room/${token.encodeURLPathPart()}"

    private companion object {
        /** How long the server session, the signaling settings and the user's typing setting hold without asking again. */
        val FRESH = 10.minutes
        const val REQUEST_MS = 10_000L
        const val ENTER_TRIES = 2
        const val NO_SESSION = "0"
        const val INACTIVE = 0
        const val ACTIVE = 1
    }
}
