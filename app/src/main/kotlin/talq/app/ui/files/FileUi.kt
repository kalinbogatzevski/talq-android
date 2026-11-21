package talq.app.ui.files

import android.content.ActivityNotFoundException
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import talq.app.appContainer
import talq.app.files.FileNotice
import talq.app.files.ReceivedFiles
import talq.app.theme.TalqTheme
import talq.app.ui.message.ImageViewer
import talq.core.model.RichObject
import talq.core.repo.TalkSession

/**
 * What a screen can do with a received file: open it (pictures in the viewer, videos in the
 * player, everything else downloaded and handed to a suitable app), save it, share it. One per
 * screen, from [rememberFileUi]; the screen must also place [FileOverlays].
 */
@Stable
class FileUi internal constructor(
    private val received: ReceivedFiles,
    private val open: FileOverlayModel,
    private val pickPlace: (RichObject) -> Unit,
) {
    /** The pictures the viewer swipes through and the one it shows first. */
    internal var viewer: Pair<List<RichObject>, Int>?
        get() = open.viewer
        set(value) {
            open.viewer = value
        }
    internal var video: RichObject?
        get() = open.video
        set(value) {
            open.video = value
            open.videoPositionMs = 0
        }
    internal val model: FileOverlayModel get() = open

    /** Messages shown over the viewer and the player, which cover the screen's own snackbar. */
    internal val overlaySnackbar = SnackbarHostState()

    /** Tap on a file that is not a picture and not audio. */
    fun open(file: RichObject) {
        if (file.isVideo && file.path.isNotEmpty()) video = file else received.open(file)
    }

    /** Open the viewer on [file], with [among] (the tab's loaded pictures) to swipe through. */
    fun openImage(file: RichObject, among: List<RichObject>) {
        val images = among.distinctBy { it.id }
        val at = images.indexOfFirst { it.id == file.id }
        viewer = if (at >= 0) images to at else listOf(file) to 0
    }

    /** Pictures go to Pictures, the rest to Downloads; before Android 10 the user picks the place. */
    fun save(file: RichObject) {
        if (received.savesThroughPicker) pickPlace(file) else received.save(file)
    }

    fun share(file: RichObject) = received.share(file)
}

/**
 * What is open over the screen. In a ViewModel (of the screen's back-stack entry), so turning
 * the phone does not close the video or the picture; the video goes on where it was.
 */
internal class FileOverlayModel : ViewModel() {
    var viewer by mutableStateOf<Pair<List<RichObject>, Int>?>(null)
    var video by mutableStateOf<RichObject?>(null)

    /** Where the video was when its player was last taken down. */
    var videoPositionMs = 0L
}

@Composable
fun rememberFileUi(snackbar: SnackbarHostState): FileUi {
    val context = LocalContext.current
    val received = context.appContainer.received
    val scope = rememberCoroutineScope()
    val model: FileOverlayModel = viewModel()

    // Saved (as the file's own JSON): the app may be recreated while the picker is in front.
    var placing by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = placing?.let { raw -> runCatching { RichObject("file", Json.parseToJsonElement(raw).jsonObject) }.getOrNull() }
        placing = null
        if (uri != null) {
            if (file != null) {
                received.saveTo(file, uri)
            } else {
                // The picker has already made an empty document: do not leave it behind.
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                received.notice.value = FileNotice("The file couldn't be saved. Try again.")
            }
        }
    }
    val ui = remember(received, model) {
        FileUi(received, model) { file ->
            placing = file.raw.toString()
            try {
                picker.launch(file.name.ifBlank { "file" })
            } catch (e: ActivityNotFoundException) {
                placing = null
                received.notice.value = FileNotice("This phone has no file picker to save with.")
            }
        }
    }

    val notice by received.notice.collectAsStateWithLifecycle()
    LaunchedEffect(notice) {
        val shown = notice ?: return@LaunchedEffect
        received.notice.value = null
        // On the screen's scope: clearing the notice restarts this effect, which must not
        // take the message down with it.
        scope.launch {
            val toSave = shown.offerSave
            // The viewer and the player are windows of their own, above the screen's snackbar.
            val host = if (ui.viewer != null || ui.video != null) ui.overlaySnackbar else snackbar
            val result = host.showSnackbar(
                message = shown.text,
                actionLabel = if (toSave != null) "Save" else null,
                duration = if (toSave != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && toSave != null) ui.save(toSave)
        }
    }
    return ui
}

/** The viewer and the player, over the screen. */
@Composable
fun FileOverlays(ui: FileUi, session: TalkSession) {
    val received = LocalContext.current.appContainer.received
    ui.viewer?.let { (images, start) ->
        ImageViewer(
            images, start, session,
            onClose = { ui.viewer = null },
            onSave = ui::save,
            onShare = ui::share,
            snackbar = ui.overlaySnackbar,
            // Remembered: where to open again after the phone was turned.
            onPage = { page -> ui.model.viewer?.let { if (it.second != page) ui.model.viewer = it.first to page } },
        )
    }
    ui.video?.let { file ->
        VideoPlayer(
            file = file,
            session = session,
            startAtMs = ui.model.videoPositionMs,
            onPosition = { ui.model.videoPositionMs = it },
            snackbar = ui.overlaySnackbar,
            onClose = { ui.video = null },
            onFailed = {
                // Streaming did not work (codec, server, connection): the whole file instead.
                ui.video = null
                received.notice.value = FileNotice("Couldn't play ${file.name.ifBlank { "the video" }} here. Downloading it to open in another app.")
                received.open(file)
            },
        )
    }
}

/** Save / Share (and "Show in chat") for one file, as a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionsSheet(
    file: RichObject,
    ui: FileUi,
    onDismiss: () -> Unit,
    onShowInChat: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodySmall,
                color = TalqTheme.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp),
            )
            FileSheetAction("Save", { Icon(Icons.Outlined.FileDownload, contentDescription = null) }) {
                onDismiss()
                ui.save(file)
            }
            FileSheetAction("Share", { Icon(Icons.Outlined.Share, contentDescription = null) }) {
                onDismiss()
                ui.share(file)
            }
            if (onShowInChat != null) {
                FileSheetAction("Show in chat", { Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null) }) {
                    onDismiss()
                    onShowInChat()
                }
            }
        }
    }
}

@Composable
private fun FileSheetAction(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
