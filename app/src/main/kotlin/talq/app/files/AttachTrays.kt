package talq.app.files

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import talq.app.ui.chat.ComposerDrafts

/** One file waiting in a tray. */
data class TrayItem(
    val id: String,
    /** The app's copy, the file as it came; null while it is being copied. */
    val original: StagedFile? = null,
    /** A photo's smaller copy without its location; null until it is made. */
    val small: StagedFile? = null,
    /** A photo that could not be made smaller: tried again by a tap on it, else it only goes out as the original. */
    val stuck: Boolean = false,
) {
    val isPhoto: Boolean get() = original != null && Staging.isPhoto(original.mimeType)

    /**
     * A video, or another file with places in it: made smaller and rid of them when its turn to go up comes
     * ([VideoShrink]), not here: it takes its time. By its content too, whatever its type says.
     */
    val isVideo: Boolean get() = original != null && (original.toClean || VideoShrink.isVideo(original.mimeType))

    val copies: List<StagedFile> get() = listOfNotNull(original, small)
}

/**
 * The files picked, dropped or pasted but not sent yet, per conversation tab: the tray above the message
 * box. TalQ for desktop's staged files (`ComposerWidget::showPendingFiles`), which has one for its one box;
 * here a tray belongs to its tab like the draft ([ComposerDrafts]; same keys, same life) and waits there
 * through Back, another chat and a rotation.
 *
 * - Everything is copied into `cache/outgoing` when it arrives: the permission to a picked, dropped or
 *   pasted item ends soon. Nothing is uploaded before Send, which takes the copies over ([take],
 *   `UploadManager.enqueue`). A file that leaves the tray any other way takes its copies with it.
 * - A photo is kept as it came and, unless originals are sent, gets a smaller copy at once: "Original" can
 *   be switched until Send, and what goes out is there (also after a restart) before it is sent. Send waits
 *   for the copies ([ready]), as desktop's does for a pasted image. A photo that cannot be made smaller
 *   ([blocked]) is never sent as it is by itself: the user turns "Original" on or removes it.
 * - The list is also in `files/trays.json` (names and paths of the copies, no text), so a tray outlives the
 *   process as long as Android leaves the cached copies; a file is kept while either of its copies is there
 *   ([left]). One whose copies are gone is dropped with a line, one that was still being copied without. A
 *   copy that `uploads.json` holds was sent already: it is not in the tray again.
 * - Emptied at sign-out (`UploadManager.clearAll`), and for a conversation that left the list
 *   ([ComposerDrafts]); moved along when its topic is gone ([ComposerDrafts.moveText]).
 *
 * Main thread only. Off it: the files of one pick are copied one after the other, and one photo at a time is
 * made smaller. A provider that hangs holds up only its own pick.
 */
class AttachTrays(
    context: Context,
    private val scope: CoroutineScope,
    private val prefs: SendPrefs,
    /** The copies an unfinished upload already has. */
    uploading: Set<String>,
    /** One line for the user. */
    private val say: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val store = File(appContext.filesDir, "trays.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()

    /** One photo in memory at a time. */
    private val shrinkLock = Mutex()
    private val trays = mutableStateMapOf<ComposerDrafts.Key, List<TrayItem>>()

    /** The copying of each file still on its way. */
    private val copying = HashMap<String, Job>()

    @Volatile private var toWrite: List<TrayRecord> = emptyList()

    /** Photos and videos go as they are (`SendPrefs.sendOriginal`, remembered on this device). */
    var sendOriginal by mutableStateOf(prefs.sendOriginal)
        private set

    init {
        restore(uploading)
        shrinkAll()
    }

    fun items(key: ComposerDrafts.Key): List<TrayItem> = trays[key].orEmpty()

    /** It can be sent as it stands. */
    fun ready(item: TrayItem): Boolean = item.original != null && (sendOriginal || !item.isPhoto || item.small != null)

    /** It waits for "Original" to be turned on, or to be removed. */
    fun blocked(item: TrayItem): Boolean = !sendOriginal && item.stuck

    /** Every copy on disk that belongs to a tray. */
    fun paths(): Set<String> = trays.values.flatten().flatMap { it.copies }.map { it.path }.toSet()

    // ---- in ------------------------------------------------------------------------------

    /**
     * Put what [uris] point to into [key]'s tray. [mime]: what they are, for a provider that does not say.
     * [done] runs once nothing more is read from them: the permission to them may end then.
     */
    fun add(key: ComposerDrafts.Key, uris: List<Uri>, mime: String = "", done: () -> Unit = {}) {
        val taken = uris.take((MAX_FILES - items(key).size).coerceAtLeast(0))
        if (taken.size < uris.size) say("At most $MAX_FILES files at once.")
        var failed = 0
        var full = false
        // The files of this pick, one at a time and in their order; another pick does not wait for them.
        val turn = Mutex()
        val jobs = taken.map { uri ->
            copy(
                key, turn,
                onFailed = { why ->
                    failed++
                    if (why is NoRoomException) full = true
                },
            ) { Staging.stage(appContext, uri, sendOriginal = true, hint = mime) }
        }
        scope.launch {
            jobs.joinAll()
            done()
            if (failed > 0) say(
                when {
                    full && taken.size == 1 -> "The phone is full: the file couldn't be added."
                    full -> "The phone is full: $failed of ${taken.size} files couldn't be added."
                    taken.size == 1 -> "The file couldn't be read."
                    else -> "$failed of ${taken.size} files couldn't be read."
                },
            )
        }
    }

    /** The photo the camera wrote into [file] (one of `Staging.newCameraFile`, taken over). */
    fun addCamera(key: ComposerDrafts.Key, file: File) {
        copy(
            key, Mutex(),
            onFailed = { why -> say(if (why is NoRoomException) "The phone is full: the photo couldn't be added." else "The photo couldn't be read.") },
        ) { Staging.stageCamera(appContext, file) }
    }

    private fun copy(key: ComposerDrafts.Key, turn: Mutex, onFailed: (why: Exception) -> Unit, stage: suspend () -> StagedFile): Job {
        val id = UUID.randomUUID().toString()
        trays[key] = items(key) + TrayItem(id)
        val job = scope.launch {
            val made = try {
                turn.withLock { stage() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Removed while it was read: nothing failed.
                ensureActive()
                Log.w(TAG, "a file to attach could not be read (${e::class.simpleName})")
                keyOf(id)?.let { at -> put(at, items(at).filterNot { it.id == id }) }
                onFailed(e)
                return@launch
            }
            if (change(id) { it.copy(original = made) }) {
                save()
                shrinkLock.withLock { shrink(id) }
            } else {
                discard(listOf(made))
            }
        }
        copying[id] = job
        job.invokeOnCompletion { copying.remove(id) }
        return job
    }

    /** The smaller copy of a photo, when it is wanted and missing. Under [shrinkLock]. */
    private suspend fun shrink(id: String) {
        val item = keyOf(id)?.let { at -> items(at).firstOrNull { it.id == id } } ?: return
        val file = item.original ?: return
        if (sendOriginal || !item.isPhoto || item.small != null || item.stuck) return
        // Never given up halfway: the copy it makes has to be known here, or it would stay behind.
        val small = withContext(NonCancellable) {
            try {
                Staging.smaller(appContext, file)
            } catch (e: IOException) {
                Log.w(TAG, "a photo could not be made smaller (${e::class.simpleName})")
                null
            } catch (e: RuntimeException) {
                Log.w(TAG, "a photo could not be made smaller (${e::class.simpleName})")
                null
            }
        }
        val kept = change(id) { if (small != null) it.copy(small = small) else it.copy(stuck = true) }
        if (kept) save() else if (small != null) discard(listOf(small))
    }

    private fun shrinkAll() {
        if (sendOriginal) return
        val waiting = trays.values.flatten().filter { it.isPhoto && it.small == null && !it.stuck }.map { it.id }
        if (waiting.isEmpty()) return
        scope.launch {
            for (id in waiting) shrinkLock.withLock { shrink(id) }
        }
    }

    /** A tap on a marked photo: once more (the phone may only have been full). */
    fun shrinkAgain(id: String) {
        if (change(id) { it.copy(stuck = false) }) shrinkAll()
    }

    /** The tray's "Original". Off: what could not be made smaller is tried once more. */
    fun useOriginals(on: Boolean) {
        sendOriginal = on
        prefs.sendOriginal = on
        if (!on) for (key in trays.keys.toList()) trays[key] = items(key).map { if (it.stuck) it.copy(stuck = false) else it }
        shrinkAll()
    }

    // ---- out -----------------------------------------------------------------------------

    /** The x on a tile: the file leaves the tray and its copies go. */
    fun remove(key: ComposerDrafts.Key, id: String) {
        val item = items(key).firstOrNull { it.id == id } ?: return
        put(key, items(key) - item)
        copying[id]?.cancel()
        save()
        discard(item.copies)
    }

    /**
     * Send: the files to hand over, in order, and the tray is empty. They belong to whoever takes them.
     * Null, and nothing goes, while one is not [ready], or when a copy that would go is gone (Android cleared
     * the cache): a smaller copy is made again, a file with no copy at all leaves the tray with a line.
     */
    fun take(key: ComposerDrafts.Key): List<StagedFile>? {
        val before = items(key)
        if (before.isEmpty() || !before.all(::ready)) return null
        val list = before.mapNotNull(::left)
        if (list != before) {
            put(key, list)
            save()
            shrinkAll()
            if (list.size < before.size) {
                sayGone(before.size - list.size)
                return null
            }
            if (sendOriginal && list.indices.any { list[it].original != before[it].original }) {
                say("A photo's original is gone: only the smaller one is left.")
                return null
            }
            if (!list.all(::ready)) return null
        }
        val sent = list.mapNotNull { if (sendOriginal || !it.isPhoto) it.original else it.small }
        trays.remove(key)
        // Later than the upload list's own write: a crash in between must not lose them.
        save(SENT_SAVE_MILLIS)
        discard(list.flatMap { it.copies } - sent.toSet())
        return sent
    }

    /** [from]'s tab is gone: its files wait in [to]'s tray, after what that holds. */
    fun move(from: ComposerDrafts.Key, to: ComposerDrafts.Key) {
        val list = trays.remove(from) ?: return
        trays[to] = items(to) + list
        save()
    }

    /** The trays of these tabs go, with their copies. */
    fun dropWhere(gone: (ComposerDrafts.Key) -> Boolean) {
        val keys = trays.keys.filter(gone)
        for (key in keys) {
            val list = trays.remove(key).orEmpty()
            for (item in list) copying[item.id]?.cancel()
            discard(list.flatMap { it.copies })
        }
        if (keys.isNotEmpty()) save()
    }

    /** Signed out. */
    fun clear() {
        dropWhere { true }
        save()
    }

    // ---- for the list --------------------------------------------------------------------

    /** What waits in [account]'s conversations, as their "Draft:" line; [except]: the one open beside the list. */
    fun previews(account: String, except: String?): Map<String, ComposerDrafts.Line> {
        val out = HashMap<String, ComposerDrafts.Line>()
        for ((key, list) in trays) {
            if (key.account == account && key.token != except) out[key.token] = ComposerDrafts.Line(describe(list), key.tab)
        }
        return out
    }

    /** The tabs of a conversation that hold files. */
    fun tabs(account: String, token: String): List<Long> =
        trays.keys.filter { it.account == account && it.token == token }.map { it.tab }

    private fun describe(list: List<TrayItem>): String {
        val only = list.singleOrNull() ?: return "${list.size} files"
        val mime = only.original?.mimeType.orEmpty()
        return when {
            mime.startsWith("image/") -> "Photo"
            mime.startsWith("video/") -> "Video"
            else -> only.original?.name ?: "File"
        }
    }

    // ---- bookkeeping ---------------------------------------------------------------------

    private fun keyOf(id: String): ComposerDrafts.Key? = trays.entries.firstOrNull { e -> e.value.any { it.id == id } }?.key

    private fun put(key: ComposerDrafts.Key, list: List<TrayItem>) {
        if (list.isEmpty()) trays.remove(key) else trays[key] = list
    }

    /** False when the file is in no tray any more (removed meanwhile). */
    private fun change(id: String, edit: (TrayItem) -> TrayItem): Boolean {
        val key = keyOf(id) ?: return false
        trays[key] = items(key).map { if (it.id == id) edit(it) else it }
        return true
    }

    private fun discard(files: List<StagedFile>) {
        if (files.isEmpty()) return
        scope.launch(Dispatchers.IO) { files.forEach { Staging.delete(appContext, it.path) } }
    }

    /**
     * [item] with the copies Android left of it: without the smaller one that is made again, without the
     * original the smaller one stands for both. Null when none is left.
     */
    private fun left(item: TrayItem): TrayItem? {
        val original = item.original?.takeIf { File(it.path).isFile }
        val small = item.small?.takeIf { File(it.path).isFile }
        return when {
            original != null -> item.copy(small = small)
            small != null -> item.copy(original = small, small = small)
            else -> null
        }
    }

    private fun sayGone(count: Int) =
        say(if (count == 1) "A file is gone from the phone: pick it again." else "$count files are gone from the phone: pick them again.")

    private fun restore(uploading: Set<String>) {
        val kept = try {
            if (store.isFile) json.decodeFromString<List<TrayRecord>>(store.readText()) else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "the list of attached files could not be read (${e::class.simpleName})")
            emptyList()
        }
        var gone = 0
        for (r in kept) {
            if (r.original.path in uploading || (r.small != null && r.small.path in uploading)) continue
            val item = left(TrayItem(r.id, r.original, r.small))
            if (item == null) {
                gone++
                continue
            }
            val key = ComposerDrafts.Key(r.account, r.token, r.tab)
            trays[key] = items(key) + item
        }
        if (gone > 0) {
            // Once: the next start has nothing to say about them.
            save()
            sayGone(gone)
        }
    }

    private fun save(waitMillis: Long = 0) {
        toWrite = trays.entries.flatMap { (key, list) ->
            list.mapNotNull { item -> item.original?.let { TrayRecord(key.account, key.token, key.tab, item.id, it, item.small) } }
        }
        scope.launch(Dispatchers.IO) {
            delay(waitMillis)
            writeLock.withLock {
                // The newest list at the time of writing: writes may queue up behind the lock.
                val snapshot = toWrite
                try {
                    if (snapshot.isEmpty()) {
                        store.delete()
                    } else {
                        val tmp = File(store.path + ".tmp")
                        tmp.writeText(json.encodeToString(snapshot))
                        if (!tmp.renameTo(store)) {
                            store.delete()
                            tmp.renameTo(store)
                        }
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "the list of attached files could not be written (${e::class.simpleName})")
                }
            }
        }
    }

    private companion object {
        const val TAG = "AttachTrays"

        /** As desktop's `MultiFileLogic.h`: more in one message is an accident. */
        const val MAX_FILES = 50
        const val SENT_SAVE_MILLIS = 1_500L
    }
}

/** A tray's file on disk between two runs of the app. */
@Serializable
private data class TrayRecord(
    val account: String,
    val token: String,
    val tab: Long,
    val id: String,
    val original: StagedFile,
    val small: StagedFile? = null,
)
