package talq.core.typing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import talq.core.call.HpbInbound
import talq.core.call.RoomJoinEntry
import talq.core.model.Room
import talq.core.model.RoomType
import talq.core.model.TalkCapabilities
import talq.core.net.obj

/*
 * The typing indicator's pure rules: where it may run, the wire message, and who is typing. The
 * stay in a conversation that carries it is TypingServer.
 */

private const val STARTED = "startedTyping"
private const val STOPPED = "stoppedTyping"

/** Someone counts as typing this long after their last sign (TalQ for desktop's clear timer). */
private const val SHOWN_MILLIS = 15_000L

/** Talk's participant type of an ordinary member who was added (see [typingPossible]). */
private const val MEMBER = 3

private val TalkCapabilities.chatConfig: JsonObject?
    get() = raw.obj("capabilities")?.obj("spreed")?.obj("config")?.obj("chat")

/**
 * The server has the typing indicator (`config.chat.typing-privacy` is there) AND lets a session be
 * marked inactive (`session-state`): a joined conversation then still sends its notifications to this
 * phone. Without the second one nothing is ever joined.
 */
val TalkCapabilities.typingIndicator: Boolean
    get() = has("session-state") && chatConfig?.containsKey("typing-privacy") == true

/**
 * The user lets others see that they type (TalQ for desktop's `sharesTypingStatus`): the server says the number 0.
 * 1 is private, and so is anything else it may say: a text, true or false, null, an object, nothing at all.
 */
val TalkCapabilities.sharesTyping: Boolean
    get() {
        val privacy = chatConfig?.get("typing-privacy") as? JsonPrimitive ?: return false
        return typingIndicator && !privacy.isString && privacy.content == "0"
    }

/**
 * Whether the conversation may be joined for the typing indicator: a one-to-one, group or public one
 * of our own server where the user may write, with no call running, and only as a lasting member.
 * The server takes a SELF-JOINED participant (type 5) out of the conversation when their last session
 * leaves it, and ends conversations made for an object (a share's password check) the same way.
 */
fun Room.typingPossible(capabilities: TalkCapabilities, nowSeconds: Long): Boolean =
    !isFederated && objectType.isEmpty() && !hasCall &&
        (type == RoomType.ONE_TO_ONE || type == RoomType.GROUP || type == RoomType.PUBLIC) &&
        (isModerator || participantType == MEMBER) &&
        takesPosts(capabilities, nowSeconds)

/** What other TalQ apps show for this one: "TalQ Android" and its version. */
const val TALQ_ANDROID: String = "TalQ Android"

/**
 * "This is TalQ Android, version [version]", to everyone in the room. TalQ for desktop sends the same
 * with "TalQ" (`SignalingClient::sendTalqClientHello`), keeps the last one per user and shows it beside
 * the name ("· TalQ Android/0.11.0") and in a one-to-one header. [userId] is in the message itself: the
 * signaling server's sender note is not always there.
 */
fun clientHello(version: String, userId: String): String = buildJsonObject {
    put("type", "message")
    putJsonObject("message") {
        putJsonObject("recipient") { put("type", "room") }
        putJsonObject("data") {
            put("type", "talq.client")
            put("client", TALQ_ANDROID)
            put("version", version)
            put("userid", userId)
        }
    }
}.toString()

/** The same hello on a call's "status" data channel (desktop: `CallManager`, after a subscriber is up). */
fun clientHelloStatus(version: String): String = buildJsonObject {
    put("type", "talq.client")
    put("client", TALQ_ANDROID)
    put("version", version)
}.toString()

/** "I am typing" / "I stopped", to everyone in the room (TalQ for desktop's `sendRoomMessage`). */
fun typingMessage(started: Boolean): String = buildJsonObject {
    put("type", "message")
    putJsonObject("message") {
        putJsonObject("recipient") { put("type", "room") }
        putJsonObject("data") { put("type", if (started) STARTED else STOPPED) }
    }
}.toString()

/**
 * Who is typing in one conversation, from its signaling events. The caller gives the clock
 * (milliseconds) and asks [expire] when [nextExpiry] has come. The user's own sessions (other
 * devices) never count. [nameOf] names a user the signaling server sent no name for.
 */
class Typists(private val ownUserId: String, private val nameOf: (userId: String) -> String) {
    private class Typist(val userId: String, val name: String, val until: Long)

    /** Everybody in the room, by signaling session. */
    private val present = HashMap<String, RoomJoinEntry>()

    /** By signaling session, in the order they started. */
    private val typing = LinkedHashMap<String, Typist>()

    /** Each person once. */
    val names: List<String>
        get() = typing.entries.distinctBy { it.value.userId.ifEmpty { it.key } }.map { it.value.name }

    /** When the first of them has to go again; null when nobody types. */
    val nextExpiry: Long? get() = typing.values.minOfOrNull { it.until }

    fun event(e: HpbInbound, now: Long) {
        when (e) {
            is HpbInbound.RoomJoined -> for (j in e.entries) present[j.sessionId] = j
            is HpbInbound.RoomLeft -> for (sid in e.sessionIds) {
                present.remove(sid)
                typing.remove(sid)
            }
            is HpbInbound.Signal -> {
                if (e.type == STARTED) started(e, now) else if (e.type == STOPPED) typing.remove(e.senderSessionId)
            }
            else -> Unit
        }
    }

    private fun started(e: HpbInbound.Signal, now: Long) {
        val who = present[e.senderSessionId]
        val userId = e.senderUserId.ifEmpty { who?.userId.orEmpty() }
        if (e.senderSessionId.isEmpty() || userId == ownUserId) return
        val name = who?.displayName.orEmpty().trim()
            .ifEmpty { if (userId.isEmpty()) "" else nameOf(userId).trim() }
            .ifEmpty { userId }
            .ifEmpty { "Someone" }
        typing[e.senderSessionId] = Typist(userId, name, now + SHOWN_MILLIS)
    }

    /** A message of theirs came: what they typed is here. */
    fun messageFrom(userId: String) {
        typing.values.removeAll { it.userId == userId }
    }

    fun expire(now: Long) {
        typing.values.removeAll { it.until <= now }
    }
}
