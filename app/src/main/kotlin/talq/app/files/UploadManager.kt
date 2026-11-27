package talq.app.files

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import talq.app.ui.chat.ComposerDrafts
import talq.app.ui.common.userMessage
import talq.core.TalkError
import talq.core.model.ChatMessage
import talq.core.repo.DiscardedFile
import talq.core.repo.FileOutboxEntry
import talq.core.repo.FileSendState
import talq.core.repo.FileToSend
import talq.core.repo.PreparedFile
import talq.core.repo.TalkSession

/** What a pending file bubble shows. */
sealed interface UploadState {
    data object Queued : UploadState

    /** A video is being made smaller ([VideoShrink]). */
    data class Compressing(val fraction: Float) : UploadState

    data class Uploading(val fraction: Float) : UploadState

    /** Uploaded; being posted into the chat. */
    data object Sharing : UploadState

    /** [original]: it can still go as it is, with its location, when the user says so ([UploadManager.sendOriginal]). */
    data class Failed(val message: String, val original: Boolean = false) : UploadState

    /** The app was closed before this file went out. Nothing sends it until the user retries. */
    data object Interrupted : UploadState

    /** The cached copy is gone (the system cleared the cache): it can only be discarded. */
    data object Lost : UploadState

    val active: Boolean get() = this is Queued || this is Compressing || this is Uploading || this is Sharing
}

/** A file on its way into a chat, as the chat pane shows it. */
data class PendingUpload(
    val id: String,
    val token: String,
    /** 0 = General, else the topic. */
    val threadId: Long,
    val name: String,
    val size: Long,
    val mimeType: String,
    /** The app's cached copy (for the thumbnail). */
    val path: String,
    val caption: String,
    val state: UploadState,
    /** The outbox key of its last attempt: its message carries it. */
    val referenceId: String = "",
)

/** What is kept on disk about a file until its message is in the chat (or it is discarded). */
@Serializable
private data class UploadRecord(
    val id: String,
    val account: String,
    val token: String,
    val threadId: Long,
    val path: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val caption: String = "",
    /** The outbox key of the last attempt: the server echoes it on the posted message. */
    val referenceId: String = "",
    /** Posted as a voice message (a recording); kept so a retry after a restart still is one. */
    val voiceMessage: Boolean = false,
    /** Sent silently; kept so a retry after a restart still is. */
    val silent: Boolean = false,
    /**
     * A video, or another file with places in it, that goes through [VideoShrink] when its turn comes ([path] stays
     * the copy as it came). Null: written before 0.10.0, which sent videos as they were.
     */
    val smaller: Boolean? = null,
    /** The message it answers, 0 for none; kept so a retry after a restart still does. */
    val replyTo: Long = 0L,
)

/**
 * The app's side of sending files: hands copies in the cache (of what was picked, see [AttachTrays],
 * shared in or recorded) to the core's `FileOutbox`, and keeps a small list on disk so that a file
 * whose upload did not finish before the process died shows up again as a failed bubble instead of
 * vanishing. `UploadWorker` keeps the process alive (with a notification) while anything is on its way.
 *
 * All state changes happen on the main thread ([scope] is the container's).
 */
class UploadManager(context: Context, private val scope: CoroutineScope, private val session: () -> TalkSession?) {
    private val appContext = context.applicationContext
    private val store = File(appContext.filesDir, "uploads.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()

    val prefs = SendPrefs(appContext)
    private val videos = VideoShrink(appContext)

    private val records = MutableStateFlow(readStore())

    /** Live outbox entries by key, for the rooms being watched. */
    private val live = MutableStateFlow<Map<String, FileOutboxEntry>>(emptyMap())

    /** Keys handed to an outbox by this process: such a key missing from the outbox = done. */
    private val sentHere = HashSet<String>()

    /** Nothing was sent by this process yet: what is on disk is left over from an earlier one. */
    var startedFresh = true
        private set
    private val lost = MutableStateFlow<Set<String>>(emptySet())
    private val resuming = MutableStateFlow<Set<String>>(emptySet())
    private val watchers = HashMap<String, Job>()
    private var watchedSession: TalkSession? = null

    private val _pending = MutableStateFlow<List<PendingUpload>>(emptyList())

    /** Every pending file of every room, oldest first. Filter by token and topic. */
    val pending: StateFlow<List<PendingUpload>> = _pending.asStateFlow()

    /** A one-line problem to show once (a file that could not be read). */
    val notice = MutableStateFlow<String?>(null)

    /** Files picked but not sent yet, per conversation tab (see [AttachTrays]). */
    val trays = AttachTrays(appContext, scope, prefs, records.value.map { it.path }.toSet()) { notice.value = it }

    init {
        publish()
        val keep = records.value.map { it.path }.toSet() + trays.paths()
        val startedAt = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) { runCatching { Staging.sweep(appContext, keep, startedAt) } }
    }

    private fun account(s: TalkSession): String = s.user.userId + "@" + s.credentials.server

    // ---- sending -------------------------------------------------------------------------

    /** What [stageAll] made of the things it was given. */
    class Staged(val files: List<StagedFile>, val unprepared: Int)

    /** Copy [count] things into the cache, one after the other; what cannot be read is left out. */
    suspend fun stageAll(count: Int, stage: suspend (index: Int) -> StagedFile): Staged {
        val out = ArrayList<StagedFile>(count)
        var unprepared = 0
        for (i in 0 until count) {
            try {
                out.add(stage(i))
            } catch (e: CancellationException) {
                out.forEach { Staging.delete(appContext, it.path) }
                throw e
            } catch (e: NotPreparedException) {
                unprepared++
                Log.w(TAG, "a photo could not be prepared (${e::class.simpleName})")
            } catch (e: IOException) {
                Log.w(TAG, "a file to send could not be read (${e::class.simpleName})")
            } catch (e: RuntimeException) {
                Log.w(TAG, "a file to send could not be read (${e::class.simpleName})")
            }
        }
        return Staged(out, unprepared)
    }

    /**
     * Hand already staged files to the room's outbox and start the background work.
     * [voiceMessage]: post them as voice messages (a recording, see `VoiceMessages`). [silent]: without
     * notifying anyone (only where the server has `media-caption`). [originals]: videos as they are too
     * (photos were shrunk, or not, when they were staged).
     */
    fun enqueue(
        session: TalkSession,
        token: String,
        threadId: Long,
        staged: List<StagedFile>,
        caption: String,
        replyTo: ChatMessage? = null,
        voiceMessage: Boolean = false,
        silent: Boolean = false,
        originals: Boolean = prefs.sendOriginal,
    ) {
        if (staged.isEmpty()) return
        startedFresh = false
        watch(session, token)
        val outbox = session.fileOutbox(token)
        fun smaller(file: StagedFile) = !originals && !voiceMessage && (file.toClean || VideoShrink.isVideo(file.mimeType))
        val keys = outbox.send(
            staged.map { toSend(it.path, it.name, it.mimeType, voiceMessage, smaller(it)) },
            caption = caption,
            threadId = threadId,
            replyTo = replyTo,
            silent = silent,
        )
        sentHere.addAll(keys)
        val account = account(session)
        val trimmed = caption.trim()
        records.update { list ->
            list + staged.mapIndexed { i, f ->
                UploadRecord(
                    id = keys[i], account = account, token = token, threadId = threadId, path = f.path, name = f.name,
                    mimeType = f.mimeType, size = f.size, caption = if (i == 0) trimmed else "", referenceId = keys[i],
                    voiceMessage = voiceMessage, silent = silent, smaller = smaller(f),
                    replyTo = if (i == 0) replyTo?.id ?: 0L else 0L,
                )
            }
        }
        persist()
        reconcile(token, outbox.entries.value)
        UploadWorker.start(appContext)
    }

    private fun toSend(path: String, name: String, mimeType: String, voiceMessage: Boolean, smaller: Boolean): FileToSend {
        val file = File(path)
        return FileToSend(
            FileByteSource(file), name, mimeType, tag = path, voiceMessage = voiceMessage,
            prepare = if (smaller) withoutDetails(file, VideoShrink.isVideo(mimeType)) else null,
        )
    }

    private fun withoutDetails(file: File, compress: Boolean): suspend (String, (Float) -> Unit) -> PreparedFile? =
        { name, onProgress -> videos.prepare(file, name, compress, onProgress) }

    // ---- the bubble's buttons ------------------------------------------------------------

    fun retry(id: String) {
        val record = records.value.firstOrNull { it.id == id } ?: return
        val current = session()?.takeIf { account(it) == record.account } ?: return
        val entry = live.value[record.referenceId]
        if (entry != null) {
            if ((entry.state as? FileSendState.Failed)?.unprepared == true) File(record.path).parentFile?.let { VideoShrink.forgetTries(it) }
            current.fileOutbox(record.token).retry(record.referenceId)
            UploadWorker.start(appContext)
        } else {
            scope.launch { resume(current, listOf(record)) }
        }
    }

    /** The user wants a file whose location could not be removed sent as it is. Only this one, and kept so after a restart. */
    fun sendOriginal(id: String) {
        val record = records.value.firstOrNull { it.id == id } ?: return
        val current = session() ?: return
        val state = live.value[record.referenceId]?.state
        if ((state as? FileSendState.Failed)?.unprepared != true) return
        records.update { list -> list.map { if (it.id == id) it.copy(smaller = false) else it } }
        persist()
        current.fileOutbox(record.token).retry(record.referenceId, asItIs = true)
        UploadWorker.start(appContext)
    }

    /**
     * The network is usable again: every file that failed for want of a connection goes again, in
     * the order it was queued (a chunked upload goes on after its last complete chunk; a post whose
     * answer was lost is looked up by its referenceId first, so nothing is posted twice). A refusal
     * (403, a full storage, a file that could not be read) still waits for the user's Retry, and a
     * file left over from an earlier process for the upload worker.
     */
    fun retryNetworkFailures() {
        val current = session() ?: return
        if (watchedSession !== current) return
        val failed = live.value.values.filter { (it.state as? FileSendState.Failed)?.error is TalkError.Network }
        if (failed.isEmpty()) return
        for (entry in failed) current.fileOutbox(entry.token).retry(entry.referenceId)
        // Its "wasn't sent" notification is old news; the worker says it again for what still fails.
        UploadWorker.clearFailureNotice(appContext)
        UploadWorker.start(appContext)
    }

    /**
     * Cancel or give up on a file. Answers the caption and the quote it carried when no other file of its
     * send has them now: they go back into the box the user looks at.
     */
    fun discard(id: String): DiscardedFile? {
        val record = records.value.firstOrNull { it.id == id } ?: return null
        val current = session()
        if (current == null || record.referenceId !in live.value) {
            remove(setOf(id))
            return DiscardedFile(record.caption, null, record.replyTo, "").takeIf { record.caption.isNotEmpty() || record.replyTo != 0L }
        }
        // The entry leaving the outbox removes the record and the cached copy.
        val carried = current.fileOutbox(record.token).discard(record.referenceId) ?: return null
        if (carried.movedTo.isEmpty()) return carried
        records.update { list ->
            list.map { if (it.referenceId == carried.movedTo) it.copy(caption = carried.caption, replyTo = carried.replyToId) else it }
        }
        persist()
        publish()
        return null
    }

    /**
     * Conversations that are not the user's any more: what could not be sent into them goes, with its copies (its
     * bubble, and so its Delete, is only drawn there). What is on its way has its own end first.
     */
    fun dropGone(session: TalkSession, gone: (token: String) -> Boolean) {
        val account = account(session)
        val over = _pending.value.filter { !it.state.active && gone(it.token) }.mapTo(HashSet()) { it.id }
        if (over.isEmpty()) return
        for (record in records.value) if (record.id in over && record.account == account) discard(record.id)
    }

    // ---- after a restart -----------------------------------------------------------------

    /** [session] is the one now: nothing another account left here is shown, or sent, in its name. */
    fun opened(session: TalkSession) {
        val account = account(session)
        remove(records.value.filter { it.account != account }.mapTo(HashSet()) { it.id })
        val key = ComposerDrafts.accountOf(session)
        trays.dropWhere { it.account != key }
    }

    /**
     * The server has these messages of [token], by what they were sent as ([refs]): a file an earlier run left
     * behind that is among them was sent (the process ended before it heard of that), and is done.
     */
    fun arrived(token: String, refs: Set<String>) {
        val done = records.value.filter {
            it.token == token && it.referenceId.isNotEmpty() && it.referenceId in refs &&
                it.referenceId !in live.value && it.referenceId !in sentHere && it.id !in resuming.value
        }
        if (done.isNotEmpty()) remove(done.mapTo(HashSet()) { it.id })
    }

    /**
     * Files left over from a process that died: send them again. Called by the worker when the
     * system runs it again. A file whose message is already in the chat (the process died
     * between the post and the cleanup) is not sent twice.
     */
    suspend fun resumeInterrupted(session: TalkSession) = withContext(Dispatchers.Main.immediate) {
        val account = account(session)
        val foreign = records.value.filter { it.account != account }
        if (foreign.isNotEmpty()) remove(foreign.map { it.id }.toSet())
        resume(session, records.value.filter { it.referenceId !in live.value && it.referenceId !in sentHere })
    }

    private suspend fun resume(session: TalkSession, which: List<UploadRecord>) {
        val todo = which.filter { it.id !in resuming.value }
        if (todo.isEmpty()) return
        resuming.update { it + todo.map { r -> r.id } }
        publish()
        try {
            for ((token, group) in todo.groupBy { it.token }) {
                val posted = try {
                    session.client.history(token, limit = 100).messages.map { it.referenceId }.toSet()
                } catch (e: TalkError) {
                    // Not known whether they went out: leave them for the user to decide.
                    continue
                }
                watch(session, token)
                val outbox = session.fileOutbox(token)
                for (record in group) {
                    if (records.value.none { it.id == record.id }) continue
                    if (record.referenceId.isNotEmpty() && record.referenceId in posted) {
                        remove(setOf(record.id))
                        continue
                    }
                    val file = File(record.path)
                    val there = withContext(Dispatchers.IO) { file.isFile && file.length() > 0 }
                    if (!there) {
                        lost.update { it + record.id }
                        continue
                    }
                    val smaller = record.smaller ?: smallerNow(record, file)
                    val key = outbox.send(
                        listOf(toSend(record.path, record.name, record.mimeType, record.voiceMessage, smaller)),
                        caption = record.caption,
                        threadId = record.threadId,
                        silent = record.silent,
                        replyToId = record.replyTo,
                    ).first()
                    sentHere.add(key)
                    records.update { list -> list.map { if (it.id == record.id) it.copy(referenceId = key, smaller = smaller) else it } }
                }
                persist()
                reconcile(token, outbox.entries.value)
            }
        } finally {
            resuming.update { it - todo.map { r -> r.id }.toSet() }
            publish()
        }
        if (_pending.value.any { it.state.active }) UploadWorker.start(appContext)
    }

    /** What [enqueue] would say of it now. A file that cannot be read is not sent as it is. */
    private suspend fun smallerNow(record: UploadRecord, file: File): Boolean {
        if (record.voiceMessage || prefs.sendOriginal) return false
        if (VideoShrink.isVideo(record.mimeType)) return true
        return withContext(Dispatchers.IO) { runCatching { Mp4Metadata.needsCleaning(file) }.getOrDefault(true) }
    }

    /** The account is gone (sign-out): nothing of it may be sent later. */
    fun clearAll() {
        watchers.values.forEach { it.cancel() }
        watchers.clear()
        watchedSession = null
        live.value = emptyMap()
        sentHere.clear()
        lost.value = emptySet()
        records.value = emptyList()
        persist()
        publish()
        trays.clear()
        notice.value = null
        scope.launch(Dispatchers.IO) { Staging.clearAll(appContext) }
    }

    // ---- bookkeeping ---------------------------------------------------------------------

    private fun watch(session: TalkSession, token: String) {
        if (watchedSession !== session) {
            watchers.values.forEach { it.cancel() }
            watchers.clear()
            live.value = emptyMap()
            watchedSession = session
        }
        if (token in watchers) return
        watchers[token] = scope.launch {
            session.fileOutbox(token).entries.collect { reconcile(token, it) }
        }
    }

    /** Bring the records of [token] in line with its outbox: what left the outbox is finished. */
    private fun reconcile(token: String, entries: List<FileOutboxEntry>) {
        val keys = entries.map { it.referenceId }.toSet()
        live.update { map -> map.filterValues { it.token != token } + entries.associateBy { it.referenceId } }
        val done = records.value.filter { it.token == token && it.referenceId in sentHere && it.referenceId !in keys }
        if (done.isNotEmpty()) {
            sentHere.removeAll(done.map { it.referenceId }.toSet())
            remove(done.map { it.id }.toSet())
        } else {
            publish()
        }
    }

    private fun remove(ids: Set<String>) {
        val gone = records.value.filter { it.id in ids }
        if (gone.isEmpty()) return
        records.update { list -> list.filterNot { it.id in ids } }
        lost.update { it - ids }
        persist()
        publish()
        scope.launch(Dispatchers.IO) { gone.forEach { Staging.delete(appContext, it.path) } }
    }

    private fun publish() {
        val entries = live.value
        val fromRecords = records.value.map { r ->
            val entry = entries[r.referenceId]
            val state = when {
                entry != null -> when (val s = entry.state) {
                    FileSendState.Queued -> UploadState.Queued
                    is FileSendState.Preparing -> UploadState.Compressing(s.fraction)
                    is FileSendState.Uploading -> UploadState.Uploading(s.fraction)
                    FileSendState.Sharing -> UploadState.Sharing
                    is FileSendState.Failed ->
                        if (s.unprepared) UploadState.Failed("Location can't be removed.", original = true) else UploadState.Failed(s.error.userMessage())
                }
                r.id in lost.value -> UploadState.Lost
                r.id in resuming.value -> UploadState.Queued
                else -> UploadState.Interrupted
            }
            // A video made smaller has another size, and maybe another ending, than what was picked.
            val made = entry?.takeIf { r.smaller == true }
            PendingUpload(
                r.id, r.token, entry?.threadId ?: r.threadId, made?.name ?: r.name, made?.size ?: r.size, r.mimeType, r.path, r.caption, state,
                r.referenceId,
            )
        }
        _pending.value = fromRecords
    }

    private fun readStore(): List<UploadRecord> = try {
        if (store.isFile) json.decodeFromString<List<UploadRecord>>(store.readText()) else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "the list of pending uploads could not be read (${e::class.simpleName})")
        emptyList()
    }

    private fun persist() {
        scope.launch(Dispatchers.IO) {
            writeLock.withLock {
                // The newest list at the time of writing: writes may queue up behind the lock.
                val snapshot = records.value
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
                    Log.w(TAG, "the list of pending uploads could not be written (${e::class.simpleName})")
                }
            }
        }
    }

    private companion object {
        const val TAG = "UploadManager"
    }
}
