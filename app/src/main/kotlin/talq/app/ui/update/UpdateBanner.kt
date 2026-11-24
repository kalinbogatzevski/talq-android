package talq.app.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import talq.app.appName
import talq.app.theme.TalqTheme
import talq.app.update.AppUpdates
import talq.app.update.UpdateState

/** Whether the conversation list shows [state] as a banner (only an offer not put off). */
fun showsUpdateBanner(state: UpdateState, dismissed: String?): Boolean =
    state is UpdateState.Available && state.version != dismissed

/** How to install what "Update" downloads: Android's download manager fetches it, the user opens it. */
@Composable
fun updateHowTo(): String = "Tap Update, then tap the finished download to install."

/** The branded build's update offer at the top of the conversation list. */
@Composable
fun UpdateBanner(state: UpdateState, updates: AppUpdates) {
    if (state !is UpdateState.Available) return
    val colors = TalqTheme.colors
    val app = appName()
    // null: not tapped yet; true: the download started; false: it could not start.
    var started by remember { mutableStateOf<Boolean?>(null) }
    val downloading by updates.downloading.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bgSecondary)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
    ) {
        Text("$app ${state.version} is available", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
        Text(
            when (started) {
                true -> if (downloading) "Downloading\u2026" else "Tap the finished download to install."
                false -> "The download could not be started on this phone."
                null -> updateHowTo()
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (started == false) colors.danger else colors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, end = 8.dp),
        )
        Row {
            TextButton(onClick = { started = updates.download() }, enabled = !downloading) {
                Text(if (downloading) "Downloading\u2026" else "Update")
            }
            TextButton(onClick = updates::dismiss) { Text("Not now", color = colors.textSecondary) }
        }
    }
}
