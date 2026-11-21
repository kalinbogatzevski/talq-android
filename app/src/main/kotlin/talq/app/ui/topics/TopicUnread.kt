package talq.app.ui.topics

import talq.core.model.TalkThreadInfo
import talq.core.repo.TalkSession
import talq.core.repo.UnreadState

/**
 * What one tab (General or a topic) shows as unread. The one rule for strip chips, topic
 * rows and anything else that says "there is something new here".
 *
 * @param dot unread, but how many is not known (the scan has not reached that far back)
 */
data class TabUnread(val count: Int = 0, val mentioned: Boolean = false, val dot: Boolean = false) {
    val any: Boolean get() = count > 0 || mentioned || dot

    /** The single badge: "@" when mentioned, else the count, else null (a [dot] is drawn, not written). */
    val badge: String?
        get() = when {
            mentioned -> "@"
            count > 99 -> "99+"
            count > 0 -> count.toString()
            else -> null
        }
}

/** General: only what the scan counted. Its newest message is not known without a scan, so no dot. */
fun generalUnread(unread: UnreadState): TabUnread = unread.of(0L).let { TabUnread(it.count, it.mentioned) }

/**
 * A topic: the scanned count, or a dot when the scan did not reach this far back (or has not
 * run yet) and the topic's newest message is past both the room's read marker
 * ([lastRead]) and what was looked at this session, and is not the user's own.
 */
fun topicUnread(session: TalkSession, unread: UnreadState, lastRead: Long, info: TalkThreadInfo): TabUnread {
    val count = unread.of(info.id)
    val newest = maxOf(info.thread.lastMessageId, info.id)
    val dot = count.count == 0 && (!unread.loaded || !unread.complete) &&
        lastRead > 0 && newest > lastRead && newest > unread.seenUpTo(info.id) &&
        info.preview?.let { !session.isMe(it.actorType, it.actorId) } != false
    return TabUnread(count.count, count.mentioned, dot)
}
