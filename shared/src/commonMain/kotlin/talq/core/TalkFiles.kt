package talq.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentLength
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart
import io.ktor.http.takeFrom
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import talq.core.files.ByteReader
import talq.core.files.ByteSink
import talq.core.files.ByteSource
import talq.core.files.FileNames
import talq.core.model.AttachmentConfig
import talq.core.model.ChatMessage
import talq.core.model.RichObject
import talq.core.net.asObject
import talq.core.net.str
import kotlin.random.Random

/** A file that is on the server but not in a chat yet: the result of [TalkFiles.upload]. */
data class UploadedFile(
    /** Path relative to the user's root, e.g. "Talk/photo.jpg". */
    val path: String,
    /** The name the file should have in the chat (already sanitized). */
    val name: String,
    /**
     * True: [path] is a temporary name in the server's draft folder and [TalkFiles.share] posts
     * it with the attachment endpoint. False: it is in the attachment folder and gets a share.
     */
    val draft: Boolean,
)

/**
 * How far a chunked upload got. Hand the same instance to [TalkFiles.upload] again and the
 * upload goes on after the last chunk the server has, instead of from the start. Empty for a
 * small file (one request, nothing to resume).
 */
class UploadResume {
    internal var collection: String = ""
    internal var target: UploadedFile? = null
    internal var chunkSize: Int = 0

    /** Chunks 1..[chunks] are on the server; they hold the first [offset] bytes. */
    internal var chunks: Int = 0
    internal var offset: Long = 0

    internal fun clear() {
        collection = ""
        target = null
        chunkSize = 0
        chunks = 0
        offset = 0
    }
}

/** Everything about a file message that is not the file (`talkMetaData`). */
data class FileShareOptions(
    /** Shown with the file (capability `media-caption`). */
    val caption: String = "",
    /** Topic the file goes into, 0 for none. Without it the file lands in the room root. */
    val threadId: Long = 0,
    /** Id of the message this one quotes, 0 for none. */
    val replyTo: Long = 0,
    /** Not empty: the file message STARTS a topic of this name (then no [threadId]/[replyTo]). */
    val threadTitle: String = "",
    /** Send as a voice message. The server only honours it for audio/mpeg and audio/wav. */
    val voiceMessage: Boolean = false,
    val silent: Boolean = false,
)

/** The kinds the shared-items endpoints sort a chat's attachments into. */
enum class SharedItemType(val key: String) {
    /** Images and videos. */
    MEDIA("media"),

    /** Files that are neither media, audio nor voice messages. */
    FILE("file"),
    AUDIO("audio"),
    VOICE("voice"),
    RECORDING("recording"),
    LOCATION("location"),
    DECK_CARD("deckcard"),
    POLL("poll"),
    PINNED("pinned"),
    OTHER("other");

    companion object {
        fun of(key: String): SharedItemType? = entries.firstOrNull { it.key == key }
    }
}

/** One page of a chat's shared items of one type, newest first. */
data class SharedItemsPage(
    val type: SharedItemType,
    val messages: List<ChatMessage>,
    /** `lastKnownMessageId` for the next (older) page; null when this was the last one. */
    val nextCursor: Long?,
)

/** The newest few shared items of every type (types with nothing are absent). */
data class SharedItemsOverview(val byType: Map<SharedItemType, List<ChatMessage>>) {
    fun of(type: SharedItemType): List<ChatMessage> = byType[type] ?: emptyList()
    val isEmpty: Boolean get() = byType.values.all { it.isEmpty() }
}

/**
 * Files of one account: upload for a chat, post into a chat, download, shared items. Every
 * function is one operation and throws [TalkError]; cancel the calling coroutine to cancel a
 * transfer. Get it from `TalkSession.files`.
 *
 * The `Authorization` header goes to the account's own server ONLY. Uploads never follow a
 * redirect; downloads follow them by hand and drop the header as soon as the target is another
 * host (or the same host over another scheme or port).
 */
class TalkFiles(
    private val client: TalkClient,
    /** The account id (NOT the login name): the WebDAV home is named after it. */
    val userId: String,
    val attachments: AttachmentConfig,
) {
    private val serverUrl get() = client.serverUrl
    private val origin = Url(client.serverUrl)

    /** Same engine and settings as the API client, but a redirect is handed back, not followed. */
    private val raw: HttpClient = client.httpClient.config { followRedirects = false }

    private val davUser get() = userId.encodeURLPathPart()

    // ---------------------------------------------------------------- the auth rule

    /** True when [url] is on the account's own server: same scheme, host and port. */
    fun isOwnServer(url: String): Boolean = try {
        isOwnServer(Url(url))
    } catch (e: Exception) {
        false
    }

    private fun isOwnServer(url: Url): Boolean =
        url.protocol.name == origin.protocol.name && url.host.equals(origin.host, ignoreCase = true) && url.port == origin.port

    /**
     * The `Authorization` header value for a request to [url], or null when [url] is not on
     * the account's server. Media players and image loaders must ask this for EVERY request,
     * a redirected one included, instead of attaching the header themselves.
     */
    fun authorizationFor(url: String): String? = if (isOwnServer(url)) client.authorizationHeader else null

    // ---------------------------------------------------------------- URLs

    /** WebDAV URL of a file by its path in the user's own files. */
    fun davUrl(path: String): String = "$serverUrl/remote.php/dav/files/$davUser/${FileNames.encodeDavPath(path)}"

    /** Preview image of a file ([RichObject.id]); the server keeps the aspect ratio. */
    fun previewUrl(fileId: String, width: Int = 480, height: Int = 480): String =
        client.filePreviewUrl(fileId, width, height)

    // ---------------------------------------------------------------- upload

    /**
     * Put [source] on the server, ready to be posted into the chat [token] with [share].
     * Small files go up in one PUT; above [chunkThreshold] in chunks of [chunkSize], where a
     * failed chunk is sent again on its own. [onProgress] gets (bytes sent, total).
     *
     * Cancelling removes the chunk collection again. A failure keeps it when a [resume] was
     * given: the next call with the same [resume] continues after the last complete chunk
     * (drop it with [discardResume] when there will be no next call). Never overwrites a file
     * of the same name.
     */
    suspend fun upload(
        token: String,
        source: ByteSource,
        fileName: String,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
        chunkThreshold: Long = CHUNK_THRESHOLD,
        chunkSize: Int = CHUNK_SIZE,
        resume: UploadResume? = null,
    ): UploadedFile {
        if (!attachments.allowed) throw TalkError.Forbidden("uploads are not allowed for this account")
        // Outside the draft flow the name is picked by "is it free?" and then written: two
        // uploads of a same-named file must not both find it free.
        return if (attachments.conversationSubfolders) uploadOne(token, source, fileName, onProgress, chunkThreshold, chunkSize, resume)
        else plainTurn.withLock { uploadOne(token, source, fileName, onProgress, chunkThreshold, chunkSize, resume) }
    }

    private val plainTurn = Mutex()

    private suspend fun uploadOne(
        token: String,
        source: ByteSource,
        fileName: String,
        onProgress: (sent: Long, total: Long) -> Unit,
        chunkThreshold: Long,
        chunkSize: Int,
        resume: UploadResume?,
    ): UploadedFile {
        val size = source.size
        if (size < 0) throw TalkError.LocalFile(null, "the size of the file is not known")
        val name = FileNames.sanitize(fileName)
        val state = resume ?: UploadResume()
        if (state.collection.isNotEmpty() && (state.offset > size || state.target == null)) state.clear()
        val resuming = state.collection.isNotEmpty()
        val target = state.target ?: prepareTarget(token, name)
        val reader = try {
            source.open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw TalkError.LocalFile(e)
        }
        try {
            onProgress(0, size)
            if (size <= chunkThreshold) {
                val bytes = ByteArray(size.toInt())
                readExactly(reader, bytes, bytes.size)
                val status = put(davUrl(target.path), bytes, bytes.size) { onProgress(it.toLong(), size) }
                if (status != 201 && status != 204) throw davError("upload", status)
            } else {
                if (!resuming) {
                    state.target = target
                    state.chunkSize = chunkSize.coerceAtLeast(1)
                }
                try {
                    uploadChunked(reader, size, target.path, state, onProgress)
                } catch (e: StaleUpload) {
                    // The server no longer has the chunks of the earlier attempt (it removes
                    // them after a day): start over, once.
                    state.clear()
                    try { reader.close() } catch (_: Throwable) {}
                    return uploadOne(token, source, fileName, onProgress, chunkThreshold, chunkSize, resume)
                } catch (e: Throwable) {
                    // Kept for the next try only when the caller holds on to the state.
                    if (resume == null || e is CancellationException) discardResume(state)
                    throw e
                }
                state.clear()
            }
            onProgress(size, size)
        } finally {
            try { reader.close() } catch (_: Throwable) {}
        }
        return target
    }

    private class StaleUpload : Exception()

    /** Remove what a failed chunked upload left on the server. Best effort, never throws. */
    suspend fun discardResume(resume: UploadResume) {
        val collection = resume.collection
        resume.clear()
        if (collection.isEmpty()) return
        withContext(NonCancellable) {
            try { dav(HttpMethod.Delete, collection) } catch (_: Throwable) {}
        }
    }

    /**
     * Where the file goes. With conversation subfolders the server names a draft folder (not
     * visible to the room) and the file goes there under a random name, keeping its extension
     * because the server derives the type from it. Otherwise it goes into the user's
     * attachment folder under its own name, numbered when that name is taken.
     */
    private suspend fun prepareTarget(token: String, name: String): UploadedFile {
        if (attachments.conversationSubfolders) {
            val r = client.ocsRaw(
                HttpMethod.Post, "apps/spreed/api/v1/chat/${token.encodeURLPathPart()}/attachment/folder",
                body = buildJsonObject { put("fileNames", JsonArray(listOf(JsonPrimitive(name)))) },
            )
            if (r.isSuccess) {
                val folder = r.data.asObject()?.str("folder").orEmpty().trim('/')
                if (folder.isEmpty()) throw TalkError.Protocol("attachment folder: no folder in the answer")
                return UploadedFile("$folder/talq-${randomHex(32)}${FileNames.extension(name)}", name, draft = true)
            }
            // 501: the admin turned the feature off since the capabilities were read.
            if (r.status != 501) throw TalkClient.errorFor(r.status, r.detail)
        }
        val folder = attachments.folder
        // A fresh account may never have had the folder made: 201 created, 405 already there.
        val made = dav(HttpMethod("MKCOL"), "$serverUrl/remote.php/dav/files/$davUser/${FileNames.encodeDavPath(folder)}")
        if (made != 201 && made != 405) throw davError("attachment folder", made)
        var candidate = name
        var n = 1
        while (exists("$folder/$candidate")) {
            if (n >= MAX_NAME_TRIES) throw TalkError.Http(409, "no free name for $name")
            candidate = FileNames.numbered(name, ++n)
        }
        return UploadedFile("$folder/$candidate", candidate, draft = false)
    }

    private suspend fun exists(path: String): Boolean = when (val status = dav(HttpMethod.Head, davUrl(path))) {
        in 200..299 -> true
        404 -> false
        else -> throw davError("name check", status)
    }

    /**
     * Nextcloud chunked upload: a collection under `dav/uploads`, numbered chunk PUTs, then a
     * MOVE of its `.file` to the destination. `Destination` and `OC-Total-Length` go on the
     * MOVE only (this server answers 404 to chunk PUTs that carry a Destination).
     */
    private suspend fun uploadChunked(
        reader: ByteReader,
        size: Long,
        destPath: String,
        state: UploadResume,
        onProgress: (Long, Long) -> Unit,
    ) {
        val resumed = state.collection.isNotEmpty()
        if (!resumed) {
            val collection = "$serverUrl/remote.php/dav/uploads/$davUser/talq-${randomHex(32)}"
            val made = dav(HttpMethod("MKCOL"), collection)
            if (made != 201 && made != 405) throw davError("start of the upload", made)
            state.collection = collection
            state.chunks = 0
            state.offset = 0
        }
        val collection = state.collection
        val buffer = ByteArray(minOf(state.chunkSize.toLong(), size).toInt())
        if (resumed) {
            // Read past what the server already has.
            var skipped = 0L
            while (skipped < state.offset) {
                val n = minOf(buffer.size.toLong(), state.offset - skipped).toInt()
                readExactly(reader, buffer, n)
                skipped += n
            }
            onProgress(state.offset, size)
        }
        while (state.offset < size) {
            val length = minOf(buffer.size.toLong(), size - state.offset).toInt()
            readExactly(reader, buffer, length)
            val base = state.offset
            try {
                putChunk("$collection/${(state.chunks + 1).toString().padStart(5, '0')}", buffer, length) { onProgress(base + it, size) }
            } catch (e: TalkError) {
                if (resumed && (e is TalkError.NotFound || (e is TalkError.Http && e.status == 409))) throw StaleUpload()
                throw e
            }
            state.chunks++
            state.offset += length
        }
        // Assembling a large file sends nothing for a long time: no ordinary timeout.
        val moved = dav(
            HttpMethod("MOVE"), "$collection/.file",
            headers = mapOf("Destination" to davUrl(destPath), "OC-Total-Length" to size.toString()),
            timeoutMillis = ASSEMBLE_TIMEOUT_MILLIS,
        )
        if (moved == 201 || moved == 204) return
        if (resumed && moved == 404) {
            // An earlier MOVE whose answer was lost may have done it already.
            if (exists(destPath)) return
            throw StaleUpload()
        }
        throw davError("assembling the file", moved)
    }

    /** One chunk, sent again on its own when the network or the server stumbled. */
    private suspend fun putChunk(url: String, bytes: ByteArray, length: Int, onSent: (Int) -> Unit) {
        var attempt = 0
        while (true) {
            attempt++
            val failure: TalkError = try {
                val status = put(url, bytes, length, onSent)
                if (status == 201 || status == 204 || status == 200) return
                davError("upload chunk", status)
            } catch (e: TalkError.Network) {
                e
            }
            val retryable = failure is TalkError.Network || failure is TalkError.Server ||
                (failure is TalkError.Http && (failure.status == 423 || failure.status == 429))
            if (!retryable || attempt >= CHUNK_ATTEMPTS) throw failure
            // 2, 4, 8, 16, then 30 s: about three minutes in all, enough for a tunnel, a lift
            // or a change of network.
            delay(minOf(CHUNK_RETRY_MILLIS shl (attempt - 1), CHUNK_RETRY_MAX_MILLIS))
        }
    }

    private suspend fun readExactly(reader: ByteReader, buffer: ByteArray, length: Int) {
        var got = 0
        try {
            while (got < length) {
                val n = reader.read(buffer, got, length - got)
                if (n < 0) break
                got += n
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw TalkError.LocalFile(e)
        }
        if (got < length) throw TalkError.LocalFile(null, "the file is shorter than its size says")
    }

    // ---------------------------------------------------------------- post into a chat

    /**
     * Post an uploaded file into the chat [token] as a message. [referenceId] comes back on
     * the message, so a pending entry can be matched to it. Returns the name the file ended up
     * with (the server numbers it when the name is taken in the conversation folder).
     */
    suspend fun share(
        token: String,
        file: UploadedFile,
        referenceId: String = "",
        options: FileShareOptions = FileShareOptions(),
    ): String {
        val meta = buildJsonObject {
            if (options.voiceMessage) put("messageType", "voice-message")
            if (options.caption.isNotBlank()) put("caption", options.caption.trim())
            if (options.threadTitle.isNotBlank()) {
                put("threadTitle", options.threadTitle.trim())
            } else {
                if (options.replyTo > 0) put("replyTo", options.replyTo)
                if (options.threadId > 0) put("threadId", options.threadId)
            }
            if (options.silent) put("silent", true)
        }
        if (file.draft) {
            val r = client.ocs(
                HttpMethod.Post, "apps/spreed/api/v1/chat/${token.encodeURLPathPart()}/attachment",
                body = buildJsonObject {
                    put("filePath", file.path)
                    put("fileName", file.name)
                    put("referenceId", referenceId)
                    if (meta.isNotEmpty()) put("talkMetaData", meta.toString())
                },
                timeoutMillis = SHARE_TIMEOUT_MILLIS,
            )
            // [{"wanted name": "final name"}]
            val renamed = (r.data.asObject()?.get("renames") as? JsonArray)?.firstOrNull()?.asObject()?.values?.firstOrNull()
            return TalkClient.primitiveText(renamed).ifEmpty { file.name }
        }
        // Files Sharing API, share type 10 = a Talk conversation. Read-only for the recipients.
        client.ocs(
            HttpMethod.Post, "apps/files_sharing/api/v1/shares",
            body = buildJsonObject {
                put("shareType", 10)
                put("shareWith", token)
                put("path", file.path)
                put("permissions", 1)
                if (referenceId.isNotEmpty()) put("referenceId", referenceId)
                if (meta.isNotEmpty()) put("talkMetaData", meta.toString())
            },
            timeoutMillis = SHARE_TIMEOUT_MILLIS,
        )
        return file.name
    }

    /** Remove an uploaded file that will not be posted after all. Best effort, never throws. */
    suspend fun discardUpload(file: UploadedFile) {
        try { dav(HttpMethod.Delete, davUrl(file.path)) } catch (_: TalkError) {}
    }

    // ---------------------------------------------------------------- download

    /**
     * Download the file of a chat message into [sink]. [onProgress] gets (bytes received,
     * total or -1 when the server did not say). Nothing is written unless the server answered
     * with the file itself.
     */
    suspend fun download(file: RichObject, sink: ByteSink, onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }) =
        download(file.fileId, file.path, sink, onProgress)

    /**
     * Download by the reader's own WebDAV [path] (as the file's rich object gives it). When the
     * path is unknown or no longer right (moved, renamed) the file is looked up by [fileId].
     */
    suspend fun download(fileId: Long, path: String, sink: ByteSink, onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }) {
        if (path.isNotBlank()) {
            try {
                return downloadUrl(davUrl(path), sink, onProgress)
            } catch (e: TalkError.NotFound) {
                if (fileId <= 0) throw e
            }
        }
        if (fileId <= 0) throw TalkError.NotFound("no path and no file id")
        val found = pathOfFile(fileId) ?: throw TalkError.NotFound("file $fileId is not in this account's files")
        downloadUrl(davUrl(found), sink, onProgress)
    }

    /** Download a preview image of a file (JPEG or PNG, at most [width] x [height]). */
    suspend fun downloadPreview(fileId: String, width: Int, height: Int, sink: ByteSink) =
        downloadUrl(previewUrl(fileId, width, height), sink)

    /**
     * GET [url] into [sink]. Redirects are followed here, one by one (at most [MAX_REDIRECTS]),
     * and each hop gets the `Authorization` header only when it is on the account's own server.
     */
    suspend fun downloadUrl(url: String, sink: ByteSink, onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }) {
        var current = try {
            Url(url)
        } catch (e: Exception) {
            throw TalkError.Protocol("not a URL: $url", e)
        }
        if (current.protocol.name != "https" && current.protocol.name != "http") throw TalkError.Protocol("not a web URL")
        var hops = 0
        while (true) {
            val target = current
            val own = isOwnServer(target)
            val next: Url? = guarded {
                raw.prepareRequest(target) {
                    method = HttpMethod.Get
                    if (own) header("Authorization", client.authorizationHeader)
                    timeout {
                        requestTimeoutMillis = TRANSFER_TIMEOUT_MILLIS
                        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
                    }
                }.execute { response ->
                    val status = response.status.value
                    when {
                        status in REDIRECTS -> {
                            val location = response.headers["Location"] ?: throw TalkError.Protocol("redirect without a target")
                            URLBuilder(target).takeFrom(location).build()
                        }
                        status in 200..299 -> {
                            receive(response, sink, onProgress)
                            null
                        }
                        else -> throw TalkClient.errorFor(status, "download")
                    }
                }
            }
            current = next ?: return
            if (++hops > MAX_REDIRECTS) throw TalkError.Protocol("too many redirects")
            // Never from https down to http, wherever it points.
            if (target.protocol.name == "https" && current.protocol.name != "https") throw TalkError.Protocol("redirect to an insecure address")
        }
    }

    private suspend fun receive(response: HttpResponse, sink: ByteSink, onProgress: (Long, Long) -> Unit) {
        val total = response.contentLength() ?: -1L
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(BUFFER)
        var received = 0L
        onProgress(0, total)
        while (true) {
            val n = channel.readAvailable(buffer, 0, buffer.size)
            if (n < 0) break
            if (n == 0) continue
            try {
                sink.write(buffer, 0, n)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw TalkError.LocalFile(e)
            }
            received += n
            onProgress(received, total)
        }
        if (total >= 0 && received != total) throw TalkError.Network(IllegalStateException("the download ended early ($received of $total bytes)"))
    }

    /** The path (in the user's own files) of the file with this id, or null: WebDAV SEARCH. */
    suspend fun pathOfFile(fileId: Long): String? {
        val body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<d:searchrequest xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\"><d:basicsearch>" +
            "<d:select><d:prop><oc:fileid/></d:prop></d:select>" +
            "<d:from><d:scope><d:href>/files/${xmlEscape(userId)}</d:href><d:depth>infinity</d:depth></d:scope></d:from>" +
            "<d:where><d:eq><d:prop><oc:fileid/></d:prop><d:literal>$fileId</d:literal></d:eq></d:where>" +
            "<d:limit><d:nresults>1</d:nresults></d:limit>" +
            "</d:basicsearch></d:searchrequest>"
        val xml = guarded {
            val response = raw.request("$serverUrl/remote.php/dav/") {
                method = HttpMethod("SEARCH")
                header("Authorization", client.authorizationHeader)
                setBody(TextContent(body, ContentType.Text.Xml))
            }
            if (response.status.value != 207) throw davError("file lookup", response.status.value)
            response.bodyAsText()
        }
        val marker = "/remote.php/dav/files/"
        val at = xml.indexOf(marker)
        if (at < 0) return null
        val end = xml.indexOf('<', at)
        if (end < 0) return null
        // ".../files/<user>/<path>": the path is what follows the user segment.
        val afterUser = xml.substring(at + marker.length, end).substringAfter('/', "")
        return afterUser.split('/').joinToString("/") { it.decodeURLPart() }.trim('/').ifEmpty { null }
    }

    // ---------------------------------------------------------------- shared items

    /** The newest [limit] (1..20) shared items of every type. Capability `rich-object-list-media`. */
    suspend fun sharedOverview(token: String, limit: Int = 7): SharedItemsOverview {
        val r = client.ocs(
            HttpMethod.Get, "apps/spreed/api/v1/chat/${token.encodeURLPathPart()}/share/overview",
            mapOf("limit" to limit),
        )
        val byType = LinkedHashMap<SharedItemType, List<ChatMessage>>()
        r.data.asObject()?.forEach { (key, value) ->
            val type = SharedItemType.of(key) ?: return@forEach
            val messages = messagesOf(value)
            if (messages.isNotEmpty()) byType[type] = messages
        }
        return SharedItemsOverview(byType)
    }

    /**
     * One page of the shared items of [type], newest first. Pass the previous page's
     * [SharedItemsPage.nextCursor] as [lastKnownMessageId] for the next one (0 = newest).
     */
    suspend fun sharedItems(token: String, type: SharedItemType, lastKnownMessageId: Long = 0, limit: Int = 100): SharedItemsPage {
        val r = client.ocs(
            HttpMethod.Get, "apps/spreed/api/v1/chat/${token.encodeURLPathPart()}/share",
            mapOf("objectType" to type.key, "limit" to limit, "lastKnownMessageId" to lastKnownMessageId.takeIf { it > 0 }),
        )
        val messages = messagesOf(r.data)
        val cursor = r.headers["X-Chat-Last-Given"]?.toLongOrNull()
        return SharedItemsPage(type, messages, cursor?.takeIf { messages.isNotEmpty() && it != lastKnownMessageId })
    }

    /** PHP sends a list as `[...]` and a map keyed by message id as `{...}`: both are read. */
    private fun messagesOf(data: JsonElement?): List<ChatMessage> {
        val items: Collection<JsonElement> = when (data) {
            is JsonArray -> data
            is JsonObject -> data.values
            else -> emptyList()
        }
        return items.mapNotNull { it.asObject() }.filter { it.containsKey("id") }.map { ChatMessage.fromJson(it) }
            .sortedByDescending { it.id }
    }

    // ---------------------------------------------------------------- transport

    /** Network failures become [TalkError.Network]; a [TalkError] and a cancellation pass through. */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TalkError) {
        throw e
    } catch (e: Throwable) {
        throw TalkError.Network(e)
    }

    /** A body-less WebDAV request to the account's own server. Returns the status; no redirects. */
    private suspend fun dav(
        method: HttpMethod,
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long? = null,
    ): Int = guarded {
        raw.request(url) {
            this.method = method
            header("Authorization", client.authorizationHeader)
            for ((k, v) in headers) header(k, v)
            if (timeoutMillis != null) timeout {
                requestTimeoutMillis = timeoutMillis
                socketTimeoutMillis = timeoutMillis
            }
        }.status.value
    }

    /** PUT the first [length] bytes of [bytes]. [onSent] gets the bytes handed to the network so far. */
    private suspend fun put(url: String, bytes: ByteArray, length: Int, onSent: (Int) -> Unit): Int = guarded {
        raw.request(url) {
            method = HttpMethod.Put
            header("Authorization", client.authorizationHeader)
            timeout {
                requestTimeoutMillis = TRANSFER_TIMEOUT_MILLIS
                socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
            }
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentLength: Long = length.toLong()
                override val contentType: ContentType = ContentType.Application.OctetStream

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    var offset = 0
                    while (offset < length) {
                        val n = minOf(BUFFER, length - offset)
                        channel.writeFully(bytes, offset, offset + n)
                        channel.flush()
                        offset += n
                        onSent(offset)
                    }
                }
            })
        }.status.value
    }

    private fun davError(what: String, status: Int): TalkError =
        if (status in REDIRECTS) TalkError.Protocol("$what: the server redirected the request (HTTP $status)")
        else TalkClient.errorFor(status, what)

    private fun xmlEscape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        /** Files larger than this go up in chunks. */
        const val CHUNK_THRESHOLD = 10L * 1024 * 1024

        /** Nextcloud accepts chunks of 5 MB to 5 GB (the last one may be smaller). */
        const val CHUNK_SIZE = 10 * 1024 * 1024

        private const val CHUNK_ATTEMPTS = 10
        private const val CHUNK_RETRY_MILLIS = 2_000L
        private const val CHUNK_RETRY_MAX_MILLIS = 30_000L
        private const val MAX_NAME_TRIES = 200
        private const val MAX_REDIRECTS = 5
        private const val BUFFER = 64 * 1024
        private const val TRANSFER_TIMEOUT_MILLIS = 6 * 60 * 60 * 1000L
        private const val SOCKET_TIMEOUT_MILLIS = 120_000L
        private const val ASSEMBLE_TIMEOUT_MILLIS = 15 * 60 * 1000L
        private const val SHARE_TIMEOUT_MILLIS = 120_000L
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        internal fun randomHex(length: Int): String {
            val hex = "0123456789abcdef"
            return buildString(length) { repeat(length) { append(hex[Random.nextInt(16)]) } }
        }
    }
}
