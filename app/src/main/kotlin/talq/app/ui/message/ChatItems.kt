package talq.app.ui.message

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import talq.app.ui.common.UiLocale
import talq.app.files.PendingUpload
import talq.core.model.ChatMessage
import talq.core.repo.OutboxMessage

/** One row of the message stream. [key] is stable across reloads. */
sealed interface ChatItem {
    val key: String

    data class Day(val label: String, override val key: String) : ChatItem

    data object NewMessages : ChatItem {
        override val key = "new-messages"
    }

    /**
     * @param startsGroup first of a run by one sender: shows the avatar and name
     * @param endsGroup last of the run: closes the group with a little air
     */
    data class Message(
        val message: ChatMessage,
        val mine: Boolean,
        val startsGroup: Boolean,
        val endsGroup: Boolean,
    ) : ChatItem {
        override val key = "m${message.id}"
    }

    data class System(val message: ChatMessage) : ChatItem {
        override val key = "m${message.id}"
    }

    data class Pending(val outbox: OutboxMessage) : ChatItem {
        override val key = "o${outbox.referenceId}"
    }

    /** A file on its way out (or one that did not make it). */
    data class PendingFile(val upload: PendingUpload) : ChatItem {
        override val key = "f${upload.id}"
    }
}

/** Messages from one sender closer together than this collapse into one group. */
private const val GROUP_SECONDS = 5 * 60

/**
 * The stream, oldest first: day separators, the "New messages" divider after the read marker,
 * grouped bubbles, then whatever is still on its way out (texts, then files).
 *
 * @param dividerAfter id of the last message that was read when the chat was opened (0 = no divider)
 * @param hideTopicNotices drop the "created topic" notice (inside the topic it says nothing new)
 */
fun buildChatItems(
    messages: List<ChatMessage>,
    outbox: List<OutboxMessage>,
    isMe: (ChatMessage) -> Boolean,
    dividerAfter: Long,
    hideTopicNotices: Boolean,
    files: List<PendingUpload> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): List<ChatItem> {
    val shown = messages.filter { !it.isUpdateSignal && !(hideTopicNotices && it.isTopicCreatedNotice) }
    val out = ArrayList<ChatItem>(shown.size + outbox.size + 8)
    var lastDay: LocalDate? = null
    var dividerPlaced = dividerAfter <= 0L
    var previous: ChatMessage? = null
    var previousIndex = -1

    for (m in shown) {
        var broke = false
        val day = Instant.ofEpochSecond(m.timestamp).atZone(zone).toLocalDate()
        if (day != lastDay) {
            // Keyed by the message under it: timestamps are not guaranteed to rise with the
            // ids, so the same day can come round twice, and list keys must be unique.
            out.add(ChatItem.Day(dayLabel(day, today), "d$day-${m.id}"))
            lastDay = day
            broke = true
        }
        if (!dividerPlaced && m.id > dividerAfter) {
            dividerPlaced = true
            // Only worth a divider when something of someone else's is below it.
            if (!isMe(m) && previous != null) {
                out.add(ChatItem.NewMessages)
                broke = true
            }
        }
        if (m.isSystem) {
            out.add(ChatItem.System(m))
            previous = null
            previousIndex = -1
            continue
        }
        val p = previous
        val continues = !broke && p != null && p.actorType == m.actorType && p.actorId == m.actorId &&
            m.timestamp - p.timestamp < GROUP_SECONDS && p.topicId == m.topicId
        if (continues && previousIndex >= 0) {
            out[previousIndex] = (out[previousIndex] as ChatItem.Message).copy(endsGroup = false)
        }
        out.add(ChatItem.Message(m, isMe(m), startsGroup = !continues, endsGroup = true))
        previous = m
        previousIndex = out.lastIndex
    }
    for (o in outbox) out.add(ChatItem.Pending(o))
    for (f in files) out.add(ChatItem.PendingFile(f))
    return out
}

private fun dayLabel(day: LocalDate, today: LocalDate): String {
    val locale = UiLocale
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale))
        else -> day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))
    }
}
