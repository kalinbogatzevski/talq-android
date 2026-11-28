package talq.core.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import talq.core.TalkError
import talq.core.model.ChatMessage
import talq.core.model.Room
import talq.core.model.mentionsByName

/**
 * Unread messages of one bucket (a topic, or General = 0). [mentioned]: the user or everyone is
 * mentioned in one of them; [direct]: the user by name.
 */
data class UnreadCount(val count: Int = 0, val mentioned: Boolean = false, val direct: Boolean = false)

internal data class UnreadEntry(val id: Long, val topicId: Long, val mention: Boolean, val direct: Boolean)

/**
 * What is unread in one room, split by topic. Key 0 is "General" (messages in no topic).
 * See [UnreadTracker] for where the numbers come from.
 */
class UnreadState internal constructor(
    internal val entries: List<UnreadEntry> = emptyList(),
    /** Topic id -> newest message id the user has looked at in that bucket, this session. */
    internal val seen: Map<Long, Long> = emptyMap(),
    /** Newest message id looked at in the whole-chat view. */
    internal val seenAll: Long = 0L,
    /** False when unread messages reach further back than was scanned: counts are a minimum. */
    val complete: Boolean = true,
    val loaded: Boolean = false,
    /** Every message up to this one has been looked at (by a scan, or as it came in live after one). */
    internal val knownTo: Long = 0L,
) {
    internal fun isLive(e: UnreadEntry): Boolean = e.id > maxOf(seenAll, seen[e.topicId] ?: 0L)

    /** Unread per bucket; buckets with nothing unread are absent. */
    val byTopic: Map<Long, UnreadCount> = entries.filter { isLive(it) }.groupBy { it.topicId }
        .mapValues { (_, list) -> UnreadCount(list.size, list.any { it.mention }, list.any { it.direct }) }

    fun of(topicId: Long): UnreadCount = byTopic[topicId] ?: UnreadCount()

    /**
     * How many messages past the server's read marker sit in [topicIds], whether or not they
     * were looked at this session: the part of the room's server-side unread number that
     * belongs to those topics.
     */
    fun pendingIn(topicIds: Set<Long>): Int = entries.count { it.topicId in topicIds }

    /**
     * The messages that [room], as the conversation list has it, counts as unread although they were looked
     * at here: something unread before them in another topic holds the server's marker. Empty where what is
     * known here is not what the server counts (a message was deleted, or not seen yet). When more came since,
     * it still holds for the ones before.
     */
    internal fun readAhead(room: Room): List<Long> {
        val newest = room.lastMessage?.id ?: 0L
        if (!loaded || !complete || newest <= 0L) return emptyList()
        val counted = entries.filter { it.id > room.lastReadMessage && it.id <= newest }
        val fits = if (knownTo >= newest) counted.size == room.unreadMessages else counted.size < room.unreadMessages
        return if (fits) counted.filterNot { isLive(it) }.map { it.id } else emptyList()
    }

    /** Newest message id the user has looked at in this bucket during this session (0 = none). */
    fun seenUpTo(topicId: Long): Long = maxOf(seenAll, seen[topicId] ?: 0L)

    internal fun copy(
        entries: List<UnreadEntry> = this.entries,
        seen: Map<Long, Long> = this.seen,
        seenAll: Long = this.seenAll,
        complete: Boolean = this.complete,
        loaded: Boolean = this.loaded,
        knownTo: Long = this.knownTo,
    ) = UnreadState(entries, seen, seenAll, complete, loaded, knownTo)
}

/**
 * Per-topic unread counts for one room.
 *
 * The server keeps ONE read marker per room and no per-topic counts. So this scans the chat
 * from the newest message back to the marker ([MAX_PAGES] pages at a time, further back each
 * time the user finishes reading a bucket) and sorts what it finds into topics. What the user reads in one topic is remembered here ([markSeen]); the
 * server's marker is only moved as far as it can go WITHOUT skipping an unread message in
 * another topic. Reading topic A therefore never turns topic B "read". The local memory is
 * per session: after a restart, a topic read out of order shows as unread again (the safe
 * direction) until the marker has passed it.
 */
class UnreadTracker internal constructor(
    private val session: TalkSession,
    val token: String,
) {
    private val client get() = session.client
    private val _state = MutableStateFlow(UnreadState())
    private val scanMutex = Mutex()

    val state: StateFlow<UnreadState> = _state.asStateFlow()

    /** The read marker as it was listed last ([follow]). Changed under [scanMutex]. */
    @kotlin.concurrent.Volatile
    private var listed = Long.MIN_VALUE

    init {
        // The conversation list takes off what was looked at here (listedRoom).
        session.launch { state.collect { if (session.unreadIfCreated(token) === this@UnreadTracker) session.noteUnread(token, it) } }
        // Marked as unread on another device: what was looked at here is unread again.
        session.launch {
            session.rooms.rooms.collect {
                val marker = session.rooms.room(token)?.lastReadMessage
                if (marker == null || marker == listed) return@collect
                // Found again among what is unread; only where something counts it (a room that was open).
                if (marker < listed && _state.value.loaded) refresh() else scanMutex.withLock { session.rooms.room(token)?.let(::follow) }
            }
        }
    }

    private fun marker(): Long = session.rooms.room(token)?.lastReadMessage ?: 0L

    private fun countable(m: ChatMessage): Boolean =
        !m.isUpdateSignal && !m.isSystem && !m.deleted && !session.isMe(m.actorType, m.actorId)

    /** The user by name (`user`) is a direct mention, `@all` (`call`) one of everyone, as the list's own flags. */
    private fun entry(m: ChatMessage): UnreadEntry {
        val direct = mentionsByName(m.parameters, session.user.userId)
        val everyone = m.parameters.values.any { it.key.startsWith("mention-") && it.type == "call" }
        return UnreadEntry(m.id, m.topicId, mention = direct || everyone, direct = direct)
    }

    /**
     * How far back the chat has been scanned: every message with an id of at least [floor] and
     * at most [top] has been looked at. `floor == 0`: nothing scanned yet. Guarded by [scanMutex].
     */
    private var floor = 0L
    private var top = 0L

    /** The newest message that came in live before anything was scanned: it was not taken in. */
    @kotlin.concurrent.Volatile
    private var missed = 0L

    /**
     * [room] as it is listed now. When its read marker went back ("Mark as unread", on any device) nothing past it counts
     * as looked at any more, and what was scanned down to the old one does not reach it. A marker that reading ahead in
     * another topic holds back never goes back; what is listed for a conversation on another server is not checked.
     * Under [scanMutex].
     */
    private fun follow(room: Room) {
        val marker = room.lastReadMessage
        if (marker < listed && !room.isFederated) {
            floor = 0L
            top = 0L
            _state.update { s ->
                s.copy(seenAll = minOf(s.seenAll, marker), seen = s.seen.mapValues { minOf(it.value, marker) }, complete = false, knownTo = 0L)
            }
        }
        listed = marker
    }

    /** The scan has reached the read marker: below 1 (the server's -2) that is before the first message. Under [scanMutex]. */
    private fun reached(marker: Long): Boolean = floor <= maxOf(marker, 1L)

    private class Scan(val found: List<UnreadEntry>, val newest: Long, val cursor: Long, val beginning: Boolean)

    /**
     * Read up to [MAX_PAGES] pages older than [from] (0 = newest), stopping once the read
     * [marker] or [knownTop] (the top of an earlier scan) is reached. A short page says nothing
     * about the end: the server drops comments this user may not see after cutting its window.
     */
    private suspend fun scan(from: Long, marker: Long, knownTop: Long): Scan {
        val found = ArrayList<UnreadEntry>()
        var cursor = from
        var newest = 0L
        var pages = 0
        while (pages < MAX_PAGES) {
            val page = client.history(token, cursor, PAGE_SIZE)
            pages++
            for (m in page.messages) {
                if (m.id > newest) newest = m.id
                if (m.id > marker && countable(m)) found.add(entry(m))
            }
            val next = page.lastGiven ?: page.messages.firstOrNull()?.id
            if (next == null || next == cursor) return Scan(found, newest, cursor, beginning = true)
            cursor = next
            if (cursor <= marker || (knownTop > 0 && cursor <= knownTop)) break
        }
        return Scan(found, newest, cursor, beginning = false)
    }

    /**
     * Scan for unread messages. Returns false when the server could not be asked. [force]: also when the
     * conversation list, which may be behind, knows of nothing newer than the read marker.
     */
    suspend fun refresh(force: Boolean = false): Boolean = scanMutex.withLock {
        // The rooms list has not answered yet: the read marker is unknown, so nothing can be
        // said about what is unread. Not "nothing is unread".
        val room = session.rooms.room(token) ?: return false
        val marker = room.lastReadMessage
        follow(room)
        // Nothing newer than the marker: no request needed.
        if (!force && (room.lastMessage?.id ?: 0L) <= marker && room.unreadMessages == 0) {
            floor = 1L
            top = maxOf(top, marker)
            _state.update { it.copy(entries = it.entries.filter { e -> e.id > marker }, complete = true, loaded = true, knownTo = top) }
            return true
        }
        try {
            val knownTop = if (floor > 0) top else 0L
            val r = scan(0L, marker, knownTop)
            // The new scan met the old one: what the old one found further back still holds.
            val stitched = !r.beginning && r.cursor > marker && knownTop > 0 && r.cursor <= knownTop
            if (!stitched) floor = if (r.beginning) 1L else r.cursor
            top = maxOf(r.newest, if (stitched) top else 0L)
            // With no marker at all (0) everything would count as unread; treat as nothing known.
            val scanned = if (marker == 0L) emptyList() else r.found
            val complete = marker == 0L || reached(marker)
            _state.update { s ->
                val kept = s.entries.filter { it.id > marker && (it.id > r.newest || (stitched && it.id < r.cursor)) }
                s.copy(
                    entries = (scanned + kept).distinctBy { it.id }.sortedBy { it.id },
                    complete = complete,
                    loaded = true,
                    knownTo = top,
                )
            }
            true
        } catch (e: TalkError) {
            false
        }
    }

    /**
     * The last scan stopped before it reached the read marker: read further back from where it
     * stopped. Called each time the user finishes reading a bucket, and again and again once nothing
     * unread is known any more (see [markSeenLocked]), so even a long backlog becomes known completely
     * and the server's marker can move. False when that got nowhere.
     */
    private suspend fun extend(): Boolean = scanMutex.withLock {
        val marker = marker()
        if (floor <= 0L) return@withLock false
        if (reached(marker)) {
            _state.update { it.copy(complete = true) }
            return@withLock true
        }
        try {
            val r = scan(floor, marker, 0L)
            floor = if (r.beginning) 1L else r.cursor
            _state.update { s ->
                s.copy(
                    entries = (s.entries + r.found).distinctBy { it.id }.sortedBy { it.id },
                    complete = reached(marker),
                )
            }
            true
        } catch (e: TalkError) {
            // Still incomplete; the next read tries again.
            false
        }
    }

    /**
     * New messages from the room's live feed, which brings everything after message [from] (where it began).
     * Without it: messages from elsewhere, so nothing is known of what lies before them.
     */
    internal fun onLiveMessages(batch: List<ChatMessage>, from: Long = Long.MAX_VALUE) {
        val newest = batch.maxOfOrNull { it.id } ?: return
        if (!_state.value.loaded) {
            missed = maxOf(missed, newest)
            return
        }
        // The chat's history was deleted: nothing from before it is unread any more.
        val cleared = batch.lastOrNull { it.systemMessage == "history_cleared" }?.id ?: 0L
        val marker = maxOf(marker(), cleared)
        val fresh = batch.filter { it.id > marker && countable(it) }.map { entry(it) }
        // A topic's first post counted in no topic (it came before its topic was made, see ChatRepository.withBatch):
        // the topic's notice says where it is. The topic id is the root's id.
        val moved = batch.filter { it.isTopicCreatedNotice && it.topicId != 0L }.mapTo(HashSet()) { it.topicId }
        if (fresh.isNotEmpty() || cleared > 0L || moved.isNotEmpty()) _state.update { s ->
            val held = s.entries.filter { it.id > cleared }
                .map { e -> if (e.topicId == 0L && e.id in moved) e.copy(topicId = e.id) else e }
            val known = held.mapTo(HashSet()) { it.id }
            s.copy(entries = (held + fresh.filterNot { it.id in known }).sortedBy { it.id })
        }
        // It follows on what was scanned: no other scan needed for these. One that runs now sees to it itself.
        if (!scanMutex.tryLock()) return
        try {
            if (newest > top && top >= maxOf(from, missed)) {
                top = newest
                _state.update { it.copy(knownTo = newest) }
            }
        } finally {
            scanMutex.unlock()
        }
    }

    /**
     * "Mark as read" of the whole conversation, from its row in the list: up to its last entry as the list
     * shows it, whatever sits in between. A conversation on another server is asked for "everything" where
     * that is known (`chat-read-last`): the ids listed for it are not checked. Throws the server's refusal.
     * The list is reloaded first: the server writes an older marker too, and another device may have read on.
     */
    internal suspend fun markRoomRead(): Unit = markMutex.withLock {
        // What the user saw: a message that came since stays unread.
        val last = (session.rooms.room(token) ?: return).lastMessage?.id ?: 0L
        if (!session.rooms.refresh()) session.rooms.state.value.error?.let { throw it }
        val room = session.rooms.room(token) ?: return
        // Never back: the marker may be further on (read elsewhere, or the last message was deleted).
        val target = maxOf(last, room.lastReadMessage)
        val everything = session.capabilities.readLast && (last <= 0L || room.isFederated)
        if (target <= 0L && !everything) return
        val answer = client.markRead(token, if (everything) null else target)
        val marker = answer?.lastReadMessage ?: target
        remember(ChatScope.All, marker)
        _state.update { it.copy(entries = it.entries.filter { e -> e.id > marker }) }
        showMarker(answer, marker, last)
    }

    /**
     * "Mark as unread" of the conversation, from its row in the list (see [talq.core.TalkClient.markUnread]).
     * What this session remembers of having been looked at goes back with the marker, so the message shows as
     * unread in its topic too. A chat that is open stays unread until something new is seen in it or it is
     * opened again. Throws the server's refusal.
     */
    internal suspend fun markRoomUnread(): Unit = markMutex.withLock {
        val last = session.rooms.room(token)?.lastMessage?.id ?: 0L
        val answer = client.markUnread(token)
        if (answer != null) showMarker(answer, answer.lastReadMessage, last) else session.rooms.refresh()
        val back = answer?.lastReadMessage ?: marker()
        // At once; what is unread is found again because the list says so too (see init).
        _state.update { s -> s.copy(seenAll = minOf(s.seenAll, back), seen = s.seen.mapValues { minOf(it.value, back) }) }
    }

    /** The row at once: the server's own numbers when it sent them ([answer]), else nothing unread; true up to [last]. */
    private fun showMarker(answer: Room?, marker: Long, last: Long) = session.rooms.applyMarker(
        token, marker,
        answer?.let { ReadLeft(it.unreadMessages, it.unreadMention, it.unreadMentionDirect) } ?: ReadLeft(0, mention = false, direct = false),
        exactUpTo = maxOf(last, answer?.lastMessage?.id ?: 0L),
    )

    /**
     * The user has looked at everything in [scope] up to message [upTo] (a message of this
     * room). Clears those counts at once and moves the server's read marker as far as no
     * other bucket's unread message is skipped. Never moves the marker backwards.
     *
     * [upTo] may be any message (the chat screen passes the newest one that has been on screen,
     * also halfway through a backlog). One call at a time per room, every tab of it included: the
     * server writes the marker it is sent, also an older one, so two requests that overtake each
     * other could move it back.
     *
     * Returns false when the server could not be asked or told: then only this session knows.
     */
    suspend fun markSeen(scope: ChatScope, upTo: Long): Boolean {
        if (upTo <= 0) return true
        // At once: another tab's call may be reading the backlog.
        remember(scope, upTo)
        return markMutex.withLock { markSeenLocked(scope, upTo) }
    }

    private val markMutex = Mutex()

    private suspend fun markSeenLocked(scope: ChatScope, upTo: Long): Boolean {
        // The read marker is unknown (the rooms list has not loaded): moving it now could
        // skip unread messages in other topics. Remembered locally only.
        if (session.rooms.room(token) == null) return false
        // Other topics' unread are unknown: remembered locally, the server marker left alone.
        if (!_state.value.loaded && !refresh() && scope != ChatScope.All) return false
        if (!_state.value.complete) extend()
        // Everything found so far is read and the scan has still not reached the marker: read on until
        // something unread turns up (its tab shows it) or the marker is reached. A room with topics has no
        // view of the whole chat that could pass it in one go: without this it kept its unread number.
        var rounds = MAX_EXTENDS
        while (scope != ChatScope.All && rounds-- > 0 && nothingFoundBeforeTheMarker()) {
            if (!extend()) break
        }
        // The rooms list, or the tab itself (a page loaded before the list answered), knows a newer entry than
        // any scan has seen: look at it before deciding what may be marked read (there may be a message before
        // it, in another topic, that nobody has seen yet).
        val last = session.rooms.room(token)?.lastMessage
        val scanned = scanMutex.withLock { top }
        // Not for a text sent here a moment ago: the server has moved its marker to it by itself, and the live
        // feed brings what came before it.
        val sentHere = session.textOutboxIfCreated(token)?.sent?.value?.containsValue(upTo) == true
        val ahead = scope != ChatScope.All && upTo > scanned && !sentHere
        val looked = if (ahead || (last != null && last.id > scanned)) refresh(force = ahead) else true
        // Could not be looked at: as a scan that did not reach the marker.
        val s = _state.value.let { if (looked) it else it.copy(complete = false) }
        // Unread messages older than what was scanned may sit in another topic: don't skip them.
        if (!s.complete && scope != ChatScope.All) return looked
        val firstLive = s.entries.firstOrNull { s.isLive(it) }?.id ?: Long.MAX_VALUE
        // Only ids known to be messages of this room are sent as the marker.
        var target = if (upTo < firstLive) upTo else 0L
        for (e in s.entries) if (e.id < firstLive && e.id > target) target = e.id
        // Nothing unread is left anywhere: the room's last entry may be passed too when it is not
        // a message to read (a reaction, a system notice, an own message). In a one-to-one room
        // Talk makes a reaction the last message and counts it as unread; without this the
        // conversation stays "1 unread" after it was read to the bottom (seen live 2026-11-22).
        if (firstLive == Long.MAX_VALUE && last != null && last.id > target && !countable(last) &&
            last.id <= scanMutex.withLock { top }
        ) {
            target = last.id
        }
        if (target <= marker()) return true
        return try {
            val answer = client.markRead(token, target)
            // The server's unread numbers after the move (only with federation-v1, and only when the
            // answer is about this marker), so a room read halfway shows what is left at once.
            val left = answer?.takeIf { session.capabilities.has("federation-v1") && it.lastReadMessage == target }
                ?.let { ReadLeft(it.unreadMessages, it.unreadMention, it.unreadMentionDirect) }
            session.rooms.applyRead(token, target, left)
            _state.update { it.copy(entries = it.entries.filter { e -> e.id > target }) }
            true
        } catch (e: TalkError) {
            // Stays unread on the server; the local memory still hides it for this session.
            false
        }
    }

    /**
     * Everything in [scope] counts as read without it being opened ("Mark as read"): the same
     * as [markSeen] up to the newest message known in the room. [atLeast] is a message id of
     * the room that is known to be in [scope] (a topic's newest), for when no scan has answered.
     */
    suspend fun markAllSeen(scope: ChatScope, atLeast: Long = 0L) {
        if (!_state.value.loaded) refresh()
        val newest = scanMutex.withLock { maxOf(top, atLeast, _state.value.entries.lastOrNull()?.id ?: 0L) }
        markSeen(scope, newest)
    }

    private fun nothingFoundBeforeTheMarker(): Boolean = _state.value.let { s -> !s.complete && s.entries.none { s.isLive(it) } }

    private fun remember(scope: ChatScope, upTo: Long) {
        _state.update { s ->
            when (scope) {
                ChatScope.All -> s.copy(seenAll = maxOf(s.seenAll, upTo))
                ChatScope.General -> s.copy(seen = s.seen + (0L to maxOf(s.seen[0L] ?: 0L, upTo)))
                is ChatScope.Topic -> s.copy(seen = s.seen + (scope.id to maxOf(s.seen[scope.id] ?: 0L, upTo)))
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 3

        /** Scans further back in one go: 3000 messages. The next read goes on from there. */
        const val MAX_EXTENDS = 10
    }
}
