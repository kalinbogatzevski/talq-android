package talq.app.ui.chat

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import talq.app.theme.TalqTheme
import talq.core.TalkError
import talq.core.model.ChatMessage
import talq.core.model.MessageRefusal
import talq.core.model.MessageRequest
import talq.core.model.canDeleteMessage
import talq.core.model.canEditMessage
import talq.core.model.classifyMessageRefusal
import talq.core.model.offersDelete
import talq.core.model.statusAndCode
import talq.core.repo.TalkSession

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

/** "Edit" may be offered for [m] of conversation [token] (rules in `talq.core.model` MessageEdits.kt). */
internal fun mayEdit(session: TalkSession, token: String, m: ChatMessage): Boolean {
    val room = session.rooms.room(token) ?: return false
    return canEditMessage(m, session.isMe(m.actorType, m.actorId), session.capabilities, room, nowSeconds())
}

/** "Delete" may be offered for [m] of conversation [token]: the message menu's and the selection bar's one rule. */
internal fun mayDelete(session: TalkSession, token: String, m: ChatMessage): Boolean {
    val room = session.rooms.room(token) ?: return false
    return canDeleteMessage(m, session.isMe(m.actorType, m.actorId), session.capabilities, room, nowSeconds())
}

/** The selection bar has a Delete in conversation [token] at all. */
internal fun deleteOffered(session: TalkSession, token: String): Boolean {
    val room = session.rooms.room(token) ?: return false
    return offersDelete(session.capabilities, room, nowSeconds())
}

/**
 * Why the server refused an edit or delete of a sent message: desktop's reasons (`MessageListModel.cpp`
 * `messageRefusalText`), shortened; a code is never shown raw.
 */
private fun refusalReason(request: MessageRequest, why: MessageRefusal, status: Int): String {
    val edit = request == MessageRequest.Edit
    return when (why) {
        MessageRefusal.NoConnection -> "can't reach the server"
        MessageRefusal.TooOld -> "a message can be edited for 24 hours"
        MessageRefusal.EmptyText -> "the text is empty"
        MessageRefusal.TooLong -> "the text is too long"
        MessageRefusal.NotYours -> if (edit) "you can't change this message" else "you can't delete this message"
        MessageRefusal.Gone -> "the message is gone"
        MessageRefusal.WrongKind -> if (edit) "this message can't be edited" else "it's gone or can't be deleted"
        MessageRefusal.Unknown -> if (status > 0) "the server refused it (HTTP $status)" else "the server refused it"
    }
}

/** The one line for a refused edit or delete of a sent message. */
fun messageRefusalText(request: MessageRequest, why: MessageRefusal, status: Int): String =
    (if (request == MessageRequest.Edit) "Not edited: " else "Not deleted: ") + refusalReason(request, why, status) + "."

/** The one line for a delete of [total] picked messages that stopped after [done] of them. */
fun deleteStoppedText(total: Int, done: Int, error: TalkError): String {
    val (status, code) = error.statusAndCode()
    val reason = refusalReason(MessageRequest.Delete, classifyMessageRefusal(MessageRequest.Delete, status, code), status)
    return if (done == 0) "Not deleted: $reason." else "Deleted $done of $total: $reason."
}

/**
 * "Delete this message?" (or, of several, "Delete [count] messages?") with Delete and Cancel, asked once
 * (desktop's question, one line). While [deleting] the dialog stays up and takes nothing more: its Delete
 * shows progress, Cancel and a tap outside do nothing. The caller closes it when the server has answered.
 */
@Composable
fun DeleteMessageDialog(deleting: Boolean, onDelete: () -> Unit, onDismiss: () -> Unit, count: Int = 1) {
    val colors = TalqTheme.colors
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(if (count == 1) "Delete this message?" else "Delete $count messages?") },
        confirmButton = {
            TextButton(onClick = onDelete, enabled = !deleting) {
                if (deleting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Deleting…")
                } else {
                    Text("Delete", color = colors.danger)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !deleting) { Text("Cancel") } },
    )
}
