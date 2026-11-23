package talq.app.call

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import talq.app.appContainer

/**
 * The notification buttons that show no screen: Decline on a ringing call, Hang up on a running
 * one. (Answer opens [CallActivity] directly: Android does not let a receiver start an activity
 * from a notification.) Not exported.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        when (intent.action) {
            ACTION_DECLINE -> {
                // Kept alive until the server's notification is dismissed (that can start the app).
                val pending = goAsync()
                container.ringer.decline(intent.getLongExtra(EXTRA_NID, 0L)) { pending.finish() }
            }
            ACTION_HANG_UP -> {
                // "Connecting…": nothing is placed; a running call is hung up.
                container.calls.cancelStart()
                container.calls.hangUp()
            }
        }
    }

    companion object {
        private const val ACTION_DECLINE = "talq.app.call.DECLINE"
        private const val ACTION_HANG_UP = "talq.app.call.HANG_UP"
        private const val EXTRA_NID = "talq.call.nid"
        private const val EXTRA_TOKEN = "talq.call.token"

        fun decline(context: Context, nid: Long, token: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, (nid.toInt() shl 3) or 5,
                CallNotifications.receiverIntent(context, ACTION_DECLINE)
                    .putExtra(EXTRA_NID, nid)
                    .putExtra(EXTRA_TOKEN, token),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun hangUp(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, CallNotifications.ONGOING_ID + 1,
                CallNotifications.receiverIntent(context, ACTION_HANG_UP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
