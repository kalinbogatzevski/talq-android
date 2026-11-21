package talq.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Context.roomTabsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "room_tabs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The tab the user last had open in each conversation, on this device, per account.
 * Read into memory once at start so opening a conversation never waits for the disk;
 * [awaitLoaded] is for the rare open that comes before that read has finished.
 */
class RoomTabStore(context: Context, private val scope: CoroutineScope) {
    private val store = context.applicationContext.roomTabsDataStore
    private val cache = MutableStateFlow<Map<String, Long>?>(null)

    init {
        scope.launch {
            val read = try {
                store.data.first().asMap().mapNotNull { (k, v) -> (v as? Long)?.let { k.name to it } }.toMap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "remembered tabs could not be read", e)
                emptyMap()
            }
            // What was set while the file was being read wins.
            cache.update { (read + it.orEmpty()) }
        }
    }

    val isLoaded: Boolean get() = cache.value != null

    suspend fun awaitLoaded() {
        cache.first { it != null }
    }

    /** The remembered tab key, or null when none is stored (or the store is not loaded yet). */
    fun peek(account: String, token: String): Long? = cache.value?.get(name(account, token))

    fun set(account: String, token: String, tabKey: Long) {
        val name = name(account, token)
        if (cache.value?.get(name) == tabKey) return
        cache.update { it.orEmpty() + (name to tabKey) }
        write { it[longPreferencesKey(name)] = tabKey }
    }

    fun clear(account: String, token: String) {
        val name = name(account, token)
        if (cache.value?.containsKey(name) != true) return
        cache.update { it.orEmpty() - name }
        write { it.remove(longPreferencesKey(name)) }
    }

    private fun write(change: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch {
            try {
                store.edit(change)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only a convenience: the conversation opens on General next time.
                Log.w(TAG, "remembered tab could not be written", e)
            }
        }
    }

    private fun name(account: String, token: String) = "$account|$token"

    private companion object {
        const val TAG = "RoomTabStore"
    }
}
