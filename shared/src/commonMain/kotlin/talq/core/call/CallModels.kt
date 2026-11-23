package talq.core.call

/** Talk's participant call flags (`POST/PUT call/{token}` `flags`, `inCall` in signaling). */
object CallFlags {
    const val IN_CALL = 1
    const val WITH_AUDIO = 2
    const val WITH_VIDEO = 4
    const val WITH_PHONE = 8

    /** Joining an audio call: in the call, sending audio. */
    const val AUDIO_CALL = IN_CALL or WITH_AUDIO

    /** Joining a video call: in the call, sending audio and video. */
    const val VIDEO_CALL = IN_CALL or WITH_AUDIO or WITH_VIDEO
}

/** One STUN or TURN server. [toString] never shows the TURN user or secret. */
data class IceServer(val urls: List<String>, val username: String = "", val credential: String = "") {
    override fun toString(): String =
        "IceServer(urls=$urls${if (username.isNotEmpty() || credential.isNotEmpty()) ", credentials=<hidden>" else ""})"
}

/**
 * What `signaling/settings` says for this user: the signaling server (HPB) to use, the hello
 * v1.0 credentials, and the STUN/TURN servers. [server] empty = the server has no HPB (internal
 * signaling only); calls are not offered then. [toString] never shows the ticket.
 */
data class SignalingSettings(
    val server: String,
    /** Further HPB nodes, when the server lists them (a patched Talk); empty otherwise. */
    val servers: List<String>,
    val helloUserId: String,
    val helloTicket: String,
    val iceServers: List<IceServer>,
    /** When these settings were fetched (TURN credentials age; refetch per call). */
    val fetchedAtMillis: Long,
) {
    val hasHpb: Boolean get() = server.isNotEmpty()

    override fun toString(): String =
        "SignalingSettings(server=$server, servers=$servers, helloUserId=$helloUserId, helloTicket=<hidden>, " +
            "iceServers=$iceServers, fetchedAtMillis=$fetchedAtMillis)"
}

/** One row of `GET call/{token}`: someone in the call, with their Nextcloud session id. */
data class CallPeer(
    val actorType: String,
    val actorId: String,
    val displayName: String,
    val ncSessionId: String,
    val lastPing: Long,
)

/** `GET call/{token}/notification-state`: whether a ringing phone should keep ringing. */
enum class NotificationState {
    /** 200: the call is on and this user has not joined: keep ringing. */
    Ringing,

    /** 201: there is no call any more: stop and show a missed call. */
    Missed,

    /** 404: this user joined on some device, or the room is gone: stop quietly. */
    Gone,

    /** 403: the session is no longer valid. */
    Forbidden,
}

/** Where a call is, as the call screen shows it. */
sealed interface CallState {
    /** Setting up: signaling, joining the call, or, once the other person is in, the media connecting. */
    data object Connecting : CallState

    /** In the call; the other person has not joined yet (an outgoing call that is ringing). */
    data object WaitingForPeer : CallState

    /** Media flows. [since] is when it first did (the session's clock, ms); kept across a reconnect. */
    data class Active(val since: Long) : CallState

    /** The signaling or the media dropped; trying to get them back (20 s at most). */
    data object Reconnecting : CallState

    data class Ended(val reason: CallEndReason) : CallState
}

enum class CallEndReason {
    /** This side hung up. */
    HungUp,

    /** The other person hung up or left. */
    PeerHungUp,

    /** Nobody answered within 60 s. */
    NoAnswer,

    /** Signaling, joining or the media failed for good. */
    Failed,

    /** The call was declined. */
    Declined,

    /** The server has no call backend (HPB), which TalQ needs for every call. */
    NoHpb,

    /** The microphone (or camera) may not be used. */
    PermissionDenied,
}

/** The other person in the call: what they send, as they announce it, and their name. */
data class PeerMedia(val audio: Boolean, val video: Boolean, val displayName: String)

/** A message the call sent on the signaling socket: its type and the session it went to. No payload. */
data class SentSignal(val type: String, val to: String)
