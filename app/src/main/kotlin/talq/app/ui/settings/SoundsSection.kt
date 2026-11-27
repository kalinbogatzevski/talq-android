package talq.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import talq.app.appContainer
import talq.app.sound.Sound
import talq.app.sound.SoundPreview
import talq.app.sound.Tones
import talq.app.theme.TalqTheme

/** Settings > Sounds: the message sound and the ringtone, each with a picker and a preview. */
@Composable
fun SoundsSection() {
    val context = LocalContext.current
    val store = context.appContainer.sounds
    val message by store.message.collectAsStateWithLifecycle()
    val ring by store.ring.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = ringtone, false = message
    SoundRow("Message sound", store.labelOf(message, ring = false)) { picking = false }
    SoundRow("Call ringtone", store.labelOf(ring, ring = true)) { picking = true }
    picking?.let { forRing ->
        SoundPicker(
            ring = forRing,
            current = if (forRing) ring else message,
            onDone = { chosen ->
                picking = null
                if (chosen != null) {
                    if (forRing) store.setRing(chosen) else store.setMessage(chosen)
                }
            },
        )
    }
}

@Composable
private fun SoundRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TalqTheme.colors.accentText)
    }
}

/**
 * The choices: the phone's default, the app's own tones, one from the phone's list, or none.
 * Picking one plays it; "Use" keeps it ([onDone] with the choice), Cancel keeps the old one.
 */
@Composable
private fun SoundPicker(ring: Boolean, current: Sound, onDone: (Sound?) -> Unit) {
    val context = LocalContext.current
    val store = context.appContainer.sounds
    val preview = remember { SoundPreview(context) }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    var chosen by remember { mutableStateOf(current) }
    fun pick(sound: Sound) {
        chosen = sound
        preview.play(store.uriOf(sound, ring), ring, store)
    }
    val phoneList = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        if (uri != null) {
            val title = runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull().orEmpty()
            pick(Sound.Picked(uri.toString(), title))
        }
    }
    val type = if (ring) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION
    val tones = if (ring) Tones.rings else Tones.messages
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(if (ring) "Call ringtone" else "Message sound") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Choice(store.labelOf(Sound.PhoneDefault, ring), chosen == Sound.PhoneDefault) { pick(Sound.PhoneDefault) }
                for (tone in tones) {
                    val sound = Sound.Bundled(tone.id)
                    Choice(tone.label, chosen == sound) { pick(sound) }
                }
                val picked = chosen as? Sound.Picked
                Choice(picked?.let { "From the phone: ${store.labelOf(it, ring)}" } ?: "From the phone…", picked != null) {
                    preview.stop()
                    phoneList.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, store.uriOf(chosen, ring)),
                    )
                }
                Choice("Silent", chosen == Sound.Silent) { pick(Sound.Silent) }
                Text(
                    if (ring) "A new sound resets this notification's Android settings."
                    else "A new sound resets both message alerts' Android settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TalqTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDone(chosen) }) { Text("Use") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
