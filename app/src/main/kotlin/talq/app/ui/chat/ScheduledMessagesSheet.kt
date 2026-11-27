package talq.app.ui.chat

import android.content.Context
import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import talq.app.appContainer
import talq.app.theme.TalqTheme
import talq.core.TalkError
import talq.core.model.ScheduledMessage
import talq.core.model.ScheduledRefusal
import talq.core.model.ScheduledRequest
import talq.core.model.classifyScheduledRefusal
import talq.core.model.messageOver
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
 * The sheet's state, kept while the phone is turned (in the room's entry), and an open edit over the end of the process.
 * Delete and Save run on the session's scope: closing the sheet or leaving the room cuts neither off, and a refusal is
 * then said app-wide. Main thread only.
 */
class ScheduledMessagesModel(
    private val session: TalkSession,
    private val token: String,
    /** The application's context (never an activity's): for a line that comes after the sheet was closed. */
    private val appContext: Context,
    private val saved: SavedStateHandle,
) : ViewModel() {
    /** Null until the first load has answered. */
    var items by mutableStateOf<List<ScheduledMessage>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    /** The last refusal of an edit or a delete. */
    var note by mutableStateOf<String?>(null)
        private set
    var deleting by mutableStateOf<String?>(null)
        private set

    /** The message being edited, with the text and the time as they are in the dialog. */
    var editing by mutableStateOf<ScheduledMessage?>(null)
        private set
    var field by mutableStateOf(TextFieldValue())
        private set
    var sendAt by mutableLongStateOf(0L)
        private set
    var saving by mutableStateOf(false)
        private set
    var editError by mutableStateOf<String?>(null)
        private set

    /** The sheet is open (also while the phone is being turned). */
    private var shown = false

    init {
        viewModelScope.launch {
            snapshotFlow { editing?.let { Triple(it.id, field.text, sendAt) } }.drop(1).collect { edit ->
                saved[KEY_ID] = edit?.first
                saved[KEY_TEXT] = edit?.second?.let { ComposerDrafts.savable(it) }
                saved[KEY_AT] = edit?.third
            }
        }
    }

    fun opened() {
        if (shown) return
        shown = true
        items = null
        loadError = null
        note = null
        load()
    }

    fun closed() {
        shown = false
        if (!saving) editing = null
    }

    override fun onCleared() {
        shown = false
    }

    fun load() {
        loading = true
        viewModelScope.launch {
            try {
                val list = session.client.scheduledMessages(token).sortedBy { it.sendAt }
                items = list
                loadError = null
                // Android ended the app with an edit open.
                val text = saved.get<String>(KEY_TEXT)
                if (editing == null && text != null) list.firstOrNull { it.id == saved.get<String>(KEY_ID) }?.let { m ->
                    edit(m, text, saved.get<Long>(KEY_AT) ?: m.sendAt)
                }
            } catch (e: TalkError) {
                if (e is TalkError.Unauthorized) appContext.appContainer.onUnauthorized()
                val (status, code) = e.statusAndCode()
                loadError = if (status == 404 && code == "actor") NOT_A_MEMBER_TEXT else "Couldn't load scheduled messages."
            } finally {
                loading = false
            }
        }
    }

    fun edit(m: ScheduledMessage) = edit(m, m.message, m.sendAt)

    private fun edit(m: ScheduledMessage, text: String, at: Long) {
        note = null
        editError = null
        field = TextFieldValue(text, TextRange(text.length))
        sendAt = at
        editing = m
    }

    fun type(next: TextFieldValue) {
        // Not for a moved caret: the refusal stays readable.
        if (next.text != field.text) editError = null
        field = next
    }

    fun pick(at: Long) {
        sendAt = at
        editError = null
    }

    fun cancelEdit() {
        if (saving) return
        editing = null
        editError = null
    }

    fun delete(m: ScheduledMessage) {
        if (deleting != null) return
        deleting = m.id
        note = null
        run(
            request = { session.client.deleteScheduledMessage(token, m.id) },
            ended = { deleting = null },
        ) { failure ->
            if (failure == null) {
                items = items?.filterNot { it.id == m.id }
                return@run
            }
            val (status, code) = failure.statusAndCode()
            val why = classifyScheduledRefusal(ScheduledRequest.Delete, status, code)
            if (why == ScheduledRefusal.Gone && shown) load()
            say(scheduledRefusalText(ScheduledRequest.Delete, why, status))
        }
    }

    fun save() {
        val m = editing ?: return
        if (saving) return
        val text = field.text.trim()
        val at = sendAt
        if (at < System.currentTimeMillis() / 1000 + MIN_LEAD_SECONDS) {
            editError = "Pick a time at least a minute ahead."
            return
        }
        saving = true
        editError = null
        run(
            // The silent flag goes back as it was: the server overwrites it with what arrives.
            request = { session.client.editScheduledMessage(token, m.id, text, at, m.silent) },
            ended = { saving = false },
        ) { failure ->
            val (status, code) = failure?.statusAndCode() ?: (0 to "")
            val why = failure?.let { classifyScheduledRefusal(ScheduledRequest.Edit, status, code) }
            if (why == null || why == ScheduledRefusal.Gone) {
                editing = null
                if (shown) load()
                if (why != null) say(GONE_TEXT)
            } else {
                val line = scheduledRefusalText(ScheduledRequest.Edit, why, status)
                if (shown) editError = line else sayAnywhere(appContext, line)
            }
        }
    }

    /** [answered] is told how it went (null = done), unless the session ended under it; [ended] always runs, before that. */
    private fun run(request: suspend () -> Unit, ended: () -> Unit, answered: (TalkError?) -> Unit) {
        session.launch {
            var failure: TalkError? = null
            var done = false
            try {
                request()
                done = true
            } catch (e: TalkError) {
                failure = e
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    ended()
                    if (done || failure != null) answered(failure)
                }
            }
        }
    }

    private fun say(line: String) {
        if (shown) note = line else sayAnywhere(appContext, line)
    }

    private companion object {
        const val KEY_ID = "edit-id"
        const val KEY_TEXT = "edit-text"
        const val KEY_AT = "edit-at"
    }
}

/**
 * "Scheduled messages" from the conversation's menu (capability `scheduled-messages`): the user's
 * own messages waiting in this room, soonest first, each with Edit (text and time; its silent
 * flag is sent back as it was) and Delete. Ported from TalQ for desktop's ScheduledMessagesDialog:
 * GET, POST and DELETE `/chat/{token}/schedule[/{id}]`. A message that is no longer waiting is
 * taken off the list (reloaded first) and that is said; every other refusal is said in one line.
 * Each Delete and Save disables itself and shows progress while its request runs. Mentions show by
 * name, here and in the edit box, as in the composer; what is sent back is the text with the ids.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledMessagesSheet(session: TalkSession, token: String, onDismiss: () -> Unit) {
    val colors = TalqTheme.colors
    val context = LocalContext.current
    val appContext = context.applicationContext
    val drafts = context.appContainer.drafts
    val vm: ScheduledMessagesModel = viewModel(key = "scheduled-$token") {
        ScheduledMessagesModel(session, token, appContext, createSavedStateHandle())
    }
    val activity = LocalActivity.current
    DisposableEffect(vm) {
        vm.opened()
        // Turning the phone is not closing.
        onDispose { if (activity?.isChangingConfigurations != true) vm.closed() }
    }
    // A message scheduled elsewhere, or before the app was ended, holds ids without a known name.
    LaunchedEffect(vm.items) { vm.items?.let { list -> resolveMentionNames(session, token, drafts, list.map { it.message }) } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                "Scheduled messages",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
            vm.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.danger, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            val list = vm.items
            val error = vm.loadError
            when {
                list == null && error != null -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::load, enabled = !vm.loading) { Text(if (vm.loading) "Loading…" else "Try again") }
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
                            deleting = vm.deleting == m.id,
                            enabled = vm.deleting == null,
                            onEdit = { vm.edit(m) },
                            onDelete = { vm.delete(m) },
                        )
                        HorizontalDivider(color = colors.divider)
                    }
                }
            }
        }
    }

    if (vm.editing != null) {
        EditScheduledDialog(vm, maxLength = session.capabilities.maxMessageLength.takeIf { it > 0 } ?: 32_000)
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
 * saving with only the time changed sends the text back unchanged. [vm] runs the request and closes the
 * dialog when it is done; while it runs the dialog takes nothing, and a refusal (one line) is shown under
 * the field while the dialog keeps what was typed. The field holds the raw text and draws its mentions as
 * the composer does (a pill with the name, kept whole: `MentionDisplay`, `keepMentionsWhole`).
 */
@Composable
private fun EditScheduledDialog(vm: ScheduledMessagesModel, maxLength: Int) {
    val colors = TalqTheme.colors
    val context = LocalContext.current
    val drafts = context.appContainer.drafts
    val saving = vm.saving
    val error = vm.editError
    val text = vm.field.text
    val over = messageOver(text.trim(), maxLength)
    var picking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = vm::cancelEdit,
        title = { Text("Edit scheduled message") },
        text = {
            Column {
                OutlinedTextField(
                    value = vm.field,
                    onValueChange = { next -> vm.type(keepMentionsWhole(vm.field, next)) },
                    visualTransformation = MentionDisplay(drafts.shownNames(text), mentionPillStyle()),
                    label = { Text("Message") },
                    minLines = 3,
                    maxLines = 8,
                    readOnly = saving,
                    isError = error != null || over > 0,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Never cut, as in the composer.
                if (over > 0) Text(tooLongText(over), style = MaterialTheme.typography.bodySmall, color = colors.danger, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Send at", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { picking = true }, enabled = !saving) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(scheduleTimeLabel(context, vm.sendAt))
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.danger, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = vm::save, enabled = !saving && text.isNotBlank() && over == 0) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = vm::cancelEdit, enabled = !saving) { Text("Cancel") } },
    )

    if (picking) {
        DateTimePickerDialogs(
            initial = Instant.ofEpochSecond(vm.sendAt).atZone(ZoneId.systemDefault()),
            onPicked = { picked ->
                picking = false
                vm.pick(picked.toEpochSecond())
            },
            onDismiss = { picking = false },
        )
    }
}
