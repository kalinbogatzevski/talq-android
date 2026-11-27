package talq.app.ui.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import talq.app.AppContainer
import talq.app.SessionState
import talq.app.call.CallActivity
import talq.app.files.Staging
import talq.app.theme.TalqTheme

/** The three ways to pick something to send. Each opens a system screen. */
class AttachActions(val photos: () -> Unit, val camera: () -> Unit, val files: () -> Unit)

/** The pickers of the app ([rememberAttachPickers]): what is picked goes into the tray [into] names. */
class AttachPickers internal constructor(private val open: (what: Pick, into: ComposerDrafts.Key, say: (String) -> Unit) -> Unit) {
    internal enum class Pick { Photos, Camera, Files }

    /** [say]: a sentence for the snackbar (no app can do it). */
    fun into(key: ComposerDrafts.Key, say: (String) -> Unit) = AttachActions(
        photos = { open(Pick.Photos, key, say) },
        camera = { open(Pick.Camera, key, say) },
        files = { open(Pick.Files, key, say) },
    )
}

val LocalAttachPickers = staticCompositionLocalOf<AttachPickers> { error("no pickers") }

private val KeySaver = listSaver<ComposerDrafts.Key?, String>(
    save = { key -> if (key == null) emptyList() else listOf(key.account, key.token, key.tab.toString()) },
    restore = { (account, token, tab) -> ComposerDrafts.Key(account, token, tab.toLongOrNull() ?: 0L) },
)

/**
 * The system pickers behind the paperclip, registered once, above every screen: what comes back goes straight into the
 * tray it was asked for, also when Android ended the app behind the camera or a picker and the conversation is not on
 * screen (any more). None needs a storage permission: the photo picker and the document picker hand out the picked items
 * only, and the camera app writes into a cache file of ours through the FileProvider. It is only opened with the camera
 * allowed, though: the app declares that permission for its calls, and Android then refuses the camera app to it without.
 */
@Composable
fun rememberAttachPickers(container: AppContainer): AttachPickers {
    val context = LocalContext.current
    // Saved: the app may be killed while one of them is in front.
    var target by rememberSaveable(stateSaver = KeySaver) { mutableStateOf<ComposerDrafts.Key?>(null) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }

    /** Where what came back goes; null: its account was signed out meanwhile. */
    fun tray(): ComposerDrafts.Key? = target?.takeIf { key ->
        when (val state = container.sessionState.value) {
            SessionState.SignedOut -> false
            is SessionState.SignedIn -> ComposerDrafts.accountOf(state.session) == key.account
            else -> true
        }
    }
    fun add(uris: List<Uri>) {
        val key = tray()
        if (uris.isNotEmpty() && key != null) container.uploads.trays.add(key, uris)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS), ::add)
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), ::add)
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = cameraPath?.let { File(it) }
        cameraPath = null
        if (file != null && Staging.isCameraFile(context, file)) {
            val key = tray()
            if (taken && file.length() > 0 && key != null) container.uploads.trays.addCamera(key, file) else file.delete()
        }
    }
    fun openCamera(say: (String) -> Unit) {
        var file: File? = null
        try {
            val (made, uri) = Staging.newCameraFile(context)
            file = made
            cameraPath = made.path
            camera.launch(uri)
            return
        } catch (e: ActivityNotFoundException) {
            say("No camera app on this device.")
        } catch (e: SecurityException) {
            say(CAMERA_NEEDED)
        } catch (e: IOException) {
            say("The photo couldn't be prepared. The phone's storage may be full.")
        }
        cameraPath = null
        file?.delete()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The conversation may be gone from the screen by now.
        if (granted) openCamera { sayAnywhere(context, it) } else sayAnywhere(context, CAMERA_NEEDED)
    }
    return remember(photoPicker, documentPicker, camera, permission) {
        AttachPickers { what, into, say ->
            target = into
            when (what) {
                AttachPickers.Pick.Photos -> try {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                } catch (e: ActivityNotFoundException) {
                    say("No photo picker on this device. Use Files instead.")
                }
                AttachPickers.Pick.Camera ->
                    if (CallActivity.granted(context, Manifest.permission.CAMERA)) openCamera(say)
                    else permission.launch(Manifest.permission.CAMERA)
                AttachPickers.Pick.Files -> try {
                    documentPicker.launch(arrayOf("*/*"))
                } catch (e: ActivityNotFoundException) {
                    say("No file picker on this device.")
                }
            }
        }
    }
}

private const val CAMERA_NEEDED = "Allow the camera in Android's settings first."
private const val MAX_PHOTOS = 20

/**
 * The sheet behind the paperclip and the tray's "+": what to attach. [onPaste]: put in what the clipboard
 * holds; null when it holds no file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(actions: AttachActions, onPaste: (() -> Unit)?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            AttachRow(Icons.Outlined.PhotoLibrary, "Photos & videos") {
                onDismiss()
                actions.photos()
            }
            AttachRow(Icons.Outlined.PhotoCamera, "Camera") {
                onDismiss()
                actions.camera()
            }
            AttachRow(Icons.AutoMirrored.Outlined.InsertDriveFile, "Files") {
                onDismiss()
                actions.files()
            }
            if (onPaste != null) {
                AttachRow(Icons.Outlined.ContentPaste, "Paste") {
                    onDismiss()
                    onPaste()
                }
            }
        }
    }
}

@Composable
private fun AttachRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = TalqTheme.colors.accentText)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
