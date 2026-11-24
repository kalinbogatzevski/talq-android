package talq.core.folders

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentLength
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import talq.core.TalkClient

/**
 * The folder sync's WebDAV client: the user's OWN files on the account's own server (never the Talk
 * API), `remote.php/dav/files/<user id>/<name>`, as TalQ for desktop's ApiClient DAV additions do it.
 * Get it from `TalkSession.folderDav`.
 *
 *  - The path uses the CANONICAL user id (`/cloud/user`), never the login name: on a server where
 *    they differ the login name names somebody else's (or nobody's) home. Auth is the account's
 *    Basic header.
 *  - No redirect is ever followed: a followed one would send the request, the credentials included,
 *    somewhere else, and a followed PUT can come back as the 200 of a GET of its target. A 3xx is
 *    handed back as the answer (classifyDav reads it as Fatal).
 *  - Every call is one request and returns the HTTP status; an HTTP answer is never thrown. A
 *    transport failure (no connection, a timeout, a body cut short) is status 0. A cancellation of
 *    the caller is rethrown; a cancellation from inside the client while the caller is still active
 *    (a timeout surfacing that way) is status 0, so it can never end the sync job silently.
 *  - GET reads OC-ETag if present, else ETag, exactly as sent (quotes and any W/ prefix kept, never
 *    normalised: a proxy that compresses may rewrite ETag, OC-ETag is the file's own). Its body is
 *    capped at MAX_FOLDER_FILE_BYTES by the declared length AND by the bytes received, whatever the
 *    status (an error answer's body is read only to count it), as desktop's is.
 *  - Timeouts are transfer timeouts as desktop's (no data for that long ends the request), with an
 *    overall ceiling of six times that, which desktop does not have.
 */
class FolderDav internal constructor(
    private val client: TalkClient,
    /** The account id (NOT the login name): the WebDAV home is named after it. */
    private val userId: String,
    private val getTimeoutMs: Long = FolderSyncTiming().getTimeoutMs,
    private val putTimeoutMs: Long = FolderSyncTiming().putTimeoutMs,
) : FolderRemote {

    /** Same engine and settings as the API client, but a redirect is handed back, not followed. */
    private val raw: HttpClient = client.httpClient.config { followRedirects = false }

    override val accountKnown: Boolean get() = userId.isNotBlank()

    /** The file [name] at the root of the user's WebDAV home. */
    fun urlOf(name: String): String =
        "${client.serverUrl}/remote.php/dav/files/${userId.encodeURLPathPart()}/${name.encodeURLPathPart()}"

    override suspend fun get(name: String): DavGet {
        if (!accountKnown) return DavGet(0)
        return try {
            raw.prepareRequest(urlOf(name)) {
                method = HttpMethod.Get
                header("Authorization", client.authorizationHeader)
                timeout {
                    requestTimeoutMillis = getTimeoutMs * OVERALL_FACTOR
                    socketTimeoutMillis = getTimeoutMs
                }
            }.execute { response ->
                val status = response.status.value
                val etag = response.headers["OC-ETag"] ?: response.headers["ETag"] ?: ""
                // The cap holds for EVERY answer, as on desktop (sendGet stops any reply over it,
                // declared or received, and onGetFinished reads that before the status): an error
                // answer over 4 MiB is "too large" (RemoteUnreadable), never a 404's "absent" (which
                // would create the file from this device), a 401's pause or a 5xx's backoff.
                val declared = response.contentLength() ?: -1L
                if (declared > MAX_FOLDER_FILE_BYTES) return@execute DavGet(status, etag, oversize = true)
                if (status !in 200..299) return@execute DavGet(status, etag, oversize = errorBodyOverCap(response))
                val channel = response.bodyAsChannel()
                var buffer = ByteArray(if (declared in 0..MAX_FOLDER_FILE_BYTES.toLong()) declared.toInt() else INITIAL_BUFFER)
                var size = 0
                val chunk = ByteArray(CHUNK)
                while (true) {
                    val n = channel.readAvailable(chunk, 0, chunk.size)
                    if (n < 0) break
                    if (n == 0) continue
                    if (size + n > MAX_FOLDER_FILE_BYTES) return@execute DavGet(status, etag, oversize = true)
                    if (size + n > buffer.size) {
                        buffer = buffer.copyOf(maxOf(buffer.size * 2, size + n).coerceAtMost(MAX_FOLDER_FILE_BYTES))
                    }
                    chunk.copyInto(buffer, size, 0, n)
                    size += n
                }
                // A 2xx whose body ended before its declared length broke off: transient, never a
                // short body (an empty one would read as "nobody wrote yet" and be overwritten).
                if (declared >= 0 && size.toLong() != declared) return@execute DavGet(0)
                DavGet(status, etag, if (size == buffer.size) buffer else buffer.copyOf(size))
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            DavGet(0)
        } catch (e: Throwable) {
            DavGet(0)
        }
    }

    override suspend fun put(name: String, body: ByteArray, ifMatch: String?): Int {
        if (!accountKnown) return 0
        // Never an unconditional write: an empty If-Match is not sent at all (Fatal, by classifyDav).
        if (ifMatch != null && ifMatch.isEmpty()) return NOT_SENT
        return statusOf {
            raw.request(urlOf(name)) {
                method = HttpMethod.Put
                header("Authorization", client.authorizationHeader)
                if (ifMatch == null) header("If-None-Match", "*") else header("If-Match", ifMatch)
                timeout {
                    requestTimeoutMillis = putTimeoutMs * OVERALL_FACTOR
                    socketTimeoutMillis = putTimeoutMs
                }
                setBody(ByteArrayContent(body, ContentType.Application.Json))
            }.status.value
        }
    }

    override suspend fun delete(name: String): Int {
        if (!accountKnown) return 0
        return statusOf {
            raw.request(urlOf(name)) {
                method = HttpMethod.Delete
                header("Authorization", client.authorizationHeader)
                timeout {
                    requestTimeoutMillis = putTimeoutMs * OVERALL_FACTOR
                    socketTimeoutMillis = putTimeoutMs
                }
            }.status.value
        }
    }

    /**
     * Reads a non-2xx answer's body only to count it: true when it passes MAX_FOLDER_FILE_BYTES. Its
     * status stands whatever happens to the body (a transfer that breaks off is false here), as
     * desktop reads the status of a reply that ended in an error; only a 2xx is made transient by
     * that. A cancellation is rethrown.
     */
    private suspend fun errorBodyOverCap(response: HttpResponse): Boolean {
        try {
            val channel = response.bodyAsChannel()
            val chunk = ByteArray(CHUNK)
            var size = 0L
            while (true) {
                val n = channel.readAvailable(chunk, 0, chunk.size)
                if (n < 0) return false
                size += n
                if (size > MAX_FOLDER_FILE_BYTES) return true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return false
        }
    }

    /** The status [block] got, or 0 for a transport failure; the caller's cancellation is rethrown. */
    private suspend fun statusOf(block: suspend () -> Int): Int = try {
        block()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        0
    } catch (e: Throwable) {
        0
    }

    private companion object {
        const val OVERALL_FACTOR = 6L
        const val INITIAL_BUFFER = 16 * 1024
        const val CHUNK = 16 * 1024

        /** Not an HTTP status: the request was refused here and never sent. */
        const val NOT_SENT = -1
    }
}
