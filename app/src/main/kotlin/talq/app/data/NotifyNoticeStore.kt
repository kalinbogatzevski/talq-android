package talq.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "notifications are off" line of the conversation list that was dismissed: which account's, and for
 * which state (`NotifyProblem.state`). One at a time, kept on the phone with the list's other notices. It
 * holds until that state changes: [now] with another one, or with none, ends it, so the next problem shows
 * (also the same one, when it comes back after it was fixed).
 */
class NotifyNoticeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("notices", Context.MODE_PRIVATE)
    private val _dismissed = MutableStateFlow(
        try {
            prefs.getString(KEY, null)
        } catch (_: Exception) {
            // An unreadable value means nothing was dismissed: the line shows.
            null
        },
    )

    /** What was dismissed, for [isDismissed]. */
    val dismissed: StateFlow<String?> = _dismissed.asStateFlow()

    fun isDismissed(dismissed: String?, account: String, state: String): Boolean = dismissed == entry(account, state)

    fun dismiss(account: String, state: String) = put(entry(account, state))

    /** The state is [state] (null = all is well), known for sure: a dismissal of anything else is over. */
    fun now(account: String, state: String?) {
        val held = _dismissed.value ?: return
        if (state == null || held != entry(account, state)) clear()
    }

    /** Over, or signed out: nothing of the account stays behind. */
    fun clear() = put(null)

    private fun put(value: String?) {
        if (_dismissed.value == value) return
        _dismissed.value = value
        prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.apply()
    }

    private fun entry(account: String, state: String) = "$account|$state"

    private companion object {
        const val KEY = "notify_dismissed"
    }
}
