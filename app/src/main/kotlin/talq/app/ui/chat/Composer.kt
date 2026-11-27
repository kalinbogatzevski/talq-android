package talq.app.ui.chat

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import talq.app.theme.TalqTheme
import talq.app.ui.adaptive.LocalTwoPane
import talq.app.ui.common.onSecondaryClick
import talq.app.ui.message.previewText
import kotlinx.coroutines.flow.first
import talq.core.model.ChatMessage
import talq.core.model.MentionCandidate
import talq.core.model.insertMention
import talq.core.model.mentionQueryAt

/**
 * The message field. Sending does not touch focus, so the keyboard stays up for the next
 * message. The on-screen keyboard's Enter makes a new line; the button sends. A hardware
 * keyboard follows TalQ for desktop's composer (`ComposeTextEdit::keyPressEvent`): Enter sends,
 * Shift+Enter makes a new line, Alt+Enter sends silently ([onSendSilently]; a plain send
 * without it). Esc ends an edit, else cancels the reply; beside the list (two panes) it never goes
 * on to Back, which would hide the app there. Up in an empty box, with no reply or edit
 * open, edits the user's newest message ([onEditLast], desktop's `requestEditLast`).
 * With [onAttach] a paperclip sits before the field. What is picked waits in the [tray] above it (null:
 * nothing waits): Send then sends the files with what is typed as the caption, also when that is empty,
 * and stays off until every copy is made. [onPaste]: a paste from a hardware keyboard; true when the
 * clipboard held a file and it was taken, else the field pastes as it does.
 *
 * [over]: the text is that many characters past the server's limit. One line says so above the field and
 * Send is off; nothing is cut.
 *
 * [editing]: a sent message is being edited. The editing bar (accent strip, "Editing", the message
 * as it was, X = [onCancelEdit]) takes the reply bar's place, [text] is the edit's text and the
 * button is a check that saves ([onSend]); [busy] is then the save on its way. The caller leaves
 * out the paperclip, the microphone and the send options meanwhile (desktop refuses them too).
 *
 * [focusTick]: each new value puts the cursor in the box (at the end of the text) and raises the
 * keyboard, once the window has the focus back (the long-press sheet is a window of its own).
 *
 * [onEscape]: what an Esc in the box closes when it has nothing of its own to close (null: nothing).
 *
 * With [onSendOptions], touching and holding the send button opens the send options (send
 * silently, send later). [busy]: a "send later" request is on its way ([busyLabel] names another one);
 * the button shows progress and takes nothing, and the box stays as it is until the server has
 * answered. [sentSilently]
 * shows a crossed-out bell on the button for a moment after a silent send.
 *
 * With [voice], an empty (or blank) box puts a microphone on the button (Send as soon as something
 * is typed): touch and hold records and letting go sends, a tap records until the button (now Send)
 * is tapped again. A press right after the microphone appeared is ignored (a double tap on Send).
 * While this room records, the row is the recording row (`RecordingRow`) and the reply bar is
 * hidden (a voice message is never a reply, as on desktop). The button stays in the same place in
 * the composition, so a press that starts a recording is followed to its release.
 *
 * With [mentions], an "@word" lists the people to mention above the box (rules in Mentions.kt): a tap,
 * or Enter / Tab, puts the mention in; Up / Down move the highlight; Esc and Back hide the list. The
 * box draws each `@"id"` as a pill with the name and keeps it whole; the text stays in the id form.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    replyTo: ChatMessage?,
    onCancelReply: () -> Unit,
    onSend: () -> Unit,
    placeholder: String,
    onAttach: (() -> Unit)? = null,
    onSendOptions: (() -> Unit)? = null,
    busy: Boolean = false,
    busyLabel: String = "Scheduling",
    sentSilently: Boolean = false,
    voice: VoiceRecordUi? = null,
    onSendSilently: (() -> Unit)? = null,
    focusTick: Int = 0,
    editing: ChatMessage? = null,
    onCancelEdit: () -> Unit = {},
    onEditLast: (() -> Boolean)? = null,
    mentions: MentionSuggester? = null,
    tray: ComposerTray? = null,
    over: Int = 0,
    onPaste: (() -> Boolean)? = null,
    onEscape: (() -> Unit)? = null,
) {
    val colors = TalqTheme.colors
    val canSend = (tray?.ready ?: text.isNotBlank()) && !busy && over == 0
    val recording = voice?.recording
    val preparing = tray?.preparing == true && recording == null
    // The button records (or, while recording, sends the recording) instead of sending text. Not
    // while the bell of a silent send shows; a box holding only spaces counts as empty.
    val micMode = voice != null && !busy && (recording != null || (text.isBlank() && !sentSilently))
    // When the microphone face appeared (after a send, or a recording ending): a press in the
    // double-tap time after that is the second tap of a double tap on Send, not a recording.
    val micSince = remember(micMode && recording == null) { SystemClock.uptimeMillis() }
    val recordGesture = if (voice != null) rememberRecordGesture(voice, micSince) else Modifier
    // The field's own value keeps the cursor and the keyboard's composing word; a text set from
    // outside (a restored draft, a send) puts the cursor at its end and becomes the field's value.
    var typed by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val value = if (typed.text == text) typed else TextFieldValue(text, TextRange(text.length))
    SideEffect { if (typed !== value) typed = value }
    // Esc / Back hid the list for this "@word" (its start and what was typed), until the word changes.
    var dismissed by remember { mutableStateOf<Pair<Int, String>?>(null) }
    // Every change of the box, typed or a mention put in: the text goes up, the "@word" to the list.
    fun change(next: TextFieldValue) {
        typed = next
        if (next.text != text) onTextChange(next.text)
        if (mentions != null) {
            val word = if (next.selection.collapsed) mentionQueryAt(next.text, next.selection.start) else null
            if (dismissed != null && dismissed != word?.let { it.start to it.query }) dismissed = null
            mentions.follow(word)
        }
    }
    // The "@word" at the caret and its rows, when the server's answer is for this word. Hidden while
    // the box is read-only or records, and after Esc / Back.
    val query = if (mentions != null && !busy && recording == null && value.selection.collapsed) {
        mentionQueryAt(value.text, value.selection.start)
    } else null
    val answer = mentions?.shown
    // No "@word" any more (a send emptied the box, a draft came back): the old answer goes too.
    SideEffect { if (query == null && answer != null) mentions.follow(null) }
    val rows = answer?.candidates?.takeIf {
        query != null && it.isNotEmpty() && answer.start == query.start && dismissed != (query.start to query.query)
    }
    var highlighted by remember(rows) { mutableIntStateOf(0) }
    fun pick(c: MentionCandidate) {
        val q = query ?: return
        val (out, caret) = insertMention(value.text, q, c)
        mentions?.picked(c)
        change(TextFieldValue(out, TextRange(caret)))
    }
    fun hideMentions() {
        if (query != null) dismissed = query.start to query.query
    }
    BackHandler(enabled = rows != null) { hideMentions() }
    Column(Modifier.fillMaxWidth().background(colors.bgSidebar)) {
        if (rows != null) {
            HorizontalDivider(color = colors.divider)
            // Measured after the field and the bars: the list never pushes the box out of a short window.
            MentionList(rows, highlighted.coerceIn(0, rows.size - 1), mentions, ::pick, Modifier.weight(1f, fill = false))
        }
        HorizontalDivider(color = colors.divider)
        // Editing and replying never show together: the reply waits with the draft while an edit is open.
        if (editing != null && recording == null) {
            BoxBar(
                title = "Editing",
                preview = previewText(editing),
                cancelLabel = "Cancel edit",
                cancelEnabled = !busy,
                onCancel = onCancelEdit,
            )
        } else if (replyTo != null && recording == null) {
            BoxBar(
                title = "Replying to ${replyTo.actorDisplayName.ifBlank { "Guest" }}",
                preview = previewText(replyTo),
                cancelLabel = "Cancel reply",
                cancelEnabled = true,
                onCancel = onCancelReply,
            )
        }
        if (recording == null) {
            // Like the list of names: measured after the field, which keeps its place in a short window.
            if (tray != null) Box(Modifier.weight(1f, fill = false)) { tray.content() }
            if (over > 0) TrayLine(tooLongText(over))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = if (onAttach != null || recording != null) 2.dp else 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (recording != null) {
                RecordingRow(recording, onCancel = voice.onCancel, modifier = Modifier.weight(1f))
            } else {
                if (onAttach != null) {
                    // Not while the box is being scheduled: a file would take it as its caption too.
                    IconButton(onClick = onAttach, enabled = !busy, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach", tint = colors.textSecondary)
                    }
                }
                val focus = remember { FocusRequester() }
                val windowInfo = LocalWindowInfo.current
                val keyboard = LocalSoftwareKeyboardController.current
                // Starts at the current count: coming back (after a recording) asks for nothing.
                var focusedFor by remember { mutableIntStateOf(focusTick) }
                LaunchedEffect(focusTick) {
                    if (focusTick == focusedFor) return@LaunchedEffect
                    focusedFor = focusTick
                    snapshotFlow { windowInfo.isWindowFocused }.first { it }
                    try {
                        focus.requestFocus()
                    } catch (_: IllegalStateException) {
                        return@LaunchedEffect
                    }
                    withFrameNanos { }
                    keyboard?.show()
                }
                var escapeTaken by remember { mutableStateOf(false) }
                val twoPane = LocalTwoPane.current
                val pill = mentionPillStyle()
                val display = if (mentions != null) MentionDisplay(mentions.namesIn(value.text), pill) else VisualTransformation.None
                BasicTextField(
                    value = value,
                    // A mention is kept whole: no caret inside it, one backspace takes all of it.
                    onValueChange = { next -> change(if (mentions != null) keepMentionsWhole(typed, next) else next) },
                    visualTransformation = display,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    readOnly = busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 6,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { e ->
                            if (!e.fromHardwareKeyboard()) return@onPreviewKeyEvent false
                            // A file on the clipboard goes into the tray; anything else is the field's own paste.
                            if (e.isPaste() && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 && onPaste?.invoke() == true) {
                                return@onPreviewKeyEvent true
                            }
                            // The list of names first, while it shows (desktop's popup keys).
                            if (rows != null) {
                                val down = e.type == KeyEventType.KeyDown
                                when (e.key) {
                                    Key.DirectionUp -> {
                                        if (down) highlighted = (highlighted - 1).coerceAtLeast(0)
                                        return@onPreviewKeyEvent true
                                    }
                                    Key.DirectionDown -> {
                                        if (down) highlighted = (highlighted + 1).coerceAtMost(rows.size - 1)
                                        return@onPreviewKeyEvent true
                                    }
                                    Key.Enter, Key.NumPadEnter, Key.Tab -> {
                                        if (down && e.nativeKeyEvent.repeatCount == 0) pick(rows[highlighted.coerceIn(0, rows.size - 1)])
                                        return@onPreviewKeyEvent true
                                    }
                                    else -> {}
                                }
                            }
                            // An "@word" whose list has not come yet (or found nobody): Tab would type a
                            // tab into it, which ends the word and goes out with the message. Taken, it
                            // does nothing; Tab anywhere else stays the field's own.
                            if (rows == null && query != null && e.key == Key.Tab) return@onPreviewKeyEvent true
                            when (e.key) {
                                Key.Enter, Key.NumPadEnter -> {
                                    // Shift+Enter: the field's own new line.
                                    if (e.isShiftPressed) return@onPreviewKeyEvent false
                                    // Down only, not repeated: one press, one message. An empty or busy
                                    // box takes the key and does nothing.
                                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 && canSend) {
                                        if (e.isAltPressed && onSendSilently != null) onSendSilently() else onSend()
                                    }
                                    true
                                }
                                // Closes the list of names, the edit or the reply (in that order); a save
                                // on its way takes it and does nothing; then what the caller has open
                                // (onEscape). With nothing to close it goes on
                                // (Android makes it Back) on a phone; beside the list Back would hide the
                                // whole app, so there the box keeps it, as desktop's box does.
                                Key.Escape -> when (e.type) {
                                    KeyEventType.KeyDown -> {
                                        escapeTaken = rows != null || editing != null || replyTo != null || onEscape != null || twoPane
                                        if (escapeTaken && e.nativeKeyEvent.repeatCount == 0) {
                                            if (rows != null) {
                                                hideMentions()
                                            } else if (editing != null) {
                                                if (!busy) onCancelEdit()
                                            } else if (replyTo != null) {
                                                onCancelReply()
                                            } else {
                                                onEscape?.invoke()
                                            }
                                        }
                                        escapeTaken
                                    }
                                    KeyEventType.KeyUp -> escapeTaken.also { escapeTaken = false }
                                    else -> false
                                }
                                // Desktop: Up in an empty box edits the newest own message; never
                                // while a reply or an edit is open, nor with Shift or Ctrl.
                                Key.DirectionUp -> e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 &&
                                    text.isEmpty() && editing == null && replyTo == null && !busy &&
                                    !e.isShiftPressed && !e.isCtrlPressed && onEditLast?.invoke() == true
                                else -> false
                            }
                        },
                    decorationBox = { field ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(colors.bgSurface)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (text.isEmpty()) {
                                Text(
                                    if (editing != null) "Edit message" else placeholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.textMuted,
                                )
                            }
                            field()
                        }
                    },
                )
            }
            Spacer(Modifier.width(6.dp))
            // The press handling sits on the circle itself: an IconButton inside would take the
            // touch and the long press would never arrive.
            val finishing = recording?.finishing == true
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (canSend || micMode) colors.accent else colors.bgSelected)
                    .then(
                        if (micMode) {
                            recordGesture.semantics(mergeDescendants = true) {
                                role = Role.Button
                                onClick(label = if (recording != null) "Send voice message" else "Record a voice message") {
                                    if (recording != null) {
                                        if (!finishing) voice.onSend()
                                    } else {
                                        voice.onStart(false)
                                    }
                                    true
                                }
                            }
                        } else {
                            Modifier.onSecondaryClick(if (canSend) onSendOptions else null).combinedClickable(
                                enabled = canSend,
                                role = Role.Button,
                                onLongClickLabel = if (onSendOptions != null) "Send options" else null,
                                onLongClick = onSendOptions,
                                onClick = onSend,
                            )
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (busy || finishing || preparing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).semantics {
                            contentDescription = when {
                                !busy -> if (preparing) "Preparing" else "Sending"
                                editing != null -> "Saving"
                                else -> busyLabel
                            }
                        },
                        strokeWidth = 2.dp,
                        color = if (busy || preparing) colors.textMuted else colors.onAccent,
                    )
                } else if (editing != null && !micMode) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Save",
                        tint = if (canSend) colors.onAccent else colors.textMuted,
                        modifier = Modifier.size(20.dp),
                    )
                } else if (micMode) {
                    Icon(
                        if (recording != null) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic,
                        contentDescription = if (recording != null) "Send voice message" else "Record a voice message",
                        tint = colors.onAccent,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(
                        if (sentSilently) Icons.Outlined.NotificationsOff else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (sentSilently) "Sent silently" else "Send",
                        tint = if (canSend) colors.onAccent else colors.textMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** The bar above the box (replying, editing): accent strip, [title], one line of [preview], and X. */
@Composable
private fun BoxBar(title: String, preview: String, cancelLabel: String, cancelEnabled: Boolean, onCancel: () -> Unit) {
    val colors = TalqTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 14.dp, top = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colors.accent))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = colors.accentText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel, enabled = cancelEnabled) {
            Icon(Icons.Filled.Close, contentDescription = cancelLabel, tint = if (cancelEnabled) colors.textSecondary else colors.textMuted)
        }
    }
}

/**
 * A key from a physical keyboard. An on-screen keyboard that sends key events marks them as its own
 * (FLAG_SOFT_KEYBOARD) or sends them from the virtual keyboard device; its Enter keeps making a new line.
 */
private fun KeyEvent.fromHardwareKeyboard(): Boolean {
    val native = nativeKeyEvent
    return native.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD && (native.flags and AndroidKeyEvent.FLAG_SOFT_KEYBOARD) == 0
}

/** The keys a text field pastes on: Ctrl+V, Shift+Insert, Paste. */
private fun KeyEvent.isPaste(): Boolean = when (key) {
    Key.V -> isCtrlPressed && !isAltPressed && !isShiftPressed
    Key.Insert -> isShiftPressed
    Key.Paste -> true
    else -> false
}
