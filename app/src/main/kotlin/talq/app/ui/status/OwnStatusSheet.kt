package talq.app.ui.status

import android.content.Context
import android.icu.text.BreakIterator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import talq.app.status.ClearAfter
import talq.app.status.OwnStatusController
import talq.app.status.OwnStatusUi
import talq.app.status.StatusDraft
import talq.app.theme.TalqTheme
import talq.app.ui.common.presenceColor
import talq.app.ui.common.presenceWord
import talq.core.model.OwnStatus
import talq.core.model.StatusType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import talq.app.ui.common.UiLocale
import talq.app.ui.common.clockPattern

/** The server caps a status message at 80 characters (TalQ for desktop's field does too). */
private const val MAX_MESSAGE = 80

/** The status types offered, in TalQ for desktop's order; Busy (not on desktop) only where the server takes it. */
private fun statusChoices(ui: OwnStatusUi): List<String> = buildList {
    add(StatusType.ONLINE)
    add(StatusType.AWAY)
    if (ui.supportsBusy) add(StatusType.BUSY)
    add(StatusType.DND)
    add(StatusType.INVISIBLE)
}

/** "🌴 Vacationing", or empty without a message. */
private fun messageLine(status: OwnStatus): String {
    val text = status.message.replace('\n', ' ').trim()
    val icon = status.icon.trim()
    return when {
        icon.isEmpty() -> text
        text.isEmpty() -> icon
        else -> "$icon $text"
    }
}

/** A painted presence disc: a colour for every state (offline, invisible and unknown in the muted ink). */
@Composable
private fun PresenceDisc(status: String, size: Dp) {
    val colors = TalqTheme.colors
    val color = presenceColor(status, colors) ?: colors.textMuted
    Canvas(Modifier.size(size).clearAndSetSemantics { }) { drawCircle(color) }
}

/**
 * The own status in one line, under the name in the conversation list's profile bar (TalQ for
 * desktop's status pill, MainWindow::refreshStatusIndicator): a painted disc in the state's colour
 * (muted for offline and invisible), the word in that colour, then " · " and the message with its
 * emoji when one is set, cut short at the end. Until the first read only "…" (the placeholder
 * status would say Offline).
 */
@Composable
fun OwnStatusReadout(ui: OwnStatusUi, modifier: Modifier = Modifier) {
    val colors = TalqTheme.colors
    val style = MaterialTheme.typography.labelSmall
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (!ui.loaded) {
            Text("…", style = style, color = colors.textMuted, maxLines = 1)
        } else {
            val status = ui.status.status
            val ink = presenceColor(status, colors) ?: colors.textMuted
            val message = messageLine(ui.status)
            PresenceDisc(status, 7.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = ink)) { append(presenceWord(status)) }
                    if (message.isNotEmpty()) withStyle(SpanStyle(color = colors.textMuted)) { append(" · $message") }
                },
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Set the own status: the type, a message (emoji + text, or one of the server's predefined ones)
 * with "Clear after", "Clear message" and Save. Patterned on TalQ for desktop's StatusPopover, but
 * nothing is sent until Save (or "Clear message", which saves with the message emptied); only
 * what changed is sent. The sheet closes once the server has taken it; a refusal is shown under
 * the field and the sheet stays.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnStatusSheet(controller: OwnStatusController, onDismiss: () -> Unit) {
    val colors = TalqTheme.colors
    val context = LocalContext.current
    val ui by controller.ui.collectAsStateWithLifecycle()
    val saving by controller.saving.collectAsStateWithLifecycle()
    val choices = statusChoices(ui)

    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var icon by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var presetId by rememberSaveable { mutableStateOf("") }
    var clearAfter by rememberSaveable { mutableStateOf<ClearAfter?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    // Until the user changes something, the fields follow the status (a read may land while the sheet is open).
    var touched by rememberSaveable { mutableStateOf(false) }
    var clearMenu by remember { mutableStateOf(false) }

    LaunchedEffect(ui.loaded, ui.status) {
        if (touched) return@LaunchedEffect
        val s = ui.status
        type = s.status.takeIf { it in choices }
        icon = s.icon
        message = s.message
        presetId = s.messageId
        clearAfter = null
    }

    fun edit() {
        touched = true
        error = null
    }

    fun save() {
        error = null
        controller.save(StatusDraft(type, icon, message, presetId, clearAfter)) { failed ->
            if (failed == null) onDismiss() else error = failed
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(bottom = 12.dp),
        ) {
            Text(
                "Status",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
            for (choice in choices) {
                ChoiceRow(selected = type == choice, onClick = {
                    edit()
                    type = choice
                }) {
                    PresenceDisc(choice, 12.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(presenceWord(choice), style = MaterialTheme.typography.bodyLarge, fontWeight = if (type == choice) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.divider)

            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ui.supportsEmoji) {
                    OutlinedTextField(
                        value = icon,
                        onValueChange = {
                            val picked = lastGrapheme(it)
                            if (picked != icon) {
                                edit()
                                icon = picked
                                presetId = ""
                            }
                        },
                        placeholder = { Text("🙂", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                        modifier = Modifier.width(64.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = {
                        val typed = it.replace('\n', ' ').take(MAX_MESSAGE)
                        if (typed != message) {
                            edit()
                            message = typed
                            presetId = ""
                        }
                    },
                    placeholder = { Text("What's your status?", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
            }
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Clear after", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { clearMenu = true }) {
                        Text(clearAfter?.label ?: keptClearLabel(context, ui.status))
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = clearMenu, onDismissRequest = { clearMenu = false }) {
                        for (choice in ClearAfter.entries) {
                            DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                                edit()
                                clearAfter = choice
                                clearMenu = false
                            })
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        edit()
                        icon = ""
                        message = ""
                        presetId = ""
                        clearAfter = null
                        save()
                    },
                    enabled = !saving && (icon.isNotEmpty() || message.isNotEmpty() || presetId.isNotEmpty()),
                ) { Text("Clear message") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { save() }, enabled = !saving) { Text(if (saving) "Saving…" else "Save") }
            }

            if (ui.predefined.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.divider)
                for (preset in ui.predefined) {
                    ChoiceRow(selected = presetId == preset.id, onClick = {
                        edit()
                        icon = preset.icon
                        message = preset.message.take(MAX_MESSAGE)
                        presetId = preset.id
                    }) {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Text(preset.icon) }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            preset.message,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (presetId == preset.id) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** A choice of the sheet (the topic menu's level row): its content, then a check when it is the one chosen. */
@Composable
private fun ChoiceRow(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { content() }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = TalqTheme.colors.accentText, modifier = Modifier.size(20.dp))
        }
    }
}

/** "Clear after" while none is picked: when the current message goes ("Until 17:30", "Until Sun 23:59"), else "Don't clear". */
private fun keptClearLabel(context: Context, status: OwnStatus): String {
    val nowSeconds = System.currentTimeMillis() / 1000
    if (!status.hasMessage || status.clearAt <= nowSeconds) return ClearAfter.Never.label
    val zone = ZoneId.systemDefault()
    val locale = UiLocale
    val then = Instant.ofEpochSecond(status.clearAt).atZone(zone)
    val time = then.format(DateTimeFormatter.ofPattern(clockPattern(context), locale))
    val days = ChronoUnit.DAYS.between(LocalDate.now(zone), then.toLocalDate())
    return when {
        days == 0L -> "Until $time"
        days in 1..6 -> "Until ${then.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} $time"
        else -> "Until ${then.format(DateTimeFormatter.ofPattern("d MMM", locale))}"
    }
}

/** The last character as the eye sees it (an emoji with its skin tone or joiners stays whole): the field holds one. */
private fun lastGrapheme(text: String): String {
    val t = text.trim()
    if (t.isEmpty()) return ""
    val it = BreakIterator.getCharacterInstance()
    it.setText(t)
    val end = it.last()
    val start = it.previous()
    return if (start == BreakIterator.DONE) t else t.substring(start, end)
}
