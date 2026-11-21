package talq.core.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import talq.core.TalkError
import talq.core.model.ChatMessage

/** Unread messages of one bucket (a topic, or General = 0). */
data class UnreadCount(val count: Int = 0, val mentioned: Boolean = false)

internal data class UnreadEntry(val id: Long, val topicId: Long, val mention: Boolean)

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
) {
    internal fun isLive(e: UnreadEntry): Boolean = e.id > maxOf(seenAll, seen[e.topicId] ?: 0L)

    /** Unread per bucket; buckets with nothing unread are absent. */
    val byTopic: Map<Long, UnreadCount> = entries.filter { isLive(it) }.groupBy { it.topicId }
        .mapValues { (_, list) -> UnreadCount(list.size, list.any { it.mention }) }

    fun of(topicId: Long): UnreadCount = byTopic[topicId] ?: UnreadCount()

    /**
     * How many messages past the server's read marker sit in [topicIds], whether or not they
     * were looked at this session: the part of the room's server-side unread number that
     * belongs to those topics.
     */
    fun pendingIn(topicIds: Set<Long>): Int = entries.count { it.topicId in topicIds }

    /** Newest message id the user has looked at in this bucket during this session (0 = none). */
    fun seenUpTo(topicId: Long): Long = maxOf(seenAll, seen[topicId] ?: 0L)

    internal fun copy(
        entries: List<UnreadEntry> = this.entries,
        seen: Map<Long, Long> = this.seen,
        seenAll: Long = this.seenAll,
        complete: Boolean = this.complete,
        loaded: Boolean = this.loaded,
    ) = UnreadState(entries, seen, seenAll, complete, loaded)
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

    private fun marker(): Long = session.rooms.room(token)?.lastReadMessage ?: 0L

    private fun countable(m: ChatMessage): Boolean =
        !m.isUpdateSignal && !m.isSystem && !m.deleted && !session.isMe(m.actorType, m.actorId)

    private fun entry(m: ChatMessage) = UnreadEntry(m.id, m.topicId, mentionsMe(m))

    private fun mentionsMe(m: ChatMessage): Boolean = m.parameters.values.any {
        it.key.startsWith("mention-") && (it.type == "call" || (it.type == "user" && it.id == session.user.userId))
    }

    /**
     * How far back the chat has been scanned: every message with an id of at least [floor] and
     * at most [top] has been looked at. `floor == 0`: nothing scanned yet. Guarded by [scanMutex].
     */
    private var floor = 0L
    private var top = 0L

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

    /** Scan for unread messages. Returns false when the server could not be asked. */
    suspend fun refresh(): Boolean = scanMutex.withLock {
        // The rooms list has not answered yet: the read marker is unknown, so nothing can be
        // said about what is unread. Not "nothing is unread".
        val room = session.rooms.room(token) ?: return false
        val marker = room.lastReadMessage
        // Nothing newer than the marker: no request needed.
        if ((room.lastMessage?.id ?: 0L) <= marker && room.unreadMessages == 0) {
            floor = 1L
            top = maxOf(top, marker)
            _state.update { it.copy(entries = it.entries.filter { e -> e.id > marker }, complete = true, loaded = true) }
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
            val complete = marker == 0L || floor <= marker
            _state.update { s ->
                val kept = s.entries.filter { it.id > marker && (it.id > r.newest || (stitched && it.id < r.cursor)) }
                s.copy(
                    entries = (scanned + kept).distinctBy { it.id }.sortedBy { it.id },
                    complete = complete,
                    loaded = true,
                )
            }
            true
        } catch (e: TalkError) {
            false
        }
    }

    /**
     * The last scan stopped before it reached the read marker: read further back from where it
     * stopped. Called each time the user finishes reading a bucket, so even a long backlog is
     * known completely after a few of those, and the server's marker can move.
     */
    private suspend fun extend() = scanMutex.withLock {
        val marker = marker()
        if (floor <= 0L) return@withLock
        if (floor <= marker) {
            _state.update { it.copy(complete = true) }
            return@withLock
        }
        try {
            val r = scan(floor, marker, 0L)
            floor = if (r.beginning) 1L else r.cursor
            _state.update { s ->
                s.copy(
                    entries = (s.entries + r.found).distinctBy { it.id }.sortedBy { it.id },
                    complete = floor <= marker,
                )
            }
        } catch (e: TalkError) {
            // Still incomplete; the next read tries again.
        }
    }

    /** New messages from the room's live feed. */
    internal fun onLiveMessages(batch: List<ChatMessage>) {
        if (!_state.value.loaded) return
        val marker = marker()
        val fresh = batch.filter { it.id > marker && countable(it) }.map { entry(it) }
        if (fresh.isEmpty()) return
        _state.update { s ->
            val known = s.entries.mapTo(HashSet()) { it.id }
            s.copy(entries = (s.entries + fresh.filterNot { it.id in known }).sortedBy { it.id })
        }
    }

    /**
     * The user has looked at everything in [scope] up to message [upTo] (a message of this
     * room). Clears those counts at once and moves the server's read marker as far as no
     * other bucket's unread message is skipped. Never moves the marker backwards.
     */
    suspend fun markSeen(scope: ChatScope, upTo: Long) {
        if (upTo <= 0) return
        if (session.rooms.room(token) == null) {
            // The read marker is unknown (the rooms list has not loaded): moving it now could
            // skip unread messages in other topics. Remember locally only.
            remember(scope, upTo)
            return
        }
        if (!_state.value.loaded && !refresh() && scope != ChatScope.All) {
            // Other topics' unread are unknown: remember locally, leave the server marker alone.
            remember(scope, upTo)
            return
        }
        remember(scope, upTo)
        if (!_state.value.complete) extend()
        val s = _state.value
        // Unread messages older than what was scanned may sit in another topic: don't skip them.
        if (!s.complete && scope != ChatScope.All) return
        val firstLive = s.entries.firstOrNull { s.isLive(it) }?.id ?: Long.MAX_VALUE
        // Only ids known to be messages of this room are sent as the marker.
        var target = if (upTo < firstLive) upTo else 0L
        for (e in s.entries) if (e.id < firstLive && e.id > target) target = e.id
        if (target <= marker()) return
        try {
            client.markRead(token, target)
            session.rooms.applyRead(token, target)
            _state.update { it.copy(entries = it.entries.filter { e -> e.id > target }) }
        } catch (e: TalkError) {
            // Stays unread on the server; the local memory still hides it for this session.
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
    }
}
