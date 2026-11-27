package talq.app.push

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import talq.app.Brand
import talq.app.appContainer
import talq.app.MainActivity
import talq.app.R
import talq.app.nav.DeepLink
import talq.app.share.ChatShortcuts

/**
 * Which alert (channel) a notification belongs to; the user tunes sound and importance of each in Android's
 * settings. What is what is decided in `UnifiedPushRegistrar.kindOf`.
 */
enum class NotificationKind {
    /** A message in a one-to-one chat, one that mentions the user by name, or an answer to the user. */
    Direct,

    /** Everything else: groups, @all, reactions, invitations, reminders; also what is not known (yet). */
    Other,

    /** Missed calls (ringing has its own channel, see talq.app.call.CallNotifications). */
    Call,
}

/** How a [NotificationKind] alerts on this phone, as the user has set it. */
enum class AlertState { On, Silent, Off }

/** What a notification that is put up does about its sound. */
enum class Alert {
    /** A new one sounds; one that is up already is changed without a sound. */
    Once,

    /** No sound (and no pop-up) yet: it is not known which alert it belongs to. [Now] follows. */
    Later,

    /** It sounds, also when it is up already: the sound [Later] held back. */
    Now,
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
    /** False while [kind] may still turn out to be [NotificationKind.Direct] (the push does not say who is mentioned). */
    val kindKnown: Boolean = true,
    /** Of a conversation marked sensitive: neither its name nor its picture is shown (the server leaves the text out). */
    val sensitive: Boolean = false,
)

/**
 * Android notifications for server notifications: one per server notification (so a delete
 * push can remove exactly that one), bundled per conversation under a summary.
 *
 * Two alerts for messages, "Direct messages and mentions" and "Other messages": the channels that were
 * "Mentions" and "Messages" until 0.10.0, renamed, so what the user set for them stays. Everything here finds
 * its notifications by tag, group and extras, never by channel: a conversation's bundle may hold both.
 */
object TalqNotifications {
    private const val TAG_ITEM = "talq.item"
    private const val TAG_SUMMARY = "talq.summary"
    private const val GROUP_PREFIX = "talq.room."
    private const val GROUP_OTHER = "talq.other"
    private const val ACCENT = 0xFF21E3C8.toInt()

    /** The notifications' colour: the organisation's (organisation mode), else the app's teal. */
    fun accentColor(context: Context): Int =
        context.appContainer.organisation.state.value?.takeIf { !Brand.isBranded }?.let { 0xFF000000.toInt() or it.accent } ?: ACCENT

    /** Missed calls; the message channels' ids come from [talq.app.sound.SoundStore]. */
    private const val CHANNEL_CALLS = "calls"
    private const val EXTRA_TOPIC = "talq.topic"
    private const val EXTRA_CHAT = "talq.chat"
    private const val EXTRA_TOPIC_UNKNOWN = "talq.topicUnknown"
    private const val EXTRA_MESSAGE = "talq.message"

    /** On an item that shows the user's own reply on its way (or not sent): the text's outbox key. */
    private const val EXTRA_REPLY = "talq.reply"

    /** The message view that is on screen right now. [bucket]: null = whole chat, 0 = General, else a topic id. */
    data class OpenChat(val token: String, val bucket: Long?) {
        /** [topicKnown] false: [threadId] 0 says nothing; only the whole chat shows the message for sure. */
        fun covers(token: String, threadId: Long, topicKnown: Boolean): Boolean =
            token == this.token && (bucket == null || (topicKnown && bucket == threadId))
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
                open.covers(
                    token,
                    it.notification.extras.getLong(EXTRA_TOPIC, 0L),
                    topicKnown = !it.notification.extras.getBoolean(EXTRA_TOPIC_UNKNOWN, false),
                )
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
     * The channel a kind of notification goes to now: the message channels get a new id when
     * the user picks another message sound (see [talq.app.sound.SoundStore]).
     */
    fun channelId(context: Context, kind: NotificationKind): String {
        val sounds = context.appContainer.sounds
        return when (kind) {
            NotificationKind.Direct -> sounds.directChannel
            NotificationKind.Other -> sounds.otherChannel
            NotificationKind.Call -> CHANNEL_CALLS
        }
    }

    /** How [kind] alerts on this phone now: the app's notifications as a whole, then its channel. */
    fun alertState(context: Context, kind: NotificationKind): AlertState {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return AlertState.Off
        // A sound picked a moment ago has given it a new channel that nothing has used yet.
        ensureChannels(context)
        val channel = manager.getNotificationChannelCompat(channelId(context, kind)) ?: return AlertState.On
        return when {
            channel.importance == NotificationManagerCompat.IMPORTANCE_NONE -> AlertState.Off
            channel.importance < NotificationManagerCompat.IMPORTANCE_DEFAULT -> AlertState.Silent
            channel.sound == null && !channel.shouldVibrate() -> AlertState.Silent
            else -> AlertState.On
        }
    }

    /**
     * The two message alerts sound and pop up alike, so it does not matter on which one a message alerts.
     * They do until the user sets one of them differently.
     */
    fun messagesAlertAlike(context: Context): Boolean {
        ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        val direct = manager.getNotificationChannelCompat(channelId(context, NotificationKind.Direct)) ?: return true
        val other = manager.getNotificationChannelCompat(channelId(context, NotificationKind.Other)) ?: return true
        return direct.importance == other.importance && direct.sound == other.sound &&
            direct.shouldVibrate() == other.shouldVibrate() &&
            direct.vibrationPattern.contentEquals(other.vibrationPattern) &&
            // One of them may get through Do not disturb.
            direct.canBypassDnd() == other.canBypassDnd()
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
            if (withSound && sounds.messagePicked) {
                builder.setSound(sounds.uriOf(picked, ring = false), sounds.attributes(ring = false))
            }
            manager.createNotificationChannel(builder.build())
        }
        // Both as loud as messages always were. Never a lower importance here: Android takes it over for a
        // channel the user has not touched, and a direct message must not stop popping up with an update.
        channel(sounds.directChannel, "Direct messages and mentions", "One-to-one chats, your name, and answers to you", withSound = true)
        channel(sounds.otherChannel, "Other messages", "Groups, @all and reactions", withSound = true)
        channel(CHANNEL_CALLS, "Missed calls", "Calls you did not answer, and calls that came while you were in another call", withSound = false)
    }

    /** [buttons]: what its "Reply" and "Mark as read" act on; null = it has none (see [MessageActions.offered]). */
    // The permission is checked through areNotificationsEnabled(), which lint cannot see.
    @SuppressLint("MissingPermission")
    fun show(
        context: Context,
        content: NotificationContent,
        roomUnread: Int = 0,
        buttons: MessageTarget? = null,
        alert: Alert = Alert.Once,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        // Only what is about a message: a reminder or an invitation is not read there.
        if (content.aboutChat && openChat?.covers(content.token, content.threadId, content.topicKnown) == true) {
            // Being read right now. Also takes down the first, undetailed version of it.
            cancel(context, listOf(content.nid))
            return
        }
        if (alertState(context, content.kind) == AlertState.Off) {
            // Switched off by the user. Its first version may be up on the other alert: that one goes too.
            cancel(context, listOf(content.nid))
            return
        }

        val channel = channelId(context, content.kind)
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
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_talq)
            .setColor(accentColor(context))
            .setContentTitle(conversation)
            .setContentText(if (content.sender.isNotEmpty() && content.isGroup) "${content.sender}: ${content.text}" else content.text)
            .setWhen(content.whenMillis)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(alert != Alert.Now)
            .setSilent(alert == Alert.Later)
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
                    putLong(EXTRA_MESSAGE, content.messageId)
                },
            )
        if (buttons != null) builder.addButtons(context, buttons)
        // Android 11+: among the phone's conversations, with the picture of its shortcut, where it has one.
        val shortcut = if (content.aboutChat && !content.sensitive) ChatShortcuts.heldId(context, context.appContainer.gate.account(), content.token) else null
        if (shortcut != null) builder.setShortcutId(shortcut).setLocusId(LocusIdCompat(shortcut))

        if (content.sender.isNotEmpty()) {
            // A conversation-shaped notification: sender as a person, room (and topic) as title.
            val me = Person.Builder().setName("You").build()
            // The shortcut of a one-to-one chat names that person by its own id.
            val key = shortcut?.takeIf { !content.isGroup } ?: content.sender
            val sender = Person.Builder().setName(content.sender).setKey(key).build()
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
        val under = items(context).filter { it.notification.group == group && it.id != content.nid.toInt() }
        val summary = NotificationCompat.Builder(context, headerChannel(context, under, channel))
            .setSmallIcon(R.drawable.ic_stat_talq)
            .setColor(accentColor(context))
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
     * The channel of a bundle's header: the loudest one of the items [under] it ([own]: of the one being put
     * up). On a quieter one the header would take a direct message down among the silent ones, and on one that
     * is switched off it would not be there at all.
     */
    private fun headerChannel(context: Context, under: List<StatusBarNotification>, own: String? = null): String {
        val manager = NotificationManagerCompat.from(context)
        val direct = channelId(context, NotificationKind.Direct)
        val ids = under.mapNotNull { NotificationCompat.getChannelId(it.notification) } + listOfNotNull(own)
        return ids.distinct().maxWithOrNull(
            compareBy(
                { manager.getNotificationChannelCompat(it)?.importance ?: NotificationManagerCompat.IMPORTANCE_NONE },
                { it == direct },
            ),
        ) ?: direct
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

    // ------------------------------------------------------------------ the buttons (see MessageActions)

    private fun NotificationCompat.Builder.addButtons(context: Context, target: MessageTarget) {
        if (target.canReply) {
            addAction(
                // No authentication asked for: whether a locked phone takes a reply is the phone's own setting.
                NotificationCompat.Action.Builder(0, "Reply", MessageActionReceiver.reply(context, target))
                    .addRemoteInput(RemoteInput.Builder(MessageActionReceiver.KEY_TEXT).setLabel("Reply").build())
                    // Only what the user types: no suggested answer that one stray tap would send.
                    .setAllowGeneratedReplies(false)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        if (target.canMarkRead) {
            addAction(
                NotificationCompat.Action.Builder(0, "Mark as read", MessageActionReceiver.markRead(context, target))
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
    }

    /** A button's work runs: [line] beside the app's name, and no buttons (a second tap has nothing to hit). */
    fun working(context: Context, nid: Long, line: String) {
        repost(context, nid) {
            clearActions()
            setSubText(line)
        }
    }

    /** The work is over and the message still there: its buttons are back, [line] says why (null = nothing to say). */
    fun ready(context: Context, target: MessageTarget, line: String?) {
        repost(context, target.nid) {
            clearActions()
            addButtons(context, target)
            setSubText(line)
        }
    }

    /**
     * The user's reply [text] under the message, with [line] on how it stands. [ref]: its outbox key; null =
     * it could not be kept anywhere (then the buttons stay, and the text can at least still be read here).
     */
    fun replying(context: Context, target: MessageTarget, ref: String?, text: String, line: String) {
        repost(context, target.nid) { old ->
            clearActions()
            if (ref == null) addButtons(context, target) else addExtras(Bundle().apply { putString(EXTRA_REPLY, ref) })
            setSubText(line)
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(old)
            if (style != null) setStyle(style.addMessage(text, System.currentTimeMillis(), null as Person?))
            else setRemoteInputHistory(arrayOf<CharSequence>(text))
        }
    }

    /** How the reply [ref] stands, while its item is up. */
    fun replyLine(context: Context, nid: Long, ref: String, line: String) {
        if (replyOn(shown(context, nid)) == ref) repost(context, nid) { setSubText(line) }
    }

    /** The reply [ref] is not on its way any more: its item goes. */
    fun replyGone(context: Context, nid: Long, ref: String) {
        if (replyOn(shown(context, nid)) == ref) cancel(context, listOf(nid))
    }

    /**
     * The reply [ref] is in [token]: its item goes, with the older ones of its topic, as "Mark as read" does it. Not what
     * came since, nor another topic's: nobody has read those.
     */
    fun replied(context: Context, token: String, nid: Long, ref: String) {
        val extras = shown(context, nid)?.takeIf { replyOn(it) == ref }?.extras ?: return
        // In one go: what was just cancelled may still be listed, and would keep the bundle header up.
        cancelRead(context, token, extras.getLong(EXTRA_TOPIC, 0L), extras.getLong(EXTRA_MESSAGE, 0L), nid)
    }

    /**
     * Take down the messages of [token] that are read now: those up to message [upTo], of topic [topicId]
     * (null = of any), and item [nid] itself. A reply on its way, or not sent, stays.
     */
    fun cancelRead(context: Context, token: String, topicId: Long?, upTo: Long, nid: Long = 0L) {
        val read = items(context).filter {
            val extras = it.notification.extras
            it.id == nid.toInt() || (
                it.notification.group == GROUP_PREFIX + token && extras.getBoolean(EXTRA_CHAT, false) &&
                    replyOn(it.notification) == null && extras.getLong(EXTRA_MESSAGE, 0L) in 1..upTo &&
                    (topicId == null || (!extras.getBoolean(EXTRA_TOPIC_UNKNOWN, false) && extras.getLong(EXTRA_TOPIC, 0L) == topicId))
                )
        }
        val manager = NotificationManagerCompat.from(context)
        read.forEach { manager.cancel(it.tag, it.id) }
        pruneSummaries(context, read.map { it.id }.toSet())
    }

    private fun items(context: Context): List<StatusBarNotification> = try {
        context.getSystemService(NotificationManager::class.java)?.activeNotifications.orEmpty().filter { it.tag == TAG_ITEM }
    } catch (_: RuntimeException) {
        emptyList()
    }

    /** Whether the notification [nid] is up: not read elsewhere, swiped off or taken down meanwhile. */
    fun isUp(context: Context, nid: Long): Boolean = shown(context, nid) != null

    private fun shown(context: Context, nid: Long): Notification? = items(context).firstOrNull { it.id == nid.toInt() }?.notification

    private fun replyOn(shown: Notification?): String? = shown?.extras?.getString(EXTRA_REPLY)

    /**
     * Put item [nid] up again, changed and without a sound. One that is not up (any more) stays away: read
     * elsewhere or swiped off, it must not come back.
     */
    @SuppressLint("MissingPermission")
    private fun repost(context: Context, nid: Long, change: NotificationCompat.Builder.(Notification) -> Unit) {
        val old = shown(context, nid) ?: return
        val builder = NotificationCompat.Builder(context, old).setOnlyAlertOnce(true).setSilent(true)
        old.extras.getCharSequenceArray(NotificationCompat.EXTRA_REMOTE_INPUT_HISTORY)?.let { builder.setRemoteInputHistory(it) }
        builder.change(old)
        NotificationManagerCompat.from(context).notify(TAG_ITEM, nid.toInt(), builder.build())
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

    /**
     * Remove bundle headers that have no notification left under them; a header whose loudest notification
     * has just gone moves to the channel of what is left.
     */
    @SuppressLint("MissingPermission")
    private fun pruneSummaries(context: Context, justCancelled: Set<Int>) {
        val platform = context.getSystemService(NotificationManager::class.java) ?: return
        val active = try {
            platform.activeNotifications
        } catch (_: RuntimeException) {
            return
        }
        // Cancelling is asynchronous: what was just cancelled may still be listed.
        val live = active.filter { it.tag == TAG_ITEM && it.id !in justCancelled }.groupBy { it.notification.group.orEmpty() }
        for (header in active) {
            if (header.tag != TAG_SUMMARY) continue
            val under = live[header.notification.group.orEmpty()]
            if (under == null) {
                platform.cancel(header.tag, header.id)
                continue
            }
            val channel = headerChannel(context, under)
            if (channel == NotificationCompat.getChannelId(header.notification)) continue
            NotificationManagerCompat.from(context).notify(
                header.tag, header.id,
                NotificationCompat.Builder(context, header.notification).setChannelId(channel).setSilent(true).build(),
            )
        }
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
