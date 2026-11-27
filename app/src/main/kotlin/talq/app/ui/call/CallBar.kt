package talq.app.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import talq.app.appContainer
import talq.app.call.CallActivity
import talq.app.theme.TalqTheme
import talq.core.call.CallState

/** "Call with … · tap to return" while a call runs; nothing otherwise. */
@Composable
fun CallInProgressBar() {
    val context = LocalContext.current
    val current by context.appContainer.calls.current.collectAsStateWithLifecycle()
    val call = current ?: return
    val state by call.session.state.collectAsStateWithLifecycle()
    if (state is CallState.Ended) return
    val colors = TalqTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .background(colors.accent)
            .clickable(onClickLabel = "Return to the call") { context.startActivity(CallActivity.showIntent(context)) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PhoneInTalk, contentDescription = null, tint = colors.onAccent)
        Spacer(Modifier.width(12.dp))
        Text(
            "Call with ${call.peerName} · tap to return",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onAccent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
