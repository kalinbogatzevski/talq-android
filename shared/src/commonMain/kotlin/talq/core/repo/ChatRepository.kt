package talq.core.repo

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import talq.core.TalkError
import talq.core.model.ChatMessage
import kotlin.concurrent.Volatile

/** Which slice of a room's chat a [ChatRepository] shows. */
sealed interface ChatScope {
    /** Every message of the room, topics included. */
    data object All : ChatScope

    /** Only messages that are in no topic. */
    data object General : ChatScope

    /** One topic: its root message and everything posted into it. */
    data class Topic(val id: Long) : ChatScope
}

internal fun ChatScope.accepts(m: ChatMessage): Boolean = when (this) {
    ChatScope.All -> true
    ChatScope.General -> m.topicId == 0L
    is ChatScope.Topic -> m.topicId == id || m.id == id
}

data class ChatState(
    /** Confirmed messages of this scope, oldest first, update signals already applied. */
    val messages: List<ChatMessage> = emptyList(),
    /** Pending and failed sends of this scope (also from another view of the room), oldest first. Render them after [messages]. */
    val outbox: List<OutboxMessage> = emptyList(),
    /** The newest page has been loaded at least once. */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadingOlder: Boolean = false,
    /** False once the beginning of the chat (or topic) has been reached. */
    val hasOlder: Boolean = true,
    /** The last load of the newest page failed with this; cleared by the next success. */
    val error: TalkError? = null,
    /** The last older page failed with this. It is not asked for again before it is tried again. */
    val olderError: TalkError? = null,
    /**
     * [messages] are what an earlier run of the app kept ([SavedChat]): the server has not answered since. To be read,
     * and nothing else: none of it counts as seen.
     */
    val saved: Boolean = false,
    /** With [messages] from an earlier run: the newest message everyone had read then. */
    val savedCommonRead: Long = 0L,
    /** How often [messages] of an earlier run were dropped whole: something was missing between them and the newest. */
    val cuts: Int = 0,
    /** Up to this id nothing is missing after the oldest of [messages]; what is newer may lie past a gap. */
    internal val reach: Long = 0L,
    /** Those of [messages] the server has not confirmed yet. Each page it answers settles the ones it covers. */
    internal val unconfirmed: Set<Long> = emptySet(),
    /** Came through the feed while [saved]: shown with the newest page, not below a gap. */
    internal val waiting: List<ChatMessage> = emptyList(),
)

/**
 * The chat of one room in one [chatScope]. Observe [state]; call [start] when the screen
 * appears and [stop] when it goes away. Get instances from [TalkSession.chat].
 *
 * Before its first load it shows what an earlier run of the app kept ([ChatState.saved], [SavedChats]); the server's
 * answer takes that in by id, what it does not have any more goes, and a gap between the two drops all of it.
 *
 * A message further back than what is loaded is shown in a [ChatWindow] of its own ([around]).
 */
class ChatRepository internal constructor(
    private val session: TalkSession,
    val token: String,
    val chatScope: ChatScope,
    private val feed: RoomFeed,
) {
    private val client get() = session.client
    private val _state = MutableStateFlow(ChatState())
    private val loadMutex = Mutex()
    private var liveJob: Job? = null

    /** How many screens currently want this repository live (see [start]). */
    private val liveUsers = MutableStateFlow(0)

    /** `lastKnownMessageId` for the next older page; 0 = nothing loaded yet. */
    @Volatile
    private var olderCursor = 0L

    /** Largest message id this repository has seen from the server, in any scope ([around]; the gap rule goes by [ChatState.reach]). */
    @Volatile
    private var newestSeen = 0L

    /**
     * The newest page was loaded since the last [start]: from [olderCursor] on nothing is missing here,
     * and the feed brings what is new.
     */
    private val fresh = MutableStateFlow(false)

    /** This view's load since the last [start] has answered. */
    val upToDate: StateFlow<Boolean> get() = fresh

    /** The server does not let the user read this view ([refused]), until it has answered again: nothing of it is kept. */
    private val closed = MutableStateFlow(false)

    /** The windows on screen somewhere ([around]): what happens to a message here happens to it there. */
    private val windows = MutableStateFlow<List<ChatWindow>>(emptyList())

    val state: StateFlow<ChatState> = _state.asStateFlow()

    /**
     * The newest message of the room that everyone (who shares their read status) has read:
     * an own message with an id up to this is read. 0 = unknown. Live while the room's feed is.
     */
    val commonRead: StateFlow<Long> get() = feed.commonRead

    /** The room's one sender of texts, shared by all its views. */
    private val outbox: TextOutbox get() = session.textOutbox(token)

    /**
     * Topics started in this room with `send(threadTitle = ...)`: the send's outbox key -> the id
     * of the topic the server made. An entry appears when the server confirms the first post.
     */
    val createdTopics: StateFlow<Map<String, Long>> get() = outbox.createdTopics

    /** Texts of this room that are in the chat now: outbox key -> message id. For who follows one it sent (the newest ones only). */
    val sent: StateFlow<Map<String, Long>> get() = outbox.sent

    /** Non-null while live updates are failing (offline etc.); the feed keeps retrying. */
    val liveError: StateFlow<TalkError?> get() = feed.error

    val topicId: Long get() = (chatScope as? ChatScope.Topic)?.id ?: 0L

    init {
        // What is known of it already is here before this view's own load has answered: a topic or General
        // opened while the whole chat is in memory, and what an earlier run of the app kept (from the first
        // frame on when that is in memory, else as soon as it was read).
        session.savedChats.open(token)
        seedKnown()
        syncOutbox()
    }

    private fun accepts(m: ChatMessage): Boolean = chatScope.accepts(m)

    /** Whether a text on its way out is shown here: where its message will be. */
    private fun accepts(o: OutboxMessage): Boolean = when (chatScope) {
        ChatScope.All -> true
        ChatScope.General -> o.threadId == 0L && o.threadTitle.isEmpty()
        is ChatScope.Topic -> o.threadId == chatScope.id
    }

    /** The room's unsent texts of this scope; one whose quote is not known (after a restart) takes it from [messages]. */
    private fun unsent(messages: List<ChatMessage>): List<OutboxMessage> {
        val all = session.textOutboxIfCreated(token)?.shown?.filter { accepts(it) } ?: return emptyList()
        if (all.isEmpty()) return all
        // One that is among the messages an earlier run kept was sent: it is not there twice until the outbox has looked.
        val refs = messages.mapTo(HashSet()) { it.referenceId }
        return all.filter { it.referenceId !in refs }.map { o ->
            if (o.replyTo != null || o.replyToId == 0L) o
            else messages.firstOrNull { it.id == o.replyToId }?.let { o.copy(replyTo = it) } ?: o
        }
    }

    /** The room's unsent texts changed. */
    internal fun syncOutbox() = _state.update { s ->
        val now = unsent(s.messages)
        if (now == s.outbox) s else s.copy(outbox = now)
    }

    /** The newest message id held here, 0 = none. */
    internal val newestHeld: Long get() = _state.value.messages.lastOrNull()?.id ?: 0L

    /**
     * One of the user's texts is in the chat as [m] (it left the outbox): its message takes the pending
     * row's place in one step. Only in a view on screen: one that is not live loads it when it is, and a
     * message put past what it misses could hide that gap. [newestSeen] and [ChatState.reach] stay the feed's.
     * One older than what is loaded (found far back in the history) comes with its page.
     */
    internal fun arrivedOwn(m: ChatMessage) {
        if (!isLive) return syncOutbox()
        _state.update { s ->
            val oldest = s.messages.firstOrNull()?.id ?: 0L
            if (m.id < oldest) s.copy(outbox = unsent(s.messages)) else applyBatch(s, listOf(m), raise = false)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Go live: load the newest page (again, if this is a restart, so nothing that arrived
     * while stopped is missed) and receive new messages as they come.
     *
     * Counted: two screens can show the same repository (the topic list and the whole-chat
     * view both hold the room's [ChatScope.All]), and during a screen change the new one
     * starts before the old one stops. Every [start] needs its own [stop]; live updates end
     * with the last one. Call both from the UI thread.
     */
    fun start() {
        if (liveUsers.getAndUpdate { it + 1 } > 0 && liveJob?.isActive == true) return
        fresh.value = false
        // From an earlier stay: the load that comes has it.
        _state.update { if (it.waiting.isEmpty()) it else it.copy(waiting = emptyList()) }
        seedKnown()
        syncOutbox()
        // Opening the room is a good moment for what could not be sent in it.
        session.textOutboxIfCreated(token)?.kick()
        liveJob = session.scope.launch {
            // Subscribe before anything else so no batch can slip past.
            launch(start = CoroutineStart.UNDISPATCHED) { feed.incoming.collect { ingest(it) } }
            // Whatever changes on screen is kept on the phone too.
            launch { _state.map { it.messages }.distinctUntilChanged { a, b -> a === b }.collect { session.savedChats.note(token) } }
            launch {
                while (true) {
                    dropExpired()
                    delay(EXPIRY_LOOK_MILLIS)
                }
            }
            feed.acquire()
            try {
                while (true) {
                    // Again every few seconds, and at once when the network is usable again (also
                    // when that happened while the load was on its way).
                    while (true) {
                        val wakes = session.waker.mark()
                        if (refresh()) break
                        // What is closed to the user does not open by hammering it.
                        val closed = _state.value.error?.closesChat == true
                        session.waker.pause(if (closed) RETRY_CLOSED_MILLIS else RETRY_LOAD_MILLIS, since = wakes)
                    }
                    fresh.value = true
                    // What came through the feed meanwhile follows on the page.
                    _state.update { it.copy(reach = maxOf(it.reach, it.messages.lastOrNull()?.id ?: 0L)) }
                    for (window in windows.value) window.reload()
                    // Closed to the user while it is on screen: as a first load that failed.
                    closed.first { it }
                    fresh.value = false
                }
            } finally {
                withContext(NonCancellable) { feed.release() }
            }
        }
    }

    /** A screen shows this repository (between [start] and [stop]): its feed brings what is new. */
    internal val isLive: Boolean get() = liveUsers.value > 0

    /**
     * [fresh] (the room's newest page, from its whole chat) holds a message of this scope newer
     * than any held here, or nothing was loaded here yet: a [refresh] would bring something.
     */
    internal fun isBehind(fresh: List<ChatMessage>): Boolean {
        val s = _state.value
        val newest = s.messages.lastOrNull()?.id ?: 0L
        return !s.loaded || fresh.any { it.id > newest && !it.isUpdateSignal && accepts(it) }
    }

    /** Undo one [start]. Loaded messages and the outbox stay. */
    fun stop() {
        if (liveUsers.updateAndGet { (it - 1).coerceAtLeast(0) } > 0) return
        fresh.value = false
        liveJob?.cancel()
        liveJob = null
    }

    // ---------------------------------------------------------------- loading

    /** Load (or reload) the newest page. Returns false and sets `state.error` on failure. */
    suspend fun refresh(): Boolean = loadMutex.withLock {
        _state.update { it.copy(loading = true) }
        try {
            val stored = feed.ensureCursor()
            val fetched = fetch(0L)
            val batch = arrived(fetched.raw)
            val newest = fetched.raw.lastOrNull()?.id ?: 0L
            // Up to here the page has all there is of this view (its own newest message may be much older).
            val known = maxOf(newest, stored)
            var gap = false
            _state.update { s ->
                gap = s.gapBefore(fetched)
                // What is newer than the page came through the feed (or was sent from here) since it was asked for.
                val kept = if (gap) {
                    s.copy(
                        messages = s.messages.filter { it.id > newest && it.id !in s.unconfirmed }, unconfirmed = emptySet(),
                        cuts = if (s.unconfirmed.isEmpty()) s.cuts else s.cuts + 1,
                    )
                } else {
                    s.settled(fetched)
                }
                // Of what waited, the page has what is not newer, as it is now; a change may be of a message before the page.
                val late = s.waiting.filter { m -> m.id > newest || (m.isUpdateSignal && fetched.raw.none { it.id == m.parent?.id }) }
                val now = applyBatch(kept, batch + late)
                now.copy(
                    outbox = unsent(now.messages),
                    loaded = true, loading = false, error = null, saved = false, waiting = emptyList(),
                    hasOlder = if (gap || !s.loaded) !fetched.exhausted else s.hasOlder,
                    olderError = if (gap) null else s.olderError,
                    reach = if (gap || s.saved) known else maxOf(s.reach, known),
                )
            }
            if (gap || olderCursor == 0L) olderCursor = fetched.nextCursor
            closed.value = false
            filesArrived(batch)
            noticed(batch)
            session.savedChats.note(token)
            true
        } catch (e: TalkError) {
            _state.update { it.copy(loading = false, error = e) }
            if (e.closesChat) session.chatRefused(token, chatScope, e)
            false
        }
    }

    /**
     * The server says that this is not for the user (any more): nothing of it stays on screen, in memory or on the
     * phone. It is asked for again like a first load, so it is all back when that was not for good.
     */
    internal fun refused(error: TalkError) {
        closed.value = true
        olderCursor = 0L
        _state.update { s ->
            s.copy(
                messages = emptyList(), outbox = unsent(emptyList()), unconfirmed = emptySet(), waiting = emptyList(),
                saved = false, loaded = false, hasOlder = true, reach = 0L, error = error, olderError = null,
            )
        }
        for (window in windows.value) window.dropBefore(Long.MAX_VALUE)
    }

    /** Something may be missing between what is held and [page], the newest one. */
    private fun ChatState.gapBefore(page: Fetched): Boolean = when {
        reach == 0L -> false
        page.raw.isNotEmpty() -> page.raw.first().id > reach
        else -> unconfirmed.isNotEmpty() && (page.exhausted || page.nextCursor > reach)
    }

    /**
     * The server answered [page], which reaches back from before message [from]: what is held from an earlier run in
     * that part of the chat is what the page has of it, or it is not there any more.
     */
    private fun ChatState.settled(page: Fetched, from: Long = Long.MAX_VALUE): ChatState {
        if (unconfirmed.isEmpty()) return this
        val to = if (page.exhausted) 0L else page.nextCursor
        val covered = unconfirmed.filterTo(HashSet()) { it in to until from }
        if (covered.isEmpty()) return this
        val there = page.raw.mapTo(HashSet()) { it.id }
        return copy(messages = messages.filter { it.id !in covered || it.id in there }, unconfirmed = unconfirmed - covered)
    }

    /** What the messages of a page or the feed say about others of this conversation, also in views that are not open. */
    private fun noticed(batch: List<ChatMessage>) {
        for (m in batch) if (m.systemMessage == "history_cleared") session.historyCleared(token, m.id)
        session.changedElsewhere(token, batch.mapNotNull { m -> m.parent?.takeIf { m.isUpdateSignal } })
    }

    /** What has disappeared for everyone by now (the server sends no notice of it, and only drops it from its pages). */
    private fun dropExpired() {
        val now = session.wall() / 1000
        _state.update { s ->
            val left = s.messages.unexpired(now)
            if (left === s.messages) return@update s
            val ids = left.mapTo(HashSet()) { it.id }
            s.copy(messages = left, unconfirmed = s.unconfirmed.filterTo(HashSet()) { it in ids })
        }
        for (window in windows.value) window.dropExpired(now)
    }

    /** Load the next older page. No-op when there is none or a load is running. A failure is `state.olderError`. */
    suspend fun loadOlder(): Boolean {
        val s = _state.value
        if (!s.loaded || !s.hasOlder || s.loadingOlder) return false
        return loadMutex.withLock {
            _state.update { it.copy(loadingOlder = true, olderError = null) }
            try {
                val from = olderCursor
                val fetched = if (from > 0) fetch(from) else Fetched(emptyList(), 0L, true)
                if (fetched.nextCursor > 0) olderCursor = fetched.nextCursor
                val batch = arrived(fetched.raw)
                _state.update { applyBatch(it.settled(fetched, from), batch).copy(loadingOlder = false, hasOlder = !fetched.exhausted, error = null) }
                true
            } catch (e: TalkError) {
                _state.update { it.copy(loadingOlder = false, olderError = e) }
                false
            } finally {
                // Given up halfway (the tab was left): the next one must not wait for it.
                _state.update { if (it.loadingOlder) it.copy(loadingOlder = false) else it }
            }
        }
    }

    /** A failed older page may be asked for again. */
    fun forgetOlderError() = _state.update { it.copy(olderError = null) }

    internal class Fetched(val raw: List<ChatMessage>, val nextCursor: Long, val exhausted: Boolean)

    /**
     * One screenful older than [from] (0 = newest). A topic is filtered by the server. General
     * has no server filter, so pages of the whole chat are read until enough non-topic
     * messages turned up.
     *
     * The end of the history is NOT read off a short page: the server cuts its window to
     * `limit` raw comments and then drops the ones this user may not see, so a full window can
     * come back short or even empty. The end is the answer without a cursor (304) or, in a
     * topic, the page that holds the topic's root.
     */
    internal suspend fun fetch(from: Long): Fetched {
        val all = ArrayList<ChatMessage>()
        var cursor = from
        var exhausted = false
        var pages = 0
        while (true) {
            val page = client.history(token, cursor, PAGE_SIZE, threadId = topicId)
            pages++
            all.addAll(page.messages)
            val next = page.lastGiven ?: page.messages.firstOrNull()?.id
            if (next == null || next == cursor) {
                exhausted = true
                break
            }
            cursor = next
            if (topicId > 0 && page.messages.any { it.id == topicId }) {
                exhausted = true
                break
            }
            if (pages >= MAX_PAGES_PER_LOAD) break
            val usable = all.count { accepts(it) && !it.isUpdateSignal }
            // Keep reading through windows that held nothing for this view.
            if (usable >= if (chatScope == ChatScope.General) GENERAL_MIN_PER_LOAD else 1) break
        }
        return Fetched(all.sortedBy { it.id }, cursor, exhausted)
    }

    /**
     * One screenful newer than [from], as [fetch] reads backwards; at most up to this view's own messages
     * ([meets]). Exhausted: there is nothing newer (the answer without a cursor).
     */
    internal suspend fun fetchNewer(from: Long): Fetched {
        val all = ArrayList<ChatMessage>()
        var cursor = from
        var pages = 0
        while (true) {
            val page = client.newer(token, cursor, PAGE_SIZE, threadId = topicId)
            pages++
            all.addAll(page.messages)
            val next = page.lastGiven ?: page.messages.lastOrNull()?.id
            if (next == null || next <= cursor) return Fetched(all, cursor, exhausted = true)
            cursor = next
            if (pages >= MAX_PAGES_PER_LOAD || meets(cursor)) break
            val usable = all.count { shows(it) }
            if (usable >= if (chatScope == ChatScope.General) GENERAL_MIN_PER_LOAD else 1) break
        }
        return Fetched(all, cursor, exhausted = false)
    }

    // ---------------------------------------------------------------- a message further back

    /** Among this view's own messages. */
    fun holds(messageId: Long): Boolean = _state.value.messages.any { it.id == messageId }

    /**
     * Where message [messageId] is: here, or (with [load]) in a window of the messages around it, read from
     * the server; or why not. Waits for this view's own load to answer first, and a moment for a message
     * newer than any seen. Reads only: no marker moves and no notification goes ([talq.core.TalkClient.newer]).
     */
    suspend fun around(messageId: Long, load: Boolean = true): Around {
        if (holds(messageId)) return Around.Here(messageId)
        withTimeoutOrNull(FRESH_WAIT_MILLIS) {
            combine(fresh, _state) { loaded, s ->
                loaded || (s.error != null && !s.loading) || s.messages.any { it.id == messageId }
            }.first { it }
        }
        withTimeoutOrNull(ARRIVAL_WAIT_MILLIS) {
            while (messageId > newestSeen && !holds(messageId)) delay(ARRIVAL_LOOK_MILLIS)
        }
        return when {
            holds(messageId) -> Around.Here(messageId)
            !load -> Around.OutOfReach
            else -> try {
                // A hundred messages to read: not on the screen's thread.
                val found = withContext(Dispatchers.Default) { ask(messageId) }
                // With the answer in hand: a search given up meanwhile leaves no window behind.
                if (found is Around.InWindow) windows.update { it + found.window }
                found
            } catch (e: TalkError) {
                Around.Failed(e)
            }
        }
    }

    private suspend fun ask(messageId: Long): Around {
        val near = try {
            near(messageId)
        } catch (e: TalkError.NotFound) {
            // No such topic (any more); the message may be there all the same.
            if (topicId == 0L) throw e
            null
        }
        val target = near?.raw?.firstOrNull { it.id == messageId }
            // The server filters by topic: it may be in another one, or in none.
            ?: (if (topicId == 0L) null else one(messageId))
            ?: return Around.Gone
        // Never shown itself: the message it changed is meant.
        if (target.isUpdateSignal) return target.parent?.let { if (holds(it.id)) Around.Here(it.id) else ask(it.id) } ?: Around.Gone
        if (target.deleted) return Around.Gone
        if (near == null && target.topicId == topicId) return Around.Gone
        if (near == null || !accepts(target)) return Around.Elsewhere(target.topicId)
        return Around.InWindow(ChatWindow(
            this, messageId,
            WindowState(
                messages = withBatch(emptyList(), arrived(near.raw)),
                // In a topic the page with the root is the first one.
                hasOlder = topicId == 0L || near.raw.none { it.id == topicId },
                hasNewer = near.hasNewer,
                meets = meets(near.newerCursor),
                olderCursor = near.olderCursor,
                newerCursor = near.newerCursor,
            ),
        ))
    }

    private suspend fun one(messageId: Long): ChatMessage? =
        client.history(token, messageId, limit = 1, includeLastKnown = true).messages.firstOrNull { it.id == messageId }

    internal class Near(val raw: List<ChatMessage>, val olderCursor: Long, val newerCursor: Long, val hasNewer: Boolean)

    /** The messages around [messageId] (itself included, when it is there), [WINDOW_SIDE] to each side. */
    internal suspend fun near(messageId: Long): Near = coroutineScope {
        val after = async { client.newer(token, messageId, WINDOW_SIDE, threadId = topicId) }
        val before = client.history(token, messageId, WINDOW_SIDE + 1, threadId = topicId, includeLastKnown = true)
        val newer = after.await()
        Near(
            raw = (before.messages + newer.messages).distinctBy { it.id },
            olderCursor = before.lastGiven ?: before.messages.firstOrNull()?.id ?: messageId,
            newerCursor = maxOf(messageId, newer.lastGiven ?: newer.messages.lastOrNull()?.id ?: 0L),
            hasNewer = newer.lastGiven != null || newer.messages.isNotEmpty(),
        )
    }

    /** Everything from [newerCursor] on is here: what was read up to there has no gap before this view's own messages. */
    internal fun meets(newerCursor: Long): Boolean = fresh.value && _state.value.loaded && olderCursor in 1L..newerCursor

    /**
     * [window] was read down to this view's own messages ([WindowState.meets]): they are one list now, as if
     * this view had been paged back that far. False when there is a gap after all; nothing changes then.
     */
    suspend fun join(window: ChatWindow): Boolean = loadMutex.withLock {
        if (window.joined) return@withLock true
        val w = window.state.value
        if (window !in windows.value || !meets(w.newerCursor)) {
            window.apart()
            return@withLock false
        }
        val further = w.olderCursor < olderCursor
        if (further) olderCursor = w.olderCursor
        _state.update { s ->
            // Where both hold a message the view's own copy is the one the feed kept up, but for one of an earlier run.
            val covered = s.unconfirmed.filterTo(HashSet()) { it in w.olderCursor..w.newerCursor }
            val own = s.messages.filterNot { it.id in covered }
            val held = own.mapTo(HashSet()) { it.id }
            val messages = (own + w.messages.filterNot { it.id in held }).sortedBy { it.id }
            s.copy(
                messages = messages,
                unconfirmed = s.unconfirmed - covered,
                outbox = unsent(messages),
                hasOlder = if (further) w.hasOlder else s.hasOlder,
                olderError = if (further) null else s.olderError,
            )
        }
        window.joined = true
        close(window)
        true
    }

    /** [window] is no longer shown. */
    fun close(window: ChatWindow) = windows.update { it - window }

    // ---------------------------------------------------------------- merging

    /**
     * Show what is known of this view until the first own load has answered: what the whole chat holds of it, and
     * what an earlier run of the app kept. Only before that load, and once (but the whole chat's answer takes the
     * place of what was kept).
     */
    internal fun seedKnown() {
        val kept = session.savedChats.view(token, chatScope)
        val whole = if (chatScope == ChatScope.All) null else session.chatIfCreated(token, ChatScope.All)?.state?.value
        if (kept == null && whole?.messages.isNullOrEmpty()) return
        _state.update { s ->
            if (s.loaded || (s.messages.isNotEmpty() && !(s.saved && whole?.loaded == true))) s else s.seeded(whole, kept)
        }
        newestSeen = maxOf(newestSeen, _state.value.reach)
    }

    private fun ChatState.seeded(whole: ChatState?, kept: SavedView?): ChatState {
        val known = whole?.messages.orEmpty().filter { shows(it) }
        // Nothing is missing between the two when they overlap. Otherwise the newer one alone.
        val meets = kept != null && whole != null && whole.messages.isNotEmpty() && kept.top >= whole.messages.first().id
        val older = if (known.isEmpty() || meets) kept?.messages.orEmpty() else emptyList()
        val ids = known.mapTo(HashSet()) { it.id }
        val messages = (older.filterNot { it.id in ids } + known).sortedBy { it.id }
        if (messages.isEmpty()) return this
        val fromPhone = known.isEmpty() || whole?.saved == true
        return copy(
            messages = messages,
            outbox = unsent(messages),
            saved = fromPhone,
            savedCommonRead = session.savedChats.commonRead(token),
            reach = maxOf(if (known.isNotEmpty() || meets) whole?.reach ?: 0L else 0L, if (known.isEmpty() || meets) kept?.top ?: 0L else 0L),
            unconfirmed = if (fromPhone) messages.mapTo(HashSet()) { it.id } else older.mapTo(HashSet()) { it.id } - ids,
        )
    }

    /** What the phone keeps of this view for the next run; null before the server has answered, and while it refuses. */
    internal fun held(): SavedChat.Held? {
        val s = _state.value
        if (!s.loaded || closed.value) return null
        return SavedChat.Held(chatScope, s.messages.filter { it.id <= s.reach }, s.reach)
    }

    /** Messages that were fetched outside the feed (a file that was just posted). */
    internal fun absorb(batch: List<ChatMessage>) {
        _state.update { applyBatch(it, batch) }
        for (window in windows.value) window.onLive(batch)
    }

    private fun ingest(raw: List<ChatMessage>) {
        // A text of the user's among them is sent, also when the answer to its post was lost or is slower.
        val batch = arrived(raw)
        val follows = fresh.value
        _state.update { s ->
            when {
                // Under what an earlier run kept it would look as if nothing were in between.
                s.saved -> s.copy(waiting = s.waiting + batch)
                else -> applyBatch(s, batch).let { if (follows) it.copy(reach = maxOf(it.reach, batch.maxOf { m -> m.id })) else it }
            }
        }
        for (window in windows.value) window.onLive(batch)
        filesArrived(batch)
        noticed(batch)
        session.topicsIfCreated(token)?.onLiveMessages(batch)
        session.unreadIfCreated(token)?.onLiveMessages(batch, from = feed.began)
    }

    /** A file this session sent has arrived as a message: its pending entry is done. */
    private fun filesArrived(batch: List<ChatMessage>) {
        val files = session.fileOutboxIfCreated(token) ?: return
        for (m in batch) if (m.referenceId.isNotEmpty() && !m.isUpdateSignal) files.onArrived(m)
    }

    /** [raise] false: [newestSeen] stays (see [arrivedOwn]). */
    private fun applyBatch(s: ChatState, batch: List<ChatMessage>, raise: Boolean = true): ChatState {
        if (batch.isEmpty()) return s
        if (raise) newestSeen = maxOf(newestSeen, batch.maxOf { it.id })
        val messages = withBatch(s.messages, batch)
        return s.copy(messages = messages, outbox = unsent(messages))
    }

    /** [held] with [batch] (history or feed, unfiltered) put in: what belongs here, and what its update signals say. */
    internal fun withBatch(held: List<ChatMessage>, batch: List<ChatMessage>): List<ChatMessage> {
        val byId = LinkedHashMap<Long, ChatMessage>(held.size + batch.size)
        for (m in held) byId[m.id] = m
        for (m in batch) {
            if (m.isUpdateSignal) {
                // "Message X changed": the signal's parent is X's new state.
                val updated = m.parent ?: continue
                byId[updated.id]?.let { byId[updated.id] = it.changedTo(updated) }
                if (m.systemMessage in QUOTE_CHANGES) {
                    for (row in byId.entries) if (row.value.quoted?.id == updated.id) row.setValue(row.value.quoting(updated))
                }
                continue
            }
            if (accepts(m)) byId[m.id] = m
        }
        return byId.values.sortedBy { it.id }
    }

    /** Messages read from the server, as they are to be shown: one of the user's unsent texts among them is sent. */
    internal fun arrived(raw: List<ChatMessage>): List<ChatMessage> = session.textOutboxIfCreated(token)?.onArrived(raw) ?: raw

    internal fun shows(m: ChatMessage): Boolean = accepts(m) && !m.isUpdateSignal

    /**
     * A message held here changed (the user's edit or delete, from the server's answer): put
     * its new state in place. By id only: nothing else moves, [ChatState.reach] stays as it was
     * (a jump there could hide a gap in a view that is not live), and a message not held is left.
     */
    internal fun applyChange(updated: ChatMessage) {
        _state.update { s ->
            if (s.messages.none { it.id == updated.id || it.quoted?.id == updated.id }) s
            else s.copy(messages = s.messages.map { if (it.id == updated.id) it.changedTo(updated) else it.quoting(updated) })
        }
        for (window in windows.value) window.change(updated.id) { it.changedTo(updated) }
    }

    /** The chat's history was deleted, as message [id] says: nothing from before it is shown any more. */
    internal fun dropBefore(id: Long) {
        _state.update { s -> if (s.messages.none { it.id < id }) s else s.copy(messages = s.messages.filter { it.id >= id }) }
        for (window in windows.value) window.dropBefore(id)
    }

    // ---------------------------------------------------------------- editing and deleting

    /**
     * Save [text] as the new text of the user's message [messageId] (`PUT /chat/{token}/{id}`). On
     * success every view of the room shows the server's new state at once (the live feed's notice
     * comes later and changes nothing more); returns null. A refusal changes nothing and is returned.
     */
    suspend fun edit(messageId: Long, text: String): TalkError? = try {
        client.editMessage(token, messageId, text)?.let { afterChange(it) }
        null
    } catch (e: TalkError) {
        e
    }

    /** Delete message [messageId]; on success every view shows it as deleted. As [edit]. */
    suspend fun delete(messageId: Long): TalkError? = try {
        client.deleteMessage(token, messageId)?.let { afterChange(it) }
        null
    } catch (e: TalkError) {
        e
    }

    /** The conversation list shows the room's last message: when that changed, it is reloaded (not awaited). */
    private fun afterChange(updated: ChatMessage) {
        session.applyChange(token, updated)
        if (session.rooms.room(token)?.lastMessage?.id == updated.id) session.scope.launch { session.rooms.refresh() }
    }

    // ---------------------------------------------------------------- sending

    /**
     * Queue a message (in the room's [TextOutbox], one after another). It shows up in `state.outbox`
     * at once and moves to `state.messages` when the server confirms it; on failure it stays in the
     * outbox as FAILED, goes again by itself when the server could not be reached, and otherwise
     * waits for [retry] or [discard]. In a topic scope the message goes into that topic; elsewhere a reply to a
     * message that is in a topic goes into that message's topic (a quote alone would not).
     *
     * With a [threadTitle] the message starts a NEW topic of that name instead, whatever the
     * scope (a topic only exists once its first post carries the title); a quote is dropped.
     * The new topic's id is reported in [createdTopics] under the returned key, and a retry
     * after a failure carries the title again. [silent] sends it without notifying anyone (the
     * caller checks the `silent-send` capability); a retry keeps that too.
     * Returns the outbox key.
     */
    fun send(text: String, replyTo: ChatMessage? = null, threadTitle: String = "", silent: Boolean = false): String {
        val title = threadTitle.trim()
        val quote = replyTo.takeIf { title.isEmpty() }
        val thread = if (title.isNotEmpty()) 0L else topicFor(replyTo)
        return outbox.send(text, quote, thread, title, silent)
    }

    /**
     * The topic a message from this view goes into: in a topic scope that topic; elsewhere the
     * topic of the message it replies to (a quote alone would not put it there); else 0.
     */
    fun topicFor(replyTo: ChatMessage?): Long = topicId.takeIf { it > 0 } ?: replyTo?.topicId ?: 0L

    /**
     * Try a FAILED outbox entry again, with everything it was first sent with: the chat is looked
     * through for it first, so one whose answer was only lost is not posted a second time.
     */
    fun retry(referenceId: String) = outbox.retry(referenceId)

    /** Give up on a FAILED outbox entry. False when there is none, or it is on its way by now. */
    fun discard(referenceId: String): Boolean = outbox.discard(referenceId)

    // ---------------------------------------------------------------- reactions

    /**
     * Add the reaction, or take it back when the user already reacted with [emoji]. The
     * message changes at once; a failure puts it back and is returned.
     */
    suspend fun toggleReaction(messageId: Long, emoji: String): TalkError? {
        val before = _state.value.messages.firstOrNull { it.id == messageId }
            ?: windows.value.firstNotNullOfOrNull { it.find(messageId) }
            ?: return null
        val remove = emoji in before.reactionsSelf
        val count = (before.reactions[emoji] ?: 0) + if (remove) -1 else 1
        val after = before.copy(
            reactions = if (count <= 0) before.reactions - emoji else before.reactions + (emoji to count),
            reactionsSelf = if (remove) before.reactionsSelf - emoji else before.reactionsSelf + emoji,
        )
        replaceMessage(after)
        return try {
            if (remove) client.removeReaction(token, messageId, emoji) else client.addReaction(token, messageId, emoji)
            null
        } catch (e: TalkError) {
            replaceMessage(before)
            e
        }
    }

    private fun replaceMessage(m: ChatMessage) {
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == m.id) m else it }) }
        for (window in windows.value) window.change(m.id) { m }
    }

    // ---------------------------------------------------------------- read marker

    /**
     * Move the room's read marker to the newest loaded message (or [upTo]). The server keeps
     * one marker per ROOM, so this never moves it backwards: reading an old topic must not
     * turn newer messages elsewhere unread again.
     */
    suspend fun markRead(upTo: Long? = null): Boolean {
        // Never by what only the phone has.
        val id = upTo ?: _state.value.takeUnless { it.saved }?.messages?.lastOrNull()?.id ?: return false
        val known = session.rooms.room(token)?.lastReadMessage ?: 0L
        if (id <= known) return true
        return try {
            client.markRead(token, id)
            session.rooms.applyRead(token, id)
            true
        } catch (e: TalkError) {
            false
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val GENERAL_MIN_PER_LOAD = 30
        const val MAX_PAGES_PER_LOAD = 5
        const val RETRY_LOAD_MILLIS = 4_000L
        const val RETRY_CLOSED_MILLIS = 30_000L

        /** A window's first load: this many raw messages to each side of the one it is for. */
        const val WINDOW_SIDE = 50

        /** The update notices after which a message reads otherwise, also where it is quoted. */
        val QUOTE_CHANGES = setOf("message_deleted", "message_edited")

        /** How often messages that disappear while they are on screen are looked for. */
        const val EXPIRY_LOOK_MILLIS = 15_000L

        /** How long [around] waits for the view's own load. */
        const val FRESH_WAIT_MILLIS = 10_000L

        /** How long it waits for the feed to bring a message newer than any seen, and how often it looks. */
        const val ARRIVAL_WAIT_MILLIS = 5_000L
        const val ARRIVAL_LOOK_MILLIS = 100L
    }
}

/** The answer of a chat whose lobby the user may not pass. */
private const val LOBBY = 412

/** The server refuses the chat itself to the user: not in it (any more), gone, or its lobby is closed. */
internal val TalkError.closesChat: Boolean
    get() = this is TalkError.Forbidden || this is TalkError.NotFound || (this is TalkError.Http && status == LOBBY)

/**
 * This message as the server now has it ([updated], from an update notice or an edit/delete answer).
 * What such a copy leaves out is kept: the quote (it is built without its own parent), the
 * user's own reactions (built without them) and the topic fields when missing.
 */
internal fun ChatMessage.changedTo(updated: ChatMessage): ChatMessage = updated.copy(
    parent = parent,
    parentDeleted = parentDeleted,
    reactionsSelf = if (updated.raw.containsKey("reactionsSelf")) updated.reactionsSelf else reactionsSelf,
    threadId = if (updated.raw.containsKey("threadId")) updated.threadId else threadId,
    isThread = if (updated.raw.containsKey("isThread")) updated.isThread else isThread,
    threadTitle = updated.threadTitle.ifEmpty { threadTitle },
)

/** This message with its quote as the quoted message now is ([updated]), where it quotes that one. */
internal fun ChatMessage.quoting(updated: ChatMessage): ChatMessage =
    if (quoted?.id != updated.id) this else copy(parent = updated.copy(parent = null, parentDeleted = false))
