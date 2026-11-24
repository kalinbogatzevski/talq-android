package talq.core.model

import kotlinx.serialization.json.JsonObject
import talq.core.TalkError
import talq.core.net.bool
import talq.core.net.long
import talq.core.net.obj
import talq.core.net.str

/**
 * One message waiting to be sent later (`TalkScheduledMessage`, capability `scheduled-messages`).
 * Only the author sees it, in `GET /chat/{token}/schedule`.
 */
data class ScheduledMessage(
    /** A snowflake id as a numeric string: kept as text end to end. */
    val id: String,
    /**
     * The text exactly as the server holds it, which is what was typed (a schedule posts plain
     * text). An edit starts from this whole text, never from a shortened copy.
     */
    val message: String,
    /** Unix seconds when the server sends it. */
    val sendAt: Long,
    /** Sent without notifying anyone. An edit sends it back unchanged (the server overwrites it). */
    val silent: Boolean,
    /** The topic it goes into, 0 for none. */
    val threadId: Long,
    val threadTitle: String,
    /** The message it replies to, when it is a reply. */
    val parent: ChatMessage?,
) {
    companion object {
        fun fromJson(o: JsonObject): ScheduledMessage = ScheduledMessage(
            id = o.str("id"),
            message = o.str("message"),
            sendAt = o.long("sendAt"),
            silent = o.bool("silent"),
            threadId = o.long("threadId"),
            threadTitle = o.str("threadTitle"),
            parent = ChatMessage.fromJsonOrNull(o.obj("parent")),
        )
    }
}

// The rules below are ported from TalQ for desktop: src/core/ScheduleFailurePolicy.h (a refused
// schedule) and the EditScheduled / CancelScheduled arms of src/core/TalkRefusalPolicy.h. They
// only decide WHICH reason applies; the app words them, and a code is never shown raw.

/** HTTP status and the machine word in `ocs.data.error` of a failed request; status 0 = no answer. */
fun TalkError.statusAndCode(): Pair<Int, String> = when (this) {
    is TalkError.Network -> 0 to ""
    is TalkError.Unauthorized -> 401 to detail
    is TalkError.Forbidden -> 403 to detail
    is TalkError.NotFound -> 404 to detail
    is TalkError.QuotaExceeded -> 507 to detail
    is TalkError.Server -> status to detail
    is TalkError.Http -> status to detail
    // A 2xx that was not JSON: an answer, but none to read a reason from.
    is TalkError.Protocol, is TalkError.LocalFile -> -1 to ""
}

/** Why the server would not schedule a message (`POST /chat/{token}/schedule`). */
enum class ScheduleRefusal {
    /** Unrecognised, or no usable reason came back. */
    Unknown,

    /** Never reached the server. */
    NoConnection,

    /** The send time is not in the future (400 send-at). */
    PastTime,

    /** There was no text (400 message). */
    EmptyMessage,

    /** Over the server's limit (413). */
    TooLong,

    /** The message replied to, or the topic, is no longer there (400 reply-to). */
    ReplyGone,

    /** May not reply to that message from here (403 reply-to). */
    ReplyForbidden,

    /** Guests and self-joined users cannot schedule (404 actor). */
    NotAMember,
}

fun classifyScheduleFailure(status: Int, error: String): ScheduleRefusal = when {
    status == 0 -> ScheduleRefusal.NoConnection
    status == 413 -> ScheduleRefusal.TooLong
    error == "send-at" -> ScheduleRefusal.PastTime
    error == "message" -> ScheduleRefusal.EmptyMessage
    error == "reply-to" -> if (status == 403) ScheduleRefusal.ReplyForbidden else ScheduleRefusal.ReplyGone
    error == "actor" -> ScheduleRefusal.NotAMember
    else -> ScheduleRefusal.Unknown
}

/**
 * Whether a refused scheduled reply stays a reply. Not when the refusal is ABOUT the reply (the
 * message is gone, or may not be replied to from here): the same reply would be refused again.
 */
fun keepReplyAfterRefusal(why: ScheduleRefusal): Boolean =
    why != ScheduleRefusal.ReplyGone && why != ScheduleRefusal.ReplyForbidden

/** The two requests on a scheduled message that can be refused. */
enum class ScheduledRequest { Edit, Delete }

/** Why the server refused an edit or a delete of a scheduled message. */
enum class ScheduledRefusal {
    Unknown,
    NoConnection,

    /** It was sent or deleted already (404 message). */
    Gone,

    /** Guests and self-joined users have no scheduled messages (404 actor). */
    NotAMember,

    /** Edit: the new send time is not in the future (400 send-at). */
    PastTime,

    /** Edit: the new text is empty (400 message). */
    EmptyText,

    /** Edit: over the server's limit (413). */
    TooLong,
}

fun classifyScheduledRefusal(request: ScheduledRequest, status: Int, error: String): ScheduledRefusal {
    if (status == 0) return ScheduledRefusal.NoConnection
    if (request == ScheduledRequest.Edit) {
        if (status == 413) return ScheduledRefusal.TooLong
        if (status == 400 && error == "send-at") return ScheduledRefusal.PastTime
        if (status == 400 && error == "message") return ScheduledRefusal.EmptyText
    }
    if (status == 404 && error == "message") return ScheduledRefusal.Gone
    if (status == 404 && error == "actor") return ScheduledRefusal.NotAMember
    return ScheduledRefusal.Unknown
}
