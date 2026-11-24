package talq.core.model

/*
 * Where a voice message goes. Ported function by function from TalQ for desktop's
 * `src/core/VoiceRoomPolicy.h` (its wiring is `MainWindow`'s VoiceRecorder handlers,
 * `endVoiceRecording` and `sayVoiceRoute`).
 *
 * A recording belongs to the room, and the topic inside it, that was open when recording
 * started:
 *   - Leaving the room (another room, the conversation list, another screen, the app going to
 *     the background) stops the recording and sends it where it STARTED; a notice names that
 *     room when it is not the one on screen. A topic switch inside the same room is not leaving:
 *     the record controls stay, and the message still goes to the start topic.
 *   - If the start room is gone (deleted, left, removed from the list), the recording is
 *     discarded with a notice.
 *   - A recording under a second is a mis-click and is dropped without a word.
 *   - A cancelled recording never reaches any of this.
 */

/** Shorter than this is a mis-click, not a message. */
const val MIN_VOICE_MILLIS = 1000L

/** A room and the topic inside it the composer posts to (0 = none: General or the whole chat). */
data class VoicePlace(val token: String, val threadId: Long = 0) {
    companion object {
        /** Nothing open (the conversation list, another screen). */
        val NONE = VoicePlace("")
    }
}

/**
 * Does going to [toToken] (empty = no room) end a recording that started in [start]? Only a
 * change of ROOM does; the same room again (a refresh, another tab) is not leaving.
 */
fun navigationEndsRecording(recording: Boolean, start: VoicePlace, toToken: String): Boolean =
    recording && toToken != start.token

/**
 * Is the room a recording started in still one the user is in? The room list is the evidence,
 * and an EMPTY list proves nothing: it is empty before the first load.
 */
fun startRoomPresent(listed: Set<String>, token: String): Boolean {
    if (token.isEmpty()) return false
    return listed.isEmpty() || token in listed
}

enum class VoiceRoute {
    /** Under a second: removed without a word. */
    Drop,

    /** Sent where it started, which is what is on screen: nothing to say. */
    Send,

    /** Sent where it started, which is NOT what is on screen: a notice names the room. */
    SendAndSay,

    /** The room it started in is gone or was left: removed, and a notice says so. */
    DiscardAndSay,
}

/**
 * What happens to a recording that has ended. [openNow] is what the user sees once this is
 * done: the room and topic open for their own Stop, where they are going when leaving ended it.
 */
fun routeFinished(durationMillis: Long, start: VoicePlace, openNow: VoicePlace, startPresent: Boolean): VoiceRoute {
    if (durationMillis < MIN_VOICE_MILLIS) return VoiceRoute.Drop
    if (start.token.isEmpty() || !startPresent) return VoiceRoute.DiscardAndSay
    return if (start == openNow) VoiceRoute.Send else VoiceRoute.SendAndSay
}

/**
 * A room or topic name as a one-line notice shows it: a server name can be 255 characters.
 * Never splits a surrogate pair.
 */
fun voiceNoticeName(name: String, maxChars: Int = 60): String {
    val n = name.trim()
    if (maxChars < 1 || n.length <= maxChars) return n
    var cut = maxChars
    if (n[cut - 1].isHighSurrogate()) cut--
    return n.take(cut) + "…"
}

/**
 * The one line for [route], null when nothing is said. [room] is the start room's name (the
 * list's current one, else the one taken at the start), [topic] the start topic's title (empty
 * outside a topic). Desktop's wording (`MainWindow::sayVoiceRoute`).
 */
fun voiceRouteText(route: VoiceRoute, room: String, topic: String, inTopic: Boolean): String? {
    val r = voiceNoticeName(room)
    return when (route) {
        VoiceRoute.Drop, VoiceRoute.Send -> null
        VoiceRoute.DiscardAndSay ->
            if (r.isEmpty()) "Voice message discarded: you are no longer in that conversation"
            else "Voice message discarded: you are no longer in $r"
        VoiceRoute.SendAndSay -> when {
            r.isNotEmpty() && inTopic && topic.isNotBlank() -> "Voice message sent to $r, topic ${voiceNoticeName(topic)}"
            r.isEmpty() -> "Voice message sent"
            else -> "Voice message sent to $r"
        }
    }
}
