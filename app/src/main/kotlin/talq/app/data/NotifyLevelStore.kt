package talq.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import talq.core.model.NotificationLevel

/**
 * The notification levels the user chose, kept on the phone so a push can be judged before
 * (or without) asking the server: one entry per conversation and per topic whose level is
 * NOT "default". Key: `server|user|token|topicId`, topic id 0 = the conversation itself.
 *
 * A small preferences file, read whole on first use: the push handler needs an answer at
 * once, in a process that may have been started a moment ago. Safe from any thread.
 */
class NotifyLevelStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("notify_levels", Context.MODE_PRIVATE)
    private val lock = Any()
    private var loaded: HashMap<String, Int>? = null

    private val _version = MutableStateFlow(0)

    /** Goes up each time a stored level changes. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun map(): HashMap<String, Int> = synchronized(lock) {
        loaded ?: HashMap<String, Int>().also { m ->
            try {
                for ((k, v) in prefs.all) if (v is Int && k != KEY_ACCOUNT) m[k] = v
            } catch (_: Exception) {
                // An unreadable file means "nothing is known": every level reads as default.
            }
            loaded = m
        }
    }

    /**
     * The account the entries were last written for (`server|user`). A push can arrive before
     * the session has been restored; this is what it is judged against until then.
     */
    var account: String
        get() = try {
            prefs.getString(KEY_ACCOUNT, "").orEmpty()
        } catch (_: Exception) {
            ""
        }
        set(value) {
            if (value != account) prefs.edit().putString(KEY_ACCOUNT, value).apply()
        }

    /** The stored level of a topic ([topicId] > 0) or of the conversation (0); default when none is stored. */
    fun level(account: String, token: String, topicId: Long): Int =
        synchronized(lock) { map()[key(account, token, topicId)] } ?: NotificationLevel.DEFAULT

    /** The topics of one conversation that are stored as "never". */
    fun silencedTopics(account: String, token: String): Set<Long> {
        val prefix = "$account|$token|"
        return synchronized(lock) {
            map().entries.mapNotNullTo(HashSet()) { (k, v) ->
                if (v != NotificationLevel.NEVER || !k.startsWith(prefix)) return@mapNotNullTo null
                k.substring(prefix.length).toLongOrNull()?.takeIf { it > 0 }
            }
        }
    }

    /** Topic levels as the server just reported them (topic id -> level). */
    fun putTopics(account: String, token: String, levels: Map<Long, Int>) {
        if (account.isEmpty() || token.isEmpty()) return
        write(levels.filterKeys { it > 0 }.mapKeys { key(account, token, it.key) })
    }

    /** Conversation levels from a fresh conversation list (token -> level). */
    fun putRooms(account: String, levels: Map<String, Int>) {
        if (account.isEmpty()) return
        write(levels.filterKeys { it.isNotEmpty() }.mapKeys { key(account, it.key, 0L) })
    }

    private fun write(entries: Map<String, Int>) {
        var changed = false
        synchronized(lock) {
            val m = map()
            val editor = prefs.edit()
            for ((k, level) in entries) {
                if (level == NotificationLevel.DEFAULT) {
                    if (m.remove(k) != null) {
                        editor.remove(k)
                        changed = true
                    }
                } else if (m[k] != level) {
                    m[k] = level
                    editor.putInt(k, level)
                    changed = true
                }
            }
            if (changed) editor.apply()
        }
        if (changed) _version.update { it + 1 }
    }

    /** Sign-out: nothing of the account stays behind. */
    fun clear() {
        synchronized(lock) {
            loaded = HashMap()
            prefs.edit().clear().apply()
        }
        _version.update { it + 1 }
    }

    private fun key(account: String, token: String, topicId: Long) = "$account|$token|$topicId"

    private companion object {
        const val KEY_ACCOUNT = "@account"
    }
}
