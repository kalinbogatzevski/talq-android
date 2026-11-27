package talq.core.model

/*
 * Editing and deleting chat messages: which messages may be offered, the text an edit starts from, and
 * why the server refused. Ported from TalQ for desktop: the Edit / Delete entries of the message menu
 * (`MainWindow.cpp`, own messages only, no Edit on a file), `beginEditingMessage` +
 * `MessageListModel::rawBodyFor` (the edit starts from the server's raw text), `mentionsForEdit` in
 * `src/core/TaskListLogic.h` (the mentions go back as `@"<mention-id>"`) and the EditMessage /
 * DeleteMessage arms of `src/core/TalkRefusalPolicy.h`.
 *
 * The time limits are the server's (Talk 25.0.7 `ChatController::editMessage`, capabilities.md):
 * an edit within 24 hours of sending, any time in a note-to-self conversation where the server has
 * `edit-messages-note-to-self`; a delete within 6 hours unless it has `delete-messages-unlimited`.
 * Desktop offers both always and lets the server refuse; here they are only offered while the
 * server would still take them, and only where it takes posts at all ([Room.takesPosts]). The server
 * checks again either way.
 *
 * Other people's messages: Talk lets the conversation's owner and moderators edit and delete them,
 * except in a one-to-one conversation (`ChatController`, 403 `permission`). Delete is offered for them;
 * Edit is not (desktop offers it for own messages only).
 */

/** Seconds after sending that a message can still be edited (outside note-to-self). */
const val EDIT_WINDOW_SECONDS = 24 * 3600L

/** Seconds after sending that a message can still be deleted without `delete-messages-unlimited`. */
const val DELETE_WINDOW_SECONDS = 6 * 3600L

/** The message parameters that are not mentions (a file, a poll, a card, a location...). */
private fun nonMentionKeys(m: ChatMessage): List<String> = m.parameters.keys.filterNot { it.startsWith("mention") }

/**
 * The text an edit of [m] starts from: the server's raw text with every `{mention-*}` put back as
 * `@"<mention-id>"` (what the server matched when it made the placeholder: "all" for @all,
 * `group/<id>`, `guest/<id>`...; the plain `id` where a server sends no `mention-id`), so a saved
 * edit keeps its mentions. Null when the message cannot be edited as text: a file (desktop offers
 * no Edit there), a text that holds any other rich object (desktop flattens it and the server
 * refuses the edit), or no text at all.
 */
fun editableText(m: ChatMessage): String? {
    if (m.file != null || m.message.isEmpty()) return null
    if (carriesRichObject(m.message, nonMentionKeys(m))) return null
    var out = m.message
    for (p in m.parameters.values) {
        if (!p.key.startsWith("mention")) continue
        val id = p.value("mention-id").ifEmpty { if (isCallMention(p.key)) "all" else p.id }
        if (id.isEmpty() || '"' in id) return null
        out = out.replace("{${p.key}}", "@\"$id\"")
    }
    return out
}

/** A message the server still knows as an ordinary message. */
private fun isLiveMessage(m: ChatMessage): Boolean = m.id > 0 && !m.deleted && !m.isSystem && !m.isUpdateSignal

/** The user may delete other people's messages in [room]. Both people of a one-to-one are its owners: not there. */
private fun moderatesMessages(room: Room): Boolean =
    room.isModerator && room.type != RoomType.ONE_TO_ONE && room.type != RoomType.FORMER_ONE_TO_ONE

/**
 * May "Edit" be offered for [m] of [room]? [mine]: the signed-in user wrote it. [nowSeconds]: the
 * phone's clock (the server's own check decides in the end).
 */
fun canEditMessage(
    m: ChatMessage,
    mine: Boolean,
    capabilities: TalkCapabilities,
    room: Room,
    nowSeconds: Long,
): Boolean {
    if (!capabilities.has("edit-messages") || !room.takesPosts(capabilities, nowSeconds)) return false
    if (!mine || !isLiveMessage(m)) return false
    if (m.messageType != "comment" || editableText(m) == null) return false
    val unlimited = room.type == RoomType.NOTE_TO_SELF && capabilities.has("edit-messages-note-to-self")
    return unlimited || nowSeconds - m.timestamp < EDIT_WINDOW_SECONDS
}

/** Is "Delete" offered at all in [room] (the selection bar's button; each message has [canDeleteMessage])? */
fun offersDelete(capabilities: TalkCapabilities, room: Room, nowSeconds: Long): Boolean =
    capabilities.has("delete-messages") && room.takesPosts(capabilities, nowSeconds)

/**
 * May "Delete" be offered for [m] of [room]: an own message, or anybody's for the conversation's owner
 * and moderators (not in a one-to-one). A file or any other shared object also needs
 * `rich-object-delete` ("delete chat messages which are file or rich object shares").
 */
fun canDeleteMessage(
    m: ChatMessage,
    mine: Boolean,
    capabilities: TalkCapabilities,
    room: Room,
    nowSeconds: Long,
): Boolean {
    if (!offersDelete(capabilities, room, nowSeconds) || !isLiveMessage(m)) return false
    if (!mine && !moderatesMessages(room)) return false
    val shared = m.file != null || carriesRichObject(m.message, nonMentionKeys(m))
    if (shared && !capabilities.has("rich-object-delete")) return false
    return capabilities.has("delete-messages-unlimited") || nowSeconds - m.timestamp < DELETE_WINDOW_SECONDS
}

/** The two requests on a sent message that can be refused. */
enum class MessageRequest { Edit, Delete }

/** Why the server refused an edit or a delete (desktop's `TalkRefusal`, the arms these two use). */
enum class MessageRefusal {
    /** Unrecognised, or no usable reason came back. */
    Unknown,

    /** Never reached the server. */
    NoConnection,

    /** Edit: the message is not there (404 message). */
    Gone,

    /** Not the author, and not a moderator who may (403 permission). */
    NotYours,

    /** A system message, a shared object, or (delete) already deleted (405 message). */
    WrongKind,

    /** Edit: more than 24 hours after sending (400 age). */
    TooOld,

    /** Edit: the new text is empty (400 message). */
    EmptyText,

    /** Edit: over the server's limit (413). */
    TooLong,
}

/**
 * Desktop's `classifyTalkRefusal` for EditMessage / DeleteMessage. A delete's 404 `message` is
 * either a missing message or a file whose share is gone already (the message stays): not one
 * reason, so it is left Unknown, as on desktop.
 */
fun classifyMessageRefusal(request: MessageRequest, status: Int, error: String): MessageRefusal {
    if (status == 0) return MessageRefusal.NoConnection
    return when (request) {
        MessageRequest.Edit -> when {
            status == 413 -> MessageRefusal.TooLong
            status == 400 && error == "age" -> MessageRefusal.TooOld
            status == 400 && error == "message" -> MessageRefusal.EmptyText
            status == 403 && error == "permission" -> MessageRefusal.NotYours
            status == 404 && error == "message" -> MessageRefusal.Gone
            status == 405 && error == "message" -> MessageRefusal.WrongKind
            else -> MessageRefusal.Unknown
        }
        MessageRequest.Delete -> when {
            status == 403 && error == "permission" -> MessageRefusal.NotYours
            status == 405 && error == "message" -> MessageRefusal.WrongKind
            else -> MessageRefusal.Unknown
        }
    }
}
