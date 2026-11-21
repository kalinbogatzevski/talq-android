package talq.core.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import talq.core.FileShareOptions
import talq.core.TalkError
import talq.core.TalkFiles
import talq.core.UploadResume
import talq.core.UploadedFile
import talq.core.files.ByteSource
import talq.core.files.FileNames
import talq.core.model.ChatMessage
import kotlin.concurrent.Volatile

/** One file handed to [FileOutbox.send]. */
class FileToSend(
    /** The bytes. Kept until the file is sent or discarded; a retry opens it again. */
    val source: ByteSource,
    val name: String,
    /** For the pending bubble only (icon, thumbnail); the server works the type out itself. */
    val mimeType: String = "",
    /** Anything the app wants back on the entry: a content URI, a cache path, a thumbnail key. */
    val tag: String = "",
    /** Send as a voice message (audio/mpeg or audio/wav only). */
    val voiceMessage: Boolean = false,
)

sealed interface FileSendState {
    /** Waiting for the files before it (one file goes up at a time per room). */
    data object Queued : FileSendState

    data class Uploading(val sent: Long, val total: Long) : FileSendState {
        /** 0..1 */
        val fraction: Float get() = if (total <= 0) 0f else (sent.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    /** The file is on the server and is being posted into the chat. */
    data object Sharing : FileSendState

    /** Stays until [FileOutbox.retry] or [FileOutbox.discard]. */
    data class Failed(val error: TalkError) : FileSendState
}

/** A file on its way into a chat. Shown as a pending bubble; never dropped silently. */
data class FileOutboxEntry(
    /** Stable key; also sent to the server so the confirmed message can be matched. */
    val referenceId: String,
    val token: String,
    /** The (sanitized, batch-unique) file name. */
    val name: String,
    val size: Long,
    val mimeType: String,
    /** [FileToSend.tag], untouched. */
    val tag: String,
    /** Only the first file of a send carries the caption. */
    val caption: String,
    /** Only the first file of a send carries the quote. */
    val replyTo: ChatMessage?,
    /** Topic the file goes into, 0 for none (General). */
    val threadId: Long,
    /** Not empty: this send starts a new topic of this name (then [threadId] is 0 at first). */
    val threadTitle: String,
    val state: FileSendState,
) {
    val failed: Boolean get() = state is FileSendState.Failed
}

/**
 * The files being sent into one room. Observe [entries] (filter by `threadId` for a topic tab,
 * 0 for General); get instances from [TalkSession.fileOutbox]. One message per file; an entry
 * goes away when its message is in the chat.
 */
class FileOutbox internal constructor(
    private val session: TalkSession,
    val token: String,
) {
    private val files: TalkFiles get() = session.files
    private val _entries = MutableStateFlow<List<FileOutboxEntry>>(emptyList())
    private val _createdTopics = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * One upload at a time per room, strictly in the order queued: one worker takes the keys
     * out of [queue] one by one. (Racing coroutines for a lock would not keep the order.)
     */
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val pending = MutableStateFlow<Map<String, Pending>>(emptyMap())

    private class Pending(val file: FileToSend, val batch: Batch, val first: Boolean) {
        @Volatile var job: Job? = null

        /** Set once the upload is through, so a retry of a failed post does not upload again. */
        @Volatile var uploaded: UploadedFile? = null

        /** How far a chunked upload got, so a retry goes on from there. */
        val resume = UploadResume()
    }

    /** What the files of one [send] share: the topic the first of them may be about to create. */
    private class Batch(val topic: CompletableDeferred<Long>)

    /** Pending and failed files, oldest first. Render them after the chat's messages. */
    val entries: StateFlow<List<FileOutboxEntry>> = _entries.asStateFlow()

    /**
     * Topics started by a `send(threadTitle = ...)`: the first file's key -> the new topic's id.
     */
    val createdTopics: StateFlow<Map<String, Long>> = _createdTopics.asStateFlow()

    init {
        session.scope.launch {
            for (key in queue) {
                val p = pending.value[key] ?: continue
                if (!p.first && !p.batch.topic.isCompleted) {
                    // Follows a file that is to start a topic and has not yet (it failed and
                    // waits for a retry): step aside until the topic exists.
                    p.job = session.scope.launch {
                        p.batch.topic.await()
                        queue.send(key)
                    }
                    continue
                }
                val job = session.scope.launch { attempt(key, p) }
                p.job = job
                // Discarded between the check above and here.
                if (key !in pending.value) job.cancel()
                job.join()
            }
        }
    }

    /**
     * Queue [files] for this room: each becomes its own message, in order. [caption] and
     * [replyTo] go with the first one. [threadId] > 0 files them in that topic, 0 in General.
     * With a [threadTitle] the first file STARTS a topic of that name and the others follow
     * into it (the quote is dropped then). Returns the outbox keys, one per file.
     */
    fun send(
        files: List<FileToSend>,
        caption: String = "",
        threadId: Long = 0,
        replyTo: ChatMessage? = null,
        threadTitle: String = "",
    ): List<String> {
        if (files.isEmpty()) return emptyList()
        val title = threadTitle.trim()
        val thread = if (title.isEmpty()) threadId.coerceAtLeast(0) else 0L
        val names = FileNames.unique(files.map { FileNames.sanitize(it.name) })
        val keys = files.map { TalkFiles.randomHex(64) }
        val batch = Batch(CompletableDeferred())
        if (title.isEmpty()) batch.topic.complete(thread)
        val created = files.mapIndexed { i, f ->
            FileOutboxEntry(
                referenceId = keys[i], token = token, name = names[i], size = f.source.size, mimeType = f.mimeType,
                tag = f.tag,
                caption = if (i == 0) caption.trim() else "",
                replyTo = replyTo.takeIf { i == 0 && title.isEmpty() },
                threadId = thread,
                threadTitle = title,
                state = FileSendState.Queued,
            )
        }
        pending.update { map -> map + files.indices.associate { i -> keys[i] to Pending(files[i], batch, first = i == 0) } }
        _entries.update { it + created }
        for (key in keys) queue.trySend(key)
        return keys
    }

    /** Try a failed file again. A file that was already uploaded is only posted again. */
    fun retry(referenceId: String) {
        if (_entries.value.none { it.referenceId == referenceId && it.failed }) return
        setState(referenceId, FileSendState.Queued)
        queue.trySend(referenceId)
    }

    /**
     * Give up on a file: stops its upload (the server-side pieces are removed) and drops the
     * entry. Discarding the file that was to start a topic discards the files waiting for it.
     */
    fun discard(referenceId: String) {
        val p = pending.value[referenceId]
        finish(referenceId)
        if (p == null) return
        p.job?.cancel()
        val up = p.uploaded
        session.launch {
            files.discardResume(p.resume)
            if (up != null) {
                // Outside the draft flow the uploaded file is the very file a share would point
                // at: when the post went through after all (its answer was lost), keep it.
                if (!up.draft && confirm(referenceId, tries = 1) != null) return@launch
                files.discardUpload(up)
            }
        }
        if (p.first && !p.batch.topic.isCompleted) {
            pending.value.filterValues { it.batch === p.batch }.keys.forEach { discard(it) }
        }
    }

    private suspend fun attempt(key: String, p: Pending) {
        try {
            run(key, p, if (p.first) 0L else p.batch.topic.await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: TalkError) {
            setState(key, FileSendState.Failed(e))
        } catch (e: Throwable) {
            setState(key, FileSendState.Failed(TalkError.LocalFile(e)))
        }
    }

    private suspend fun run(key: String, p: Pending, batchTopic: Long) {
        val entry = _entries.value.firstOrNull { it.referenceId == key } ?: return
        val startsTopic = startsTopic(p, entry)
        val threadId = if (p.first) entry.threadId else batchTopic
        if (threadId != entry.threadId) {
            _entries.update { list -> list.map { if (it.referenceId == key) it.copy(threadId = threadId) else it } }
        }

        // A retry of a post that failed: its answer may only have been lost. Posting again
        // would fail forever (the server has moved the file), so look in the chat first.
        if (p.uploaded != null) {
            val already = confirm(key, tries = 1)
            if (already != null) {
                p.uploaded = null
                settleTopic(key, p, entry, already)
                finish(key)
                return
            }
        }

        val uploaded = p.uploaded ?: run {
            setState(key, FileSendState.Uploading(0, entry.size))
            var lastShown = 0L
            files.upload(token, p.file.source, entry.name, resume = p.resume, onProgress = { sent, total ->
                // Not every 64 KB: a state update per percent is plenty for a progress bar.
                if (sent == total || sent - lastShown >= maxOf(total / 100, 1L)) {
                    lastShown = sent
                    setState(key, FileSendState.Uploading(sent, total), onlyWhileUploading = true)
                }
            }).also { p.uploaded = it }
        }

        setState(key, FileSendState.Sharing)
        var message: ChatMessage? = null
        try {
            files.share(
                token, uploaded, key,
                FileShareOptions(
                    caption = entry.caption,
                    threadId = threadId,
                    replyTo = entry.replyTo?.id ?: 0L,
                    threadTitle = if (startsTopic) entry.threadTitle else "",
                    voiceMessage = p.file.voiceMessage,
                ),
            )
        } catch (e: TalkError) {
            // The answer may have been lost after the server posted the file: only a message
            // in the chat tells. (A plain refusal is not worth the look.)
            if (e is TalkError.Forbidden || e is TalkError.Unauthorized || e is TalkError.QuotaExceeded) throw e
            message = confirm(key, tries = 1) ?: throw e
        }
        // From here the file IS posted: nothing below may turn the entry into a failure, and
        // a discard must not delete the file any more.
        p.uploaded = null
        if (message == null) message = confirm(key)
        settleTopic(key, p, entry, message)
        finish(key)
    }

    private fun startsTopic(p: Pending, entry: FileOutboxEntry): Boolean =
        p.first && entry.threadTitle.isNotEmpty() && !p.batch.topic.isCompleted

    /** The first file of a topic-starting send is posted: tell the files that wait for the topic. */
    private fun settleTopic(key: String, p: Pending, entry: FileOutboxEntry, message: ChatMessage?) {
        if (!startsTopic(p, entry)) return
        val topicId = message?.let { it.topicId.takeIf { id -> id != 0L } ?: it.id } ?: 0L
        if (message != null && topicId != 0L) {
            session.topics(token).onCreated(
                message.copy(isThread = true, threadId = topicId, threadTitle = message.threadTitle.ifEmpty { entry.threadTitle }),
                entry.threadTitle,
            )
            _createdTopics.update { it + (key to topicId) }
        }
        // Unknown topic id (the message could not be read back): the rest go to General rather
        // than wait forever.
        p.batch.topic.complete(topicId)
    }

    /**
     * Read the posted message back (the post itself does not return it) and hand it to the
     * open views, so the pending bubble is replaced by the message without a gap. Null when it
     * could not be found; the live feed will bring it.
     */
    private suspend fun confirm(key: String, tries: Int = CONFIRM_TRIES): ChatMessage? {
        repeat(tries) { attempt ->
            try {
                val found = session.client.history(token, limit = CONFIRM_WINDOW).messages.lastOrNull { it.referenceId == key }
                if (found != null) {
                    session.absorb(token, listOf(found))
                    return found
                }
            } catch (e: TalkError) {
                return null
            }
            if (attempt < tries - 1) delay(CONFIRM_DELAY_MILLIS)
        }
        return null
    }

    /**
     * The live feed delivered a message with one of our keys: that file is in the chat, whatever
     * its entry says. This also covers a post whose answer was lost (the entry would otherwise
     * fail, and a retry would post the file twice).
     */
    internal fun onArrived(message: ChatMessage) {
        val key = message.referenceId
        val p = pending.value[key] ?: return
        val entry = _entries.value.firstOrNull { it.referenceId == key } ?: return
        p.uploaded = null
        settleTopic(key, p, entry, message)
        finish(key)
        p.job?.cancel()
    }

    private fun finish(key: String) {
        pending.update { it - key }
        _entries.update { list -> list.filterNot { it.referenceId == key } }
    }

    private fun setState(key: String, state: FileSendState, onlyWhileUploading: Boolean = false) {
        _entries.update { list ->
            list.map {
                if (it.referenceId != key || (onlyWhileUploading && it.state !is FileSendState.Uploading)) it else it.copy(state = state)
            }
        }
    }

    private companion object {
        const val CONFIRM_TRIES = 3
        const val CONFIRM_WINDOW = 30
        const val CONFIRM_DELAY_MILLIS = 800L
    }
}
