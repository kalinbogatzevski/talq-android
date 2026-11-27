package talq.app.typing

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import talq.app.AppContainer
import talq.app.net.NetLink
import talq.core.call.CallState
import talq.core.repo.TalkSession
import talq.core.typing.TypingEnd
import talq.core.typing.TypingServer
import talq.core.typing.Typists
import talq.core.typing.typingIndicator
import talq.core.typing.typingMessage
import talq.core.typing.typingPossible

/**
 * "typing…" (`appContainer.typing`): the one conversation in front on the phone is joined on the call
 * backend, so typing can be seen and shown there ([TypingServer] says why that is harmless). Main thread.
 *
 * Joined only while the conversation's screen is resumed ([chatInFront] to [chatLeft]), from 2 s after it
 * came to the front, and only while no call is being set up, running or ringing on this phone, the phone has
 * a network and the conversation takes it ([typingPossible]: never with a call running in it, never one on
 * another server). It is left at once when any of that ends; a call being placed sets up its media meanwhile
 * and waits for the goodbye, a second at most ([leaveForCall]), and has a server session of its own anyway.
 * One stay at a time: the next one waits until the last has left.
 *
 * A stay that breaks off is tried again after 10 s, 30 s, then 90 s, five times in all (twice on a metered
 * network) until the conversation is opened again; a minute that worked starts the count over. Coming and
 * going of the network counts as tries too, so nothing loops on a bad one.
 *
 * Sending ([typed]) is the user's setting on the server (typing-privacy, as on desktop; [TypingServer] reads it
 * with the stay, and nothing is sent before), receiving is not.
 * Started at the first key, again every 10 s while keys come, stopped after 8 s without one, with a send and
 * with an empty box.
 */
class TypingPresence(context: Context, private val container: AppContainer) {
    /** Who is typing in the conversation [token] (nobody: no names). */
    data class Shown(val token: String, val names: List<String>)

    /** One time the conversation is in front. */
    private class Visit(val session: TalkSession, val token: String) {
        var job: Job? = null
        var attempts = 0
        var lastEnd: TypingEnd? = null

        /** Sends to the room; null while it is not joined, or when the user does not share that they type. */
        var send: ((String) -> Unit)? = null

        /** When the user last typed, and when the room was last told so (0: not, or told that it stopped). */
        var typedAt = 0L
        var toldAt = 0L
        var idle: Job? = null
        var expiry: Job? = null
    }

    private val appContext = context.applicationContext
    private val _shown = MutableStateFlow(NOBODY)
    val shown: StateFlow<Shown> = _shown.asStateFlow()

    private var visit: Visit? = null
    private val oneStay = Mutex()

    /** A stay is in the room on the call backend (or entering it): only then is there a goodbye to wait for. */
    private val connected = MutableStateFlow(false)
    private var server: TypingServer? = null
    private var serverOf: TalkSession? = null

    /** The conversation's screen is in front. */
    fun chatInFront(session: TalkSession, token: String) {
        visit?.let {
            if (it.session === session && it.token == token) return
            end(it)
        }
        // Its own client would ask the server with a password that may be dead (the saved list's account).
        if (!session.checked || !session.capabilities.typingIndicator) return
        val v = Visit(session, token)
        visit = v
        v.job = container.scope.launch {
            allowed(v).distinctUntilChanged().collectLatest { ok -> if (ok) keepJoined(v) }
        }
    }

    /** It is not any more: left at once. */
    fun chatLeft(session: TalkSession, token: String) {
        visit?.takeIf { it.session === session && it.token == token }?.let { end(it) }
    }

    /** The user changed the text in the box ([hasText] false: it is empty now, or was sent). */
    fun typed(session: TalkSession, token: String, hasText: Boolean) {
        val v = visit?.takeIf { it.session === session && it.token == token } ?: return
        if (!hasText) return stopped(v)
        val now = SystemClock.elapsedRealtime()
        v.typedAt = now
        if (v.toldAt == 0L || now - v.toldAt >= REPEAT_MS) started(v, now)
        v.idle?.cancel()
        v.idle = container.scope.launch {
            delay(IDLE_MS)
            stopped(v)
        }
    }

    /** A call is being placed or answered: the conversation's goodbye goes first, waited for a second at most. */
    suspend fun leaveForCall() {
        withTimeoutOrNull(CALL_WAIT_MS) { connected.first { !it } }
    }

    /** Signing out: left properly while the account still works. */
    suspend fun signedOut() {
        visit?.let { end(it) }
        val old = dropServer()
        withTimeoutOrNull(SIGN_OUT_WAIT_MS) { oneStay.withLock { } }
        old?.close()
    }

    /** The account is gone already (401). */
    fun abort() {
        visit?.let { end(it) }
        val old = dropServer() ?: return
        container.scope.launch {
            withTimeoutOrNull(SIGN_OUT_WAIT_MS) { oneStay.withLock { } }
            old.close()
        }
    }

    private fun end(v: Visit) {
        if (visit === v) visit = null
        v.idle?.cancel()
        v.expiry?.cancel()
        v.job?.cancel()
        clear(v.token)
    }

    private fun clear(token: String) = _shown.update { if (it.token == token) NOBODY else it }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun allowed(v: Visit): Flow<Boolean> {
        val calls = container.calls
        val callState: Flow<CallState?> = calls.current.flatMapLatest { it?.session?.state ?: flowOf(null) }
        val calling = combine(calls.starting, callState) { starting, state ->
            starting || (state != null && state !is CallState.Ended)
        }
        return combine(calling, container.ringer.ringing, container.network.link, v.session.rooms.rooms) { call, ring, link, rooms ->
            !call && ring == null && link != NetLink.None &&
                rooms.firstOrNull { it.token == v.token }
                    ?.typingPossible(v.session.capabilities, System.currentTimeMillis() / 1000) == true
        }
    }

    private suspend fun keepJoined(v: Visit) {
        while (v.attempts < attemptsAllowed()) {
            delay(pauseBefore(v))
            v.attempts++
            val began = SystemClock.elapsedRealtime()
            try {
                v.lastEnd = oneStay.withLock { stay(v) }
            } finally {
                if (SystemClock.elapsedRealtime() - began >= GOOD_STAY_MS) v.attempts = 0
            }
            if (v.lastEnd == TypingEnd.Refused) v.attempts = MAX_ATTEMPTS
        }
    }

    private fun pauseBefore(v: Visit): Long {
        val pause = when (v.attempts) {
            0 -> SETTLE_MS
            1 -> 10_000L
            2 -> 30_000L
            else -> 90_000L
        }
        return if (v.lastEnd == TypingEnd.CallRunning) maxOf(pause, AFTER_CALL_MS) else pause
    }

    private fun attemptsAllowed(): Int {
        val metered = appContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
        return if (metered) METERED_ATTEMPTS else MAX_ATTEMPTS
    }

    /** In the conversation until it breaks off, a call starts or the caller is cancelled. */
    private suspend fun stay(v: Visit): TypingEnd {
        val who = Typists(v.session.user.userId) { id -> nameIn(v, id) }
        val messages = container.scope.launch { clearOnMessages(v, who) }
        try {
            return serverFor(v.session).stay(
                token = v.token,
                onJoining = { connected.value = true },
                onJoined = { send -> withContext(Dispatchers.Main.immediate) { joined(v, send) } },
                onEvent = { e ->
                    withContext(Dispatchers.Main.immediate) {
                        who.event(e, SystemClock.elapsedRealtime())
                        show(v, who)
                    }
                },
                onLeft = {
                    connected.value = false
                    clear(v.token)
                },
            )
        } finally {
            connected.value = false
            messages.cancel()
            v.send = null
            v.toldAt = 0L
            v.expiry?.cancel()
            clear(v.token)
        }
    }

    private fun serverFor(session: TalkSession): TypingServer {
        server?.let { if (serverOf === session) return it else it.close() }
        return TypingServer(session.credentials) { Log.i(TAG, it) }.also {
            server = it
            serverOf = session
        }
    }

    private fun dropServer(): TypingServer? = server.also {
        server = null
        serverOf = null
    }

    private fun joined(v: Visit, send: ((String) -> Unit)?) {
        if (visit !== v) return
        v.send = send
        val now = SystemClock.elapsedRealtime()
        // Typed before it was joined: said now.
        if (v.typedAt != 0L && now - v.typedAt < IDLE_MS) started(v, now)
    }

    private fun started(v: Visit, now: Long) {
        val send = v.send ?: return
        send(typingMessage(started = true))
        v.toldAt = now
    }

    private fun stopped(v: Visit) {
        v.idle?.cancel()
        v.typedAt = 0L
        if (v.toldAt == 0L) return
        v.toldAt = 0L
        v.send?.invoke(typingMessage(started = false))
    }

    /** Shows who types now, and again when the next of them is due to go. */
    private fun show(v: Visit, who: Typists) {
        if (visit !== v) return
        val now = SystemClock.elapsedRealtime()
        who.expire(now)
        _shown.value = Shown(v.token, who.names)
        v.expiry?.cancel()
        v.expiry = who.nextExpiry?.let { due ->
            container.scope.launch {
                delay((due - now).coerceAtLeast(1L))
                show(v, who)
            }
        }
    }

    /** A message of somebody who types ends that. */
    private suspend fun clearOnMessages(v: Visit, who: Typists) {
        val chat = v.session.chat(v.token)
        var seen = chat.state.value.messages.lastOrNull()?.id ?: 0L
        chat.state.collect { s ->
            val fresh = s.messages.takeLastWhile { it.id > seen }
            if (fresh.isEmpty()) return@collect
            seen = fresh.last().id
            for (m in fresh) if (m.actorType == "users" && !m.isSystem) who.messageFrom(m.actorId)
            show(v, who)
        }
    }

    /** The name a user's messages here carry; empty when none is loaded. */
    private fun nameIn(v: Visit, userId: String): String =
        v.session.chat(v.token).state.value.messages
            .lastOrNull { it.actorType == "users" && it.actorId == userId }?.actorDisplayName.orEmpty()

    private companion object {
        const val TAG = "TalqTyping"
        val NOBODY = Shown("", emptyList())
        const val SETTLE_MS = 2_000L
        const val MAX_ATTEMPTS = 5
        const val METERED_ATTEMPTS = 2
        const val GOOD_STAY_MS = 60_000L
        const val AFTER_CALL_MS = 60_000L
        const val REPEAT_MS = 10_000L
        const val IDLE_MS = 8_000L
        const val CALL_WAIT_MS = 1_000L
        const val SIGN_OUT_WAIT_MS = 3_000L
    }
}
