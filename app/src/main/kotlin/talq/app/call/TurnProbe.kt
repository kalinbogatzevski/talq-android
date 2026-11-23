package talq.app.call

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.audio.JavaAudioDeviceModule
import talq.core.call.IceServer

/**
 * Asks STUN and TURN servers for what a call would get from them: a connection that gathers ICE
 * candidates, nothing sent to anyone. A TURN server proves itself with a relay candidate (it
 * accepted the credentials and allocated an address), a STUN server with a server-reflexive one.
 * Used by the diagnostics screen only; the server credentials are never logged or shown.
 */
object TurnProbe {
    sealed interface Result {
        /** The server answered: a candidate of the asked type, after [millis]. */
        data class Answered(val millis: Long) : Result

        /** Gathering ended (or timed out) without one. */
        data object NoAnswer : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * [url] one URL of [server] ("turn:host:3478?transport=udp", "turns:...", "stun:..."). Runs on
     * IO and takes at most [timeoutMs] plus the clean-up.
     */
    suspend fun probe(context: Context, server: IceServer, url: String, timeoutMs: Long = 8_000): Result =
        withContext(Dispatchers.IO) {
            WebRtcEngine.initialize(context)
            val relay = url.startsWith("turn:") || url.startsWith("turns:")
            val wanted = if (relay) " typ relay" else " typ srflx"
            val adm = JavaAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule()
            val factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
            val ice = PeerConnection.IceServer.builder(url).apply {
                if (server.username.isNotEmpty()) setUsername(server.username)
                if (server.credential.isNotEmpty()) setPassword(server.credential)
            }.createIceServer()
            val config = PeerConnection.RTCConfiguration(listOf(ice)).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                // Only what this server can give: relay candidates for TURN, all for STUN.
                iceTransportsType = if (relay) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }
            val started = SystemClock.elapsedRealtime()
            val found = CompletableDeferred<Long>()
            val finished = CompletableDeferred<Unit>()
            val observer = object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    if (candidate.sdp.contains(wanted)) found.complete(SystemClock.elapsedRealtime() - started)
                }

                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                    if (newState == PeerConnection.IceGatheringState.COMPLETE) finished.complete(Unit)
                }

                override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(dataChannel: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
            }
            val pc = factory.createPeerConnection(config, observer)
            try {
                if (pc == null) return@withContext Result.Failed("the call library could not start")
                // Something to negotiate, so candidates are gathered.
                pc.createDataChannel("probe", DataChannel.Init())
                pc.awaitSetLocal(pc.awaitCreate(offer = true))
                val millis = withTimeoutOrNull(timeoutMs) {
                    // The first of: the wanted candidate, or gathering over without it.
                    kotlinx.coroutines.selects.select<Long?> {
                        found.onAwait { it }
                        finished.onAwait { null }
                    } ?: found.takeIf { it.isCompleted }?.await()
                }
                if (millis != null) Result.Answered(millis) else Result.NoAnswer
            } catch (e: Exception) {
                Result.Failed(e.javaClass.simpleName)
            } finally {
                // dispose() blocks on libwebrtc's threads: never on the main thread, never under a lock.
                pc?.dispose()
                factory.dispose()
                adm.release()
            }
        }
}
