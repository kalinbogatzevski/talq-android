package talq.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import talq.app.theme.TalqColors
import talq.app.theme.TalqTheme
import talq.core.model.Room
import talq.core.shift.ShiftEntry
import talq.core.shift.ShiftState

// Presence (is the app open: a Nextcloud fact) and shift (are they at work: an ERP fact) are
// two different answers and are drawn so they cannot be confused: presence is a DISC at the
// avatar's bottom right, shift a rounded SQUARE at its bottom left. Position and shape carry
// the difference, not hue (on shift and online are the same green on purpose: two greens =
// "at work and reachable").

private val DRAWN_PRESENCE = setOf("online", "away", "dnd", "busy")

/** The presence colour, or null when nothing is drawn (offline, invisible, unknown). */
fun presenceColor(status: String, colors: TalqColors): Color? = when (status) {
    "online" -> colors.online
    "away" -> colors.amber
    // "busy" (in a meeting or a call) is a do-not-interrupt state too.
    "dnd", "busy" -> colors.danger
    else -> null
}

/** The presence in a word, or empty when the server gave none. */
fun presenceWord(status: String): String = when (status) {
    "" -> ""
    "online" -> "Online"
    "away" -> "Away"
    "dnd" -> "Do not disturb"
    "busy" -> "Busy"
    // Only the user's own status reads so; others see an invisible person as offline.
    "invisible" -> "Invisible"
    else -> "Offline"
}

/** The shift colour, or null for Unknown (which is never drawn). */
fun shiftColor(state: ShiftState, colors: TalqColors): Color? = when (state) {
    ShiftState.OnShift -> colors.online
    ShiftState.OnBreak -> colors.amber
    ShiftState.OffShift -> colors.textMuted
    ShiftState.Unknown -> null
}

/** The ERP's own wording when it sent one, else the app's word. Empty for Unknown. */
fun shiftLabel(entry: ShiftEntry?): String = when (entry?.state) {
    null, ShiftState.Unknown -> ""
    ShiftState.OnShift -> entry.label.ifBlank { "On shift" }
    ShiftState.OnBreak -> entry.label.ifBlank { "On break" }
    ShiftState.OffShift -> entry.label.ifBlank { "Off shift" }
}

private fun shiftWord(state: ShiftState): String = when (state) {
    ShiftState.OnShift -> "on shift"
    ShiftState.OnBreak -> "on break"
    ShiftState.OffShift -> "off shift"
    ShiftState.Unknown -> ""
}

/** "Online, on shift" for a screen reader; empty when there is nothing to say. [everyState] as in [StatusAvatar]. */
fun statusDescription(presence: String, shift: ShiftState, everyState: Boolean = false): String {
    // Only what is drawn is read out: where offline has no dot it gets no word.
    val shown = if (everyState || presence in DRAWN_PRESENCE) presenceWord(presence) else ""
    val work = shiftWord(shift)
    return when {
        shown.isEmpty() -> work.replaceFirstChar { it.uppercase() }
        work.isEmpty() -> shown
        else -> "$shown, $work"
    }
}

/**
 * [Avatar] with the two status marks of a one-to-one conversation. With an empty [presence]
 * and an Unknown [shift] it is exactly [Avatar].
 *
 * @param ring the colour of the ground the avatar sits on: each mark is ringed in it, so it
 *   reads against any picture behind it
 * @param presence the room's `status` (`online`, `away`, `dnd`, `busy`, ...)
 * @param everyState the user's own avatar, and the lists of people: the disc is drawn for every known state,
 *   offline and invisible in the muted ink (TalQ for desktop's own status dot); others show none for those
 */
@Composable
fun StatusAvatar(
    url: String,
    authorization: String,
    name: String,
    ring: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    presence: String = "",
    shift: ShiftState = ShiftState.Unknown,
    everyState: Boolean = false,
) {
    val colors = TalqTheme.colors
    val dot = presenceColor(presence, colors) ?: colors.textMuted.takeIf { everyState && presence.isNotEmpty() }
    val square = shiftColor(shift, colors)
    val description = statusDescription(presence, shift, everyState)
    Box(
        modifier
            .size(size)
            .then(if (description.isEmpty()) Modifier else Modifier.semantics { contentDescription = description }),
    ) {
        // Off shift recedes: the mark says which state, the dimming is what the eye skips.
        Avatar(url, authorization, name, Modifier.alpha(if (shift == ShiftState.OffShift) 0.55f else 1f), size)
        if (dot != null || square != null) {
            Canvas(Modifier.fillMaxSize()) {
                // Scaled from the avatar: 12dp marks on a 48dp avatar, 9dp on the top bar's 36dp.
                val mark = this.size.minDimension * 0.25f
                val ringWidth = (this.size.minDimension * 0.035f).coerceAtLeast(1.5.dp.toPx())
                val out = 1.dp.toPx()
                val top = this.size.height - mark + out
                if (square != null) {
                    val left = -out
                    drawRoundRect(
                        color = ring,
                        topLeft = Offset(left - ringWidth, top - ringWidth),
                        size = Size(mark + 2 * ringWidth, mark + 2 * ringWidth),
                        cornerRadius = CornerRadius(mark * 0.25f + ringWidth),
                    )
                    drawRoundRect(square, Offset(left, top), Size(mark, mark), CornerRadius(mark * 0.25f))
                }
                if (dot != null) {
                    val center = Offset(this.size.width - mark / 2 + out, top + mark / 2)
                    drawCircle(ring, mark / 2 + ringWidth, center)
                    drawCircle(dot, mark / 2, center)
                }
            }
        }
    }
}

/**
 * The status line under a one-to-one conversation's name: a painted disc and the shift label
 * in the state's colour (only when the shift is known), then " · " and the presence: the
 * person's status message with its icon when they set one, else the word, in the presence
 * colour. One line of one text, so what does not fit ends in "…": the presence part, last,
 * gives way first. Draws nothing when there is neither.
 */
@Composable
fun StatusLine(room: Room, shift: ShiftEntry?, modifier: Modifier = Modifier) {
    val colors = TalqTheme.colors
    val work = shiftLabel(shift)
    // Null when no shift part is drawn.
    val workColor = shift?.let { shiftColor(it.state, colors) }?.takeIf { work.isNotEmpty() }
    val message = room.statusMessage.replace('\n', ' ').trim()
    val presence = when {
        message.isNotEmpty() -> if (room.statusIcon.isBlank()) message else "${room.statusIcon.trim()} $message"
        else -> presenceWord(room.status)
    }
    val presenceInk = if (message.isNotEmpty()) colors.textMuted else presenceColor(room.status, colors) ?: colors.textMuted
    if (workColor == null && presence.isEmpty()) return
    // One text, not one per part: a part laid in a row of its own is cut at the edge with no
    // "…" when the line is short, and leaves the parts after it no room for theirs.
    val line = buildAnnotatedString {
        if (workColor != null) {
            withStyle(SpanStyle(color = workColor)) { append(work) }
            if (presence.isNotEmpty()) withStyle(SpanStyle(color = colors.textMuted)) { append(" · ") }
        }
        if (presence.isNotEmpty()) withStyle(SpanStyle(color = presenceInk)) { append(presence) }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (workColor != null) {
            Canvas(Modifier.size(7.dp).clearAndSetSemantics { }) { drawCircle(workColor) }
            Spacer(Modifier.width(5.dp))
        }
        Text(
            line,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** What a one-to-one conversation's status line would say, for deciding whether to show it. */
fun hasStatusLine(room: Room, shift: ShiftEntry?): Boolean =
    shift?.state?.isKnown == true || room.status.isNotEmpty() || room.statusMessage.isNotBlank()
