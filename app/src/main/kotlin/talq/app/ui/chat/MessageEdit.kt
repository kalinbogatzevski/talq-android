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
import talq.core.model.MessageRefusal
import talq.core.model.MessageRequest

/**
 * The one line for a refused edit or delete of a sent message. Desktop's reasons
 * (`MessageListModel.cpp` `messageRefusalText`), shortened to one line each; a code is never shown raw.
 */
fun messageRefusalText(request: MessageRequest, why: MessageRefusal, status: Int): String {
    val edit = request == MessageRequest.Edit
    val not = if (edit) "Not edited" else "Not deleted"
    return when (why) {
        MessageRefusal.NoConnection -> "$not: can't reach the server."
        MessageRefusal.TooOld -> "$not: a message can be edited for 24 hours."
        MessageRefusal.EmptyText -> "$not: the text is empty."
        MessageRefusal.TooLong -> "$not: the text is too long."
        MessageRefusal.NotYours -> if (edit) "$not: you can't change this message." else "$not: you can't delete this message."
        MessageRefusal.Gone -> "$not: the message is gone."
        MessageRefusal.WrongKind -> if (edit) "$not: this message can't be edited." else "$not: it's gone or can't be deleted."
        MessageRefusal.Unknown -> if (status > 0) "$not: the server refused it (HTTP $status)." else "$not: the server refused it."
    }
}

/**
 * "Delete this message?" with Delete and Cancel, asked once (desktop's question, one line). While
 * [deleting] the dialog stays up and takes nothing more: its Delete shows progress, Cancel and a tap
 * outside do nothing. The caller closes it when the server has answered.
 */
@Composable
fun DeleteMessageDialog(deleting: Boolean, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val colors = TalqTheme.colors
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("Delete this message?") },
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
