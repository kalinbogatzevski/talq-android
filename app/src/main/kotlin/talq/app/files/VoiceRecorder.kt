package talq.app.files

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.log10

/** What a finished capture holds. [peak] is the loudest sample (0..32768). */
class Recorded(val file: File, val durationMillis: Long, val dataBytes: Long, val peak: Int) {
    /** The loudest peak in dBFS; minus infinity for digital silence. */
    val peakDb: Double get() = if (peak <= 0) Double.NEGATIVE_INFINITY else 20.0 * log10(peak / 32768.0)
}

/** A capture that could not go on; [message] is the one line for the user. */
class VoiceRecordException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Records the microphone into a WAV file for a voice message. Ported from TalQ for desktop's
 * `src/core/VoiceRecorder.*`: it owns only the microphone, the file and the clock. It does not
 * upload, knows no rooms, and never decides whether the file is sent (`VoiceMessages` does).
 *
 * WAV (16-bit PCM, mono) rather than desktop's mp3, and deliberately so: Talk keeps a file's
 * voice-message flag only for audio/mpeg and audio/wav (`postAttachmentToRoom`, and
 * `fixMimeTypeOfVoiceMessage` for a classic share); Ogg/Opus or AAC would arrive as a plain audio
 * file. Android has no mp3 encoder, and WAV plays everywhere: TalQ for desktop (GStreamer
 * `wavparse`), browsers, and this app (Media3). 16 kHz mono is speech bandwidth, about 1.9 MB a
 * minute (desktop's mp3 is about 0.5 MB).
 *
 * [record] blocks (run it on an IO thread) until [stop] is called, then completes the header
 * (the sizes are written last) and returns. Elapsed time is counted from the samples written.
 */
internal class VoiceRecorder(val file: File) {
    @Volatile private var stopping = false

    /** End the capture: [record] returns once the file is complete. Any thread. */
    fun stop() {
        stopping = true
    }

    /** The microphone permission is checked by the caller (`VoiceMessages.start`). */
    @SuppressLint("MissingPermission")
    fun record(onElapsed: (Long) -> Unit): Recorded {
        val rate = RATES.firstOrNull { AudioRecord.getMinBufferSize(it, CHANNEL, ENCODING) > 0 }
            ?: throw VoiceRecordException(CANT_OPEN)
        val chunk = rate / 10 // samples per read: 100 ms, so a stop is seen within that
        val buffer = maxOf(AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING), chunk * 2 * 4)
        val audio = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, CHANNEL, ENCODING, buffer)
        } catch (e: Exception) {
            throw VoiceRecordException(CANT_OPEN, e)
        }
        try {
            if (audio.state != AudioRecord.STATE_INITIALIZED) throw VoiceRecordException(CANT_OPEN)
            try {
                RandomAccessFile(file, "rw").use { out ->
                    out.setLength(0)
                    out.write(header(rate, 0))
                    try {
                        audio.startRecording()
                    } catch (e: IllegalStateException) {
                        throw VoiceRecordException(CANT_OPEN, e)
                    }
                    if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw VoiceRecordException(CANT_OPEN)
                    val samples = ShortArray(chunk)
                    val bytes = ByteArray(chunk * 2)
                    var total = 0L
                    var peak = 0
                    var shown = 0L
                    while (!stopping) {
                        val n = audio.read(samples, 0, chunk)
                        if (n < 0) throw VoiceRecordException("Recording stopped: the microphone failed.")
                        for (i in 0 until n) {
                            val s = samples[i].toInt()
                            val a = if (s < 0) -s else s
                            if (a > peak) peak = a
                            bytes[2 * i] = s.toByte()
                            bytes[2 * i + 1] = (s shr 8).toByte()
                        }
                        out.write(bytes, 0, n * 2)
                        total += n
                        val ms = total * 1000 / rate
                        if (ms - shown >= TICK_MILLIS) {
                            shown = ms
                            onElapsed(ms)
                        }
                    }
                    runCatching { audio.stop() }
                    // The sizes, now that they are known: a header saying 0 bytes is a file that
                    // some players read as empty.
                    val data = total * 2
                    out.seek(4)
                    out.write(le32(36 + data))
                    out.seek(40)
                    out.write(le32(data))
                    return Recorded(file, total * 1000 / rate, data, peak)
                }
            } catch (e: IOException) {
                throw VoiceRecordException("The recording couldn't be saved.", e)
            }
        } finally {
            audio.release()
        }
    }

    private companion object {
        /** 16 kHz first (speech); every phone must offer 44.1 kHz. */
        val RATES = intArrayOf(16_000, 44_100, 48_000)
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val TICK_MILLIS = 200L
        const val CANT_OPEN = "Couldn't open the microphone."

        fun le32(v: Long): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

        fun le16(v: Int): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte())

        /** The 44-byte RIFF/WAVE header of 16-bit mono PCM at [rate] with [data] bytes. */
        fun header(rate: Int, data: Long): ByteArray =
            "RIFF".toByteArray(Charsets.US_ASCII) + le32(36 + data) + "WAVE".toByteArray(Charsets.US_ASCII) +
                "fmt ".toByteArray(Charsets.US_ASCII) + le32(16) + le16(1) + le16(1) + le32(rate.toLong()) +
                le32(rate * 2L) + le16(2) + le16(16) +
                "data".toByteArray(Charsets.US_ASCII) + le32(data)
    }
}
