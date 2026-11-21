package talq.app.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import talq.app.theme.TalqTheme
import talq.app.ui.chat.Composer
import talq.app.ui.common.MessagePane
import talq.app.ui.message.PendingRow
import talq.core.model.NotificationLevel
import talq.core.repo.OutboxMessage

private const val MAX_TITLE = 200

/**
 * "Next: <topic> (n) ->": the shortcut to the next tab with something unread, shown above the
 * composer while the user is at the bottom of the open tab.
 */
@Composable
fun NextUnreadPill(chip: StripChip, onClick: () -> Unit) {
    val colors = TalqTheme.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), contentAlignment = Alignment.CenterEnd) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(colors.accentSoft)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Next: ", style = MaterialTheme.typography.labelLarge, color = colors.accentSoftInk)
            Text(
                text = chip.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.accentSoftInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 190.dp),
            )
            chip.unread.badge?.let {
                Text(" ($it)", style = MaterialTheme.typography.labelLarge, color = colors.accentSoftInk, maxLines = 1)
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = colors.accentSoftInk, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * The menu of one chip (long-press), as a sheet.
 *
 * @param level the topic's notification level; null for General, which has only "Mark as read"
 * @param onRename null hides Rename (General)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipMenuSheet(
    title: String,
    level: Int?,
    onLevel: (Int) -> Unit,
    onRename: (() -> Unit)?,
    onMarkRead: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TalqTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
            if (level != null) {
                Text(
                    "Notifications",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                LevelRow("Default", "As set for the conversation", level == NotificationLevel.DEFAULT) { onLevel(NotificationLevel.DEFAULT) }
                LevelRow("All messages", null, level == NotificationLevel.ALWAYS) { onLevel(NotificationLevel.ALWAYS) }
                LevelRow("Mentions only", null, level == NotificationLevel.MENTION) { onLevel(NotificationLevel.MENTION) }
                LevelRow("Never", null, level == NotificationLevel.NEVER) { onLevel(NotificationLevel.NEVER) }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.divider)
            }
            if (onRename != null) MenuRow("Rename", { Icon(Icons.Outlined.Edit, contentDescription = null) }, onRename)
            MenuRow("Mark as read", { Icon(Icons.Outlined.DoneAll, contentDescription = null) }, onMarkRead)
        }
    }
}

@Composable
private fun LevelRow(label: String, hint: String?, selected: Boolean, onClick: () -> Unit) {
    val colors = TalqTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = colors.accentText, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}

@Composable
private fun MenuRow(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Asks for a topic title: for a new topic ("+") and for Rename. The confirm button stays off
 * until there is a title (and, for a rename, until it differs).
 *
 * @param error what went wrong with the last confirm (the server's refusal), shown under the field
 * @param busy the confirm is being sent
 */
@Composable
fun TopicTitleDialog(
    heading: String,
    confirmLabel: String,
    initial: String = "",
    error: String? = null,
    busy: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val clean = title.trim()
    val ready = clean.isNotEmpty() && clean != initial.trim() && !busy
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.replace('\n', ' ').take(MAX_TITLE) },
                    label = { Text("Topic title") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ready) onConfirm(clean) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = TalqTheme.colors.danger,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(clean) }, enabled = ready) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The content of a draft topic's tab: nothing yet, and a composer for its first message.
 * Sending that message is what creates the topic. While it is on its way (or has failed) it is
 * shown in place of the composer, so a second "first message" cannot make a second topic.
 */
@Composable
fun DraftTopicPane(
    title: String,
    text: String,
    onTextChange: (String) -> Unit,
    sending: Boolean,
    pending: OutboxMessage?,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    onEditAgain: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            if (pending != null) {
                PendingRow(outbox = pending, onRetry = onRetry, onDiscard = onEditAgain)
            } else if (!sending) {
                MessagePane(
                    title = title,
                    detail = "Write the first message below. The topic is created when you send it.",
                )
            }
        }
        if (!sending) {
            Composer(
                text = text,
                onTextChange = onTextChange,
                replyTo = null,
                onCancelReply = {},
                onSend = onSend,
                placeholder = "First message in $title…",
            )
        }
    }
}
