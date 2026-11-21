package talq.app.ui.chat

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
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import talq.app.files.SendPrefs
import talq.app.files.Staging
import talq.app.theme.TalqTheme

/** The three ways to pick something to send. Each opens a system screen. */
class AttachActions(val photos: () -> Unit, val camera: () -> Unit, val files: () -> Unit)

/**
 * The system pickers behind the paperclip. None needs a storage or camera permission: the
 * photo picker and the document picker hand out the picked items only, and the camera app
 * writes into a cache file of ours through the FileProvider.
 *
 * @param onPicked content URIs from the photo or document picker (never empty)
 * @param onPhotoTaken the cache file the camera wrote
 * @param onProblem a sentence for the snackbar (no app can do it)
 */
@Composable
fun rememberAttachActions(
    onPicked: (List<Uri>) -> Unit,
    onPhotoTaken: (File) -> Unit,
    onProblem: (String) -> Unit,
): AttachActions {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val photoTaken by rememberUpdatedState(onPhotoTaken)
    val problem by rememberUpdatedState(onProblem)
    // Saved: the app may be killed while the camera is in front.
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) picked(uris)
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) picked(uris)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = cameraPath?.let { File(it) }
        cameraPath = null
        if (file != null && Staging.isCameraFile(context, file)) {
            if (taken && file.length() > 0) photoTaken(file) else file.delete()
        }
    }
    return remember(photoPicker, documentPicker, camera) {
        AttachActions(
            photos = {
                try {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                } catch (e: ActivityNotFoundException) {
                    problem("No photo picker on this device. Use Files instead.")
                }
            },
            camera = {
                try {
                    val (file, uri) = Staging.newCameraFile(context)
                    cameraPath = file.path
                    camera.launch(uri)
                } catch (e: ActivityNotFoundException) {
                    problem("No camera app on this device.")
                } catch (e: java.io.IOException) {
                    problem("The photo couldn't be prepared. The phone's storage may be full.")
                }
            },
            files = {
                try {
                    documentPicker.launch(arrayOf("*/*"))
                } catch (e: ActivityNotFoundException) {
                    problem("No file picker on this device.")
                }
            },
        )
    }
}

private const val MAX_PHOTOS = 20

/** The sheet behind the paperclip: what to attach, and whether photos go unchanged. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(actions: AttachActions, prefs: SendPrefs, onDismiss: () -> Unit) {
    val colors = TalqTheme.colors
    var original by remember { mutableStateOf(prefs.sendOriginal) }
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
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        original = !original
                        prefs.sendOriginal = original
                    }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Send original", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (original) "Photos go unchanged, with their location and camera details."
                        else "Photos are made smaller and their location is removed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = original,
                    onCheckedChange = {
                        original = it
                        prefs.sendOriginal = it
                    },
                )
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
