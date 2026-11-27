package talq.app.ui.message

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.File
import talq.app.files.PendingUpload
import talq.app.files.UploadState
import talq.app.theme.TalqTheme

/**
 * A file on its way out: thumbnail (photos) or a type icon, name, size, what is happening to
 * it and its progress, with Cancel while it is going and Retry / Delete once it failed; [onOriginal] sends a
 * file whose location could not be removed as it is. Like a failed text, it stays until the user decides.
 */
@Composable
fun PendingFileRow(
    upload: PendingUpload,
    onRetry: () -> Unit,
    onOriginal: () -> Unit,
    onDiscard: () -> Unit,
    caption: String = upload.caption,
) {
    val colors = TalqTheme.colors
    val context = LocalContext.current
    val state = upload.state
    val failed = !state.active
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 50.dp, end = 10.dp, top = 3.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = bubbleMaxWidth())
                .clip(RoundedCornerShape(14.dp))
                .background(colors.bgMessageOwn)
                .then(if (failed) Modifier.border(1.dp, colors.danger, RoundedCornerShape(14.dp)) else Modifier)
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = if (failed) 10.dp else 0.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(colors.bgSecondary),
                    contentAlignment = Alignment.Center,
                ) {
                    val icon = when {
                        upload.mimeType.startsWith("image/") -> Icons.Outlined.Image
                        upload.mimeType.startsWith("video/") -> Icons.Outlined.Movie
                        upload.mimeType.startsWith("audio/") -> Icons.Outlined.AudioFile
                        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
                    }
                    Icon(icon, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(26.dp))
                    if (upload.mimeType.startsWith("image/") && upload.path.isNotEmpty()) {
                        AsyncImage(
                            model = File(upload.path),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(52.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false).widthIn(min = 150.dp)) {
                    Text(
                        upload.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    val size = if (upload.size > 0) Formatter.formatShortFileSize(context, upload.size) else ""
                    val status = when (state) {
                        UploadState.Queued -> "Waiting"
                        is UploadState.Compressing -> "Compressing ${(state.fraction * 100).toInt()}%"
                        is UploadState.Uploading -> "${(state.fraction * 100).toInt()}%"
                        UploadState.Sharing -> "Sending…"
                        is UploadState.Failed, UploadState.Interrupted, UploadState.Lost -> "Not sent"
                    }
                    Text(
                        listOf(size, status).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                    if (!failed) {
                        Spacer(Modifier.height(5.dp))
                        val bar = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                        val fraction = (state as? UploadState.Compressing)?.fraction ?: (state as? UploadState.Uploading)?.fraction
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = bar,
                                color = colors.accent,
                                trackColor = colors.bgSecondary,
                                drawStopIndicator = {},
                            )
                        } else {
                            LinearProgressIndicator(modifier = bar, color = colors.accent, trackColor = colors.bgSecondary)
                        }
                    }
                }
                if (!failed) {
                    IconButton(onClick = onDiscard) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel upload", tint = colors.textSecondary)
                    }
                }
            }
            if (caption.isNotEmpty()) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 10.dp),
                )
            }
        }
        if (failed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.danger, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    when (state) {
                        is UploadState.Failed -> "Not sent. " + state.message
                        UploadState.Lost -> "Not sent, and the phone has cleared its copy. Pick the file again."
                        else -> "Not sent: the app was closed before it went out."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.danger,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Row {
                TextButton(onClick = onDiscard) { Text("Delete", color = colors.textSecondary) }
                if (state != UploadState.Lost) TextButton(onClick = onRetry) { Text("Retry") }
                if (state is UploadState.Failed && state.original) TextButton(onClick = onOriginal) { Text("Send original") }
            }
        }
    }
}
