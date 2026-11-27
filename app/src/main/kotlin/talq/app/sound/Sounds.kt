package talq.app.sound

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import androidx.annotation.RawRes
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import talq.app.R

/** What plays for a kind of notification. */
sealed interface Sound {
    /** The phone's own default notification sound or ringtone (what the app did before). */
    data object PhoneDefault : Sound

    data object Silent : Sound

    /** One of the app's own tones ([Tones]). */
    data class Bundled(val id: String) : Sound

    /** A sound picked from the phone's own list. */
    data class Picked(val uri: String, val title: String) : Sound
}

/** One of the app's own tones, the same ones TalQ for desktop plays. */
data class Tone(val id: String, val label: String, @RawRes val res: Int)

/**
 * The bundled tones. The message tones are CC0 (Kenney's Interface Sounds), the bells CC0 and the
 * phone rings synthesised for TalQ (see THIRD-PARTY-NOTICES.md).
 */
object Tones {
    val messages = listOf(
        Tone("chime", "Chime", R.raw.tone_chime),
        Tone("pop", "Pop", R.raw.tone_pop),
        Tone("ding", "Ding", R.raw.tone_ding),
        Tone("notify", "Notify", R.raw.tone_notify),
        Tone("soft", "Soft", R.raw.tone_soft),
        Tone("bong", "Bong", R.raw.tone_bong),
    )
    val rings = listOf(
        Tone("classic", "Classic bell", R.raw.ring_classic),
        Tone("bright", "Bright bell", R.raw.ring_bright),
        Tone("softbell", "Soft bell", R.raw.ring_soft),
        Tone("landline", "Landline", R.raw.ring_landline),
        Tone("double", "Double ring", R.raw.ring_uk),
        Tone("oldphone", "Old phone", R.raw.ring_oldphone),
        Tone("trill", "Trill", R.raw.ring_trill),
    )
}

/**
 * The message sound and the ringtone, on this phone (not per account). Android fixes a
 * notification channel's sound when the channel is made, so a change gives the channel a new id
 * (the old one is deleted): version 0 keeps the ids every earlier version used, so nothing
 * changes for anyone who never picks a sound. The cost of a change: Android's own settings for
 * that channel (importance, vibration) start again from the app's defaults.
 */
class SoundStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("sounds", Context.MODE_PRIVATE)
    private val _message = MutableStateFlow(read(KEY_MESSAGE))
    private val _ring = MutableStateFlow(read(KEY_RING))
    val message: StateFlow<Sound> = _message.asStateFlow()
    val ring: StateFlow<Sound> = _ring.asStateFlow()

    /**
     * The channel ids for direct messages and mentions, for other messages, and for ringing as they stand now.
     * The first two are the channels earlier versions had for mentions and for all messages (see [directBase]).
     */
    val directChannel: String get() = versioned(directBase, prefs.getInt(KEY_MESSAGE_VERSION, 0))
    val otherChannel: String get() = versioned(BASE_OTHER, prefs.getInt(KEY_MESSAGE_VERSION, 0))
    val ringChannel: String get() = versioned("call_ring", prefs.getInt(KEY_RING_VERSION, 0))

    /** A message sound was picked: the message channels carry it. Before that they have the phone's default. */
    val messagePicked: Boolean get() = prefs.getInt(KEY_MESSAGE_VERSION, 0) > 0

    /**
     * The direct channel is the old "Mentions" one, with the settings the user gave it, except on a phone where
     * it had been turned down below "Messages": one-to-one messages came on "Messages" until 0.10.0 and must not
     * go quiet with it. There the direct channel is a new one, and the old one is left alone. Decided once.
     */
    private val directBase: String by lazy {
        prefs.getString(KEY_DIRECT_BASE, null) ?: run {
            val manager = NotificationManagerCompat.from(app)
            val version = prefs.getInt(KEY_MESSAGE_VERSION, 0)
            val messages = manager.getNotificationChannelCompat(versioned(BASE_OTHER, version))
            val mentions = manager.getNotificationChannelCompat(versioned(BASE_MENTIONS, version))
            val turnedDown = messages != null && mentions != null &&
                (mentions.importance < messages.importance || (mentions.sound == null && messages.sound != null))
            (if (turnedDown) BASE_DIRECT else BASE_MENTIONS).also { prefs.edit().putString(KEY_DIRECT_BASE, it).apply() }
        }
    }

    /** Picks the message sound: both message channels are made anew with it. */
    fun setMessage(sound: Sound) {
        if (sound == _message.value) return
        val old = listOf(directChannel, otherChannel)
        write(KEY_MESSAGE, sound)
        prefs.edit().putInt(KEY_MESSAGE_VERSION, prefs.getInt(KEY_MESSAGE_VERSION, 0) + 1).apply()
        _message.value = sound
        delete(old)
    }

    /** Picks the ringtone: the ringing channel is made anew with it. */
    fun setRing(sound: Sound) {
        if (sound == _ring.value) return
        val old = listOf(ringChannel)
        write(KEY_RING, sound)
        prefs.edit().putInt(KEY_RING_VERSION, prefs.getInt(KEY_RING_VERSION, 0) + 1).apply()
        _ring.value = sound
        delete(old)
    }

    /** The address Android plays for [sound]; null for [Sound.Silent]. */
    fun uriOf(sound: Sound, ring: Boolean): Uri? = when (sound) {
        Sound.Silent -> null
        Sound.PhoneDefault -> RingtoneManager.getDefaultUri(if (ring) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION)
        // By name, not by number: Android keeps a channel's sound for good, and a later build may
        // number the resources differently.
        is Sound.Bundled -> (if (ring) Tones.rings else Tones.messages).firstOrNull { it.id == sound.id }
            ?.let { Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${app.packageName}/raw/${app.resources.getResourceEntryName(it.res)}") }
            ?: RingtoneManager.getDefaultUri(if (ring) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION)
        is Sound.Picked -> Uri.parse(sound.uri)
    }

    /** A name for [sound] in the settings. */
    fun labelOf(sound: Sound, ring: Boolean): String = when (sound) {
        Sound.Silent -> "Silent"
        Sound.PhoneDefault -> if (ring) "Phone's ringtone" else "Phone's notification sound"
        is Sound.Bundled -> (if (ring) Tones.rings else Tones.messages).firstOrNull { it.id == sound.id }?.label ?: "Phone's default"
        is Sound.Picked -> sound.title.ifBlank { "From the phone" }
    }

    /** Audio attributes for a channel of this kind. */
    fun attributes(ring: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (ring) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun delete(ids: List<String>) {
        val manager = NotificationManagerCompat.from(app)
        ids.forEach { manager.deleteNotificationChannel(it) }
    }

    private fun read(key: String): Sound {
        val kind = prefs.getString("${key}_kind", null)
        val value = prefs.getString("${key}_value", null).orEmpty()
        return when (kind) {
            "silent" -> Sound.Silent
            "bundled" -> Sound.Bundled(value)
            "picked" -> Sound.Picked(value, prefs.getString("${key}_title", null).orEmpty())
            else -> Sound.PhoneDefault
        }
    }

    private fun write(key: String, sound: Sound) {
        val edit = prefs.edit()
        when (sound) {
            Sound.PhoneDefault -> edit.putString("${key}_kind", "default").remove("${key}_value")
            Sound.Silent -> edit.putString("${key}_kind", "silent").remove("${key}_value")
            is Sound.Bundled -> edit.putString("${key}_kind", "bundled").putString("${key}_value", sound.id)
            is Sound.Picked -> edit.putString("${key}_kind", "picked").putString("${key}_value", sound.uri).putString("${key}_title", sound.title)
        }
        edit.apply()
    }

    private fun versioned(base: String, version: Int) = if (version == 0) base else "${base}_v$version"

    private companion object {
        const val KEY_MESSAGE = "message"
        const val KEY_RING = "ring"
        const val KEY_MESSAGE_VERSION = "message_channel_version"
        const val KEY_RING_VERSION = "ring_channel_version"
        const val KEY_DIRECT_BASE = "direct_channel"
        const val BASE_OTHER = "messages"
        const val BASE_MENTIONS = "mentions"
        const val BASE_DIRECT = "direct"
    }
}

/** Plays one sound at a time for the picker's preview. */
class SoundPreview(private val context: Context) {
    private var playing: Ringtone? = null

    fun play(uri: Uri?, ring: Boolean, store: SoundStore) {
        stop()
        if (uri == null) return
        playing = RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = store.attributes(ring)
            play()
        }
    }

    fun stop() {
        playing?.stop()
        playing = null
    }
}
