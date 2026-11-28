package talq.core.call

import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject

/**
 * Someone else in the call, as [CallSession]'s loop keeps them: owned by the loop only (no locks).
 * One per signaling session. A one-to-one call holds at most one (the adopted peer); a group call
 * one per person in the call.
 */
internal class PeerLink(val sid: String) {
    var userId: String = ""
    var name: String = ""

    /** Their call flags as last reported; [flagsKnown] = the signaling server said so (not a REST guess). */
    var flags: Int = 0
    var flagsKnown: Boolean = false

    /** What they send, as flags, mute messages and data-channel status last said. */
    var audio: Boolean = false
    var video: Boolean = false
    var speaking: Boolean = false

    /** They share a screen now (a screen offer came, no `unshareScreen` since). */
    var sharing: Boolean = false

    /** A camera `requestoffer` is out and its retry runs: a second one would make a second handle. */
    var offerRequested: Boolean = false
    var offerGeneration: Int = 0
    var offerRequests: Int = 0
    var retryJob: Job? = null

    /** Subscribers rebuilt for this person so far (each failure asks again, 8 times at most). */
    var rebuilds: Int = 0

    /** The media server's handle id (the offer's `sid`) per kind, while a subscriber is set up. */
    val handle: HashMap<StreamKind, String> = HashMap()

    /** Each subscriber's connection as the engine reports it. */
    val state: HashMap<StreamKind, MediaConnection> = HashMap()

    /** A watchdog per kind for an offer whose media never connects. */
    val stallJobs: HashMap<StreamKind, Job> = HashMap()

    /** Candidates the media server trickled before their handle's offer, per handle id. */
    val early: LinkedHashMap<String, MutableList<JsonObject>> = LinkedHashMap()

    /** The camera substream the call screen wants, and the one last sent (-1 = none on this handle yet). */
    var wantedSubstream: Int = 1
    var sentSubstream: Int = -1

    fun cancelJobs() {
        retryJob?.cancel()
        retryJob = null
        stallJobs.values.forEach { it.cancel() }
        stallJobs.clear()
    }

    fun participant(): CallParticipant = CallParticipant(
        sid = sid,
        userId = userId,
        displayName = name.ifEmpty { userId }.ifEmpty { "Guest" },
        audio = audio,
        video = video,
        speaking = speaking && audio,
        camera = state[StreamKind.Camera] ?: MediaConnection.New,
        sharing = sharing,
        screen = state[StreamKind.Screen] ?: MediaConnection.New,
    )
}
