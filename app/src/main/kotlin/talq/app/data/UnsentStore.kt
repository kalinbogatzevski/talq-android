package talq.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import talq.core.repo.UnsentKeeper
import talq.core.repo.UnsentText

/**
 * The texts the server has not confirmed yet, kept between two runs of the app (the core's `TextOutbox`
 * has the rules; `uploads.json` is the same for files). One file, `filesDir/unsent.json`, of ONE account:
 * another account's is deleted when an account opens, and every sign-out ([clear]) deletes it before it
 * returns and ends the old session's writing. Out of backups (data_extraction_rules.xml). Never logged:
 * a decoding error quotes what it read, so no exception goes to the log from here.
 */
class UnsentStore(context: Context) {
    @Serializable
    private data class Kept(val account: String, val texts: List<UnsentText>)

    private val file = File(context.applicationContext.filesDir, "unsent.json")
    private val temp = File(file.path + ".tmp")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    /** Whose writes count: the session opened last and not signed out. */
    private var current: Keeper? = null

    private inner class Keeper(private val account: String) : UnsentKeeper {
        override suspend fun keep(all: List<UnsentText>): Boolean = withContext(Dispatchers.IO) { write(this@Keeper, account, all) }
    }

    /** [account] is signed in: its keeper, and what an earlier run of the app left unsent. */
    suspend fun open(account: String): Pair<UnsentKeeper, List<UnsentText>> = withContext(Dispatchers.IO) {
        val keeper = Keeper(account)
        synchronized(lock) {
            current = keeper
            val kept = read()
            if (kept != null && kept.account != account) delete()
            keeper to (kept?.takeIf { it.account == account }?.texts ?: emptyList())
        }
    }

    /** What an earlier run left unsent of [account], to be shown only: nothing is taken up, the file stays as it is. */
    suspend fun peek(account: String): List<UnsentText> = withContext(Dispatchers.IO) {
        synchronized(lock) { read()?.takeIf { it.account == account }?.texts.orEmpty() }
    }

    /**
     * One more text of [account], which has no open session (a reply from a notification in a process that
     * was just started): the next [open] hands it out with the rest. False when it is not kept: a session
     * has the file (its own writes would drop the text: it takes it itself), the file is another account's,
     * or could not be written.
     */
    suspend fun add(account: String, text: UnsentText): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (current != null) return@synchronized false
            val kept = read()
            if (kept != null && kept.account != account) return@synchronized false
            save(Kept(account, kept?.texts.orEmpty() + text))
        }
    }

    /** Signed out: nothing of the account stays, and nothing more of it is written. */
    fun clear() = synchronized(lock) {
        current = null
        delete()
    }

    private fun read(): Kept? = try {
        if (file.isFile) json.decodeFromString<Kept>(file.readText()) else null
    } catch (_: Exception) {
        Log.w(TAG, "the unsent texts could not be read")
        null
    }

    private fun write(keeper: Keeper, account: String, all: List<UnsentText>): Boolean = synchronized(lock) {
        if (current !== keeper) return@synchronized false
        if (all.isNotEmpty()) return@synchronized save(Kept(account, all))
        delete()
        true
    }

    /** Whole or not at all: to the disk first, then in place of the old one. Under [lock]. */
    private fun save(kept: Kept): Boolean = try {
        FileOutputStream(temp).use {
            it.write(json.encodeToString(kept).encodeToByteArray())
            it.fd.sync()
        }
        temp.renameTo(file).also { if (!it) Log.w(TAG, "the unsent texts could not be written") }
    } catch (_: Exception) {
        Log.w(TAG, "the unsent texts could not be written")
        false
    }

    private fun delete() {
        file.delete()
        temp.delete()
    }

    private companion object {
        const val TAG = "UnsentStore"
    }
}
