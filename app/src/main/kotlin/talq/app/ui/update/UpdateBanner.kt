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
import talq.app.appName
import talq.app.theme.TalqTheme
import talq.app.update.AppUpdates
import talq.app.update.UpdateState

/** Whether the conversation list shows [state] as a banner (only an offer not put off). */
fun showsUpdateBanner(state: UpdateState, dismissed: String?): Boolean =
    state is UpdateState.Available && state.version != dismissed

/** How to install what "Update" downloads: the browser does the download, the user opens it. */
@Composable
fun updateHowTo(): String =
    "Update opens the download in your browser. When it is done, open the file and confirm to install. " +
        "Text you have not sent yet is lost when ${appName()} restarts."

/** The branded build's update offer at the top of the conversation list. */
@Composable
fun UpdateBanner(state: UpdateState, updates: AppUpdates) {
    if (state !is UpdateState.Available) return
    val colors = TalqTheme.colors
    val app = appName()
    var noBrowser by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bgSecondary)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
    ) {
        Text("$app ${state.version} is available", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
        Text(
            if (noBrowser) "No browser on this phone could open the download." else updateHowTo(),
            style = MaterialTheme.typography.bodySmall,
            color = if (noBrowser) colors.danger else colors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, end = 8.dp),
        )
        Row {
            TextButton(onClick = { noBrowser = !updates.download() }) { Text("Update") }
            TextButton(onClick = updates::dismiss) { Text("Not now", color = colors.textSecondary) }
        }
    }
}
