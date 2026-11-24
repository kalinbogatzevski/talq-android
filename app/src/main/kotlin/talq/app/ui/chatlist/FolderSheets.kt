package talq.app.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import talq.app.appName
import talq.app.folders.FOLDER_NAME_SHORTENED_TEXT
import talq.app.folders.FOLDER_REMOVED_ELSEWHERE_TEXT
import talq.app.folders.FolderController
import talq.app.folders.FolderUi
import talq.app.folders.folderResultText
import talq.app.theme.TalqTheme
import talq.core.folders.Folder
import talq.core.folders.FolderResult
import talq.core.folders.MAX_FOLDER_NAME_CHARS
import talq.core.folders.folderIndexOf
import talq.core.folders.folderOf
import talq.core.folders.foldersInNameOrder
import talq.core.folders.normaliseFolderName

// The folder menus of the conversation list (TalQ for desktop's "Move to folder" submenu of a
// chat, its folder header menu and its folder name dialog), as sheets and a dialog in the style of
// the topic chip menu (ui/room/TopicMenus.kt). Every sheet and dialog keeps the chat's TOKEN or
// the folder's ID, never a list position (the list reloads and reorders every few seconds), and
// looks the folder up again each time it is drawn: a change from another device can remove it
// while the sheet is open, and the sheet then closes.

/**
 * Which folder sheet or dialog is open over the list. Each is a saved string, so turning the
 * phone keeps it open.
 */
@Stable
class FolderOverlays internal constructor(
    moveState: MutableState<String?>,
    menuState: MutableState<String?>,
    newState: MutableState<String?>,
    renameState: MutableState<String?>,
) {
    /** The chat whose "Move to folder" sheet is open (its token). */
    var moveFor: String? by moveState

    /** The folder whose menu is open (its id). */
    var menuFor: String? by menuState

    /** The "New folder" dialog is open: the token of the chat to file in the new folder, "" for none. */
    var newFor: String? by newState

    /** The folder being renamed (its id). */
    var renameId: String? by renameState
}

@Composable
fun rememberFolderOverlays(): FolderOverlays {
    val moveState = rememberSaveable { mutableStateOf<String?>(null) }
    val menuState = rememberSaveable { mutableStateOf<String?>(null) }
    val newState = rememberSaveable { mutableStateOf<String?>(null) }
    val renameState = rememberSaveable { mutableStateOf<String?>(null) }
    return remember { FolderOverlays(moveState, menuState, newState, renameState) }
}

/**
 * Draws whichever folder sheet or dialog [overlays] has open, and carries out what is picked
 * there through [folders]. [roomName] is the name of a chat the list shows, null for one it does
 * not (then its sheet closes). [say] shows a short notice on the list (a refusal outside a
 * dialog, a name that was shortened, a folder that is gone).
 */
@Composable
fun FolderOverlayHost(
    overlays: FolderOverlays,
    folders: FolderController,
    ui: FolderUi,
    roomName: (String) -> String?,
    say: (String) -> Unit,
) {
    val appLabel = appName()
    val state = ui.state
    fun textFor(result: FolderResult, typedName: String = "", filing: Boolean = false): String? =
        folderResultText(result, ui.readOnly, appLabel, typedName, filing)

    overlays.moveFor?.let { token ->
        val title = roomName(token)
        if (title == null) {
            LaunchedEffect(token) { overlays.moveFor = null }
        } else {
            MoveToFolderSheet(
                title = title,
                folders = state.folders,
                currentId = folderOf(state, token),
                onPick = { id ->
                    overlays.moveFor = null
                    textFor(folders.assign(token, id), filing = true)?.let(say)
                },
                onNewFolder = {
                    overlays.moveFor = null
                    // What no name could fix is said before a name is asked for.
                    val refusal = folders.createRefusal()
                    if (refusal != null) {
                        textFor(refusal)?.let(say)
                    } else {
                        overlays.newFor = token
                    }
                },
                onRemove = {
                    overlays.moveFor = null
                    textFor(folders.assign(token, null))?.let(say)
                },
                onDismiss = { overlays.moveFor = null },
            )
        }
    }

    overlays.menuFor?.let { id ->
        val index = folderIndexOf(state, id)
        if (index < 0) {
            LaunchedEffect(id) { overlays.menuFor = null }
        } else {
            val inNameOrder = remember(state.folders) { foldersInNameOrder(state) }
            FolderMenuSheet(
                name = state.folders[index].name,
                canMoveUp = index > 0,
                canMoveDown = index < state.folders.size - 1,
                canSort = !inNameOrder,
                onRename = {
                    overlays.menuFor = null
                    overlays.renameId = id
                },
                onMoveUp = {
                    overlays.menuFor = null
                    textFor(folders.moveUp(id))?.let(say)
                },
                onMoveDown = {
                    overlays.menuFor = null
                    textFor(folders.moveDown(id))?.let(say)
                },
                onSort = {
                    overlays.menuFor = null
                    textFor(folders.sortAZ())?.let(say)
                },
                onDelete = {
                    // No confirmation, as on desktop: its chats go back to Other.
                    overlays.menuFor = null
                    textFor(folders.delete(id))?.let(say)
                },
                onDismiss = { overlays.menuFor = null },
            )
        }
    }

    overlays.newFor?.let { fileToken ->
        HoldApplies(folders)
        FolderNameDialog(
            heading = "New folder",
            confirmLabel = "Create",
            onConfirm = { name ->
                val made = folders.create(name, fileToken = fileToken.ifEmpty { null })
                val refusal = if (made.result.isRefusal) textFor(made.result, typedName = name) else null
                if (refusal != null) {
                    refusal
                } else {
                    overlays.newFor = null
                    val filed = made.filing
                    if (filed != null && filed.isRefusal) {
                        textFor(filed, filing = true)?.let(say)
                    }
                    if (made.shortened) say(FOLDER_NAME_SHORTENED_TEXT)
                    null
                }
            },
            onDismiss = { overlays.newFor = null },
        )
    }

    overlays.renameId?.let { id ->
        // Read once: the field keeps what was typed even if a sync renames the folder meanwhile.
        val current = state.folders.getOrNull(folderIndexOf(state, id))?.name.orEmpty()
        HoldApplies(folders)
        FolderNameDialog(
            heading = "Rename folder",
            confirmLabel = "Rename",
            initial = current,
            onConfirm = { name ->
                when (val result = folders.rename(id, name)) {
                    FolderResult.Ok, FolderResult.Unchanged -> {
                        overlays.renameId = null
                        // Also on Unchanged: a long name whose first 40 characters are the current one.
                        if (folders.nameShortened(name)) say(FOLDER_NAME_SHORTENED_TEXT)
                        null
                    }
                    FolderResult.NotFound -> {
                        // Deleted on another device while the dialog was open.
                        overlays.renameId = null
                        say(FOLDER_REMOVED_ELSEWHERE_TEXT)
                        null
                    }
                    else -> textFor(result, typedName = name) ?: run {
                        overlays.renameId = null
                        null
                    }
                }
            },
            onDismiss = { overlays.renameId = null },
        )
    }
}

/** While a folder name dialog is open, a change from another device waits (desktop's apply gate). */
@Composable
private fun HoldApplies(folders: FolderController) {
    DisposableEffect(folders) {
        folders.holdApplies()
        onDispose { folders.releaseApplies() }
    }
}

/**
 * "Move to folder" for one chat (long-press on its row): every folder in its order with the
 * chat's own one checked, then "New folder…" (makes a folder and files the chat in it) and
 * "Remove from folder" (only with folders; off while the chat is in none). [currentId] is "" for
 * a chat in no folder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToFolderSheet(
    title: String,
    folders: List<Folder>,
    currentId: String,
    onPick: (folderId: String) -> Unit,
    onNewFolder: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TalqTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
            Text(
                "Move to folder",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            for (folder in folders) {
                FolderCheckRow(folder.name, selected = folder.id == currentId) { onPick(folder.id) }
            }
            if (folders.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.divider)
            FolderActionRow("New folder…", Icons.Outlined.CreateNewFolder, onClick = onNewFolder)
            if (folders.isNotEmpty()) {
                FolderActionRow("Remove from folder", Icons.Outlined.FolderOff, enabled = currentId.isNotEmpty(), onClick = onRemove)
            }
        }
    }
}

/**
 * A folder's menu (long-press on its header): Rename…, Move up (not on the first), Move down (not
 * on the last), Sort folders A to Z (off while they already are), and Delete folder, which asks
 * nothing: its chats go back to Other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderMenuSheet(
    name: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canSort: Boolean,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onSort: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TalqTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
            FolderActionRow("Rename…", Icons.Outlined.Edit, onClick = onRename)
            FolderActionRow("Move up", Icons.Outlined.ArrowUpward, enabled = canMoveUp, onClick = onMoveUp)
            FolderActionRow("Move down", Icons.Outlined.ArrowDownward, enabled = canMoveDown, onClick = onMoveDown)
            FolderActionRow("Sort folders A to Z", Icons.Outlined.SortByAlpha, enabled = canSort, onClick = onSort)
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.divider)
            FolderActionRow("Delete folder", Icons.Outlined.Delete, tint = colors.danger, onClick = onDelete)
        }
    }
}

/**
 * Asks for a folder name: "New folder" and "Rename folder". The field is not capped: the shared
 * name rules (desktop's) clean the name and cut it to MAX_FOLDER_NAME_CHARS characters, counted
 * as characters, not as the field's text units, and the dialog says so while the typed name is
 * longer. [onConfirm] answers the refusal to show under the field, or null when the dialog is
 * done (the caller then closes it). Confirm stays on with an empty field: the refusal says why.
 */
@Composable
fun FolderNameDialog(
    heading: String,
    confirmLabel: String,
    initial: String = "",
    onConfirm: (String) -> String?,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val willShorten = remember(name) { normaliseFolderName(name).shortened }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val confirm = { error = onConfirm(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.replace('\n', ' ')
                        error = null
                    },
                    label = { Text("Folder name") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                val shown = error
                if (shown != null) {
                    Text(
                        shown,
                        style = MaterialTheme.typography.bodySmall,
                        color = TalqTheme.colors.danger,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else if (willShorten) {
                    Text(
                        "The folder name will be shortened to $MAX_FOLDER_NAME_CHARS characters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TalqTheme.colors.textMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { confirm() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A folder to pick, checked when the chat is in it (the topic menu's level row). */
@Composable
private fun FolderCheckRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = TalqTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = colors.accentText, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** An action of a folder sheet (the topic menu's row, with an off state and an optional ink). */
@Composable
private fun FolderActionRow(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    val ink = if (tint == Color.Unspecified) TalqTheme.colors.textPrimary else tint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = ink)
    }
}
