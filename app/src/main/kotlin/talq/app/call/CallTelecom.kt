package talq.app.call

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One audio output of a call. [id] identifies it to [CallTelecom.select]. */
data class AudioOutput(val id: String, val name: String, val kind: Kind) {
    enum class Kind { Earpiece, Speaker, Bluetooth, Wired, Other }
}

/**
 * The phone's own call handling (androidx core-telecom), for one call at a time: the call is
 * known to the system (Bluetooth headsets, cars and watches see it, a regular phone call puts it
 * on hold), the system picks and switches the audio output ([endpoints], [endpoint], [select]),
 * and sets the audio mode. A video call starts on the speaker unless a headset is connected.
 *
 * Where the system refuses the call, the call still runs: the audio mode is then set here and
 * the output is earpiece or speaker only.
 *
 * Main thread.
 */
class CallTelecom(context: Context, private val scope: CoroutineScope) {
    /** What the system tells the call. */
    interface Listener {
        /** Hung up from outside the app (a headset button, a watch, the system). */
        fun onSystemHangUp()

        /** Put on hold (true) or taken off hold (false), e.g. around a regular phone call. */
        fun onHold(held: Boolean)

        /** Muted or unmuted from outside the app (a headset, a car, a watch). */
        fun onSystemMute(muted: Boolean)
    }

    private val appContext = context.applicationContext
    private val manager by lazy { CallsManager(appContext) }
    private var registered = false

    private val _endpoints = MutableStateFlow<List<AudioOutput>>(emptyList())
    val endpoints: StateFlow<List<AudioOutput>> = _endpoints.asStateFlow()

    private val _endpoint = MutableStateFlow<AudioOutput?>(null)
    val endpoint: StateFlow<AudioOutput?> = _endpoint.asStateFlow()

    private sealed interface Cmd {
        data object Active : Cmd
        data class Route(val endpoint: CallEndpointCompat) : Cmd
        data class End(val cause: Int) : Cmd
    }

    private var commands: Channel<Cmd>? = null
    private var job: Job? = null
    private var available: List<CallEndpointCompat> = emptyList()
    private var fallback = false
    private var savedMode = AudioManager.MODE_NORMAL

    fun begin(peerName: String, token: String, incoming: Boolean, video: Boolean, listener: Listener) {
        end(DisconnectCause.LOCAL)
        _endpoints.value = emptyList()
        _endpoint.value = null
        val cmds = Channel<Cmd>(Channel.UNLIMITED)
        commands = cmds
        val callType = if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL
        val attributes = CallAttributesCompat(
            displayName = peerName.ifBlank { "Call" },
            // "sip:" is what Android 8 and 8.1 accept; the token only identifies the call.
            address = Uri.parse("sip:$token"),
            direction = if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            callType = callType,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        job = scope.launch {
            try {
                register()
                manager.addCall(
                    attributes,
                    onAnswer = { /* Answered on a watch or in a car: this side has answered already. */ },
                    onDisconnect = { listener.onSystemHangUp() },
                    onSetActive = { listener.onHold(false) },
                    onSetInactive = { listener.onHold(true) },
                ) {
                    // Not a suspend block: everything that waits runs in a child. The flows never
                    // end on their own, so they are cancelled with the call, or addCall never returns.
                    var routedForVideo = !video
                    val outputs = launch {
                        availableEndpoints.collect { list ->
                            available = list
                            _endpoints.value = list.map(::output)
                        }
                    }
                    val inUse = launch {
                        currentCallEndpoint.collect { current ->
                            _endpoint.value = output(current)
                            val headset = available.any {
                                it.type == CallEndpointCompat.TYPE_BLUETOOTH || it.type == CallEndpointCompat.TYPE_WIRED_HEADSET
                            }
                            if (!routedForVideo && current.type == CallEndpointCompat.TYPE_EARPIECE && !headset) {
                                routedForVideo = true
                                available.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }?.let { cmds.trySend(Cmd.Route(it)) }
                            } else if (current.type != CallEndpointCompat.TYPE_EARPIECE) {
                                routedForVideo = true
                            }
                        }
                    }
                    // The app's own mute is the truth; a mute from a headset or car is passed on.
                    val muting = launch {
                        var first = true
                        isMuted.collect { muted ->
                            if (first) first = false else listener.onSystemMute(muted)
                        }
                    }
                    launch {
                        if (incoming) report("answer", answer(callType))
                        for (cmd in cmds) {
                            when (cmd) {
                                Cmd.Active -> report("set active", setActive())
                                is Cmd.Route -> report("route", requestEndpointChange(cmd.endpoint))
                                is Cmd.End -> {
                                    report("disconnect", disconnect(DisconnectCause(cmd.cause)))
                                    break
                                }
                            }
                        }
                        outputs.cancel()
                        inUse.cancel()
                        muting.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only for a call that is still this one (the refusal can come seconds later).
                if (commands === cmds) {
                    Log.w(TAG, "the system did not take the call (${e::class.simpleName}); routing audio here")
                    startFallback(video)
                }
            }
        }
    }

    /** The media flows: the system may now treat the call as running. */
    fun setActive() {
        commands?.trySend(Cmd.Active)
    }

    fun select(output: AudioOutput) {
        if (fallback) {
            setSpeaker(output.kind == AudioOutput.Kind.Speaker)
            return
        }
        available.firstOrNull { it.identifier.toString() == output.id }?.let { commands?.trySend(Cmd.Route(it)) }
    }

    /** Ends the system's call ([cause]: a [DisconnectCause] code). Safe to call twice. */
    fun end(cause: Int) {
        if (fallback) stopFallback()
        val cmds = commands ?: return
        commands = null
        cmds.trySend(Cmd.End(cause))
        cmds.close()
        val running = job
        job = null
        // The system answers a disconnect within a moment; never keep the call registered.
        scope.launch {
            delay(END_GRACE_MS)
            running?.cancel()
        }
        _endpoints.value = emptyList()
        _endpoint.value = null
    }

    private fun register() {
        if (registered) return
        manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING)
        registered = true
    }

    private fun report(what: String, result: CallControlResult) {
        if (result is CallControlResult.Error) Log.w(TAG, "system call: $what failed (${result.errorCode})")
    }

    private fun output(e: CallEndpointCompat): AudioOutput = AudioOutput(
        id = e.identifier.toString(),
        name = e.name.toString(),
        kind = when (e.type) {
            CallEndpointCompat.TYPE_EARPIECE -> AudioOutput.Kind.Earpiece
            CallEndpointCompat.TYPE_SPEAKER -> AudioOutput.Kind.Speaker
            CallEndpointCompat.TYPE_BLUETOOTH -> AudioOutput.Kind.Bluetooth
            CallEndpointCompat.TYPE_WIRED_HEADSET -> AudioOutput.Kind.Wired
            else -> AudioOutput.Kind.Other
        },
    )

    // ---------------------------------------------------------------- without the system

    private val audio get() = appContext.getSystemService(AudioManager::class.java)

    private fun startFallback(video: Boolean) {
        fallback = true
        val am = audio ?: return
        savedMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(video)
    }

    private fun stopFallback() {
        fallback = false
        val am = audio ?: return
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = false
        am.mode = savedMode
    }

    private fun setSpeaker(on: Boolean) {
        val am = audio ?: return
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = on
        val earpiece = AudioOutput("earpiece", "Phone", AudioOutput.Kind.Earpiece)
        val speaker = AudioOutput("speaker", "Speaker", AudioOutput.Kind.Speaker)
        _endpoints.value = listOf(earpiece, speaker)
        _endpoint.value = if (on) speaker else earpiece
    }

    private companion object {
        const val TAG = "TalqCall"
        const val END_GRACE_MS = 5_000L
    }
}
