package talq.app.ui.common

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
    val colors = TalqTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.bgSecondary)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDisc(colors.amber, 7.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            state.text,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
