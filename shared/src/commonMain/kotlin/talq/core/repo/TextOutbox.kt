package talq.core.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import talq.core.TalkError
import talq.core.TalkFiles
import talq.core.TalkClient
import talq.core.model.ChatMessage
import talq.core.model.statusAndCode
import talq.core.net.int

enum class OutboxStatus { SENDING, FAILED }

/** Why an unsent text does not go again by itself. */
enum class OutboxStop {
    /** It does, as soon as the server can be reached. */
    NONE,

    /** The server refused it; the same again would be refused again. */
    REFUSED,

    /** It may be in the chat already: the history could not prove that it is not. */
    UNCLEAR,

    /** It failed too often in a row. The network coming back starts over. */
    SPENT,
}

/** A message the user sent that the server has not confirmed yet. Never dropped silently. */
data class OutboxMessage(
    /** Stable key; also sent to the server so the confirmed message can be matched. */
    val referenceId: String,
    val text: String,
    /** The quoted message, as far as it is loaded ([replyToId] is what is sent). */
    val replyTo: ChatMessage?,
    /** Topic the message goes into, 0 for none. */
    val threadId: Long,
    val status: OutboxStatus,
    /** Why the last attempt failed, when [status] is FAILED. */
    val error: TalkError? = null,
    /** Not empty: this message starts a new topic with this title (then [threadId] is 0). */
    val threadTitle: String = "",
    /** Sent without notifying anyone (capability `silent-send`); a retry keeps it. */
    val silent: Boolean = false,
    /** The message it answers, 0 for none. */
    val replyToId: Long = replyTo?.id ?: 0L,
    val stop: OutboxStop = OutboxStop.NONE,
    /** It was refused for the message it answered, and answers none any more: a retry sends the text alone. */
    val quoteGone: Boolean = false,
) {
    /** Not sent, and it goes again by itself. */
    val waiting: Boolean get() = status == OutboxStatus.FAILED && stop == OutboxStop.NONE
}

/** An unsent text as it is kept between two runs of the app (see [UnsentKeeper]). */
@Serializable
data class UnsentText(
    val token: String,
    val referenceId: String,
    val text: String,
    val threadId: Long = 0L,
    val threadTitle: String = "",
    val replyTo: Long = 0L,
    val silent: Boolean = false,
    /** When it was sent off, milliseconds on the phone's clock. */
    val createdAt: Long = 0L,
    /** The newest message id of the conversation known before it was first posted, 0 = none. */
    val knownNewest: Long = 0L,
    /** A post of it was started and may have reached the server. */
    val posted: Boolean = false,
    val postedAt: Long = 0L,
    /** Nothing is done about it before this (the phone's clock): its last post may still be on its way. */
    val settleAt: Long = 0L,
    /** An [OutboxStop] by name. */
    val stop: String = OutboxStop.NONE.name,
    /** HTTP status of the last failure; 0 = no answer, -1 = none to read a status from. */
    val status: Int = 0,
) {
    companion object {
        /** A text never posted yet, to be kept until the account's session can take it up (`TalkSession.keepUnsent`). */
        fun fresh(token: String, text: String, threadId: Long, createdAt: Long): UnsentText =
            UnsentText(token, TalkFiles.randomHex(64), text, threadId, createdAt = createdAt)
    }
}

/** Keeps the signed-in account's unsent texts between two runs of the app (the app writes a file). */
interface UnsentKeeper {
    /** [all]: every unsent text, each conversation's in order; empty = none. Returns once written; false = could not be. */
    suspend fun keep(all: List<UnsentText>): Boolean
}

/**
 * Sending texts again, for a whole session: one conversation after another, and while the server cannot
 * be reached one look at it per wait, however many conversations have something unsent.
 */
internal class ResendGate {
    /** Held through one conversation's check and post. */
    val lock = Mutex()
    private val misses = MutableStateFlow(0)
    private val closed = MutableStateFlow<Closed?>(null)

    /** Nobody looks before [until]: the last look ended with [error]. */
    class Closed(val until: Long, val error: TalkError)

    fun closedAt(now: Long): Closed? = closed.value?.takeIf { it.until > now }

    /** No answer from the server: nobody else asks for a while. */
    fun failed(now: Long, error: TalkError) {
        val n = misses.value + 1
        misses.value = n
        closed.value = Closed(now + SendRetry.probeDelay(n), error)
    }

    /** It answered. True when it had not before. */
    fun answered(): Boolean {
        val had = misses.value > 0
        misses.value = 0
        closed.value = null
        return had
    }

    /** Something says it may answer again. */
    fun open() {
        closed.value = null
    }
}

/**
 * The texts of one conversation (all its views) that the server has not confirmed. THE one sender of
 * texts: one worker per conversation, strictly in the order they were sent, one request at a time.
 * The rules are [SendRetry]'s (TalQ for desktop's send outbox, `MessageListModel.cpp`):
 *
 * - A fresh text is posted at once. Anything after that (its next try by itself, the user's Retry, a
 *   text kept from an earlier run of the app) first reads the conversation's history: found by its
 *   `referenceId` it IS sent, provably not there it is posted, and what cannot be told stays "Not sent"
 *   until the user presses Retry (which posts it once, whatever the history proves).
 * - No answer, a 5xx, 408 and 429 go again by themselves: after 5 s, 15 s, 45 s, 2 min, and sooner when
 *   something says the server may answer again ([kick]). Any other refusal waits for the user.
 * - A text waits for the older ones that wait; one the server refused holds nobody up.
 * - [UnsentKeeper] has every entry before its first post starts, so the end of the process loses nothing
 *   and sends nothing twice.
 */
internal class TextOutbox(private val session: TalkSession, val token: String) {

    internal data class Entry(
        val message: OutboxMessage,
        val createdAt: Long,
        val knownNewest: Long,
        /** It was tried, or comes from an earlier run: the history is read before it is posted. */
        val tried: Boolean = false,
        /** A post of it was started and may have reached the server. */
        val posted: Boolean = false,
        val postedAt: Long = 0L,
        val settleAt: Long = 0L,
        val posts: Int = 0,
        /** The user pressed Retry: one post even when the history proves nothing. */
        val manual: Boolean = false,
        /** When the history let it go (see [look]), for the post that follows; -1 = not. */
        val clearedAt: Long = -1L,
        // Monotonic milliseconds of this session ([TalkSession.now]).
        val nextTry: Long = 0L,
        val lastActivity: Long = 0L,
        val settleUntil: Long = 0L,
    ) {
        val ref: String get() = message.referenceId
        val active: Boolean get() = message.stop == OutboxStop.NONE
    }

    private val entries = MutableStateFlow<List<Entry>>(emptyList())
    private val bell = Channel<Unit>(Channel.CONFLATED)
    private val running = MutableStateFlow(false)

    /** Looks at the history that got nowhere, in a row. The worker's. */
    private var misses = 0

    private val _createdTopics = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Topics started by `send(threadTitle = ...)`: the outbox key -> the topic's id. */
    val createdTopics: StateFlow<Map<String, Long>> = _createdTopics.asStateFlow()

    /** The titles of the topics started from here, by outbox key (see [onArrived]). */
    private val startedTitles = MutableStateFlow<Map<String, String>>(emptyMap())

    private val _sent = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** The texts last confirmed (the newest [KEEP_SENT]): outbox key -> message id. Set before the entry is gone from any view. */
    val sent: StateFlow<Map<String, Long>> = _sent.asStateFlow()

    /** Pending and failed texts, oldest first. */
    val shown: List<OutboxMessage> get() = entries.value.map { it.message }

    val isEmpty: Boolean get() = entries.value.isEmpty()

    // ---------------------------------------------------------------- from the app

    fun send(text: String, replyTo: ChatMessage?, threadId: Long, threadTitle: String, silent: Boolean): String {
        val message = OutboxMessage(
            TalkFiles.randomHex(64), text, replyTo, threadId, OutboxStatus.SENDING, threadTitle = threadTitle, silent = silent,
        )
        val entry = Entry(message, createdAt = session.wall(), knownNewest = session.newestKnown(token), lastActivity = session.now())
        change { it + entry }
        session.saveUnsentSoon()
        session.roomActions.wroteIn(token)
        // An older one that waits is tried now, and this one after it.
        kick()
        return message.referenceId
    }

    /** The user's Retry on a FAILED one: looked for in the chat first, then posted once more. */
    fun retry(ref: String) {
        val now = session.now()
        change { list ->
            if (list.none { it.ref == ref && it.message.status == OutboxStatus.FAILED }) list
            else list.map {
                when {
                    it.ref == ref -> it.copy(
                        message = it.message.copy(status = OutboxStatus.SENDING, error = null, stop = OutboxStop.NONE, quoteGone = false),
                        tried = true, manual = true, posts = 0, clearedAt = -1L,
                        // Right after it failed the server may still be storing it.
                        nextTry = if (it.posted) maxOf(now, it.lastActivity + SendRetry.MIN_SPACING_MILLIS) else now,
                    )
                    // The ones before it in line go first, now too.
                    it.active -> it.copy(nextTry = minOf(it.nextTry, maxOf(now, it.settleUntil)))
                    else -> it
                }
            }
        }
        session.saveUnsentSoon()
        wake()
    }

    /** Give up on a FAILED one. False when it is not there, or on its way by now (then nothing changes). */
    fun discard(ref: String): Boolean {
        var gone = false
        change { list ->
            gone = list.any { it.ref == ref && it.message.status == OutboxStatus.FAILED }
            if (gone) list.filterNot { it.ref == ref } else list
        }
        if (!gone) return false
        session.saveUnsentSoon()
        // Whatever waited behind it may go now.
        wake()
        return true
    }

    /** The account is signed out: nothing of it goes out any more. */
    fun clear() = change { emptyList() }

    /**
     * Sending may work now: what waits is tried soon, never before [SendRetry]'s spacing and settle
     * times. [networkBack]: the phone's network is usable again, so one that failed too often starts over.
     */
    fun kick(networkBack: Boolean = false) {
        if (isEmpty) return
        val now = session.now()
        change { list ->
            list.map {
                val soon = SendRetry.kicked(if (it.active) it.nextTry else Long.MAX_VALUE, now, it.lastActivity, it.settleUntil)
                when {
                    it.active -> if (it.tried) it.copy(nextTry = soon) else it.copy(nextTry = minOf(it.nextTry, now))
                    networkBack && it.message.stop == OutboxStop.SPENT ->
                        it.copy(message = it.message.copy(stop = OutboxStop.NONE), posts = 0, nextTry = soon)
                    else -> it
                }
            }
        }
        wake()
    }

    /** What an earlier run of the app left unsent: "Not sent" until the history was read. */
    fun restore(kept: List<UnsentText>) {
        val now = session.now()
        val wall = session.wall()
        change { list ->
            val have = list.mapTo(HashSet()) { it.ref }
            kept.filter { it.referenceId !in have }.map { k ->
                val stop = runCatching { OutboxStop.valueOf(k.stop) }.getOrDefault(OutboxStop.NONE)
                val settle = now + (k.settleAt - wall).coerceIn(0L, SendRetry.SLOW_SETTLE_MILLIS)
                Entry(
                    message = OutboxMessage(
                        k.referenceId, k.text, replyTo = null, threadId = k.threadId, status = OutboxStatus.FAILED,
                        error = errorOf(k.status), threadTitle = k.threadTitle, silent = k.silent, replyToId = k.replyTo,
                        stop = if (stop == OutboxStop.SPENT) OutboxStop.NONE else stop,
                    ),
                    createdAt = k.createdAt, knownNewest = k.knownNewest, tried = true, posted = k.posted,
                    postedAt = k.postedAt, settleAt = k.settleAt, nextTry = settle, lastActivity = now, settleUntil = settle,
                )
            } + list
        }
        wake()
    }

    /** What is kept for the next run of the app. */
    fun records(): List<UnsentText> = entries.value.map {
        val m = it.message
        UnsentText(
            token = token, referenceId = it.ref, text = m.text, threadId = m.threadId, threadTitle = m.threadTitle,
            replyTo = m.replyToId, silent = m.silent, createdAt = it.createdAt, knownNewest = it.knownNewest,
            posted = it.posted, postedAt = it.postedAt, settleAt = it.settleAt, stop = m.stop.name,
            status = m.error?.statusAndCode()?.first ?: 0,
        )
    }

    /** The conversation is no longer in the user's list: what the server refused as gone has nowhere to show. */
    fun roomGone() {
        if (entries.value.none { it.gone() }) return
        change { list -> list.filterNot { it.gone() } }
        session.saveUnsentSoon()
    }

    private fun Entry.gone(): Boolean = message.stop == OutboxStop.REFUSED && message.error is TalkError.NotFound

    // ---------------------------------------------------------------- messages that came in

    /**
     * Messages from the server (the feed, a page that was loaded): one of ours in it is sent, whatever its
     * entry says. Returns [batch] as it is to be shown: the server makes the topic only after it has stored
     * (and announced) its first message, so the feed can bring that one first, still in no topic. It reads as
     * its topic's root all the same, or it would stand in General and be no topic in the whole chat.
     */
    fun onArrived(batch: List<ChatMessage>): List<ChatMessage> {
        if (!isEmpty) for (m in batch) if (m.referenceId.isNotEmpty() && !m.isUpdateSignal) arrived(m.referenceId, m, live = true)
        val titles = startedTitles.value
        if (titles.isEmpty()) return batch
        return batch.map { m -> titles[m.referenceId]?.takeIf { m.topicId == 0L && !m.isUpdateSignal }?.let { m.asTopic(it) } ?: m }
    }

    /** The first post of a new topic IS the topic, whatever this copy of it carries. */
    private fun ChatMessage.asTopic(title: String): ChatMessage =
        copy(isThread = true, threadId = topicId.takeIf { it != 0L } ?: id, threadTitle = threadTitle.ifEmpty { title })

    /**
     * [ref] is in the chat as [m] (its post's answer, the history, or the [live] views' own news): the
     * entry goes and the message takes its place. Once per entry, whoever comes first.
     */
    private fun arrived(ref: String, m: ChatMessage, live: Boolean) {
        val title = find(ref)?.message?.threadTitle ?: return
        // First, so whoever follows this text never sees it gone without knowing that it is sent (and what
        // topic it starts).
        _sent.update { (if (it.size < KEEP_SENT) it else it - it.keys.first()) + (ref to m.id) }
        if (title.isNotEmpty()) startedTitles.update { it + (ref to title) }
        val entry = take(ref) ?: return
        val message = when {
            // So no view files it under General.
            title.isNotEmpty() -> m.asTopic(title)
            // The answer to a post has no `parent`; keep the quote we already know.
            m.parent == null && entry.message.replyTo != null -> m.copy(parent = entry.message.replyTo)
            else -> m
        }
        if (title.isNotEmpty()) session.topics(token).onCreated(message, title)
        session.arrivedOwn(token, message)
        if (!live) {
            session.topicsIfCreated(token)?.onLiveMessages(listOf(message))
            session.unreadIfCreated(token)?.onLiveMessages(listOf(message))
        }
        session.saveUnsentSoon()
        // Last, so whoever waits for the new topic's id finds the topic listed and its first
        // message already in the chat.
        if (title.isNotEmpty()) _createdTopics.update { it + (ref to (message.topicId.takeIf { id -> id != 0L } ?: message.id)) }
        wake()
    }

    // ---------------------------------------------------------------- the worker

    private fun wake() {
        if (isEmpty) return
        if (running.compareAndSet(expect = false, update = true)) session.launch { work() }
        bell.trySend(Unit)
    }

    private suspend fun work() {
        while (true) {
            // Strictly in order: the oldest one that still goes by itself decides.
            val head = entries.value.firstOrNull { it.active }
            if (head == null) {
                bell.receive()
                continue
            }
            val wait = head.nextTry - session.now()
            try {
                when {
                    wait > 0 -> withTimeoutOrNull(wait) { bell.receive() }
                    head.tried -> again(head.ref)
                    else -> post(head.ref)
                }
            } catch (e: Exception) {
                // The session ended, and so does this.
                currentCoroutineContext().ensureActive()
                // Not expected (a cancellation that is not ours, out of the HTTP client, too): it waits like
                // any failure. The worker never ends with it: nothing of this conversation would go out again.
                missed(TalkError.Protocol("unsent text", e))
            }
        }
    }

    /** Try [ref] again: one conversation of the session at a time, and not while the server is known not to answer. */
    private suspend fun again(ref: String) = session.resend.lock.withLock {
        // Waiting for its turn took time: it may have arrived, been discarded or put off meanwhile.
        val entry = find(ref) ?: return@withLock
        val now = session.now()
        if (!entry.active || entry.nextTry > now) return@withLock
        val closed = session.resend.closedAt(now)
        if (closed != null && !entry.manual) {
            change { list ->
                list.map {
                    if (!it.active) it
                    else it.copy(
                        message = if (it.message.status == OutboxStatus.FAILED) it.message
                        else it.message.copy(status = OutboxStatus.FAILED, error = closed.error),
                        nextTry = maxOf(it.nextTry, closed.until),
                    )
                }
            }
            return@withLock
        }
        if (entry.clearedAt >= 0 && now - entry.clearedAt <= CLEARED_MILLIS || look(ref)) post(ref)
    }

    /**
     * Read the conversation's history for its unsent texts that were tried. Found ones are sent; the ones
     * that may go again are marked (good for a moment: the rest of the line need not ask again each). True
     * when [ref] may be posted now.
     */
    private suspend fun look(ref: String): Boolean {
        val wanted = entries.value.filter { it.active && it.tried }
        if (!session.capabilities.referenceId) {
            // The server keeps no reference: nothing can be looked up.
            change { list -> list.map { if (it.ref in wanted.map { w -> w.ref }) it.judged(absent = false, newest = 0L) else it } }
            session.saveUnsentSoon()
            return find(ref)?.clearedAt?.let { it >= 0 } == true
        }
        val refs = wanted.mapTo(HashSet()) { it.ref }
        val found = HashMap<String, ChatMessage>()
        var oldest = 0L
        var newest = 0L
        var whole = false
        try {
            // Whether it is on another server, and whether its messages disappear, is in the list.
            if (session.rooms.room(token) == null && !session.rooms.state.value.loaded && !session.rooms.refresh()) {
                return missed(session.rooms.state.value.error ?: TalkError.Protocol("conversation list"))
            }
            // Back to the newest message that was known before any of them went out.
            val reach = wanted.filter { it.posted }.minOfOrNull { it.knownNewest }
            var cursor = 0L
            for (pages in 1..MAX_PAGES) {
                val page = session.client.history(token, cursor, PAGE_SIZE)
                for (m in page.messages) {
                    if (m.id > newest) newest = m.id
                    if (m.referenceId in refs && !m.isUpdateSignal) found[m.referenceId] = m
                }
                val last = page.lastGiven ?: 0L
                if (last == 0L) {
                    // No cursor and nothing in it: the beginning of the chat (an empty one, for the first page).
                    whole = page.isEmpty
                    break
                }
                oldest = last
                if (reach == null || found.size == refs.size || (reach > 0 && last <= reach + 1) || last == cursor) break
                cursor = last
            }
        } catch (e: TalkError) {
            return missed(e)
        }
        misses = 0
        val answersAgain = session.resend.answered()
        val room = session.rooms.room(token)
        val expiry = (room?.raw?.int("messageExpiration") ?: 0) * 1000L
        val wall = session.wall()
        val window = SendRetry.Window(oldest, whole)
        change { list ->
            list.map {
                if (it.ref !in refs || it.ref in found || !it.active) it
                else it.judged(
                    absent = SendRetry.provablyAbsent(
                        window, it.knownNewest,
                        possibleExpiry = expiry > 0 && wall - it.postedAt >= expiry,
                        trusted = room != null && !room.isFederated,
                    ),
                    newest = newest,
                )
            }
        }
        for ((key, m) in found) arrived(key, m, live = false)
        session.saveUnsentSoon()
        if (answersAgain) session.tryUnsent()
        return find(ref)?.let { it.active && it.clearedAt >= 0 } == true
    }

    /** What the history said about this one, not found there: it may go, or it stays until the user presses Retry. */
    private fun Entry.judged(absent: Boolean, newest: Long): Entry {
        val now = session.now()
        return when {
            // It was not stored up to [newest]: stored later, its id is above that.
            absent || !posted -> copy(clearedAt = now, knownNewest = maxOf(knownNewest, newest), lastActivity = now)
            manual -> copy(clearedAt = now, lastActivity = now)
            else -> copy(
                message = message.copy(status = OutboxStatus.FAILED, error = null, stop = OutboxStop.UNCLEAR),
                clearedAt = -1L, lastActivity = now,
            )
        }
    }

    /** The history could not be read. Nothing was posted: no attempt is used up. */
    private fun missed(error: TalkError): Boolean {
        misses++
        val now = session.now()
        val again = SendRetry.retryable(error)
        if (error is TalkError.Network) session.resend.failed(now, error)
        change { list ->
            list.map {
                if (!it.active) it
                else it.copy(
                    message = it.message.copy(
                        status = OutboxStatus.FAILED, error = error, stop = if (again) OutboxStop.NONE else OutboxStop.REFUSED,
                    ),
                    nextTry = maxOf(now + SendRetry.probeDelay(misses), it.settleUntil), lastActivity = now,
                )
            }
        }
        session.saveUnsentSoon()
        return false
    }

    private suspend fun post(ref: String) {
        var before: Entry? = null
        val now = session.now()
        val wall = session.wall()
        val newest = session.newestKnown(token)
        change { list ->
            before = list.firstOrNull { it.ref == ref && it.active }
            if (before == null) list
            else list.map {
                if (it.ref != ref) it
                else it.copy(
                    message = it.message.copy(status = OutboxStatus.SENDING, error = null),
                    knownNewest = if (it.posted) it.knownNewest else maxOf(it.knownNewest, newest),
                    tried = true, posted = true, postedAt = wall, settleAt = wall + SendRetry.SLOW_SETTLE_MILLIS,
                    posts = it.posts + 1, manual = false, clearedAt = -1L, lastActivity = now,
                )
            }
        }
        val was = before ?: return
        // On disk before it goes out, marked as posted: the end of the process from here on neither
        // loses it nor sends it twice.
        session.saveUnsent()
        // Discarded meanwhile.
        val out = find(ref)?.message ?: return
        val started = session.now()
        val answer = try {
            session.client.sendMessage(
                token, out.text,
                replyTo = out.replyToId,
                threadId = out.threadId,
                threadTitle = out.threadTitle,
                referenceId = out.referenceId,
                silent = out.silent,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: TalkError) {
            return failed(was, e, session.now() - started)
        } catch (e: Exception) {
            return failed(was, TalkError.Protocol("sent message", e), session.now() - started)
        }
        arrived(ref, answer, live = false)
        if (session.resend.answered()) session.tryUnsent()
    }

    /** The post of [was] (as it was before) failed after [hung] milliseconds. Not proof that it did not arrive. */
    private fun failed(was: Entry, error: TalkError, hung: Long) {
        val now = session.now()
        val wall = session.wall()
        val retryable = SendRetry.retryable(error)
        // No address, no connection: it never left the phone. No attempt is used up.
        val never = SendRetry.neverSent(error)
        if (never) misses++
        if (error is TalkError.Network) session.resend.failed(now, error)
        val slow = hung >= SendRetry.SLOW_FAILURE_MILLIS
        val settle = if (slow) SendRetry.SLOW_SETTLE_MILLIS else SendRetry.MIN_SPACING_MILLIS
        change { list ->
            val self = list.firstOrNull { it.ref == was.ref } ?: return@change list
            val stop = when {
                !retryable -> OutboxStop.REFUSED
                !never && self.posts >= SendRetry.POST_BUDGET -> OutboxStop.SPENT
                else -> OutboxStop.NONE
            }
            // A first attempt that broke off quickly says "Sending" until the chat was looked at, a few
            // seconds later: mostly it just goes then.
            val quiet = stop == OutboxStop.NONE && !never && !slow && self.posts <= 1
            val status = if (quiet) OutboxStatus.SENDING else OutboxStatus.FAILED
            list.map {
                when {
                    it.ref == was.ref -> it.copy(
                        message = it.message.withoutRefusedReply(error).copy(status = status, error = error, stop = stop),
                        posted = if (never) was.posted else true,
                        posts = if (never) was.posts else it.posts,
                        postedAt = if (never) was.postedAt else it.postedAt,
                        settleAt = if (never) was.settleAt else wall + settle,
                        settleUntil = if (never) was.settleUntil else now + settle,
                        nextTry = now + if (never) SendRetry.probeDelay(misses) else maxOf(SendRetry.retryDelay(it.posts), settle),
                        lastActivity = now,
                    )
                    // Signed out on the server: the rest would be refused the same way.
                    it.active && error is TalkError.Unauthorized ->
                        it.copy(message = it.message.copy(status = OutboxStatus.FAILED, error = error, stop = OutboxStop.REFUSED))
                    // The ones behind it wait with it.
                    it.active && stop == OutboxStop.NONE && !quiet ->
                        it.copy(message = it.message.copy(status = OutboxStatus.FAILED, error = error))
                    else -> it
                }
            }
        }
        session.saveUnsentSoon()
    }

    /**
     * What it answers is gone, or may not be answered (400 or 403 `reply-to`): the same again would be refused for good,
     * so the text goes on without it, as a message for later does (`keepReplyAfterRefusal`). Without a reply the same
     * word is about the topic.
     */
    private fun OutboxMessage.withoutRefusedReply(error: TalkError): OutboxMessage {
        val (status, code) = error.statusAndCode()
        val refused = replyToId != 0L && code == "reply-to" && (status == 400 || status == 403)
        return if (refused) copy(replyTo = null, replyToId = 0L, quoteGone = true) else this
    }

    // ---------------------------------------------------------------- state

    private fun find(ref: String): Entry? = entries.value.firstOrNull { it.ref == ref }

    /** Take [ref] out; null when it is not there (any more): exactly one caller gets it. */
    private fun take(ref: String): Entry? {
        var taken: Entry? = null
        entries.update { list ->
            taken = list.firstOrNull { it.ref == ref }
            if (taken == null) list else list.filterNot { it.ref == ref }
        }
        return taken
    }

    /** Change the entries and show it in every view at once. [f] may run more than once. */
    private fun change(f: (List<Entry>) -> List<Entry>) {
        entries.update(f)
        session.outboxChanged(token)
    }

    private companion object {
        /** A look is good for the posts right after it. */
        const val CLEARED_MILLIS = 10_000L
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 5

        /** How many confirmed texts are remembered for who follows one ([sent]). */
        const val KEEP_SENT = 32

        fun errorOf(status: Int): TalkError = when {
            status == 0 -> TalkError.Network(Exception("not sent before the app was closed"))
            status < 0 -> TalkError.Protocol("not sent before the app was closed")
            else -> TalkClient.errorFor(status, "")
        }
    }
}
