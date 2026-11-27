package talq.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import talq.app.net.NetLink
import talq.app.net.offline
import talq.app.theme.TalqTheme
import talq.core.TalkError

/** A painted disc for a state shown beside its words (never a glyph); the words say the state. */
@Composable
fun StatusDisc(color: Color, size: Dp = 8.dp) {
    Canvas(Modifier.size(size).clearAndSetSemantics { }) { drawCircle(color) }
}

/** What the connection line says. */
enum class ConnectionState(val text: String) {
    /** The phone has no working network ([offline]). */
    Offline("No connection"),

    /** The phone has a network, but the server does not answer: TalQ for desktop's words. */
    Connecting("Connecting…"),
}

/**
 * The one connection line of a screen: [link] from the app's network watch, [failing] = the
 * screen's own requests fail for want of a connection (`TalkError.Network`). Null when all is well.
 */
fun connectionState(link: NetLink, failing: Boolean): ConnectionState? = when {
    link.offline(failing) -> ConnectionState.Offline
    failing -> ConnectionState.Connecting
    else -> null
}

/**
 * TalQ for desktop's quiet connection strip (MainWindow.cpp, "Connection-status strip"): one thin,
 * muted line under the header, with an amber painted disc; no button, it goes away by itself, and
 * what is on screen stays usable. Read out once by TalkBack when it appears or changes.
 */
@Composable
fun ConnectionLine(state: ConnectionState, modifier: Modifier = Modifier) {
    StateLine(state.text, TalqTheme.colors.amber, spoken = true, modifier)
}

/** What cannot be done, or is not known, before the server has answered says this. */
const val WAITING_FOR_SERVER = "Waiting for the server"

/** What the line says while the rows are the saved ones (the server's are not there yet): of the list, and of a chat. */
enum class SavedState(val list: String, val messages: String) {
    /** The account is being checked, or its list (the chat) loaded. */
    Updating("Updating…", "Updating…"),

    /** The phone is offline, or the server was not reached. */
    Offline("Offline, saved list", "Offline, saved messages"),

    /** The server answered with an error. */
    Refused("Server error, saved list", "Server error, saved messages"),
}

/** A chat's saved messages go without the line at first: mostly the server answers sooner. */
const val SAVED_LINE_MILLIS = 1_000L

/** [error]: what the last check, or the last request for the rows, ended with; null while one runs or before it. */
fun savedState(link: NetLink, error: TalkError?): SavedState = when {
    link == NetLink.None || error is TalkError.Network -> SavedState.Offline
    error != null -> SavedState.Refused
    else -> SavedState.Updating
}

/**
 * The line over saved rows, in the place of [ConnectionLine]: over the list there from the first frame, over a chat
 * ([messages]) only once the answer takes long ([SAVED_LINE_MILLIS]). When the server's rows are there ([state] null)
 * it closes instead of jumping away under the finger. The usual start is not read out, that it stays so is.
 */
@Composable
fun SavedLine(state: SavedState?, messages: Boolean = false) {
    val last = remember { arrayOfNulls<SavedState>(1) }
    if (state != null) last[0] = state
    val colors = TalqTheme.colors
    AnimatedVisibility(
        visible = state != null,
        enter = if (messages) expandVertically() + fadeIn() else EnterTransition.None,
        exit = shrinkVertically() + fadeOut(),
    ) {
        val shown = last[0] ?: return@AnimatedVisibility
        StateLine(
            if (messages) shown.messages else shown.list,
            when (shown) {
                SavedState.Updating -> colors.amber
                SavedState.Offline -> colors.textMuted
                SavedState.Refused -> colors.danger
            },
            spoken = shown != SavedState.Updating,
        )
    }
}

@Composable
private fun StateLine(text: String, disc: Color, spoken: Boolean, modifier: Modifier = Modifier) {
    val colors = TalqTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.bgSecondary)
            .semantics(mergeDescendants = true) { if (spoken) liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDisc(disc, 7.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
