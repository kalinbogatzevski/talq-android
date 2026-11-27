package talq.app.ui.message

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import talq.app.appContainer
import talq.app.theme.TalqTheme
import talq.app.ui.chat.sayAnywhere
import talq.app.ui.common.LocalPicturesFromPhone
import talq.app.ui.common.WAITING_FOR_SERVER
import talq.app.ui.common.rememberPicture
import talq.core.model.RichObject
import talq.core.repo.TalkSession

/**
 * A file that is neither a picture, a video nor audio: icon, name, size. A tap downloads it
 * inside the app and opens it ([onClick]); while it downloads, the card shows the progress and
 * a tap cancels.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCard(file: RichObject, onClick: () -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TalqTheme.colors
    val context = LocalContext.current
    val received = context.appContainer.received
    val fraction = downloadProgress(file.id)
    Row(
        modifier = modifier
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.bgPrimary.copy(alpha = 0.5f))
            .combinedClickable(
                onClick = { if (fraction != null) received.cancel(file.id) else onClick() },
                onLongClick = onLongPress,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (fraction != null) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel the download", tint = colors.textSecondary, modifier = Modifier.size(26.dp))
        } else {
            Icon(
                if (file.isVideo) Icons.Outlined.Movie else Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = colors.accentText,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                file.name.ifBlank { "File" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (fraction != null) {
                val bar = Modifier.padding(top = 5.dp, bottom = 3.dp).width(150.dp).height(3.dp)
                if (fraction >= 0f) LinearProgressIndicator(progress = { fraction }, modifier = bar)
                else LinearProgressIndicator(modifier = bar)
                Text(
                    if (fraction >= 0f) "Downloading… ${(fraction * 100).toInt()}%" else "Downloading…",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            } else if (file.size > 0) {
                Text(
                    Formatter.formatShortFileSize(context, file.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

/**
 * How far the download of this file is: null when it is not being downloaded, below 0 when the
 * size is not known, else 0..1.
 */
@Composable
fun downloadProgress(fileId: String): Float? {
    val received = LocalContext.current.appContainer.received
    val progress by remember(fileId) { received.active.map { it[fileId] }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = null)
    return progress
}

/** A download in progress, laid over a picture, a video or a voice card: a ring; a tap cancels. */
@Composable
fun DownloadRing(fileId: String, fraction: Float, modifier: Modifier = Modifier, size: Int = 46) {
    val received = LocalContext.current.appContainer.received
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClickLabel = "Cancel the download") { received.cancel(fileId) },
        contentAlignment = Alignment.Center,
    ) {
        val ring = Modifier.size((size - 8).dp)
        val stroke = (size / 15f).dp
        if (fraction >= 0f) CircularProgressIndicator(progress = { fraction }, modifier = ring, color = Color.White, strokeWidth = stroke, trackColor = Color.White.copy(alpha = 0.25f))
        else CircularProgressIndicator(modifier = ring, color = Color.White, strokeWidth = stroke)
        Icon(Icons.Filled.Close, contentDescription = "Cancel the download", tint = Color.White, modifier = Modifier.size((size * 0.42f).dp))
    }
}

/** The round play mark laid over a video's thumbnail. */
@Composable
fun PlayMark(modifier: Modifier = Modifier, size: Int = 46) {
    Box(
        modifier = modifier.size(size.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size((size * 0.62f).dp))
    }
}

/** A video in a bubble: its thumbnail (when the server has one) under a play mark. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoAttachment(file: RichObject, session: TalkSession, onClick: () -> Unit, onLongPress: () -> Unit) {
    val ratio = if (file.width > 0 && file.height > 0) (file.width.toFloat() / file.height).coerceIn(0.6f, 2.2f) else 16f / 9f
    Box(
        modifier = Modifier
            .padding(vertical = 3.dp)
            .width(if (ratio < 1f) 190.dp else 260.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        if (file.previewAvailable) {
            val url = session.client.filePreviewUrl(file.id, 720, 720)
            val request = rememberPicture(url, session.client.authorizationHeader)
            AsyncImage(model = request, contentDescription = file.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        val fetching = downloadProgress(file.id)
        if (fetching != null) DownloadRing(file.id, fetching) else PlayMark()
    }
}

fun playTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * A voice message or an audio file, played in place: play/pause, a bar that follows (and
 * seeks), position and length. One plays at a time across the app (`VoicePlayer`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceCard(file: RichObject, voiceMessage: Boolean, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TalqTheme.colors
    val context = LocalContext.current
    val voice = context.appContainer.voice
    val mine by remember(file.id) { voice.state.map { if (it.fileId == file.id) it else null }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = null)
    val state = mine
    // Long-press Save / Share, or the fallback when it cannot be streamed.
    val fetching = downloadProgress(file.id)
    // Nothing plays before the account is checked.
    val waiting = LocalPicturesFromPhone.current
    Row(
        modifier = modifier
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.bgPrimary.copy(alpha = 0.5f))
            .combinedClickable(
                onClick = { if (waiting) sayAnywhere(context, WAITING_FOR_SERVER) else voice.toggle(file) },
                onLongClick = onLongPress,
            )
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (fetching != null) {
            DownloadRing(file.id, fetching, size = 38)
        } else Box(Modifier.size(38.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
            when {
                state != null && state.loading && !state.playing ->
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onAccent)
                state?.playing == true -> Icon(Icons.Filled.Pause, contentDescription = "Pause", tint = colors.onAccent)
                else -> Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = colors.onAccent)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(170.dp)) {
            if (!voiceMessage) {
                Text(
                    file.name.ifBlank { "Audio" },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Slider(
                value = state?.fraction ?: 0f,
                onValueChange = { voice.seek(file, it) },
                enabled = state != null && state.durationMs > 0,
                modifier = Modifier.fillMaxWidth().height(26.dp),
            )
            Text(
                text = when {
                    state == null -> (if (voiceMessage) "Voice message" else "Audio") +
                        if (file.size > 0) " · " + Formatter.formatShortFileSize(context, file.size) else ""
                    state.durationMs > 0 -> playTime(state.positionMs) + " / " + playTime(state.durationMs)
                    else -> "Loading…"
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}
