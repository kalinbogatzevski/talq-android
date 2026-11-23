package talq.app.push

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import talq.app.appContainer
import talq.app.MainActivity
import talq.app.R
import talq.app.nav.DeepLink

/** Which channel a notification goes to. The user tunes sound and importance per channel. */
enum class NotificationKind(val channelId: String) {
    Message("messages"),
    Mention("mentions"),

    /** Missed calls (ringing has its own channel, see talq.app.call.CallNotifications). */
    Call("calls"),
}

/** Everything needed to draw one server notification. */
data class NotificationContent(
    /** The server's notification id; also the Android notification id. */
    val nid: Long,
    val kind: NotificationKind,
    /** Conversation token; empty when the notification is not about a conversation. */
    val token: String,
    /** Topic id, 0 when the message is in no topic (or it is unknown). */
    val threadId: Long,
    /** Message to scroll to, 0 when unknown. */
    val messageId: Long,
    /** Conversation name (or the app name when unknown). */
    val title: String,
    /** Topic title when known, else empty. */
    val topicTitle: String,
    /** Who wrote it; empty when unknown (then [text] is the server's whole subject line). */
    val sender: String,
    val text: String,
    val whenMillis: Long,
    /** More than two people: the sender is worth showing next to the conversation name. */
    val isGroup: Boolean,
    /**
     * About a chat message (a message, mention, reply or reaction): the kind a muted
     * conversation or topic silences. Not a call, an invitation or a reminder the user set.
     */
    val aboutChat: Boolean = false,
    /**
     * False while it is not known which topic the message is in (the push does not say, and
     * the message has not been asked yet or could not be): [threadId] 0 then does not mean
     * "in no topic".
     */
    val topicKnown: Boolean = true,
)

/**
 * Android notifications for server notifications: one per server notification (so a delete
 * push can remove exactly that one), bundled per conversation under a summary.
 */
object TalqNotifications {
    private const val TAG_ITEM = "talq.item"
    private const val TAG_SUMMARY = "talq.summary"
    private const val GROUP_PREFIX = "talq.room."
    private const val GROUP_OTHER = "talq.other"
    private const val ACCENT = 0xFF21E3C8.toInt()
    private const val EXTRA_TOPIC = "talq.topic"
    private const val EXTRA_CHAT = "talq.chat"
    private const val EXTRA_TOPIC_UNKNOWN = "talq.topicUnknown"

    /** The message view that is on screen right now. [bucket]: null = whole chat, 0 = General, else a topic id. */
    data class OpenChat(val token: String, val bucket: Long?) {
        fun covers(token: String, threadId: Long): Boolean = token == this.token && (bucket == null || bucket == threadId)
    }

    /**
     * Set by the chat screen while it is in front. What arrives for that view is not announced
     * (it is already being read), and what was announced for it earlier is taken down.
     */
    @Volatile
    var openChat: OpenChat? = null
        private set

    fun chatOpened(context: Context, token: String, bucket: Long?) {
        val open = OpenChat(token, bucket)
        openChat = open
        val platform = context.getSystemService(NotificationManager::class.java) ?: return
        val active = try {
            platform.activeNotifications
        } catch (_: RuntimeException) {
            return
        }
        val gone = active.filter {
            it.tag == TAG_ITEM && it.notification.group == GROUP_PREFIX + token &&
                open.covers(token, it.notification.extras.getLong(EXTRA_TOPIC, 0L))
        }
        if (gone.isEmpty()) return
        gone.forEach { platform.cancel(it.tag, it.id) }
        pruneSummaries(context, gone.map { it.id }.toSet())
    }

    /**
     * Take down every notification about a chat message that [muted] says yes to;
     * it is asked with the conversation token, the topic id (0 = no topic) and whether the
     * topic is known at all.
     */
    fun cancelWhere(context: Context, muted: (token: String, topicId: Long, topicKnown: Boolean) -> Boolean) {
        val platform = context.getSystemService(NotificationManager::class.java) ?: return
        val active = try {
            platform.activeNotifications
        } catch (_: RuntimeException) {
            return
        }
        val gone = active.filter {
            val group = it.notification.group.orEmpty()
            it.tag == TAG_ITEM && group.startsWith(GROUP_PREFIX) &&
                it.notification.extras.getBoolean(EXTRA_CHAT, false) &&
                muted(
                    group.removePrefix(GROUP_PREFIX),
                    it.notification.extras.getLong(EXTRA_TOPIC, 0L),
                    !it.notification.extras.getBoolean(EXTRA_TOPIC_UNKNOWN, false),
                )
        }
        if (gone.isEmpty()) return
        gone.forEach { platform.cancel(it.tag, it.id) }
        pruneSummaries(context, gone.map { it.id }.toSet())
    }

    fun chatClosed(token: String, bucket: Long?) {
        if (openChat == OpenChat(token, bucket)) openChat = null
    }

    /**
     * The channel a kind of notification goes to now: the message and mention channels get a
     * new id when the user picks another message sound (see [talq.app.sound.SoundStore]).
     */
    fun channelId(context: Context, kind: NotificationKind): String {
        val sounds = context.appContainer.sounds
        return when (kind) {
            NotificationKind.Message -> sounds.messagesChannel
            NotificationKind.Mention -> sounds.mentionsChannel
            NotificationKind.Call -> kind.channelId
        }
    }

    /** Idempotent; cheap enough to call before every notification. */
    fun ensureChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val sounds = context.appContainer.sounds
        val picked = sounds.message.value
        fun channel(id: String, name: String, description: String, withSound: Boolean) {
            val builder = NotificationChannelCompat.Builder(id, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(name)
                .setDescription(description)
                // The launcher may show a dot or a count for these (its choice).
                .setShowBadge(true)
            // The first channels (before any sound was picked) keep the phone's default untouched.
            if (withSound && id != NotificationKind.Message.channelId && id != NotificationKind.Mention.channelId) {
                builder.setSound(sounds.uriOf(picked, ring = false), sounds.attributes(ring = false))
            }
            manager.createNotificationChannel(builder.build())
        }
        channel(sounds.messagesChannel, "Messages", "New messages in your conversations", withSound = true)
        channel(sounds.mentionsChannel, "Mentions", "Messages that mention you", withSound = true)
        channel(NotificationKind.Call.channelId, "Missed calls", "Calls you did not answer, and calls that came while you were in another call", withSound = false)
    }

    // The permission is checked through areNotificationsEnabled(), which lint cannot see.
    @SuppressLint("MissingPermission")
    fun show(context: Context, content: NotificationContent, roomUnread: Int = 0) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (content.kind != NotificationKind.Call && content.token.isNotEmpty() &&
            openChat?.covers(content.token, content.threadId) == true
        ) {
            // Being read right now. Also takes down the first, undetailed version of it.
            cancel(context, listOf(content.nid))
            return
        }
        ensureChannels(context)

        val group = if (content.token.isEmpty()) GROUP_OTHER else GROUP_PREFIX + content.token
        val open = openIntent(context, content.token, content.threadId, content.messageId)
        val tap = PendingIntent.getActivity(
            context, requestCode(content.nid), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val conversation = when {
            content.topicTitle.isNotEmpty() -> "${content.title} · ${content.topicTitle}"
            else -> content.title
        }
        val builder = NotificationCompat.Builder(context, channelId(context, content.kind))
            .setSmallIcon(R.drawable.ic_stat_talq)
            .setColor(ACCENT)
            .setContentTitle(conversation)
            .setContentText(if (content.sender.isNotEmpty() && content.isGroup) "${content.sender}: ${content.text}" else content.text)
            .setWhen(content.whenMillis)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .setGroup(group)
            .setCategory(if (content.kind == NotificationKind.Call) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setNumber(badgeNumber(context, group, content.nid.toInt(), roomUnread))
            .addExtras(
                Bundle().apply {
                    putLong(EXTRA_TOPIC, content.threadId)
                    putBoolean(EXTRA_CHAT, content.aboutChat)
                    putBoolean(EXTRA_TOPIC_UNKNOWN, !content.topicKnown)
                },
            )

        if (content.sender.isNotEmpty()) {
            // A conversation-shaped notification: sender as a person, room (and topic) as title.
            val me = Person.Builder().setName("You").build()
            val sender = Person.Builder().setName(content.sender).setKey(content.sender).build()
            val style = NotificationCompat.MessagingStyle(me)
                .setGroupConversation(content.isGroup)
                .addMessage(content.text, content.whenMillis, sender)
            if (content.isGroup) style.setConversationTitle(conversation)
            builder.setStyle(style)
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
        }
        manager.notify(TAG_ITEM, content.nid.toInt(), builder.build())

        // The bundle header for this conversation. It never makes a sound of its own.
        val summaryTap = PendingIntent.getActivity(
            context, group.hashCode(), openIntent(context, content.token, 0L, 0L),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val summary = NotificationCompat.Builder(context, channelId(context, content.kind))
            .setSmallIcon(R.drawable.ic_stat_talq)
            .setColor(ACCENT)
            .setContentTitle(content.title)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(content.title))
            .setGroup(group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(summaryTap)
            .build()
        manager.notify(TAG_SUMMARY, group.hashCode(), summary)
    }

    /**
     * The number this notification carries for launchers that add up the numbers of an app's
     * notifications: what brings the conversation's notifications together to [roomUnread],
     * its unread messages. At least 1; 1 when the unread number is not known.
     */
    private fun badgeNumber(context: Context, group: String, id: Int, roomUnread: Int): Int {
        if (roomUnread <= 1) return 1
        val others = try {
            context.getSystemService(NotificationManager::class.java)?.activeNotifications.orEmpty()
                .filter { it.tag == TAG_ITEM && it.id != id && it.notification.group == group }
                .sumOf { maxOf(1, it.notification.number) }
        } catch (_: RuntimeException) {
            0
        }
        return (roomUnread - others).coerceAtLeast(1)
    }

    /** A delete push: the server notification is gone (read elsewhere, or dismissed). */
    fun cancel(context: Context, nids: List<Long>) {
        val manager = NotificationManagerCompat.from(context)
        val gone = nids.map { it.toInt() }.toSet()
        gone.forEach { manager.cancel(TAG_ITEM, it) }
        pruneSummaries(context, gone)
    }

    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    /** Remove bundle headers that have no notification left under them. */
    private fun pruneSummaries(context: Context, justCancelled: Set<Int>) {
        val platform = context.getSystemService(NotificationManager::class.java) ?: return
        val active = try {
            platform.activeNotifications
        } catch (_: RuntimeException) {
            return
        }
        // Cancelling is asynchronous: what was just cancelled may still be listed.
        val liveGroups = active
            .filter { it.tag == TAG_ITEM && it.id !in justCancelled }
            .mapNotNull { it.notification.group }
            .toSet()
        active
            .filter { it.tag == TAG_SUMMARY && it.notification.group.orEmpty() !in liveGroups }
            .forEach { platform.cancel(it.tag, it.id) }
    }

    private fun openIntent(context: Context, token: String, threadId: Long, messageId: Long): Intent =
        if (token.isEmpty()) {
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            DeepLink(token, threadId.takeIf { it > 0 }, messageId.takeIf { it > 0 }).toIntent(context)
        }

    private fun requestCode(nid: Long): Int = nid.toInt()
}
