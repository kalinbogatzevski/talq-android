package talq.app.call

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import talq.app.appContainer
import talq.app.R
import talq.app.nav.DeepLink
import talq.app.push.NotificationKind
import talq.app.push.TalqNotifications

/**
 * The notifications of calls: the ringing one (CallStyle, full screen over the lock screen, the
 * ringtone until it is answered, declined or stops; it is also the ring service's notification),
 * the one of a running call (the call service's, with Hang up), and missed or busy calls (an
 * ordinary notification that opens the chat, with Call back).
 *
 * The ringing and the running call have fixed ids ([RING_ID], [ONGOING_ID]); missed, busy and
 * group calls are tagged [TAG] with the server notification id, so a delete push for that id
 * can take them down.
 */
object CallNotifications {
    const val TAG = "talq.call"
    const val ONGOING_ID = 0x7a20
    const val RING_ID = 0x7a21
    private const val CHANNEL_ONGOING = "call_ongoing"
    private const val DECLINE_COLOR = 0xFFE5484D.toInt()

    /** Idempotent. The missed-calls channel is the old "calls" one (its sound stays the user's). */
    fun ensureChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        // The ringtone picked in Settings > Sounds (the phone's own by default). A new pick gives
        // the channel a new id: Android fixes a channel's sound when it is made.
        val sounds = context.appContainer.sounds
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(sounds.ringChannel, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Incoming calls")
                .setDescription("Rings while someone calls you")
                .setSound(sounds.uriOf(sounds.ring.value, ring = true), sounds.attributes(ring = true))
                .setVibrationEnabled(true)
                .setVibrationPattern(longArrayOf(0, 800, 600, 800, 600))
                .setShowBadge(false)
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ONGOING, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Running call")
                .setDescription("Shown while you are in a call")
                .setShowBadge(false)
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(TalqNotifications.channelId(context, NotificationKind.Call), NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Missed calls")
                .setDescription("Calls you did not answer, and calls that came while you were in another call")
                .setShowBadge(true)
                .build(),
        )
    }

    /** The ringing notification. [avatar] may still be null (it is posted again once it loads). */
    fun incoming(context: Context, ring: CallRinger.Ringing, avatar: Bitmap?): Notification {
        val person = person(ring.callerName, avatar)
        val answer = PendingIntent.getActivity(
            context, requestCode(ring.nid, 1),
            CallActivity.answerIntent(context, ring.token, ring.nid),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ringScreen = PendingIntent.getActivity(
            context, requestCode(ring.nid, 2),
            CallActivity.ringIntent(context, ring.token, ring.nid),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = CallActionReceiver.decline(context, ring.nid, ring.token)
        val n = NotificationCompat.Builder(context, context.appContainer.sounds.ringChannel)
            .setSmallIcon(R.drawable.ic_stat_talq)
            .setColor(TalqNotifications.accentColor(context))
            .setContentTitle(ring.callerName)
            .setContentText(if (ring.oneToOne == false) "Group call" else "Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            // Not "alert once": an update (the caller's name, their picture) muted that way can
            // stop an insistent ringtone. An insistent sound keeps looping across updates.
            .setContentIntent(ringScreen)
            .setFullScreenIntent(ringScreen, true)
            .addPerson(person)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(person, decline, answer)
                    .setDeclineButtonColorHint(DECLINE_COLOR),
            )
            // A safety net: the ringer takes it down itself after 45 s.
            .setTimeoutAfter(CallRinger.RING_TIMEOUT_MS + 10_000)
            .build()
        // The ringtone loops until the notification goes.
        n.flags = n.flags or Notification.FLAG_INSISTENT
        return n
    }

    /** The running call's notification (the foreground service shows it). */
    fun ongoing(context: Context, peerName: String, video: Boolean, activeSince: Long?): Notification {
        val person = person(peerName, null)
        val open = PendingIntent.getActivity(
            context, ONGOING_ID,
            CallActivity.showIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_talq)
            .setColor(TalqNotifications.accentColor(context))
            .setContentTitle(peerName)
            .setContentText(if (activeSince == null) "Connecting…" else "In a call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addPerson(person)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, CallActionReceiver.hangUp(context)).setIsVideo(video))
        if (activeSince != null) {
            b.setWhen(activeSince).setUsesChronometer(true).setShowWhen(true)
        }
        return b.build()
    }

    /** The call service's notification while a call is being set up. */
    fun connecting(context: Context): Notification = ongoing(context, talq.app.Brand.appName(context), video = false, activeSince = null)

    /** A plain placeholder for a call service that has nothing left to show. */
    fun ended(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_talq)
            .setColor(TalqNotifications.accentColor(context))
            .setContentTitle("Call ended")
            .setSilent(true)
            .build()

    /** "You missed a call" (or, [busy], "called while you were in another call"). [callBack]: offer Call back. */
    @SuppressLint("MissingPermission")
    fun showMissed(context: Context, nid: Long, token: String, callerName: String, busy: Boolean, callBack: Boolean = true) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, requestCode(nid, 3), DeepLink(token, null, null).toIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val callBackIntent = PendingIntent.getActivity(
            context, requestCode(nid, 4), CallActivity.outgoingIntent(context, token, video = false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, TalqNotifications.channelId(context, NotificationKind.Call))
            .setSmallIcon(R.drawable.ic_stat_talq)
            .setColor(TalqNotifications.accentColor(context))
            .setContentTitle(callerName)
            .setContentText(if (busy) "Called you while you were in another call" else "Missed call")
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(open)
        // Placing a call needs the phone unlocked (the call screen shows over the lock screen).
        if (callBack) b.addAction(NotificationCompat.Action.Builder(0, "Call back", callBackIntent).setAuthenticationRequired(true).build())
        val n = b.build()
        manager.notify(TAG, nid.toInt(), n)
    }

    /** Takes down a missed, busy or group call notification. */
    fun cancel(context: Context, nid: Long) {
        NotificationManagerCompat.from(context).cancel(TAG, nid.toInt())
    }

    fun cancelRing(context: Context) {
        NotificationManagerCompat.from(context).cancel(RING_ID)
    }

    /**
     * A group call still running that the user did not answer: quiet (no sound, no pop-up), Join
     * places the user in it, a tap opens the chat. The ringer takes it down when the call is over.
     */
    @SuppressLint("MissingPermission")
    fun showGroupJoin(context: Context, nid: Long, token: String, roomName: String, keepMs: Long) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, requestCode(nid, 3), DeepLink(token, null, null).toIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val join = PendingIntent.getActivity(
            context, requestCode(nid, 5), CallActivity.joinIntent(context, token, video = false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, TalqNotifications.channelId(context, NotificationKind.Call))
            .setSmallIcon(R.drawable.ic_stat_talq)
            .setColor(TalqNotifications.accentColor(context))
            .setContentTitle(roomName.ifBlank { "A conversation" })
            .setContentText("Group call in progress")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setSilent(true)
            // Also when the app's own watch is gone with its process.
            .setTimeoutAfter(keepMs)
            .setAutoCancel(true)
            .setContentIntent(open)
            // Joining needs the phone unlocked (the call screen shows over the lock screen).
            .addAction(NotificationCompat.Action.Builder(0, "Join", join).setAuthenticationRequired(true).build())
            .build()
        manager.notify(TAG, nid.toInt(), n)
    }

    private fun person(name: String, avatar: Bitmap?): Person =
        Person.Builder()
            .setName(name.ifBlank { "Someone" })
            .setImportant(true)
            .apply { if (avatar != null) setIcon(IconCompat.createWithBitmap(avatar)) }
            .build()

    /** One request code per notification and action, so two never share a PendingIntent. */
    private fun requestCode(nid: Long, action: Int): Int = (nid.toInt() shl 3) or action

    /** Explicit intent to our own receiver (two installed TalQ apps never cross). */
    internal fun receiverIntent(context: Context, action: String): Intent =
        Intent(context, CallActionReceiver::class.java).setAction(action)
}
