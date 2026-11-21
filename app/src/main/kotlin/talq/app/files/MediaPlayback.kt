package talq.app.files

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import talq.core.TalkFiles
import talq.core.model.RichObject
import talq.core.repo.TalkSession

/**
 * HTTP for the in-app players. The account's header is decided per request ON THE WIRE (a
 * network interceptor sees every redirect hop): it is set for the account's own server over
 * https and removed for everything else. A redirect from https to http is not followed.
 */
fun mediaHttpClient(files: TalkFiles): OkHttpClient = OkHttpClient.Builder()
    .followSslRedirects(false)
    .addNetworkInterceptor { chain ->
        val request = chain.request()
        val builder = request.newBuilder().removeHeader("Authorization")
        files.authorizationFor(request.url.toString())?.let { builder.header("Authorization", it) }
        chain.proceed(builder.build())
    }
    .build()

/**
 * A player that streams the account's files (WebDAV, with Range) through [mediaHttpClient].
 * It takes the audio focus (pauses for a call, quiets other players) and pauses when the
 * headphones are pulled out.
 */
@OptIn(UnstableApi::class)
fun newMediaPlayer(context: Context, files: TalkFiles, speech: Boolean = false): ExoPlayer =
    ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(OkHttpDataSource.Factory(mediaHttpClient(files))))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(if (speech) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

/**
 * Voice messages and audio files played inside the chat. ONE plays at a time: starting another
 * stops the first. [stop] (leaving the chat, the app going to the background, signing out)
 * releases the player. Main thread only.
 *
 * @param onFailed streaming did not work for this file (the caller falls back to download-and-open)
 */
class VoicePlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val session: () -> TalkSession?,
    private val onFailed: (RichObject) -> Unit,
) {
    /** [fileId] empty = nothing loaded. */
    data class State(
        val fileId: String = "",
        val playing: Boolean = false,
        val loading: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
    ) {
        val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var current: RichObject? = null
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                // Back to the start, ready to be played again.
                player?.run {
                    pause()
                    seekTo(0)
                }
            }
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            val file = current
            stop()
            if (file != null) onFailed(file)
        }
    }

    /** Play [file], or pause/resume it when it is the one loaded. */
    fun toggle(file: RichObject) {
        val loaded = player
        if (loaded != null && current?.id == file.id) {
            if (loaded.isPlaying || (loaded.playWhenReady && loaded.playbackState == Player.STATE_BUFFERING)) loaded.pause() else loaded.play()
            publish()
            return
        }
        stop()
        val session = session() ?: return
        if (file.path.isEmpty()) {
            onFailed(file)
            return
        }
        val created = newMediaPlayer(context, session.files, speech = true)
        created.addListener(listener)
        created.setMediaItem(MediaItem.fromUri(session.files.davUrl(file.path)))
        created.prepare()
        created.play()
        player = created
        current = file
        publish()
        ticker = scope.launch {
            while (isActive) {
                delay(200)
                publish()
            }
        }
    }

    /** Jump to [fraction] (0..1) of the loaded file; ignored for any other file. */
    fun seek(file: RichObject, fraction: Float) {
        val loaded = player ?: return
        if (current?.id != file.id || loaded.duration <= 0) return
        loaded.seekTo((loaded.duration * fraction.coerceIn(0f, 1f)).toLong())
        publish()
    }

    fun pause() {
        player?.pause()
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        player?.run {
            removeListener(listener)
            release()
        }
        player = null
        current = null
        _state.value = State()
    }

    private fun publish() {
        val loaded = player
        val file = current
        if (loaded == null || file == null) return
        _state.value = State(
            fileId = file.id,
            playing = loaded.isPlaying,
            loading = loaded.playbackState == Player.STATE_BUFFERING || loaded.playbackState == Player.STATE_IDLE,
            positionMs = loaded.currentPosition.coerceAtLeast(0),
            durationMs = loaded.duration.takeIf { it > 0 } ?: 0,
        )
    }
}
