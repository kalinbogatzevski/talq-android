package talq.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import talq.app.appContainer
import talq.app.files.RecordingState
import talq.app.files.newMediaPlayer
import talq.core.model.RichObject
import talq.core.repo.TalkSession

/**
 * A video, full screen, streamed from the account's files (WebDAV with Range) with the
 * account's credentials, which go to the account's own server only (`mediaHttpClient`).
 * [onFailed] when it cannot be played this way.
 */
@Composable
fun VideoPlayer(
    file: RichObject,
    session: TalkSession,
    onClose: () -> Unit,
    onFailed: () -> Unit,
    /** Where to start (the player is rebuilt when the phone is turned), and where it got to. */
    startAtMs: Long = 0,
    onPosition: (Long) -> Unit = {},
    snackbar: SnackbarHostState? = null,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val context = LocalContext.current
        val failed by rememberUpdatedState(onFailed)
        val position by rememberUpdatedState(onPosition)
        val exo = remember(file.id) {
            // A voice message that is playing gives way.
            context.appContainer.voice.stop()
            newMediaPlayer(context.applicationContext, session.files).apply {
                setMediaItem(MediaItem.fromUri(session.files.davUrl(file.path)), startAtMs.coerceAtLeast(0))
                prepare()
                // Not by itself while a voice message is recorded (it would be in the message).
                playWhenReady = context.appContainer.voiceMessages.state.value !is RecordingState.Recording
            }
        }
        DisposableEffect(exo) {
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) = failed()
            }
            exo.addListener(listener)
            onDispose {
                exo.removeListener(listener)
                position(if (exo.playbackState == Player.STATE_ENDED) 0L else exo.currentPosition.coerceAtLeast(0))
                exo.release()
            }
        }
        // Nothing keeps playing behind the lock screen or another app.
        LifecycleStartEffect(exo) {
            onStopOrDispose { exo.pause() }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { PlayerView(it).apply { player = exo } },
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (snackbar != null) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).systemBarsPadding())
        }
    }
}
