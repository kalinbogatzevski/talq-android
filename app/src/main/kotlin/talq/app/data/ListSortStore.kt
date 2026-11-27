package talq.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import talq.core.model.RoomFilter
import talq.core.model.RoomSort

private val Context.listSortDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "list_sort",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * How the conversation list is sorted and what of it is shown, on this device for every account (as on
 * desktop): signing out keeps both. Read into memory once at start, without waiting for it; until then
 * ([known] is false) they are [RoomSort.Recent] and [RoomFilter.All]. Main thread.
 */
class ListSortStore(context: Context, private val scope: CoroutineScope) {
    private val store = context.applicationContext.listSortDataStore
    private val _sort = MutableStateFlow(RoomSort.Recent)
    private val _filter = MutableStateFlow(RoomFilter.All)
    private val _known = MutableStateFlow(false)
    private var sortPicked = false
    private var filterPicked = false

    val sort: StateFlow<RoomSort> = _sort.asStateFlow()
    val filter: StateFlow<RoomFilter> = _filter.asStateFlow()

    /** Both were read (or could not be): until then the list is not drawn, so never in another way first. */
    val known: StateFlow<Boolean> = _known.asStateFlow()

    init {
        scope.launch {
            val read = try {
                store.data.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the list's order could not be read", e)
                emptyPreferences()
            }
            // What was picked while the file was being read wins.
            if (!sortPicked) _sort.value = RoomSort.fromId(read[SORT])
            if (!filterPicked) _filter.value = RoomFilter.fromId(read[FILTER])
            _known.value = true
        }
    }

    fun set(choice: RoomSort) {
        sortPicked = true
        _sort.value = choice
        write(SORT, choice.id)
    }

    fun set(choice: RoomFilter) {
        filterPicked = true
        _filter.value = choice
        write(FILTER, choice.id)
    }

    private fun write(key: Preferences.Key<Int>, id: Int) {
        scope.launch {
            try {
                store.edit { it[key] = id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only a convenience: the list is as before after a restart.
                Log.w(TAG, "the list's order could not be written", e)
            }
        }
    }

    private companion object {
        const val TAG = "ListSortStore"

        /** Desktop's names for them (`Sidebar/sortMode`, `Sidebar/filterMode`). */
        val SORT = intPreferencesKey("sortMode")
        val FILTER = intPreferencesKey("filterMode")
    }
}
