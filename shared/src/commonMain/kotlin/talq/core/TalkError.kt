package talq.core

/**
 * Every failure the core reports. Repositories catch these and expose them as state; the raw
 * client throws them.
 */
sealed class TalkError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 401: the app password is wrong or was revoked. The UI should send the user to login. */
    class Unauthorized(val detail: String = "") : TalkError("Not signed in ($detail)")

    /** 403: not allowed (read-only room, missing permission, feature disabled by the admin). */
    class Forbidden(val detail: String = "") : TalkError("Not allowed ($detail)")

    /** 404: room, message or topic does not exist (or the user is not in the room). */
    class NotFound(val detail: String = "") : TalkError("Not found ($detail)")

    /** The request never got an answer: offline, DNS, TLS, timeout. Safe to retry. */
    class Network(cause: Throwable) : TalkError("Network error: ${cause.message ?: cause::class.simpleName}", cause)

    /** 5xx from the server. */
    class Server(val status: Int, val detail: String = "") : TalkError("Server error $status ($detail)")

    /** Any other non-success status (400, 412 lobby, 413 too long, 429 ...). */
    class Http(val status: Int, val detail: String = "") : TalkError("HTTP $status ($detail)")

    /** The server answered with something that is not the documented shape. */
    class Protocol(detail: String, cause: Throwable? = null) : TalkError("Unexpected server response: $detail", cause)

    /** 507: the user's storage is full (or the upload does not fit in what is left). */
    class QuotaExceeded(val detail: String = "") : TalkError("Storage full ($detail)")

    /** The file to send could not be read, or the downloaded file could not be written. */
    class LocalFile(cause: Throwable?, detail: String = "") :
        TalkError("Local file error: ${detail.ifEmpty { cause?.message ?: cause?.let { it::class.simpleName } ?: "unknown" }}", cause)
}
