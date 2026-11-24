package talq.core.repo

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import talq.core.TalkError
import talq.core.model.ChatMessage
import kotlin.random.Random

/** Which slice of a room's chat a [ChatRepository] shows. */
sealed interface ChatScope {
    /** Every message of the room, topics included. */
    data object All : ChatScope

    /** Only messages that are in no topic. */
    data object General : ChatScope

    /** One topic: its root message and everything posted into it. */
    data class Topic(val id: Long) : ChatScope
}

enum class OutboxStatus { SENDING, FAILED }

/** A message the user sent that the server has not confirmed yet. Never dropped silently. */
data class OutboxMessage(
    /** Stable key; also sent to the server so the confirmed message can be matched. */
    val referenceId: String,
    val text: String,
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
)

data class ChatState(
    /** Confirmed messages of this scope, oldest first, update signals already applied. */
    val messages: List<ChatMessage> = emptyList(),
    /** Pending and failed sends, oldest first. Render them after [messages]. */
    val outbox: List<OutboxMessage> = emptyList(),
    /** The newest page has been loaded at least once. */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadingOlder: Boolean = false,
    /** False once the beginning of the chat (or topic) has been reached. */
    val hasOlder: Boolean = true,
    /** The last load failed with this; cleared by the next success. */
    val error: TalkError? = null,
)

/**
 * The chat of one room in one [chatScope]. Observe [state]; call [start] when the screen
 * appears and [stop] when it goes away. Get instances from [TalkSession.chat].
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
    private var olderCursor = 0L

    /** Largest message id this repository has seen from the server, in any scope. */
    private var newestSeen = 0L

    val state: StateFlow<ChatState> = _state.asStateFlow()

    /**
     * The newest message of the room that everyone (who shares their read status) has read:
     * an own message with an id up to this is read. 0 = unknown. Live while the room's feed is.
     */
    val commonRead: StateFlow<Long> get() = feed.commonRead

    private val _createdTopics = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Topics started from here with `send(threadTitle = ...)`: the send's outbox key -> the id
     * of the topic the server made. An entry appears when the server confirms the first post.
     */
    val createdTopics: StateFlow<Map<String, Long>> = _createdTopics.asStateFlow()

    /** Non-null while live updates are failing (offline etc.); the feed keeps retrying. */
    val liveError: StateFlow<TalkError?> get() = feed.error

    val topicId: Long get() = (chatScope as? ChatScope.Topic)?.id ?: 0L

    init {
        // A topic or General opened while the whole chat is in memory: its messages are here
        // from the very first frame, before this view's own load has answered.
        seedFromWholeChat()
    }

    private fun seedFromWholeChat() {
        if (chatScope != ChatScope.All) session.chatIfCreated(token, ChatScope.All)?.let { seed(it.state.value.messages) }
    }

    private fun accepts(m: ChatMessage): Boolean = when (chatScope) {
        ChatScope.All -> true
        ChatScope.General -> m.topicId == 0L
        is ChatScope.Topic -> m.topicId == chatScope.id || m.id == chatScope.id
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
        seedFromWholeChat()
        liveJob = session.scope.launch {
            // Subscribe before anything else so no batch can slip past.
            launch(start = CoroutineStart.UNDISPATCHED) { feed.incoming.collect { ingest(it) } }
            feed.acquire()
            try {
                // Again every few seconds, and at once when the network is usable again (also
                // when that happened while the load was on its way).
                while (true) {
                    val wakes = session.waker.mark()
                    if (refresh()) break
                    session.waker.pause(RETRY_LOAD_MILLIS, since = wakes)
                }
                awaitCancellation()
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
        liveJob?.cancel()
        liveJob = null
    }

    // ---------------------------------------------------------------- loading

    /** Load (or reload) the newest page. Returns false and sets `state.error` on failure. */
    suspend fun refresh(): Boolean = loadMutex.withLock {
        _state.update { it.copy(loading = true) }
        try {
            feed.ensureCursor()
            val fetched = fetch(0L)
            val gap = fetched.raw.isNotEmpty() && newestSeen > 0 && fetched.raw.first().id > newestSeen
            if (gap || olderCursor == 0L) olderCursor = fetched.nextCursor
            merge(fetched.raw, replace = gap)
            _state.update {
                it.copy(
                    loaded = true, loading = false, error = null,
                    hasOlder = if (gap || !it.loaded) !fetched.exhausted else it.hasOlder,
                )
            }
            true
        } catch (e: TalkError) {
            _state.update { it.copy(loading = false, error = e) }
            false
        }
    }

    /** Load the next older page. No-op when there is none or a load is running. */
    suspend fun loadOlder(): Boolean {
        val s = _state.value
        if (!s.loaded || !s.hasOlder || s.loadingOlder) return false
        return loadMutex.withLock {
            _state.update { it.copy(loadingOlder = true) }
            try {
                val fetched = if (olderCursor > 0) fetch(olderCursor) else Fetched(emptyList(), 0L, true)
                if (fetched.nextCursor > 0) olderCursor = fetched.nextCursor
                merge(fetched.raw, replace = false)
                _state.update { it.copy(loadingOlder = false, hasOlder = !fetched.exhausted, error = null) }
                true
            } catch (e: TalkError) {
                _state.update { it.copy(loadingOlder = false, error = e) }
                false
            }
        }
    }

    private class Fetched(val raw: List<ChatMessage>, val nextCursor: Long, val exhausted: Boolean)

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
    private suspend fun fetch(from: Long): Fetched {
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

    // ---------------------------------------------------------------- merging

    /**
     * Show what another view of the same room already holds (the whole chat's messages that
     * belong to this scope) until the first own load has answered. Only before that load.
     */
    private fun seed(known: List<ChatMessage>) {
        if (known.isEmpty()) return
        _state.update { s -> if (s.loaded || s.messages.isNotEmpty()) s else applyBatch(s, known.filterNot { it.isUpdateSignal }) }
    }

    private fun merge(raw: List<ChatMessage>, replace: Boolean) {
        if (raw.isEmpty() && !replace) return
        _state.update { applyBatch(if (replace) it.copy(messages = emptyList()) else it, raw) }
    }

    /** Messages that were fetched outside the feed (a file that was just posted). */
    internal fun absorb(batch: List<ChatMessage>) {
        _state.update { applyBatch(it, batch) }
    }

    private fun ingest(batch: List<ChatMessage>) {
        _state.update { applyBatch(it, batch) }
        // A file this session sent has arrived as a message: its pending entry is done.
        session.fileOutboxIfCreated(token)?.let { files ->
            for (m in batch) if (m.referenceId.isNotEmpty()) files.onArrived(m)
        }
        session.topicsIfCreated(token)?.onLiveMessages(batch)
        session.unreadIfCreated(token)?.onLiveMessages(batch)
    }

    private fun applyBatch(s: ChatState, batch: List<ChatMessage>): ChatState {
        if (batch.isEmpty()) return s
        val byId = LinkedHashMap<Long, ChatMessage>(s.messages.size + batch.size)
        for (m in s.messages) byId[m.id] = m
        var outbox = s.outbox
        for (m in batch) {
            if (m.id > newestSeen) newestSeen = m.id
            if (m.isUpdateSignal) {
                // "Message X changed": the signal's parent is X's new state.
                val updated = m.parent ?: continue
                val old = byId[updated.id] ?: continue
                byId[updated.id] = changed(old, updated)
                continue
            }
            if (m.referenceId.isNotEmpty()) {
                val mine = outbox.firstOrNull { it.referenceId == m.referenceId }
                if (mine != null) {
                    outbox = outbox.filterNot { it.referenceId == m.referenceId }
                    // The first post of a new topic arrived through the feed (the answer to
                    // the send itself was lost, or is slower): the topic exists all the same.
                    if (mine.threadTitle.isNotEmpty()) {
                        _createdTopics.update { it + (mine.referenceId to (m.topicId.takeIf { id -> id != 0L } ?: m.id)) }
                    }
                }
            }
            if (accepts(m)) byId[m.id] = m
        }
        return s.copy(messages = byId.values.sortedBy { it.id }, outbox = outbox)
    }

    /**
     * [old] as the server now has it ([updated], from an update notice or an edit/delete answer).
     * What such a copy leaves out is kept: the quote (it is built without its own parent), the
     * user's own reactions (built without them) and the topic fields when missing.
     */
    private fun changed(old: ChatMessage, updated: ChatMessage): ChatMessage = updated.copy(
        parent = old.parent,
        parentDeleted = old.parentDeleted,
        reactionsSelf = if (updated.raw.containsKey("reactionsSelf")) updated.reactionsSelf else old.reactionsSelf,
        threadId = if (updated.raw.containsKey("threadId")) updated.threadId else old.threadId,
        isThread = if (updated.raw.containsKey("isThread")) updated.isThread else old.isThread,
        threadTitle = updated.threadTitle.ifEmpty { old.threadTitle },
    )

    /**
     * A message held here changed (the user's own edit or delete, from the server's answer): put
     * its new state in place. By id only: nothing else moves, the newest id seen stays as it was
     * (a jump there could hide a gap in a view that is not live), and a message not held is left.
     */
    internal fun applyChange(updated: ChatMessage) {
        _state.update { s ->
            if (s.messages.none { it.id == updated.id }) s
            else s.copy(messages = s.messages.map { if (it.id == updated.id) changed(it, updated) else it })
        }
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

    /** Delete the user's message [messageId]; on success every view shows it as deleted. As [edit]. */
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
     * Queue a message. It shows up in `state.outbox` at once and moves to `state.messages`
     * when the server confirms it; on failure it stays in the outbox as FAILED until [retry]
     * or [discard]. In a topic scope the message goes into that topic; elsewhere a reply to a
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
        val entry = OutboxMessage(newReferenceId(), text, quote, thread, OutboxStatus.SENDING, threadTitle = title, silent = silent)
        _state.update { it.copy(outbox = it.outbox + entry) }
        launchSend(entry)
        return entry.referenceId
    }

    /**
     * The topic a message from this view goes into: in a topic scope that topic; elsewhere the
     * topic of the message it replies to (a quote alone would not put it there); else 0.
     */
    fun topicFor(replyTo: ChatMessage?): Long = topicId.takeIf { it > 0 } ?: replyTo?.topicId ?: 0L

    /** Try a FAILED outbox entry again, with everything it was first sent with. */
    fun retry(referenceId: String) {
        val entry = _state.value.outbox.firstOrNull { it.referenceId == referenceId && it.status == OutboxStatus.FAILED }
            ?: return
        val sending = entry.copy(status = OutboxStatus.SENDING, error = null)
        _state.update { s -> s.copy(outbox = s.outbox.map { if (it.referenceId == referenceId) sending else it }) }
        launchSend(sending)
    }

    /** Give up on an outbox entry. */
    fun discard(referenceId: String) {
        _state.update { s -> s.copy(outbox = s.outbox.filterNot { it.referenceId == referenceId }) }
    }

    private fun launchSend(entry: OutboxMessage) {
        session.scope.launch {
            try {
                val sent = client.sendMessage(
                    token, entry.text,
                    replyTo = entry.replyTo?.id ?: 0L,
                    threadId = entry.threadId,
                    threadTitle = entry.threadTitle,
                    referenceId = entry.referenceId,
                    silent = entry.silent,
                ).let { m ->
                    // The first post of a new topic IS the topic: make sure it reads that way
                    // whatever the answer carried, so no view files it under General.
                    if (entry.threadTitle.isEmpty()) m
                    else m.copy(isThread = true, threadId = m.id, threadTitle = m.threadTitle.ifEmpty { entry.threadTitle })
                }
                if (entry.threadTitle.isNotEmpty()) session.topics(token).onCreated(sent, entry.threadTitle)
                _state.update { s -> s.copy(outbox = s.outbox.filterNot { it.referenceId == entry.referenceId }) }
                // The answer has no `parent`; keep the quote we already know.
                ingest(listOf(if (sent.parent == null && entry.replyTo != null) sent.copy(parent = entry.replyTo) else sent))
                // Last, so whoever waits for the new topic's id finds the topic listed and its
                // first message already in the chat.
                if (entry.threadTitle.isNotEmpty()) _createdTopics.update { it + (entry.referenceId to sent.id) }
            } catch (e: TalkError) {
                _state.update { s ->
                    s.copy(outbox = s.outbox.map {
                        if (it.referenceId == entry.referenceId) it.copy(status = OutboxStatus.FAILED, error = e) else it
                    })
                }
            }
        }
    }

    // ---------------------------------------------------------------- reactions

    /**
     * Add the reaction, or take it back when the user already reacted with [emoji]. The
     * message changes at once; a failure puts it back and is returned.
     */
    suspend fun toggleReaction(messageId: Long, emoji: String): TalkError? {
        val before = _state.value.messages.firstOrNull { it.id == messageId } ?: return null
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
    }

    // ---------------------------------------------------------------- read marker

    /**
     * Move the room's read marker to the newest loaded message (or [upTo]). The server keeps
     * one marker per ROOM, so this never moves it backwards: reading an old topic must not
     * turn newer messages elsewhere unread again.
     */
    suspend fun markRead(upTo: Long? = null): Boolean {
        val id = upTo ?: _state.value.messages.lastOrNull()?.id ?: return false
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

        /** 64 hex characters, the shape the server documents for `referenceId`. */
        fun newReferenceId(): String {
            val hex = "0123456789abcdef"
            return buildString(64) { repeat(64) { append(hex[Random.nextInt(16)]) } }
        }
    }
}
