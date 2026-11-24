package talq.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import talq.app.files.RecordingState
import talq.app.theme.TalqTheme

/**
 * The composer's voice-message controls (TalQ for desktop's mic button, `ComposerWidget`
 * `setRecordingState` / `setRecordingElapsed`). [recording] is the recording of THIS room (any
 * tab of it), null when none runs here.
 *
 * - [onStart]: start recording; [holding] = started by touch and hold. False when it did not
 *   start (the microphone permission is being asked, a call runs, ...).
 * - [onLock]: the finger left without sending: the controls stay, a tap on the button sends.
 * - [onSend]: stop and send. [onCancel]: discard.
 */
class VoiceRecordUi(
    val recording: RecordingState.Recording?,
    val onStart: (holding: Boolean) -> Boolean,
    val onLock: () -> Unit,
    val onSend: () -> Unit,
    val onCancel: () -> Unit,
)

/** How far the finger slides toward the start side before a held recording is discarded. */
private val CANCEL_SLIDE = 96.dp

/**
 * The gestures of the record button, as ONE modifier that is never recreated (its press must
 * survive the composer turning into the recording row under the finger):
 * - touch and hold records, letting go sends, sliding toward the start side discards;
 * - a tap starts recording, and a tap on the same button (now Send) stops and sends.
 * The recording starts on the touch itself; a touch let go before the long-press time is a tap.
 * A gesture that is lost (the screen turned under the finger) leaves the recording running with
 * its controls. A touch within the double-tap time after [micSince] (when the microphone face
 * appeared, `SystemClock.uptimeMillis`) starts nothing: it is the second tap of a double tap on
 * Send, which emptied the box under the finger.
 */
@Composable
internal fun rememberRecordGesture(voice: VoiceRecordUi, micSince: Long): Modifier {
    val current by rememberUpdatedState(voice)
    val since by rememberUpdatedState(micSince)
    val rtl by rememberUpdatedState(LocalLayoutDirection.current == LayoutDirection.Rtl)
    val slide = with(LocalDensity.current) { CANCEL_SLIDE.toPx() }
    val holdMillis = LocalViewConfiguration.current.longPressTimeoutMillis
    return remember(slide, holdMillis) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val running = current.recording
                if (running != null) {
                    // Recording after a tap: this tap sends (nothing while it is being finished).
                    val up = waitForUpOrCancellation()
                    if (up != null && !running.finishing) {
                        up.consume()
                        current.onSend()
                    }
                    return@awaitEachGesture
                }
                if (down.uptimeMillis - since < viewConfiguration.doubleTapTimeoutMillis) return@awaitEachGesture
                down.consume()
                if (!current.onStart(true)) return@awaitEachGesture
                var settled = false
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            settled = true
                            if (change.uptimeMillis - down.uptimeMillis >= holdMillis) current.onSend() else current.onLock()
                            break
                        }
                        val dx = change.position.x - down.position.x
                        if ((if (rtl) dx else -dx) > slide) {
                            change.consume()
                            settled = true
                            current.onCancel()
                            break
                        }
                        change.consume()
                    }
                } finally {
                    if (!settled) current.onLock()
                }
            }
        }
    }
}

/**
 * What stands in for the text field while recording: the bin (discard, first in the row and away
 * from the button that sends, as on desktop), a painted red disc, the time, and one hint while the
 * finger holds. While the file is being finished the disc is amber and nothing takes a tap.
 */
@Composable
internal fun RecordingRow(recording: RecordingState.Recording, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TalqTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCancel, enabled = !recording.finishing, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Outlined.Delete, contentDescription = "Discard recording", tint = colors.textSecondary)
        }
        Spacer(Modifier.width(4.dp))
        RecordingDisc(finishing = recording.finishing)
        Spacer(Modifier.width(8.dp))
        Text(
            elapsedText(recording.elapsedMillis),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.semantics { contentDescription = "Recording, " + elapsedText(recording.elapsedMillis) },
        )
        Spacer(Modifier.width(12.dp))
        val hint = when {
            recording.finishing -> "Sending…"
            recording.holding -> if (rtl) "Slide right to cancel" else "Slide left to cancel"
            else -> ""
        }
        if (hint.isNotEmpty()) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The recording light: a painted disc, red and slowly pulsing while recording, amber while finishing. */
@Composable
private fun RecordingDisc(finishing: Boolean) {
    val colors = TalqTheme.colors
    val pulse = rememberInfiniteTransition(label = "recording")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "recording-alpha",
    )
    Box(
        Modifier
            .size(10.dp)
            .graphicsLayer { this.alpha = if (finishing) 1f else alpha }
            .clip(CircleShape)
            .background(if (finishing) colors.amber else colors.danger),
    )
}

/** m:ss, as desktop shows it. */
internal fun elapsedText(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/** Keeps the screen on while this is shown (a recording: the phone locking would end it). */
@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
