package talq.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import talq.app.appContainer
import talq.app.theme.TalqTheme
import talq.core.TalkError
import talq.core.model.ScheduledMessage
import talq.core.model.ScheduledRefusal
import talq.core.model.ScheduledRequest
import talq.core.model.classifyScheduledRefusal
import talq.core.model.statusAndCode
import talq.core.repo.TalkSession
import java.time.Instant
import java.time.ZoneId

/** A new send time must be at least this far ahead (TalQ for desktop's edit picker minimum). */
private const val MIN_LEAD_SECONDS = 60L

private const val GONE_TEXT = "That message was already sent or deleted."
private const val NOT_A_MEMBER_TEXT = "Scheduled messages need you to be a member here."

/** Why an edit or a delete was refused, in one line (TalQ for desktop's ScheduledMessagesDialog::showRefusal). */
private fun scheduledRefusalText(request: ScheduledRequest, why: ScheduledRefusal, status: Int): String {
    val verb = if (request == ScheduledRequest.Edit) "Couldn't save" else "Couldn't delete"
    return when (why) {
        ScheduledRefusal.NoConnection -> "$verb: can't reach the server."
        ScheduledRefusal.Gone -> GONE_TEXT
        ScheduledRefusal.NotAMember -> NOT_A_MEMBER_TEXT
        ScheduledRefusal.PastTime -> "$verb: that time has already passed."
        ScheduledRefusal.EmptyText -> "$verb: the message can't be empty."
        ScheduledRefusal.TooLong -> "$verb: the message is too long."
        ScheduledRefusal.Unknown -> if (status > 0) "$verb (HTTP $status)." else "$verb."
    }
}

/**
 * "Scheduled messages" from the conversation's menu (capability `scheduled-messages`): the user's
 * own messages waiting in this room, soonest first, each with Edit (text and time; its silent
 * flag is sent back as it was) and Delete. Ported from TalQ for desktop's ScheduledMessagesDialog:
 * GET, POST and DELETE `/chat/{token}/schedule[/{id}]`. A message that is no longer waiting is
 * taken off the list (reloaded first) and that is said; every other refusal is said in one line.
 * Each Delete and Save disables itself and shows progress while its request runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledMessagesSheet(session: TalkSession, token: String, onDismiss: () -> Unit) {
    val colors = TalqTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // null until the first load has answered.
    var items by remember { mutableStateOf<List<ScheduledMessage>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // The last refusal of an edit or a delete.
    var note by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ScheduledMessage?>(null) }
    var saving by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true
        try {
            items = session.client.scheduledMessages(token).sortedBy { it.sendAt }
            loadError = null
        } catch (e: TalkError) {
            if (e is TalkError.Unauthorized) context.appContainer.onUnauthorized()
            val (status, code) = e.statusAndCode()
            loadError = if (status == 404 && code == "actor") NOT_A_MEMBER_TEXT else "Couldn't load scheduled messages."
        } finally {
            loading = false
        }
    }
    LaunchedEffect(token) { load() }

    fun delete(m: ScheduledMessage) {
        if (deleting != null) return
        deleting = m.id
        note = null
        scope.launch {
            try {
                session.client.deleteScheduledMessage(token, m.id)
                items = items?.filterNot { it.id == m.id }
            } catch (e: TalkError) {
                val (status, code) = e.statusAndCode()
                val why = classifyScheduledRefusal(ScheduledRequest.Delete, status, code)
                if (why == ScheduledRefusal.Gone) load()
                note = scheduledRefusalText(ScheduledRequest.Delete, why, status)
            } finally {
                deleting = null
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                "Scheduled messages",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.danger, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            val list = items
            val error = loadError
            when {
                list == null && error != null -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { load() } }, enabled = !loading) { Text(if (loading) "Loading…" else "Try again") }
                }
                list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
                list.isEmpty() -> Text(
                    "No scheduled messages.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(list, key = { it.id }) { m ->
                        ScheduledRow(
                            message = m,
                            whenLabel = scheduleTimeLabel(context, m.sendAt),
                            deleting = deleting == m.id,
                            enabled = deleting == null,
                            onEdit = {
                                note = null
                                editError = null
                                editing = m
                            },
                            onDelete = { delete(m) },
                        )
                        HorizontalDivider(color = colors.divider)
                    }
                }
            }
        }
    }

    editing?.let { m ->
        EditScheduledDialog(
            message = m,
            maxLength = session.capabilities.maxMessageLength.takeIf { it > 0 } ?: 32_000,
            saving = saving,
            error = editError,
            onEdited = { editError = null },
            onSave = { text, sendAt ->
                if (!saving) {
                    if (sendAt < System.currentTimeMillis() / 1000 + MIN_LEAD_SECONDS) {
                        editError = "Pick a time at least a minute ahead."
                    } else {
                        saving = true
                        editError = null
                        // On the sheet's scope: the dialog goes away before the list is reloaded.
                        scope.launch {
                            try {
                                // The silent flag goes back as it was: the server overwrites it with what arrives.
                                session.client.editScheduledMessage(token, m.id, text, sendAt, m.silent)
                                editing = null
                                saving = false
                                load()
                            } catch (e: TalkError) {
                                val (status, code) = e.statusAndCode()
                                val why = classifyScheduledRefusal(ScheduledRequest.Edit, status, code)
                                saving = false
                                if (why == ScheduledRefusal.Gone) {
                                    editing = null
                                    load()
                                    note = GONE_TEXT
                                } else {
                                    editError = scheduledRefusalText(ScheduledRequest.Edit, why, status)
                                }
                            }
                        }
                    }
                }
            },
            onDismiss = {
                if (!saving) {
                    editing = null
                    editError = null
                }
            },
        )
    }
}

/** One waiting message: when (and a crossed-out bell when it goes silently), its text, Edit and Delete. */
@Composable
private fun ScheduledRow(
    message: ScheduledMessage,
    whenLabel: String,
    deleting: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = TalqTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(whenLabel, style = MaterialTheme.typography.labelMedium, color = colors.accentText)
            if (message.silent) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.NotificationsOff, contentDescription = "Sent silently", tint = colors.textMuted, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        // Plain text, as it was typed: never read as markup (mentions by name, as in the box).
        Text(
            LocalContext.current.appContainer.drafts.readable(message.message).ifEmpty { "(empty)" },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEdit, enabled = enabled) { Text("Edit") }
            TextButton(onClick = onDelete, enabled = enabled) {
                if (deleting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Deleting…")
                } else {
                    Text("Delete", color = if (enabled) colors.danger else colors.textMuted)
                }
            }
        }
    }
}

/**
 * Edit a scheduled message's text and time. Starts from the whole text as the server holds it, so
 * saving with only the time changed sends the text back unchanged. The caller runs the request
 * ([onSave]) and closes the dialog when it is done; while [saving] the dialog takes nothing, and a
 * refusal ([error], one line) is shown under the field while the dialog keeps what was typed.
 */
@Composable
private fun EditScheduledDialog(
    message: ScheduledMessage,
    maxLength: Int,
    saving: Boolean,
    error: String?,
    onEdited: () -> Unit,
    onSave: (text: String, sendAt: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TalqTheme.colors
    val context = LocalContext.current
    var text by remember(message.id) { mutableStateOf(message.message) }
    var sendAt by remember(message.id) { mutableLongStateOf(message.sendAt) }
    var picking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit scheduled message") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.take(maxLength)
                        onEdited()
                    },
                    label = { Text("Message") },
                    minLines = 3,
                    maxLines = 8,
                    readOnly = saving,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Send at", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { picking = true }, enabled = !saving) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(scheduleTimeLabel(context, sendAt))
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.danger, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim(), sendAt) }, enabled = !saving && text.isNotBlank()) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )

    if (picking) {
        DateTimePickerDialogs(
            initial = Instant.ofEpochSecond(sendAt).atZone(ZoneId.systemDefault()),
            onPicked = { picked ->
                picking = false
                sendAt = picked.toEpochSecond()
                onEdited()
            },
            onDismiss = { picking = false },
        )
    }
}
