package talq.core.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import talq.core.TalkError
import talq.core.model.ChatMessage
import talq.core.model.NotificationLevel
import talq.core.model.Room
import kotlin.concurrent.Volatile

// How many of a conversation's unread messages were muted: the grey number beside the coloured one.
// The server counts a room's unread messages without knowing its topics, so the part in topics set to
// Never is read off the chat's newest messages. The rules are TalQ for desktop's (ConversationListModel
// `silencedUnreadOf`, `reclassifySilenced`; ToastGatePolicy `silencedUnreadCount`):
//  - only rooms with unread messages, not on Never themselves, with a topic known to be on Never;
//  - one read of the newest messages (it marks nothing as read), 20 to 100 rows, at most 50 of them
//    judged: what is older stays in the coloured number;
//  - asked again only when the room's unread number, read marker, newest message or silenced topics
//    changed; a figure above zero also after 3 minutes;
//  - before anything turns grey the server's own topic list must say Never: a level stored here may
//    have been changed on another device, and a topic the list does not show is not muted;
//  - one request at a time per room, two in all, none in the background or offline (`attach`);
//  - an answer for a room that was read meanwhile, and a figure that no longer fits the room, is not
//    applied. Unknown = not split: a coloured number that is too high is safe, a message that is not
//    muted in the grey one is not. (As on desktop, a figure stands while more came since: when a muted
//    message was deleted at the same time, one that is not muted is grey until the answer, never all.)
//  - a conversation on Never counts for nothing, a topic of it that was switched back on included.
// Decided differences: rows up to the read marker are never judged; a figure keeps the ids it counted, so
// reading on here only takes the read ones off (desktop drops the figure and shows the full number until
// it has asked again); one more message in a topic the server has just called Never is grey at once; what
// was looked at here while the marker could not pass a muted message is taken off (listedRoom: desktop's
// marker passes it); a room whose row names no newest message (a sensitive conversation, one on
// another server) is not split, its answer could not be matched with the row.

/** Where the muted part of one room's unread number stood when it was worked out; see [silencedUnread]. */
class SilencedFigure internal constructor(
    internal val unread: Int,
    internal val lastRead: Long,
    internal val newest: Long,
    /** The topics that were known to be on Never. */
    internal val topics: Set<Long>,
    /** The unread messages that were judged, and those of them in silenced topics. */
    internal val judged: List<Long>,
    internal val silenced: List<Long>,
    /** The topics the server said are on Never when it was asked for this figure. */
    internal val confirmed: Set<Long>,
    /** The phone's clock, milliseconds. */
    internal val at: Long,
    /** False when the server would not say: nothing is split, and it is asked again after a while. */
    internal val known: Boolean,
) {
    /** The unread number [room] has if nothing happened but reading on; none fits a marker that went back. */
    internal fun expectedUnread(room: Room): Int = when {
        room.lastReadMessage == lastRead -> unread
        room.lastReadMessage > lastRead -> judged.count { it > room.lastReadMessage }
        else -> Int.MAX_VALUE
    }

    /** [room]'s newest message has come since, is one Talk counts and not the user's own, in a [confirmed] topic. */
    internal fun silences(room: Room): Boolean {
        val m = room.lastMessage ?: return false
        return m.id > newest && m.topicId in confirmed && counts(m) && !(m.actorType == room.actorType && m.actorId == room.actorId)
    }
}

/** Talk counts [m] as unread: `comment` rows, shared files too. */
private fun counts(m: ChatMessage): Boolean =
    m.systemMessage.isEmpty() && (m.messageType == "comment" || m.messageType == "voice-message")

private fun newestOf(room: Room): Long = room.lastMessage?.id ?: 0L

/**
 * The unread messages of a room, newest first, read off [messages] (a page of its newest, in any order):
 * the newest [unread] rows past [lastRead] that Talk counts (`comment` rows, shared files too) and that
 * are not the user's own. Rows newer than [newest] arrived after the list the number came from.
 */
internal fun unreadRows(
    messages: List<ChatMessage>,
    unread: Int,
    lastRead: Long,
    newest: Long,
    isMine: (ChatMessage) -> Boolean,
): List<ChatMessage> = messages
    .filter { m -> m.id > 0L && m.id > lastRead && m.id <= newest && counts(m) && !isMine(m) }
    .sortedByDescending { it.id }
    .take(maxOf(0, unread))

/**
 * How many of [room]'s unread messages sit in silenced topics, by [figure]: 0 unless it still fits. Fewer
 * unread than it knows of, the same number with another newest message (one went, one came), or a read
 * marker that went back: not applied. More unread than it knew: it stands for the ones it knew, and when
 * the one that came since is muted for sure ([SilencedFigure.silences]), that one is counted too.
 */
internal fun silencedUnread(room: Room, figure: SilencedFigure?): Int {
    if (figure == null || room.unreadMessages <= 0) return 0
    val expected = figure.expectedUnread(room)
    if (room.unreadMessages < expected) return 0
    if (room.unreadMessages == expected && newestOf(room) != figure.newest) return 0
    val since = if (room.unreadMessages - expected == 1 && figure.silences(room)) 1 else 0
    return minOf(figure.silenced.count { it > room.lastReadMessage } + since, room.unreadMessages)
}

/** The muted part of [room]'s unread number: all of it in a room on Never, else [silencedUnread]. */
fun mutedUnread(room: Room, figure: SilencedFigure?): Int =
    if (room.notificationLevel == NotificationLevel.NEVER) maxOf(0, room.unreadMessages) else silencedUnread(room, figure)

/** A conversation as the list counts it: [muted] of [room]'s unread messages are grey, the others coloured. */
class ListedRoom internal constructor(val room: Room, val muted: Int)

/**
 * [room] as the list counts it. Where a topic is muted, one unread message in it holds the server's read
 * marker, and everything read after it in other tabs stays in the server's number: what was looked at in
 * this session ([here], the room's unread tracker) is taken off.
 */
fun listedRoom(room: Room, figure: SilencedFigure?, here: UnreadState?): ListedRoom {
    val muted = mutedUnread(room, figure)
    if (figure == null || here == null || room.notificationLevel == NotificationLevel.NEVER) return ListedRoom(room, muted)
    val read = here.readAhead(room)
    if (read.isEmpty()) return ListedRoom(room, muted)
    val readMuted = if (muted > 0) read.count { it in figure.silenced } else 0
    val loud = maxOf(0, room.unreadMessages - muted - (read.size - readMuted))
    val grey = maxOf(0, muted - readMuted)
    return ListedRoom(room.copy(unreadMessages = loud + grey), grey)
}

/** The figures of all conversations of the account; the rules are at the top of this file. */
class SilencedUnread internal constructor(private val session: TalkSession) {
    private val _figures = MutableStateFlow<Map<String, SilencedFigure>>(emptyMap())

    /** Room token -> its figure; only rooms that were asked about. Judge it with [listedRoom]. */
    val figures: StateFlow<Map<String, SilencedFigure>> = _figures.asStateFlow()

    /** Rooms being asked about. */
    private val asking = MutableStateFlow<Set<String>>(emptySet())
    private val permits = Semaphore(MAX_ASKING)

    @Volatile private var silencedTopics: (token: String) -> Set<Long> = { emptySet() }
    @Volatile private var mayAsk: () -> Boolean = { false }

    /**
     * What the app knows: the topics of a room stored as Never, and whether a request may go out now (in
     * front, not offline). Both are called from any thread. Nothing is asked before this.
     */
    fun attach(silencedTopics: (token: String) -> Set<Long>, mayAsk: () -> Boolean) {
        this.silencedTopics = silencedTopics
        this.mayAsk = mayAsk
    }

    /** The conversation list was reloaded, or stored topic levels changed: ask about what is not known any more. */
    fun recheck() {
        if (!session.supportsTopics) return
        val wanted = HashMap<String, Set<Long>>()
        for (room in session.rooms.rooms.value) {
            if (!splits(room)) continue
            val topics = silencedTopics(room.token)
            if (topics.isNotEmpty()) wanted[room.token] = topics
        }
        // Nothing to split any more, or a topic that is not on Never any more: its messages are coloured at once.
        _figures.update { all -> all.filter { (token, figure) -> wanted[token]?.containsAll(figure.topics) == true } }
        if (!mayAsk()) return
        for ((token, topics) in wanted) {
            val room = session.rooms.room(token) ?: continue
            if (!upToDate(room, topics)) ask(token)
        }
    }

    private fun splits(room: Room): Boolean =
        room.unreadMessages > 0 && room.notificationLevel != NotificationLevel.NEVER && newestOf(room) > 0L

    private fun upToDate(room: Room, topics: Set<Long>): Boolean {
        val figure = _figures.value[room.token] ?: return false
        return figure.topics == topics && figure.newest == newestOf(room) &&
            figure.expectedUnread(room) == room.unreadMessages &&
            ((figure.known && figure.silenced.isEmpty()) || now() - figure.at in 0L until VERIFIED_MILLIS)
    }

    private fun ask(token: String) {
        var first = false
        asking.update { tokens ->
            first = token !in tokens
            tokens + token
        }
        // Being asked already: its answer will say, and the next reload of the list looks again.
        if (!first) return
        session.scope.launch {
            try {
                permits.withPermit { workOut(token) }
            } finally {
                asking.update { it - token }
            }
        }
    }

    /** One room, with a permit held. */
    private suspend fun workOut(token: String) {
        // It may have waited for its turn: the room as the list has it now.
        val room = session.rooms.room(token) ?: return
        val topics = silencedTopics(token)
        if (!splits(room) || topics.isEmpty() || upToDate(room, topics) || !mayAsk()) return

        val wanted = minOf(room.unreadMessages, MAX_JUDGED)
        var rows: List<ChatMessage> = emptyList()
        var silenced: Set<Long> = emptySet()
        var stored = topics
        var known = true
        try {
            val page = session.client.history(token, limit = (wanted * 2 + 10).coerceIn(MIN_PAGE, MAX_PAGE))
            rows = unreadRows(page.messages, wanted, room.lastReadMessage, newestOf(room)) { session.isMe(it.actorType, it.actorId) }
            if (rows.any { it.topicId in topics }) {
                // Something would turn grey: the server's word first.
                if (!mayAsk()) return
                val listed = session.client.recentThreads(token).associate { it.id to it.attendee.notificationLevel }
                // Changed here while the answer was on its way: which of the two is newer is not known.
                if (silencedTopics(token) != topics) return
                session.noteTopicLevels(token, listed)
                silenced = listed.filterValues { it == NotificationLevel.NEVER }.keys
                // What that left stored: a topic the list does not show keeps its level.
                stored = topics - listed.keys + silenced
            }
        } catch (e: TalkError.Network) {
            // Unverified: the number stays as it is, the next reload asks again.
            return
        } catch (e: TalkError) {
            known = false
        }

        // Only if the room is still as it was asked about: it may have been read meanwhile, here or elsewhere.
        val current = session.rooms.room(token) ?: return
        if (current.unreadMessages <= 0 || current.lastReadMessage != room.lastReadMessage) return
        val figure = SilencedFigure(
            unread = room.unreadMessages,
            lastRead = room.lastReadMessage,
            newest = newestOf(room),
            topics = stored,
            judged = rows.map { it.id },
            silenced = rows.filter { it.topicId in silenced }.map { it.id },
            confirmed = silenced,
            at = now(),
            known = known,
        )
        _figures.update { it + (token to figure) }
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private companion object {
        /** Request chains at once, over all rooms. */
        const val MAX_ASKING = 2

        const val MAX_JUDGED = 50
        const val MIN_PAGE = 20
        const val MAX_PAGE = 100

        /** How long a figure above zero (or a refusal) stands unasked. */
        const val VERIFIED_MILLIS = 3L * 60 * 1000
    }
}
