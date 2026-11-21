package talq.app.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import talq.app.MainActivity

/**
 * "Open this conversation (and topic, and message)". The contract between whatever builds a
 * notification and [MainActivity]: build the intent with [toIntent], read it with [fromIntent].
 *
 * - [token]: the conversation token. Required.
 * - [threadId]: the topic to open; null or 0 means the message is in no topic (or there is
 *   no message: then the conversation opens on its remembered tab).
 * - [messageId]: the message the notification was about, to scroll to; null when unknown.
 */
data class DeepLink(
    val token: String,
    val threadId: Long? = null,
    val messageId: Long? = null,
) {
    /**
     * An intent that opens [MainActivity] at this place. Suitable for
     * `PendingIntent.getActivity`: its data URI is different for every distinct deep link, so
     * two notifications never share (and overwrite) one PendingIntent.
     */
    fun toIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN
            data = Uri.Builder().scheme("talq").authority("open")
                .appendPath(token)
                .appendPath((threadId ?: 0L).toString())
                .appendPath((messageId ?: 0L).toString())
                .build()
            putExtra(EXTRA_TOKEN, token)
            putExtra(EXTRA_SECRET, installSecret(context))
            threadId?.takeIf { it > 0 }?.let { putExtra(EXTRA_THREAD_ID, it) }
            messageId?.takeIf { it > 0 }?.let { putExtra(EXTRA_MESSAGE_ID, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    companion object {
        const val ACTION_OPEN = "talq.app.action.OPEN_CONVERSATION"
        const val EXTRA_TOKEN = "talq.extra.TOKEN"
        const val EXTRA_THREAD_ID = "talq.extra.THREAD_ID"
        const val EXTRA_MESSAGE_ID = "talq.extra.MESSAGE_ID"
        private const val EXTRA_SECRET = "talq.extra.SECRET"
        private const val PREFS = "talq_deeplink"
        private const val PREF_SECRET = "secret"

        @Volatile private var cachedSecret: String? = null

        /**
         * A random per-install value only this app knows. [MainActivity] is exported (it is the
         * launcher entry), so any app on the phone can send it an intent; only intents carrying
         * this secret (the ones our own notifications build) are treated as deep links.
         * Kept in app-private storage so notification taps still work after process death.
         */
        private fun installSecret(context: Context): String {
            cachedSecret?.let { return it }
            synchronized(this) {
                cachedSecret?.let { return it }
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val secret = prefs.getString(PREF_SECRET, null) ?: run {
                    val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
                        .also { prefs.edit().putString(PREF_SECRET, it).apply() }
                }
                cachedSecret = secret
                return secret
            }
        }

        fun toIntent(context: Context, token: String, threadId: Long? = null, messageId: Long? = null): Intent =
            DeepLink(token, threadId, messageId).toIntent(context)

        /**
         * The deep link carried by [intent], or null when it is an ordinary launch or an intent
         * that did not come from this app (wrong action or missing/wrong secret).
         */
        fun fromIntent(context: Context, intent: Intent?): DeepLink? {
            if (intent?.action != ACTION_OPEN) return null
            val given = intent.getStringExtra(EXTRA_SECRET) ?: return null
            if (!java.security.MessageDigest.isEqual(given.toByteArray(), installSecret(context).toByteArray())) return null
            val token = intent.getStringExtra(EXTRA_TOKEN)?.takeIf { it.isNotEmpty() } ?: return null
            return DeepLink(
                token = token,
                threadId = intent.getLongExtra(EXTRA_THREAD_ID, 0L).takeIf { it > 0 },
                messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0L).takeIf { it > 0 },
            )
        }
    }
}
