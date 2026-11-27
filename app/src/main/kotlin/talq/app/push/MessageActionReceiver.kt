package talq.app.push

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.RemoteInput
import talq.app.appContainer

/**
 * The buttons of one message notification act on this. It rides in their intents and never holds any
 * text of a message.
 */
data class MessageTarget(
    /** The server's notification id. */
    val nid: Long,
    /** `server|user` ([talq.app.ui.chat.ComposerDrafts.accountOf]): with another account signed in meanwhile nothing is done. */
    val account: String,
    val token: String,
    /** The topic the message is in, 0 = none. */
    val topicId: Long,
    /** The newest message the notification shows, 0 = unknown. */
    val messageId: Long,
    val canReply: Boolean,
    val canMarkRead: Boolean,
)

/**
 * "Reply" (typed in the notification) and "Mark as read". They open no screen, so they also work on a
 * locked phone, and can start the app. Not exported; the work is in [MessageActions].
 */
class MessageActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = read(intent) ?: return
        val actions = context.appContainer.messageActions
        // The process is kept (about ten seconds at most) until the work is done, or goes on by itself.
        val pending = goAsync()
        when (intent.action) {
            ACTION_REPLY -> {
                val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString().orEmpty()
                actions.reply(target, typed) { pending.finish() }
            }
            ACTION_READ -> actions.markRead(target) { pending.finish() }
            else -> pending.finish()
        }
    }

    companion object {
        private const val ACTION_REPLY = "talq.app.push.REPLY"
        private const val ACTION_READ = "talq.app.push.MARK_READ"

        /** The key of the text the user types in the notification. */
        const val KEY_TEXT = "talq.reply.text"

        private const val EXTRA_NID = "talq.nid"
        private const val EXTRA_ACCOUNT = "talq.account"
        private const val EXTRA_TOKEN = "talq.token"
        private const val EXTRA_TOPIC = "talq.topic"
        private const val EXTRA_MESSAGE = "talq.message"
        private const val EXTRA_CAN_REPLY = "talq.canReply"
        private const val EXTRA_CAN_READ = "talq.canRead"

        /** Mutable, as it must be (the system puts the typed text into it); the intent names our own receiver. */
        fun reply(context: Context, target: MessageTarget): PendingIntent = PendingIntent.getBroadcast(
            context, 0, intent(context, ACTION_REPLY, target),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
        )

        fun markRead(context: Context, target: MessageTarget): PendingIntent = PendingIntent.getBroadcast(
            context, 0, intent(context, ACTION_READ, target),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** One per notification and button, by its action and address (extras do not tell two intents apart). */
        private fun intent(context: Context, action: String, target: MessageTarget): Intent =
            Intent(context, MessageActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.Builder().scheme("talq").authority("notification").appendPath(target.nid.toString()).build())
                // The user is waiting for it.
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(EXTRA_NID, target.nid)
                .putExtra(EXTRA_ACCOUNT, target.account)
                .putExtra(EXTRA_TOKEN, target.token)
                .putExtra(EXTRA_TOPIC, target.topicId)
                .putExtra(EXTRA_MESSAGE, target.messageId)
                .putExtra(EXTRA_CAN_REPLY, target.canReply)
                .putExtra(EXTRA_CAN_READ, target.canMarkRead)

        private fun read(intent: Intent): MessageTarget? {
            val target = MessageTarget(
                nid = intent.getLongExtra(EXTRA_NID, 0L),
                account = intent.getStringExtra(EXTRA_ACCOUNT).orEmpty(),
                token = intent.getStringExtra(EXTRA_TOKEN).orEmpty(),
                topicId = intent.getLongExtra(EXTRA_TOPIC, 0L),
                messageId = intent.getLongExtra(EXTRA_MESSAGE, 0L),
                canReply = intent.getBooleanExtra(EXTRA_CAN_REPLY, false),
                canMarkRead = intent.getBooleanExtra(EXTRA_CAN_READ, false),
            )
            return target.takeIf { it.nid > 0 && it.account.isNotEmpty() && it.token.isNotEmpty() }
        }
    }
}
