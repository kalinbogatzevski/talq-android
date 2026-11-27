package talq.app.files

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import talq.core.TalkError
import talq.core.files.FileNames
import talq.core.repo.PreparedFile

/**
 * Videos go out smaller, as photos do, unless originals are sent (`SendPrefs.sendOriginal`): at most 1280 px
 * on the long side, H.264 and AAC, about 2.5 Mbit/s, at most 30 frames a second, HDR as SDR, without the
 * location and the camera's details. [prepare] runs in the file's turn of the outbox
 * (`FileToSend.prepare`), so it keeps the order, stops with Cancel and is not run again by Retry.
 *
 * - **Nothing goes with its details.** What is sent is a movie file that [Mp4Metadata] has made clean: the smaller
 *   one; or the original, when that is small already, cannot be read, has no room beside it or does not get
 *   smaller, whatever went wrong; or, when the original cannot be cleaned where it is, the same picture and sound
 *   in a new file. With none of them the file is not sent, as a photo that cannot be cleaned: only "Send original"
 *   sends it, untouched. Every other way to the original goes through [prepare] again.
 * - Matroska and WebM (a converted phone video keeps its location there) are never cleaned where they are: the
 *   same picture and sound in a new file, or not sent. Other containers (AVI, MPEG-TS, FLV, WMV) that are not made
 *   smaller go as they are, with what is in them.
 * - Sound alone comes here only for what it says of a place (or with a video's type), and is cleaned where it is or
 *   not sent; with nothing of the kind in it nothing is touched ([Mp4Metadata.isPlainSound]).
 * - One video at a time, in all rooms together (a phone has few encoders).
 * - The result is `small/video.mp4` beside the app's copy, so it lives as long as that and an upload tried
 *   again after a restart finds it. What is still being written has another name: one the process died on is
 *   removed ([dropUnfinished]) and made again from the copy, unless it died [MAX_TRIES] times over this video
 *   (a driver that takes the app down with it must not do so at every start): none is written any more then,
 *   until the user says Retry ([forgetTries]). A copy needs no encoder as a rule and is counted apart.
 */
class VideoShrink(context: Context) {
    private val appContext = context.applicationContext
    private val turn = Mutex()

    /** The transformer needs a thread with a looper; not the main one, which its stopping would hold up. */
    private val thread: CoroutineDispatcher by lazy {
        Handler(HandlerThread("video-shrink").apply { start() }.looper).asCoroutineDispatcher()
    }

    /**
     * What to send instead of [original], under which name ([name] is the original's); null: the file as it is,
     * which has no details any more (or is in a container that is not looked into). Throws (a [TalkError.LocalFile],
     * or what went wrong) when neither can be had: it must not go then. [compress]: its type says video. Any other
     * file in a movie's boxes only loses its details.
     */
    suspend fun prepare(original: File, name: String, compress: Boolean, onProgress: (Float) -> Unit): PreparedFile? {
        if (withContext(Dispatchers.IO) { Mp4Metadata.isPlainSound(original) }) return null
        val done = File(File(original.parentFile, FOLDER), DONE)
        // One made before: by a process that died since, or for an upload that could not read it.
        val made = done.takeIf { clean(it) }
            ?: (if (compress) written(original, done, onProgress) { planFor(original) } else null)
        if (made != null) return PreparedFile(FileByteSource(made), mp4Name(name))
        if (clean(original) || withContext(Dispatchers.IO) { !Mp4Metadata.isIso(original) && !Mp4Metadata.needsCleaning(original) }) return null
        val anew = written(original, done, onProgress) { planToCopy(original) }
            ?: throw TalkError.LocalFile(null, "a video's details could not be removed")
        return PreparedFile(FileByteSource(anew), mp4Name(name))
    }

    /** It is clean now. False: it cannot be, or that could not be done. */
    private suspend fun clean(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            file.isFile && Mp4Metadata.blank(file)
        } catch (e: IOException) {
            Log.w(TAG, "a video could not be cleaned (${e::class.simpleName})")
            false
        }
    }

    /** The video written as [plan] says, clean; null when there is no plan or it did not work out, whatever went wrong. */
    private suspend fun written(original: File, done: File, onProgress: (Float) -> Unit, plan: () -> Plan?): File? = try {
        withContext(Dispatchers.IO) { plan() }?.let { turn.withLock { make(original, it, done, onProgress) } }
    } catch (e: Throwable) {
        // Cancelled; anything else (a codec path may throw an Error too) must still reach what comes after it.
        currentCoroutineContext().ensureActive()
        Log.w(TAG, "a video could not be written (${e::class.simpleName})")
        null
    }

    private suspend fun make(original: File, plan: Plan, done: File, onProgress: (Float) -> Unit): File? {
        val part = File(done.parentFile, PART)
        val tries = File(done.parentFile, if (plan.copy) COPY_TRIES else TRIES)
        val start = withContext(Dispatchers.IO) {
            part.delete()
            // Not `mkdirs`: a file discarded meanwhile has no folder any more.
            val folder = part.parentFile
            if (folder == null || !(folder.isDirectory || folder.mkdir()) || tries.length() >= MAX_TRIES) {
                false
            } else {
                tries.appendText("x")
                true
            }
        }
        if (!start) return null
        try {
            if (!export(original, part, plan, onProgress)) return null
            return withContext(Dispatchers.IO) {
                val made = probe(part)
                val whole = made != null && made.durationUs >= plan.durationUs / 10 * 9 && (made.audio || !plan.audio)
                val wanted = whole && (plan.copy || part.length() < original.length())
                if (wanted && Mp4Metadata.blank(part)) done.takeIf { part.renameTo(it) } else null
            }
        } finally {
            part.delete()
            tries.delete()
        }
    }

    // ---- what to do with it --------------------------------------------------------------

    private class Probe(
        /** Upright, as it is watched. */
        val width: Int,
        val height: Int,
        /** 0: not known. */
        val frameRate: Int,
        val durationUs: Long,
        val audio: Boolean,
    )

    private class Plan(
        /** The upright height to scale to; null: it is small enough. */
        val height: Int?,
        val dropFrames: Boolean,
        val durationUs: Long,
        val audio: Boolean,
        /** Picture and sound as they are, only in another file. */
        val copy: Boolean = false,
    )

    /** Null: it is not made smaller. */
    private fun planFor(file: File): Plan? {
        val size = file.length()
        if (size < SMALL_BYTES) return null
        val video = probe(file) ?: return null
        if (video.width <= 0 || video.height <= 0 || video.durationUs <= 0L) return null
        val bitrate = VIDEO_BITRATE + (if (video.audio) AUDIO_BITRATE else 0)
        val expected = bitrate * video.durationUs / 8_000_000L
        // Already at the target, or too little to gain for what it costs.
        if (expected >= size / 10 * 9) return null
        if (file.usableSpace < expected + expected / 4 + SPACE_RESERVE_BYTES) return null
        val longEdge = maxOf(video.width, video.height)
        // Even, as encoders want it.
        val scaled = ((video.height.toLong() * MAX_EDGE / longEdge).toInt() / 2 * 2).coerceAtLeast(2)
        return Plan(
            height = if (longEdge <= MAX_EDGE) null else scaled,
            dropFrames = video.frameRate > MAX_FRAME_RATE + FRAME_RATE_SLACK,
            durationUs = video.durationUs,
            audio = video.audio,
        )
    }

    /** Null: it cannot be written into another file. */
    private fun planToCopy(file: File): Plan? {
        val video = probe(file) ?: return null
        val size = file.length()
        if (video.durationUs <= 0L || file.usableSpace < size + size / 4 + SPACE_RESERVE_BYTES) return null
        return Plan(height = null, dropFrames = false, durationUs = video.durationUs, audio = video.audio, copy = true)
    }

    /** Null: not a video this phone can read. */
    private fun probe(file: File): Probe? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val video = tracks.firstOrNull { mime(it).startsWith("video/") } ?: return null
            val width = int(video, MediaFormat.KEY_WIDTH)
            val height = int(video, MediaFormat.KEY_HEIGHT)
            val turned = int(video, MediaFormat.KEY_ROTATION) % 180 != 0
            return Probe(
                width = if (turned) height else width,
                height = if (turned) width else height,
                frameRate = int(video, MediaFormat.KEY_FRAME_RATE),
                durationUs = if (video.containsKey(MediaFormat.KEY_DURATION)) video.getLong(MediaFormat.KEY_DURATION) else 0L,
                audio = tracks.any { mime(it).startsWith("audio/") },
            )
        } catch (e: IOException) {
            return null
        } catch (e: RuntimeException) {
            return null
        } finally {
            extractor.release()
        }
    }

    private fun mime(format: MediaFormat): String = format.getString(MediaFormat.KEY_MIME).orEmpty()

    /** 0: not there, or not a whole number. */
    private fun int(format: MediaFormat, key: String): Int =
        if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrDefault(0) else 0

    // ---- doing it ------------------------------------------------------------------------

    /** Write [from] as [plan] says into [to]. False when it did not work out; cancelling stops it. */
    @OptIn(UnstableApi::class)
    private suspend fun export(from: File, to: File, plan: Plan, onProgress: (Float) -> Unit): Boolean = withContext(thread) {
        val finished = CompletableDeferred<Boolean>()
        // What is made of the user's videos is nobody's statistics.
        val builder = Transformer.Builder(appContext).setUsePlatformDiagnostics(false)
        // With nothing asked of it, it copies picture and sound as they are.
        if (!plan.copy) {
            builder
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(appContext)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build())
                        .build(),
                )
        }
        val transformer = builder
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished.complete(true)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        Log.w(TAG, "a video could not be written (${exportException.errorCode})")
                        finished.complete(false)
                    }
                },
            )
            .build()
        transformer.start(composition(from, plan), to.path)
        try {
            onProgress(0f)
            val holder = ProgressHolder()
            var percent = 0
            var written = 0L
            var movedAt = SystemClock.uptimeMillis()
            var worked: Boolean? = null
            while (worked == null) {
                worked = withTimeoutOrNull(POLL_MILLIS) { finished.await() }
                if (worked != null) break
                val nowPercent = if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else percent
                val nowWritten = to.length()
                val now = SystemClock.uptimeMillis()
                if (nowPercent != percent || nowWritten != written) movedAt = now
                if (nowPercent != percent) onProgress(nowPercent / 100f)
                percent = nowPercent
                written = nowWritten
                if (now - movedAt > STALL_MILLIS || from.usableSpace < SPACE_RESERVE_BYTES / 2) {
                    Log.w(TAG, "a video is given up on at $percent%")
                    worked = false
                }
            }
            worked == true
        } finally {
            if (!finished.isCompleted) runCatching { transformer.cancel() }
        }
    }

    @OptIn(UnstableApi::class)
    private fun composition(from: File, plan: Plan): Composition {
        val effects = buildList<Effect> {
            if (plan.dropFrames) add(FrameDropEffect.createDefaultFrameDropEffect(MAX_FRAME_RATE.toFloat()))
            if (plan.height != null) add(Presentation.createForHeight(plan.height))
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(from)))
            .setEffects(Effects(emptyList(), effects))
            .build()
        // Asked for by name: a video that has no sound must not get a silent track.
        val sequence = if (plan.audio) {
            EditedMediaItemSequence.withAudioAndVideoFrom(listOf(item))
        } else {
            EditedMediaItemSequence.withVideoFrom(listOf(item))
        }
        val composition = Composition.Builder(sequence)
        if (!plan.copy) composition.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        return composition.build()
    }

    private fun mp4Name(name: String): String {
        val ext = FileNames.extension(name)
        return if (ext.lowercase() == ".mp4") name else name.removeSuffix(ext) + ".mp4"
    }

    companion object {
        private const val TAG = "VideoShrink"
        private const val FOLDER = "small"
        private const val DONE = "video.mp4"
        private const val PART = "part.mp4"

        /** One byte for each time a process began and did not live to end it; the copy's own. */
        private const val TRIES = "tries"
        private const val COPY_TRIES = "copy-tries"
        private const val MAX_TRIES = 2

        private const val MAX_EDGE = 1280
        private const val MAX_FRAME_RATE = 30

        /** A phone's "30" may read 31: not worth dropping frames. */
        private const val FRAME_RATE_SLACK = 3
        private const val VIDEO_BITRATE = 2_500_000
        private const val AUDIO_BITRATE = 128_000
        private const val SMALL_BYTES = 8L * 1024 * 1024
        private const val SPACE_RESERVE_BYTES = 200L * 1024 * 1024
        private const val POLL_MILLIS = 500L

        /** No progress and no byte written for this long: the encoder hangs. */
        private const val STALL_MILLIS = 60_000L

        fun isVideo(mimeType: String): Boolean = mimeType.startsWith("video/", ignoreCase = true)

        /** Remove what a process that died before [before] left half written beside the copy in [folder]. */
        fun dropUnfinished(folder: File, before: Long) {
            val part = File(File(folder, FOLDER), PART)
            if (part.lastModified() in 1 until before) part.delete()
        }

        /** The user has the copy in [folder] tried once more: what processes died on before does not count any more. */
        fun forgetTries(folder: File) {
            for (name in listOf(TRIES, COPY_TRIES)) File(File(folder, FOLDER), name).delete()
        }
    }
}
