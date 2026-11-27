package talq.app.data

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import talq.core.model.Room
import talq.core.repo.SavedList
import talq.core.repo.TalkSession

/**
 * The conversation list kept for the next start of the app (the core's [SavedList] says what of it and what
 * for). One file, `filesDir/list.sealed`, of ONE account: zipped, sealed with a Keystore key of its own, out
 * of backups (data_extraction_rules.xml). Every sign-out ([clear]) deletes it before it returns and ends the
 * old session's writing. Nothing of it goes to the log: a decoding error quotes what it read.
 *
 * Written off the main thread, whole or not at all, and not for every reload: a moment after a session's first
 * list, then at most every [LATER_MILLIS], at once when a conversation is gone (it must not come back at the
 * next start) or the app leaves the screen ([leaving]), and only when something of it changed.
 */
class SavedListStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "list.sealed")
    private val sealed = SealedText("talq.list.v1", MAX_BYTES, MAX_PLAIN_BYTES)
    private val lock = Any()

    /** Whose writes count: the session opened last and not signed out. */
    private var current: TalkSession? = null

    private val left = MutableStateFlow(0)

    /** What an earlier run of the app kept; null when there is nothing, or nothing that can be read. */
    suspend fun read(): SavedList? = withContext(Dispatchers.IO) {
        try {
            if (file.length() in 1..MAX_BYTES) SavedList.decode(sealed.open(file.readBytes())) else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next write replaces it.
            Log.w(TAG, "the saved list could not be read")
            null
        }
    }

    /** [session] is the one whose list is kept from here on. */
    fun open(session: TalkSession) = synchronized(lock) { current = session }

    /** Keeps the list of [session] for as long as it runs: on the session's own scope, so it ends with it. */
    suspend fun keep(session: TalkSession) {
        session.rooms.state.first { it.loaded }
        val rooms = session.rooms.rooms
        var written: Written? = null
        var wait = SOON_MILLIS
        rooms.collect {
            val before = left.value
            withTimeoutOrNull(wait) {
                combine(rooms, left) { list, now -> now != before || written?.lacks(list) == true }.first { it }
            }
            wait = LATER_MILLIS
            written = withContext(Dispatchers.IO) { write(session, rooms.value, written) } ?: written
        }
    }

    /** What the file holds: the conversations, and [stamp], the length and hash of its text (null: there is no file). */
    private class Written(val tokens: Set<String>, val stamp: Pair<Int, Int>?) {
        fun lacks(list: List<Room>): Boolean = list.mapTo(HashSet()) { it.token }.let { now -> tokens.any { it !in now } }
    }

    /** The app leaves the screen: what is waiting to be written is written now. */
    fun leaving() = left.update { it + 1 }

    /** Signed out: nothing of the account stays, and nothing more of it is written. */
    fun clear() = synchronized(lock) {
        current = null
        delete()
    }

    /** Nothing is touched when the text is the one written last ([last]); an empty list is no file. Null: not written. */
    private fun write(session: TalkSession, list: List<Room>, last: Written?): Written? = try {
        val text = SavedList.encode(session, list)
        val now = Written(list.mapTo(HashSet()) { it.token }, text?.let { it.length to it.hashCode() })
        if (last != null && last.stamp == now.stamp) {
            now
        } else {
            val bytes = text?.let { sealed.close(it) }
            synchronized(lock) {
                when {
                    current !== session -> null
                    bytes == null -> {
                        delete()
                        now
                    }
                    else -> now.takeIf { writeWhole(file, bytes) }
                }
            }
        }
    } catch (_: Exception) {
        Log.w(TAG, "the list could not be saved")
        null
    }

    private fun delete() {
        file.delete()
        tempOf(file).delete()
    }

    private companion object {
        const val TAG = "SavedListStore"
        const val SOON_MILLIS = 2_000L
        const val LATER_MILLIS = 30_000L

        /** The core writes at most a million characters. */
        const val MAX_PLAIN_BYTES = 4 * 1024 * 1024
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
