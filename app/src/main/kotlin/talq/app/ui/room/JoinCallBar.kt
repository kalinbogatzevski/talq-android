package talq.app.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import talq.app.theme.TalqTheme
import talq.app.ui.common.StatusDisc

/**
 * Under a conversation's top bar while a call runs there that the user is not in: a painted green
 * disc, "Call in progress", Join (audio) and a camera button (joins with video). [video] is null
 * when the user may not send video in this conversation.
 */
@Composable
fun JoinCallBar(onJoin: (video: Boolean) -> Unit, videoAllowed: Boolean) {
    val colors = TalqTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.accentSoft)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        StatusDisc(colors.online, size = 10.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "Call in progress",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (videoAllowed) {
            IconButton(onClick = { onJoin(true) }) {
                Icon(Icons.Outlined.Videocam, contentDescription = "Join with video")
            }
        }
        FilledTonalButton(onClick = { onJoin(false) }) {
            Icon(Icons.Outlined.Call, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Join")
        }
    }
}
