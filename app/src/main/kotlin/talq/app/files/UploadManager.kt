package talq.app.files

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID
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
import talq.app.ui.common.userMessage
import talq.core.TalkError
import talq.core.model.ChatMessage
import talq.core.repo.FileOutboxEntry
import talq.core.repo.FileSendState
import talq.core.repo.FileToSend
import talq.core.repo.TalkSession

/** What a pending file bubble shows. */
sealed interface UploadState {
    /** Being copied into the cache (and shrunk, for a photo). */
    data object Preparing : UploadState

    data object Queued : UploadState

    data class Uploading(val fraction: Float) : UploadState

    /** Uploaded; being posted into the chat. */
    data object Sharing : UploadState

    data class Failed(val message: String) : UploadState

    /** The app was closed before this file went out. Nothing sends it until the user retries. */
    data object Interrupted : UploadState

    /** The cached copy is gone (the system cleared the cache): it can only be discarded. */
    data object Lost : UploadState

    val active: Boolean get() = this is Preparing || this is Queued || this is Uploading || this is Sharing
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
    /** The app's cached copy (for the thumbnail); empty while preparing. */
    val path: String,
    val caption: String,
    val state: UploadState,
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
)

/**
 * The app's side of sending files: copies what was picked into the cache, hands it to the
 * core's `FileOutbox`, and keeps a small list on disk so that a file whose upload did not
 * finish before the process died shows up again as a failed bubble instead of vanishing.
 * `UploadWorker` keeps the process alive (with a notification) while anything is on its way.
 *
 * All state changes happen on the main thread ([scope] is the container's).
 */
class UploadManager(context: Context, private val scope: CoroutineScope, private val session: () -> TalkSession?) {
    private val appContext = context.applicationContext
    private val store = File(appContext.filesDir, "uploads.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()

    val prefs = SendPrefs(appContext)

    private val records = MutableStateFlow(readStore())
    private val preparing = MutableStateFlow<List<PendingUpload>>(emptyList())

    /** The copying of each placeholder's batch, so the bubble's Cancel can stop it. */
    private val preparingJobs = HashMap<String, Job>()

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

    init {
        publish()
        val keep = records.value.map { it.path }.toSet()
        scope.launch(Dispatchers.IO) { runCatching { Staging.sweep(appContext, keep) } }
    }

    private fun account(s: TalkSession): String = s.user.userId + "@" + s.credentials.server

    // ---- sending -------------------------------------------------------------------------

    /**
     * Send what the pickers returned into [token] (topic [threadId], 0 = General). The caption
     * and the quote go with the first file. [onNothingSent] gets the caption back when not a
     * single file could be read.
     */
    fun sendUris(
        token: String,
        threadId: Long,
        uris: List<Uri>,
        caption: String,
        replyTo: ChatMessage?,
        onNothingSent: (caption: String) -> Unit = {},
    ) {
        if (uris.isEmpty()) return
        val original = prefs.sendOriginal
        prepareAndSend(token, threadId, uris.size, caption, replyTo, onNothingSent) { index ->
            Staging.stage(appContext, uris[index], original)
        }
    }

    /** Send the photo the camera wrote into [file] (one of `Staging.newCameraFile`). */
    fun sendCameraPhoto(
        token: String,
        threadId: Long,
        file: File,
        caption: String,
        replyTo: ChatMessage?,
        onNothingSent: (caption: String) -> Unit = {},
    ) {
        val original = prefs.sendOriginal
        prepareAndSend(token, threadId, 1, caption, replyTo, onNothingSent) {
            Staging.stageCamera(appContext, file, original)
        }
    }

    private fun prepareAndSend(
        token: String,
        threadId: Long,
        count: Int,
        caption: String,
        replyTo: ChatMessage?,
        onNothingSent: (String) -> Unit,
        stage: suspend (index: Int) -> StagedFile,
    ) {
        val placeholders = List(count) {
            PendingUpload("p" + UUID.randomUUID(), token, threadId, "", 0, "", "", "", UploadState.Preparing)
        }
        val ids = placeholders.map { it.id }.toSet()
        preparing.update { it + placeholders }
        publish()
        // The copying is part of the sending: the process is kept alive for it too.
        UploadWorker.start(appContext)
        val job = scope.launch {
            val result = try {
                stageAll(count, stage)
            } catch (e: CancellationException) {
                // Cancelled from the bubble: the message goes back into the composer.
                onNothingSent(caption)
                throw e
            } finally {
                ids.forEach { preparingJobs.remove(it) }
                preparing.update { list -> list.filterNot { it.id in ids } }
                publish()
            }
            val staged = result.files
            notice.value = result.problem(count) ?: notice.value
            val current = session()
            if (staged.isEmpty() || current == null) {
                staged.forEach { Staging.delete(appContext, it.path) }
                onNothingSent(caption)
            } else {
                enqueue(current, token, threadId, staged, caption, replyTo)
            }
        }
        if (job.isActive) ids.forEach { preparingJobs[it] = job }
    }

    /** What [stageAll] made of the things it was given. */
    class Staged(val files: List<StagedFile>, val unreadable: Int, val unprepared: Int) {
        /** One line for the user about what was left out, null when nothing was. */
        fun problem(count: Int): String? {
            val failed = unreadable + unprepared
            return when {
                failed == 0 -> null
                unprepared == failed && failed == 1 ->
                    "A photo couldn't be made smaller, so it wasn't sent. Turn on Send original to send it as it is."
                unprepared == failed ->
                    "$failed photos couldn't be made smaller, so they weren't sent. Turn on Send original to send them as they are."
                count == 1 -> "The file couldn't be read."
                else -> "$failed of $count files couldn't be sent."
            }
        }
    }

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
                Log.w(TAG, "a photo could not be prepared", e)
            } catch (e: IOException) {
                Log.w(TAG, "a file to send could not be read", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "a file to send could not be read", e)
            }
        }
        return Staged(out, count - out.size - unprepared, unprepared)
    }

    /**
     * Hand already staged files to the room's outbox and start the background work.
     * [voiceMessage]: post them as voice messages (a recording, see `VoiceMessages`).
     */
    fun enqueue(
        session: TalkSession,
        token: String,
        threadId: Long,
        staged: List<StagedFile>,
        caption: String,
        replyTo: ChatMessage? = null,
        voiceMessage: Boolean = false,
    ) {
        if (staged.isEmpty()) return
        startedFresh = false
        watch(session, token)
        val outbox = session.fileOutbox(token)
        val keys = outbox.send(
            staged.map { FileToSend(FileByteSource(File(it.path)), it.name, it.mimeType, tag = it.path, voiceMessage = voiceMessage) },
            caption = caption,
            threadId = threadId,
            replyTo = replyTo,
        )
        sentHere.addAll(keys)
        val account = account(session)
        val trimmed = caption.trim()
        records.update { list ->
            list + staged.mapIndexed { i, f ->
                UploadRecord(
                    id = keys[i], account = account, token = token, threadId = threadId, path = f.path, name = f.name,
                    mimeType = f.mimeType, size = f.size, caption = if (i == 0) trimmed else "", referenceId = keys[i],
                    voiceMessage = voiceMessage,
                )
            }
        }
        persist()
        reconcile(token, outbox.entries.value)
        UploadWorker.start(appContext)
    }

    // ---- the bubble's buttons ------------------------------------------------------------

    fun retry(id: String) {
        val record = records.value.firstOrNull { it.id == id } ?: return
        val current = session() ?: return
        if (record.referenceId in live.value) {
            current.fileOutbox(record.token).retry(record.referenceId)
            UploadWorker.start(appContext)
        } else {
            scope.launch { resume(current, listOf(record)) }
        }
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

    /** Cancel or give up on a file. */
    fun discard(id: String) {
        preparingJobs[id]?.let {
            it.cancel()
            return
        }
        val record = records.value.firstOrNull { it.id == id } ?: return
        val current = session()
        if (current != null && record.referenceId in live.value) {
            // The entry leaving the outbox removes the record and the cached copy.
            current.fileOutbox(record.token).discard(record.referenceId)
        } else {
            remove(setOf(id))
        }
    }

    // ---- after a restart -----------------------------------------------------------------

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
                    val key = outbox.send(
                        listOf(FileToSend(FileByteSource(file), record.name, record.mimeType, tag = record.path, voiceMessage = record.voiceMessage)),
                        caption = record.caption,
                        threadId = record.threadId,
                    ).first()
                    sentHere.add(key)
                    records.update { list -> list.map { if (it.id == record.id) it.copy(referenceId = key) else it } }
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

    /** The account is gone (sign-out): nothing of it may be sent later. */
    fun clearAll() {
        watchers.values.forEach { it.cancel() }
        watchers.clear()
        watchedSession = null
        live.value = emptyMap()
        sentHere.clear()
        lost.value = emptySet()
        records.value = emptyList()
        preparing.value = emptyList()
        persist()
        publish()
        preparingJobs.values.toList().forEach { it.cancel() }
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
                    is FileSendState.Uploading -> UploadState.Uploading(s.fraction)
                    FileSendState.Sharing -> UploadState.Sharing
                    is FileSendState.Failed -> UploadState.Failed(s.error.userMessage())
                }
                r.id in lost.value -> UploadState.Lost
                r.id in resuming.value -> UploadState.Queued
                else -> UploadState.Interrupted
            }
            PendingUpload(r.id, r.token, entry?.threadId ?: r.threadId, r.name, r.size, r.mimeType, r.path, r.caption, state)
        }
        _pending.value = fromRecords + preparing.value
    }

    private fun readStore(): List<UploadRecord> = try {
        if (store.isFile) json.decodeFromString<List<UploadRecord>>(store.readText()) else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "the list of pending uploads could not be read", e)
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
                    Log.w(TAG, "the list of pending uploads could not be written", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "UploadManager"
    }
}
