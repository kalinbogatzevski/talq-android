package talq.app.files

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import talq.app.call.CallActivity
import talq.core.model.MIN_VOICE_MILLIS
import talq.core.model.VoicePlace
import talq.core.model.VoiceRoute
import talq.core.model.navigationEndsRecording
import talq.core.model.routeFinished
import talq.core.model.startRoomPresent
import talq.core.model.voiceRouteText
import talq.core.repo.TalkSession

/** The record controls' state, for the composer of the room the recording belongs to. */
sealed interface RecordingState {
    data object Idle : RecordingState

    data class Recording(
        /** The room and topic the message goes to: where recording started. */
        val place: VoicePlace,
        val elapsedMillis: Long = 0,
        /** The finger is on the button: letting go sends, sliding away cancels. */
        val holding: Boolean = false,
        /** Stopped and being finished and handed to the upload: takes nothing more. */
        val finishing: Boolean = false,
    ) : RecordingState
}

/**
 * Voice messages from the composer (`appContainer.voiceMessages`, main thread only). One
 * recording at a time, in the container so that turning the phone keeps it. Ported from TalQ for
 * desktop's `MainWindow` wiring of its `VoiceRecorder` and `src/core/VoiceRoomPolicy.h` (the
 * rules are in `talq.core.model.VoiceRoomPolicy`):
 *
 * - A recording goes to the room and topic where it STARTED. Another tab of the same room is not
 *   leaving (the controls stay); another room, the conversation list, another screen, the app
 *   going to the background (Android silences a microphone in the background) end it and send it
 *   there, with a one-line notice when that is not what is on screen.
 * - Under a second is dropped without a word; a room that is gone discards it with a notice; a
 *   capture with no bytes or only digital silence is not sent and says why (desktop's -75 dBFS).
 * - Signing out discards it ([signedOut]), before the outgoing folder is cleared.
 *
 * The file is written straight into `cache/outgoing/<random>/voice-message-<UTC stamp>.wav`
 * (desktop's name with WAV) and handed to [UploadManager.enqueue] with the voice-message flag.
 * Notices are toasts: by the time one is said the user may be on any screen.
 */
class VoiceMessages(
    context: Context,
    private val scope: CoroutineScope,
    private val uploads: UploadManager,
    private val player: VoicePlayer,
    private val inCall: () -> Boolean,
    private val session: () -> TalkSession?,
) {
    private val appContext = context.applicationContext

    private sealed interface Ending {
        /** Send where it started; [openNow] is what the user sees once it is done. */
        data class Send(val openNow: VoicePlace) : Ending

        data class Discard(val notice: String?) : Ending
    }

    private class Take(
        val session: TalkSession,
        val place: VoicePlace,
        val roomName: String,
        val topicTitle: String,
        val recorder: VoiceRecorder,
    ) {
        var ending: Ending? = null
        var roomWatch: Job? = null

        /**
         * The start room has been seen in the conversation list during this recording. Only then
         * does its absence mean it is gone: a room opened before the list knew it (a new
         * conversation opened from a notification) is not "gone".
         */
        var listed = false

        /** Desktop's `startRoomPresent`, with that extra caution. */
        fun present(tokens: Set<String>): Boolean = !listed || startRoomPresent(tokens, place.token)
    }

    private var take: Take? = null
    private var focus: AudioFocusRequest? = null

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /**
     * Start recording for [place] ([roomName] and [topicTitle] are kept for the notice: a room that
     * is gone by then is no longer in the list). [holding]: started by touch and hold. Returns null
     * when recording started, else the one line saying why not.
     */
    fun start(session: TalkSession, place: VoicePlace, roomName: String, topicTitle: String, holding: Boolean): String? {
        // Only a recording still being finished can be here (leaving its room ended it).
        if (take != null) return "The last recording is still being saved."
        if (!CallActivity.granted(appContext, Manifest.permission.RECORD_AUDIO)) return "Voice messages need the microphone."
        if (inCall()) return "Not during a call."
        val dir = File(Staging.outgoingDir(appContext), UUID.randomUUID().toString())
        if (!dir.mkdirs()) return "No room on the phone for a recording."
        player.stop()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val recorder = VoiceRecorder(File(dir, "voice-message-$stamp.wav"))
        val t = Take(session, place, roomName, topicTitle.trim(), recorder)
        take = t
        _state.value = RecordingState.Recording(place, holding = holding)
        takeFocus()
        scope.launch {
            val result: Result<Recorded> = try {
                Result.success(
                    withContext(Dispatchers.IO) {
                        recorder.record { ms ->
                            _state.update { s -> if (take === t && s is RecordingState.Recording) s.copy(elapsedMillis = ms) else s }
                        }
                    },
                )
            } catch (e: VoiceRecordException) {
                Log.w(TAG, "recording ended", e)
                Result.failure(e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "recording ended", e)
                Result.failure(VoiceRecordException("Recording failed.", e))
            }
            ended(t, result)
        }
        // The room it started in leaving the list (deleted, left, removed elsewhere): nowhere to go.
        t.roomWatch = scope.launch {
            session.rooms.rooms.collect { list ->
                val tokens = list.mapTo(HashSet()) { it.token }
                if (place.token in tokens) t.listed = true
                if (t.ending == null && !t.present(tokens)) {
                    val route = routeFinished(elapsedOf(t), place, place, startPresent = false)
                    discard(t, voiceRouteText(route, nameOf(t), t.topicTitle, place.threadId > 0))
                }
            }
        }
        return null
    }

    /** The finger left the button without sending (a tap, or the gesture was lost): tap again to send. */
    fun lock() {
        _state.update { s -> if (s is RecordingState.Recording && s.holding) s.copy(holding = false) else s }
    }

    /** The user's Stop: send it where it started; [openNow] is the room and topic on screen. */
    fun stop(openNow: VoicePlace) {
        val t = take ?: return
        if (t.ending != null) return
        t.ending = Ending.Send(openNow)
        _state.update { s -> if (s is RecordingState.Recording) s.copy(finishing = true, holding = false) else s }
        t.recorder.stop()
    }

    /** Discard the recording without a word (the bin, sliding away). */
    fun cancel() {
        take?.let { discard(it, null) }
    }

    /** A room screen for [token] came up: a recording that started in another room ends and is sent there. */
    fun roomShown(token: String) {
        val t = take ?: return
        if (navigationEndsRecording(t.ending == null, t.place, token)) stop(VoicePlace(token, 0))
    }

    /** The screen of room [token] went away (not a turn of the phone): a recording that started there is sent. */
    fun roomLeft(token: String) {
        val t = take ?: return
        if (t.place.token == token) stop(VoicePlace.NONE)
    }

    /** The app is leaving the front with [onScreen] open: the microphone stops, the message is sent. */
    fun appLeft(onScreen: VoicePlace) {
        stop(onScreen)
    }

    /** Sign-out: nothing of the account may be sent later. Before the outgoing folder is cleared. */
    fun signedOut() {
        take?.let { discard(it, null) }
    }

    private fun discard(t: Take, notice: String?) {
        if (t.ending is Ending.Discard) return
        t.ending = Ending.Discard(notice)
        t.recorder.stop()
        // At once: the controls go, and a new recording may start while this one is cleaned up.
        if (take === t) {
            take = null
            _state.value = RecordingState.Idle
        }
    }

    private fun ended(t: Take, result: Result<Recorded>) {
        t.roomWatch?.cancel()
        if (take === t) {
            take = null
            _state.value = RecordingState.Idle
        }
        if (take == null) dropFocus()
        val file = t.recorder.file
        val recorded = result.getOrNull()
        when (val ending = t.ending) {
            is Ending.Discard -> {
                delete(file)
                ending.notice?.let(::say)
            }
            null -> {
                // Failed on its own (the microphone went away).
                delete(file)
                say(result.exceptionOrNull()?.message ?: "Recording failed.")
            }
            is Ending.Send -> {
                if (recorded == null) {
                    delete(file)
                    say(result.exceptionOrNull()?.message ?: "Recording failed.")
                    return
                }
                // Under a second is a mis-click: dropped without a word, before anything is said.
                if (recorded.durationMillis < MIN_VOICE_MILLIS) {
                    delete(file)
                    return
                }
                // A recording with no bytes is a failed capture; one that never rose off digital
                // silence is a muted microphone (an empty file would go to somebody otherwise).
                if (recorded.dataBytes <= 0) {
                    delete(file)
                    say("Nothing was recorded.")
                    return
                }
                if (recorded.peakDb < SILENCE_DB) {
                    delete(file)
                    say("No sound was recorded: is the microphone off?")
                    return
                }
                // Signed out (or into another account) meanwhile: nothing goes anywhere.
                if (session() !== t.session) {
                    delete(file)
                    return
                }
                val tokens = t.session.rooms.rooms.value.mapTo(HashSet()) { it.token }
                val route = routeFinished(recorded.durationMillis, t.place, ending.openNow, t.present(tokens))
                when (route) {
                    VoiceRoute.Drop, VoiceRoute.DiscardAndSay -> delete(file)
                    VoiceRoute.Send, VoiceRoute.SendAndSay -> uploads.enqueue(
                        t.session, t.place.token, t.place.threadId,
                        listOf(StagedFile(file.path, file.name, MIME, file.length())),
                        caption = "",
                        voiceMessage = true,
                    )
                }
                voiceRouteText(route, nameOf(t), t.topicTitle, t.place.threadId > 0)?.let(::say)
            }
        }
    }

    private fun elapsedOf(t: Take): Long =
        (_state.value as? RecordingState.Recording)?.takeIf { take === t }?.elapsedMillis ?: 0L

    /** The name the list has now (a rename shows), else the one taken at the start. */
    private fun nameOf(t: Take): String =
        t.session.rooms.room(t.place.token)?.displayName?.takeIf { it.isNotBlank() } ?: t.roomName

    private fun delete(file: File) {
        scope.launch(Dispatchers.IO) { Staging.delete(appContext, file.path) }
    }

    private fun say(text: String) {
        Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
    }

    /** Other players pause while recording (they would be in the message otherwise). */
    private fun takeFocus() {
        // Still held for a recording that is being cleaned up: kept for this one.
        if (focus != null) return
        val audio = appContext.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = request
    }

    private fun dropFocus() {
        val request = focus ?: return
        focus = null
        appContext.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
    }

    private companion object {
        const val TAG = "VoiceMessages"
        const val MIME = "audio/wav"

        /** Desktop's line between "the device produced digital silence" and "a quiet room" (dBFS). */
        const val SILENCE_DB = -75.0
    }
}
