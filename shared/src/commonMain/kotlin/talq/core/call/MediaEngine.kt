package talq.core.call

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The state of one media connection (the publisher, or one subscriber). */
enum class MediaConnection { New, Connecting, Connected, Failed }

/**
 * A local ICE candidate to signal. [peerSessionId] null = the publisher (sent to our own
 * session), otherwise the subscriber of that peer's [kind] of media.
 */
sealed interface LocalCandidate {
    val peerSessionId: String?
    val kind: StreamKind

    data class Candidate(
        override val peerSessionId: String?,
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int,
        override val kind: StreamKind = StreamKind.Camera,
    ) : LocalCandidate

    /** That connection has no more candidates (`endOfCandidates`). */
    data class End(override val peerSessionId: String?, override val kind: StreamKind = StreamKind.Camera) : LocalCandidate
}

/**
 * The media of one call (WebRTC on Android), driven by [CallSession]: one publisher that sends
 * our microphone and camera to the call backend, and one subscriber per person and kind
 * ([SubscriberKey]: their camera, or a screen they share) that receives theirs. Every connection
 * goes through the backend's media server, also in a one-to-one call.
 *
 * The publisher carries only what [PublishRights] allow (a disabled video track while the camera
 * is off, when video is allowed) and always a data channel named "status". Offers and answers are
 * SDP text; the engine never logs them.
 */
interface MediaEngine {
    /**
     * Builds a new publisher and returns its offer: an audio section only with [rights].audio, a
     * video section only with [rights].video. [video] = start with the camera on. Never called
     * without any right (the user only listens then).
     */
    suspend fun createPublisherOffer(iceServers: List<IceServer>, rights: PublishRights, video: Boolean): String

    suspend fun setPublisherAnswer(sdp: String)

    /** Drops the publisher: nothing is sent and the microphone is let go (the user only listens). */
    fun closePublisher()

    /**
     * Builds a NEW subscriber for [key] from the backend's offer (dropping an earlier one for that
     * key) and returns its answer.
     */
    suspend fun answerSubscriber(key: SubscriberKey, offerSdp: String, iceServers: List<IceServer>): String

    /** Drops the subscriber of [key] (the person left, stopped sharing, or a new offer is awaited). */
    fun closeSubscriber(key: SubscriberKey)

    /**
     * A candidate from the backend. [key] null = for the publisher. A candidate can arrive before
     * its connection's remote description: the engine keeps it until then.
     */
    fun addRemoteCandidate(key: SubscriberKey?, candidate: String, sdpMid: String?, sdpMLineIndex: Int)

    fun setLocalAudio(enabled: Boolean)

    fun setLocalVideo(enabled: Boolean)

    /** Sends `{"type": [type]}` on the "status" data channel: audioOn, audioOff, videoOn, videoOff. */
    fun sendStatus(type: String)

    val localCandidates: Flow<LocalCandidate>

    /** The publisher's connection (what we send). New again after [rebuild]. */
    val publisher: StateFlow<MediaConnection>

    /** Every subscriber's connection (what we receive); a key leaves when its subscriber is closed. */
    val subscribers: StateFlow<Map<SubscriberKey, MediaConnection>>

    /**
     * What the others' clients say on their data channels (audioOn, audioOff, videoOn, videoOff,
     * speaking, stoppedSpeaking), plus speaking / stoppedSpeaking the engine derives from the
     * received audio level for clients that do not send them.
     */
    val remoteStatus: Flow<RemoteStatus>

    /**
     * Drops every connection (to build them again after a lost signaling session); the engine
     * stays usable. A new publisher or subscriber alone needs no rebuild: [createPublisherOffer]
     * and [answerSubscriber] replace the old one.
     */
    fun rebuild()

    /** Ends all media for good. */
    fun close()
}
