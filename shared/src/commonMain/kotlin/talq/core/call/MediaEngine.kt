package talq.core.call

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The state of all of a call's media connections taken together. */
enum class MediaConnection { New, Connecting, Connected, Failed }

/**
 * A local ICE candidate to signal. [peerSessionId] null = the publisher (sent to our own
 * session), otherwise the subscriber of that peer's media.
 */
sealed interface LocalCandidate {
    val peerSessionId: String?

    data class Candidate(
        override val peerSessionId: String?,
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int,
    ) : LocalCandidate

    /** That connection has no more candidates (`endOfCandidates`). */
    data class End(override val peerSessionId: String?) : LocalCandidate
}

/**
 * The media of one call (WebRTC on Android), driven by [CallSession]: one publisher that sends
 * our microphone and camera to the call backend, and one subscriber per peer that receives
 * theirs. Every connection goes through the backend's media server, also in a one-to-one call.
 *
 * The publisher always has a video section (a disabled track while the camera is off) and a data
 * channel named "status". Offers and answers are SDP text; the engine never logs them.
 */
interface MediaEngine {
    /** Builds a new publisher and returns its offer. [video] = start with the camera on. */
    suspend fun createPublisherOffer(iceServers: List<IceServer>, video: Boolean): String

    suspend fun setPublisherAnswer(sdp: String)

    /**
     * Builds a NEW subscriber for [peerSessionId] from the backend's offer (dropping an earlier
     * one for that peer) and returns its answer.
     */
    suspend fun answerSubscriber(peerSessionId: String, offerSdp: String, iceServers: List<IceServer>): String

    /**
     * A candidate from the backend. [peerSessionId] null = for the publisher. A publisher
     * candidate can arrive before the publisher's answer: the engine keeps it until then.
     */
    fun addRemoteCandidate(peerSessionId: String?, candidate: String, sdpMid: String?, sdpMLineIndex: Int)

    fun setLocalAudio(enabled: Boolean)

    fun setLocalVideo(enabled: Boolean)

    /** Sends `{"type": [type]}` on the "status" data channel: audioOn, audioOff, videoOn, videoOff. */
    fun sendStatus(type: String)

    val localCandidates: Flow<LocalCandidate>

    /** The publisher's connection (what we send). New again after [rebuild]. */
    val publisher: StateFlow<MediaConnection>

    /** The current subscriber's connection (what we receive); New while there is none. */
    val subscriber: StateFlow<MediaConnection>

    /**
     * Drops every connection (to build them again after a lost signaling session); the engine
     * stays usable. A new publisher or subscriber alone needs no rebuild: [createPublisherOffer]
     * and [answerSubscriber] replace the old one.
     */
    fun rebuild()

    /** Ends all media for good. */
    fun close()
}
