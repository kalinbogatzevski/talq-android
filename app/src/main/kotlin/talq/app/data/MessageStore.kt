package talq.app.data

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import talq.core.repo.MessageKeeper
import talq.core.repo.SavedChat

/**
 * The newest messages of the conversations used last, kept for the next start of the app (the core's [SavedChat] says
 * what of them and what for). One file per conversation in `filesDir/messages`, sealed like the saved list
 * ([SealedText]) with a Keystore key of its own, out of backups (data_extraction_rules.xml); its name is a hash, its
 * time is when the conversation was last used. At most [MAX_CHATS] files and [MAX_TOTAL_BYTES] in all (one at most
 * [MAX_BYTES]): the ones used longest ago go first.
 *
 * Written off the main thread, whole or not at all, and only for what the conversation list holds ([Keeper.retain]).
 * Every sign-out ([clear]) deletes all of it before it returns and ends the old session's writing. Nothing of it goes
 * to the log: a decoding error quotes what it read.
 */
class MessageStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "messages")
    private val sealed = SealedText("talq.messages.v1", MAX_BYTES - HEAD_BYTES, MAX_PLAIN_BYTES)
    private val lock = Any()

    /** Whose writes count: the session opened last and not signed out. Under [lock], as the two below. */
    private var current: Keeper? = null

    /** Counts the sign-outs: what is read across one is not handed out. */
    private var clears = 0

    /** When the first message in a file disappears (seconds), by its name, 0 = never; read once, then kept up. */
    private val expiries = HashMap<String, Long>()

    /** What was read last: the open session goes on with what the screen before it has shown. */
    @Volatile
    private var last: SavedChat? = null

    /** [account] is signed in: what its session reads and writes with. */
    fun open(account: String): MessageKeeper = Keeper(account).also { synchronized(lock) { current = it } }

    /** What was kept of [token], to be shown only (the account is not checked yet). */
    suspend fun peek(account: String, token: String): SavedChat? = withContext(Dispatchers.IO) { read(account, token) }

    /** Signed out: nothing of the account stays, and nothing more of it is written. */
    fun clear() = synchronized(lock) {
        current = null
        clears++
        last = null
        expiries.clear()
        files().forEach { it.delete() }
    }

    private inner class Keeper(override val account: String) : MessageKeeper {
        /** The names of the conversations that may be kept; none before the list is known. Under [lock]. */
        var allowed: Set<String> = emptySet()

        override fun held(token: String): SavedChat? = last?.takeIf { it.account == account && it.token == token }

        override suspend fun read(token: String): SavedChat? = withContext(Dispatchers.IO) { read(account, token) }

        override suspend fun keep(token: String, chat: SavedChat.Encoded?): Boolean = withContext(Dispatchers.IO) {
            val bytes = quietly(NOT_KEPT) { chat?.let { pack(it) } }
            synchronized(lock) {
                val file = fileOf(account, token)
                if (current !== this@Keeper || file.name !in allowed) {
                    false
                } else {
                    if (last?.token == token) last = null
                    val done = bytes != null && put(file, bytes, chat?.expires ?: 0L)
                    // Not kept is better than kept as it was: the messages may have been deleted.
                    if (!done) drop(file)
                    trim()
                    done
                }
            }
        }

        override suspend fun used(token: String) {
            withContext(Dispatchers.IO) { synchronized(lock) { touch(fileOf(account, token)) } }
        }

        override suspend fun retain(tokens: Set<String>) {
            withContext(Dispatchers.IO) {
                val names = tokens.mapTo(HashSet()) { fileOf(account, it).name }
                synchronized(lock) {
                    if (current === this@Keeper) {
                        allowed = names
                        files().filter { it.name !in names }.forEach { drop(it) }
                        if (last?.let { it.account != account || it.token !in tokens } == true) last = null
                    }
                }
            }
        }

        override suspend fun sweep() {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis() / 1000
                val due = synchronized(lock) { if (current === this@Keeper) files().filter { expiry(it) in 1..now } else emptyList() }
                for (file in due) {
                    val before = file.lastModified()
                    val left = quietly(NOT_READ) { unpack(file)?.let { SavedChat.encode(it, now) } }
                    val bytes = left?.let { quietly(NOT_KEPT) { pack(it) } }
                    synchronized(lock) {
                        // Not when it was written anew meanwhile: that is without what has disappeared.
                        if (current === this@Keeper && file.lastModified() == before) {
                            if (bytes == null || !put(file, bytes, left.expires)) drop(file) else file.setLastModified(before)
                        }
                    }
                }
            }
        }
    }

    private fun read(account: String, token: String): SavedChat? {
        val before = synchronized(lock) { clears }
        val file = fileOf(account, token)
        val chat = quietly(NOT_READ) { unpack(file) }?.takeIf { it.account == account && it.token == token }
        synchronized(lock) {
            if (clears != before) return null
            last = chat
            if (chat != null) touch(file)
        }
        return chat
    }

    /** Null when there is no file, or it is none this version made. */
    private fun unpack(file: File): SavedChat? {
        if (file.length() <= HEAD_BYTES || file.length() > MAX_BYTES) return null
        val bytes = file.readBytes()
        return SavedChat.decode(sealed.open(bytes.copyOfRange(HEAD_BYTES, bytes.size)))
    }

    /** When its first message disappears, readable without the key, and the sealed text. Null when that is too large. */
    private fun pack(chat: SavedChat.Encoded): ByteArray? =
        sealed.close(chat.text)?.let { ByteBuffer.allocate(HEAD_BYTES + it.size).putLong(chat.expires).put(it).array() }

    private fun expiry(file: File): Long = expiries.getOrPut(file.name) {
        quietly(NOT_READ) { DataInputStream(file.inputStream()).use { it.readLong() } } ?: 0L
    }

    private fun put(file: File, bytes: ByteArray, expires: Long): Boolean = quietly(NOT_KEPT) {
        dir.mkdirs()
        writeWhole(file, bytes).also { if (it) expiries[file.name] = expires }
    } ?: false

    private fun touch(file: File) {
        if (file.exists()) file.setLastModified(System.currentTimeMillis())
    }

    private fun drop(file: File) {
        expiries.remove(file.name)
        file.delete()
        tempOf(file).delete()
    }

    private fun trim() {
        var count = 0
        var bytes = 0L
        for (file in files().sortedByDescending { it.lastModified() }) {
            bytes += file.length()
            if (++count > MAX_CHATS || bytes > MAX_TOTAL_BYTES) drop(file)
        }
    }

    private fun files(): List<File> = dir.listFiles()?.toList().orEmpty()

    /** The conversation's token is in no file name. */
    private fun fileOf(account: String, token: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest("$account\n$token".encodeToByteArray())
        return File(dir, hash.take(16).joinToString("") { "%02x".format(it) })
    }

    private inline fun <T> quietly(what: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Log.w(TAG, what)
        null
    }

    private companion object {
        const val TAG = "MessageStore"
        const val NOT_READ = "a kept conversation could not be read"
        const val NOT_KEPT = "a conversation could not be kept"
        const val MAX_CHATS = 40
        const val MAX_TOTAL_BYTES = 4L * 1024 * 1024
        const val MAX_BYTES = 1024L * 1024

        /** The core writes at most 400,000 characters of messages, and a head. */
        const val MAX_PLAIN_BYTES = 2 * 1024 * 1024
        const val HEAD_BYTES = 8
    }
}
