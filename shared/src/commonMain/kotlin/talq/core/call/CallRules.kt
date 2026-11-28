package talq.core.call

import talq.core.model.ParticipantType
import talq.core.model.Room
import talq.core.model.RoomType
import talq.core.model.membersForModeratorsOnly

/*
 * Pure call rules shared by the call session and the call screen.
 */

/**
 * The simulcast substream to ask for a camera shown [heightDp] tall (TalQ for desktop's
 * `SubstreamPolicy.h`, in dp as desktop measures logical pixels): 480 dp or more = 2 (720p),
 * 240 dp or more = 1 (360p), smaller = 0 (180p). A higher layer is taken at once; a lower one only
 * once the tile is clearly below the boundary of [current] (90 % of it), so a tile that shrinks a
 * little does not flip back and forth.
 */
fun substreamFor(heightDp: Float, current: Int): Int {
    val target = when {
        heightDp >= HIGH_DP -> 2
        heightDp >= MID_DP -> 1
        else -> 0
    }
    if (target >= current) return target
    val boundary = if (current >= 2) HIGH_DP else MID_DP
    return if (heightDp < boundary * STEP_DOWN) target else current
}

private const val HIGH_DP = 480f
private const val MID_DP = 240f
private const val STEP_DOWN = 0.9f

/**
 * Whether [room] offers a call (the audio and video call buttons, the Join bar): the server has a
 * call backend; a one-to-one, group or public conversation; not read-only; not a channel (Talk
 * allows no calls there); the lobby lets the user in (moderators, guest moderators, the
 * ignore-lobby permission, a lobby whose time has passed); and a call is running or the user may
 * start one. [joining] (an answer, a Join): a call runs already, the right to start one does not
 * matter (and the list's `hasCall` may be older than the call).
 */
fun callOffered(room: Room, hasHpb: Boolean, nowSeconds: Long, joining: Boolean = false): Boolean {
    if (!hasHpb) return false
    if (room.type != RoomType.ONE_TO_ONE && room.type != RoomType.GROUP && room.type != RoomType.PUBLIC) return false
    if (room.isReadOnly || membersForModeratorsOnly(room)) return false
    val inLobby = room.lobbyState != 0 && room.lobbyTimer !in 1..nowSeconds
    if (inLobby && !room.isModerator && room.participantType != ParticipantType.GUEST_MODERATOR &&
        room.permissions and CallPermissions.IGNORE_LOBBY == 0
    ) return false
    return joining || room.hasCall || room.canStartCall
}

/** An offer that carries media (an audio or video section), not only the data channel. */
fun offerHasMedia(sdp: String): Boolean =
    sdp.lineSequence().any { it.startsWith("m=audio") || it.startsWith("m=video") }
