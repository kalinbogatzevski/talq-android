package talq.app.call

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * The ringing the caller hears while a call waits for the other side (TalQ for desktop plays its
 * own "brrr-brrr"): Android's built-in ringback tone on the call's audio stream, so it comes out
 * where the call does (earpiece, speaker, headset). Main thread.
 */
class Ringback {
    private var tone: ToneGenerator? = null

    fun start() {
        if (tone != null) return
        // A phone without a free tone generator simply stays quiet.
        tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, VOLUME) }.getOrNull()?.also {
            runCatching { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        }
    }

    fun stop() {
        val t = tone ?: return
        tone = null
        runCatching { t.stopTone() }
        runCatching { t.release() }
    }

    private companion object {
        const val VOLUME = 80
    }
}
