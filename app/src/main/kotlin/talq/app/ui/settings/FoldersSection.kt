package talq.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import talq.app.SessionState
import talq.app.appContainer
import talq.app.appName
import talq.app.folders.folderSyncDescription
import talq.app.folders.folderSyncRow
import talq.app.theme.TalqTheme
import talq.app.ui.common.WAITING_FOR_SERVER
import talq.core.folders.FolderSyncState

/**
 * Settings > Folders.
 *
 * "Show folders" draws the conversation list in its folders: a choice of this device for every
 * account, never synced (TalQ for desktop's "Group by Folders"). Making the first folder turns it on.
 *
 * "Keep my folders in sync" is per account and off by default: TalQ for desktop's switch, with its
 * text ("device" where desktop says "computer"). Turning it on first checks that the server saves
 * the shared file safely; when it does not, or cannot be reached, the switch goes back off and
 * says why. Under it: the sync's state, and "Sync now" where that can do something. Everything
 * here only shows the controllers' state and hands the taps to them.
 */
@Composable
fun FoldersSection() {
    val container = LocalContext.current.appContainer
    val folders = container.folders
    val ui by folders.ui.collectAsStateWithLifecycle()
    val storeStatus by folders.store.status.collectAsStateWithLifecycle()
    val loadFailed by folders.loadFailed.collectAsStateWithLifecycle()
    val syncStatus by folders.sync.status.collectAsStateWithLifecycle()
    val session by container.sessionState.collectAsStateWithLifecycle()
    val appLabel = appName()
    val colors = TalqTheme.colors
    // "Last synced: today 14:32" becomes "yesterday 14:32" after midnight.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }

    FolderSwitchRow(
        title = "Show folders",
        detail = if (ui.state.folders.isEmpty()) {
            "Touch and hold a chat to make a folder."
        } else {
            "Group the list by folder."
        },
        checked = ui.showFolders,
        onChange = { on -> folders.setShowFolders(on) },
    )

    val row = folderSyncRow(
        signedIn = session is SessionState.SignedIn,
        loadFailed = loadFailed,
        store = storeStatus,
        sync = syncStatus,
        appName = appLabel,
        // Never before the last sync (the ticker is up to a minute behind).
        nowMs = maxOf(now, syncStatus.lastSyncedMs),
    )
    // The account behind the saved list is being checked: the switch as it is kept, and nothing to change yet.
    val waiting = session !is SessionState.SignedIn && session !is SessionState.SignedOut
    FolderSwitchRow(
        title = "Keep my folders in sync",
        detail = folderSyncDescription(),
        checked = if (waiting) folders.sync.isEnabledFor(folders.store.scopeKey) else row.enabled,
        enabled = row.available,
        onChange = { on -> if (on) folders.sync.enable() else folders.sync.disable() },
    )
    when {
        waiting -> FolderStatusLine(WAITING_FOR_SERVER, colors.textSecondary)
        !row.available && row.whyNot.isNotEmpty() -> FolderStatusLine(row.whyNot, colors.textSecondary)
        row.statusText.isNotEmpty() -> FolderStatusLine(
            row.statusText,
            // Off with a text: turning it on was refused. On: a pause is a problem to act on.
            if (!row.enabled || syncStatus.state == FolderSyncState.Paused) colors.danger else colors.textSecondary,
        )
    }
    if (row.enabled && row.canSyncNow) {
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { folders.sync.syncNow() }) { Text("Sync now") }
        }
    }
}

/** A setting with a switch: the whole row toggles it (one control for TalkBack). */
@Composable
private fun FolderSwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.6f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = TalqTheme.colors.textMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun FolderStatusLine(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
