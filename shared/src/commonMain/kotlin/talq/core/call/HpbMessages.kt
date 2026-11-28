package talq.core.call

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import talq.core.net.EmptyJsonObject
import talq.core.net.TalkJson
import talq.core.net.arr
import talq.core.net.asObject
import talq.core.net.asText
import talq.core.net.bool
import talq.core.net.int
import talq.core.net.jsonDeeperThan
import talq.core.net.obj
import talq.core.net.str
import talq.core.net.strings

/*
 * The messages of the call backend's signaling socket (the Talk High Performance Backend,
 * protocol "hello" version 1.0): pure functions, no I/O. Wire shapes are in
 * docs/superpowers/specs/2026-11-22-call-protocol-map.md.
 */

/**
 * The WebSocket address of a signaling server as `signaling/settings` names it: a trailing "/"
 * and a trailing "/spreed" are dropped, https becomes wss (http becomes ws), and "/spreed" is
 * added back.
 */
fun hpbWebSocketUrl(server: String): String {
    var s = server.trim().trimEnd('/')
    if (s.endsWith("/spreed")) s = s.removeSuffix("/spreed").trimEnd('/')
    s = when {
        s.startsWith("https://", ignoreCase = true) -> "wss://" + s.substring(8)
        s.startsWith("http://", ignoreCase = true) -> "ws://" + s.substring(7)
        else -> s
    }
    return "$s/spreed"
}

/**
 * Someone in the room as a `participants` update lists them. [sessionId] is their HPB session;
 * [permissions] their attendee permissions (0 = not sent); [internal] = a session the signaling
 * server added itself (the SIP bridge, a phone participant), which publishes no media of its own.
 */
data class HpbUser(
    val sessionId: String,
    val inCall: Int,
    val userId: String,
    val ncSessionId: String,
    val displayName: String = "",
    val permissions: Int = 0,
    val internal: Boolean = false,
)

/** One session that joined the room: its HPB session, user id, Nextcloud room session and the person's name. */
data class RoomJoinEntry(val sessionId: String, val userId: String, val ncSessionId: String, val displayName: String = "")

/** What the signaling server sends. Anything not listed here arrives as [Other]. */
sealed interface HpbInbound {
    /** Sent on connect, before hello: what the server supports. */
    data class Welcome(val features: Set<String>) : HpbInbound

    /**
     * The answer to a hello. [resumeId] is null on the answer to a resume (keep the old one), and
     * [features] is empty there too.
     */
    data class HelloOk(val sessionId: String, val resumeId: String?, val features: Set<String>) : HpbInbound

    /** The answer to a `room` message: now in [roomId] ("" = in no room). [id] echoes the request's id. */
    data class RoomAck(val roomId: String, val id: String? = null) : HpbInbound

    /**
     * Who is in the call. [users] lists the sessions in the call; [changed] the ones whose call
     * state just changed, including those that LEFT (inCall 0): Talk puts a leaver only there.
     */
    data class ParticipantsUpdate(val users: List<HpbUser>, val changed: List<HpbUser> = emptyList()) : HpbInbound

    /** A moderator ended the call in [roomId] for everyone (`incall` 0 with `all`, no users list). */
    data class CallEndedForEveryone(val roomId: String) : HpbInbound
    data class RoomJoined(val entries: List<RoomJoinEntry>) : HpbInbound
    data class RoomLeft(val sessionIds: List<String>) : HpbInbound

    /** A message from another session (or from the MCU on our own session's behalf). */
    data class Signal(
        val senderSessionId: String,
        val type: String,
        val sid: String,
        val roomType: String,
        val payload: JsonObject,
        val senderUserId: String = "",
    ) : HpbInbound

    /** A moderator muted [peerId]'s microphone. */
    data class ForceMute(val peerId: String) : HpbInbound

    /** [id] echoes the id of the request that failed, when it had one. */
    data class Error(val code: String, val message: String, val id: String? = null) : HpbInbound

    /** The server ended the session. */
    data object Bye : HpbInbound

    data class Other(val type: String) : HpbInbound
}

/** Deeper than any message is nested (a handful of levels). */
private const val MAX_DEPTH = 64

/** The object in [text], which anybody in the room may have written; null when it holds none or is nested too deep. */
private fun objectIn(text: String): JsonObject? {
    if (jsonDeeperThan(text, MAX_DEPTH)) return null
    return try {
        TalkJson.parseToJsonElement(text).asObject()
    } catch (e: Exception) {
        null
    }
}

/** Reads one text frame. Never throws: what cannot be read is [HpbInbound.Other]. */
fun parseHpb(text: String): HpbInbound {
    val o = objectIn(text) ?: return HpbInbound.Other("invalid")
    val id = o["id"].asText()
    return when (val type = o.str("type")) {
        "welcome" -> HpbInbound.Welcome(o.obj("welcome")?.strings("features")?.toSet() ?: emptySet())
        "hello" -> {
            val h = o.obj("hello") ?: return HpbInbound.Other(type)
            HpbInbound.HelloOk(
                sessionId = h.str("sessionid"),
                resumeId = h["resumeid"].asText()?.takeIf { it.isNotEmpty() },
                features = h.obj("server")?.strings("features")?.toSet() ?: emptySet(),
            )
        }
        "room" -> HpbInbound.RoomAck(o.obj("room")?.str("roomid") ?: "", id)
        "event" -> event(o.obj("event"))
        "message" -> message(o.obj("message"))
        "control" -> {
            val data = o.obj("control")?.let { dataObject(it["data"]) }
            if (data?.str("action") == "forceMute") HpbInbound.ForceMute(data.str("peerId")) else HpbInbound.Other(type)
        }
        "error" -> {
            val e = o.obj("error")
            HpbInbound.Error(e?.str("code") ?: "", e?.str("message") ?: "", id)
        }
        "bye" -> HpbInbound.Bye
        else -> HpbInbound.Other(type)
    }
}

private fun event(e: JsonObject?): HpbInbound {
    if (e == null) return HpbInbound.Other("event")
    val target = e.str("target")
    val type = e.str("type")
    return when {
        target == "participants" && type == "update" &&
            e.obj("update")?.let { it.bool("all") && it.int("incall") == 0 && it["users"] == null } == true ->
            HpbInbound.CallEndedForEveryone(e.obj("update")?.str("roomid") ?: "")
        target == "participants" && type == "update" -> HpbInbound.ParticipantsUpdate(
            users = hpbUsers(e.obj("update"), "users"),
            changed = hpbUsers(e.obj("update"), "changed"),
        )
        target == "room" && type == "join" -> HpbInbound.RoomJoined(
            e.arr("join").mapNotNull { it.asObject() }.map { j ->
                RoomJoinEntry(j.str("sessionid"), j.str("userid"), j.str("roomsessionid"), j.obj("user")?.str("displayname") ?: "")
            },
        )
        target == "room" && type == "leave" -> HpbInbound.RoomLeft(e.strings("leave"))
        else -> HpbInbound.Other("event:$target/$type")
    }
}

private fun hpbUsers(update: JsonObject?, key: String): List<HpbUser> =
    update?.arr(key)?.mapNotNull { it.asObject() }?.map { u ->
        HpbUser(
            sessionId = u.str("sessionId"),
            inCall = u.int("inCall"),
            userId = u.str("userId").ifEmpty { u.str("actorId") },
            ncSessionId = u.str("nextcloudSessionId"),
            displayName = u.str("displayName"),
            permissions = u.int("participantPermissions"),
            internal = u.bool("internal") || u.bool("virtual"),
        )
    } ?: emptyList()

private fun message(m: JsonObject?): HpbInbound {
    if (m == null) return HpbInbound.Other("message")
    val data = dataObject(m["data"]) ?: return HpbInbound.Other("message")
    return HpbInbound.Signal(
        senderSessionId = m.obj("sender")?.str("sessionid") ?: "",
        type = data.str("type"),
        sid = data.str("sid"),
        roomType = data.str("roomType").ifEmpty { "video" },
        payload = data.obj("payload") ?: EmptyJsonObject,
        senderUserId = m.obj("sender")?.str("userid") ?: "",
    )
}

/** Longer than any status message a client sends on its data channel. */
private const val MAX_STATUS_CHARS = 4096

/**
 * The `type` of a status message another person's client sent on its data channel (audioOn,
 * speaking, ...). Anybody in the call writes these: null for anything too long, too deep, not an
 * object or without a type.
 */
fun parseRemoteStatus(text: String): String? {
    if (text.length > MAX_STATUS_CHARS) return null
    return objectIn(text)?.str("type")?.takeIf { it.isNotEmpty() && it.length <= 64 }
}

/** `data` is an object; some senders put it in as JSON text instead. */
private fun dataObject(e: JsonElement?): JsonObject? = e.asObject() ?: e.asText()?.let { objectIn(it) }

/** Hello with the v1.0 ticket from `signaling/settings`. [backendUrl] is the Talk backend endpoint. */
fun helloV1(backendUrl: String, userId: String, ticket: String): String = buildJsonObject {
    put("type", "hello")
    putJsonObject("hello") {
        put("version", "1.0")
        putJsonObject("auth") {
            put("url", backendUrl)
            putJsonObject("params") {
                put("userid", userId)
                put("ticket", ticket)
            }
        }
    }
}.toString()

/** Hello that resumes an earlier session after a dropped socket. */
fun helloResume(resumeId: String): String = buildJsonObject {
    put("type", "hello")
    putJsonObject("hello") {
        put("version", "1.0")
        put("resumeid", resumeId)
    }
}.toString()

/** Join [roomId] (the conversation token) with the Nextcloud room session; "" leaves the room. */
fun roomJoin(roomId: String, ncSessionId: String, id: String? = null): String = buildJsonObject {
    if (id != null) put("id", id)
    put("type", "room")
    putJsonObject("room") {
        put("roomid", roomId)
        put("sessionid", ncSessionId)
    }
}.toString()

/**
 * A message for one session: `data` carries [type], [to], [sid] (left out when null),
 * [roomType] and [payload], plus [extra] (the publisher offer's `audiocodec`/`videocodec`).
 */
fun signal(
    to: String,
    type: String,
    sid: String?,
    roomType: String = "video",
    payload: JsonObject,
    extra: Map<String, String> = emptyMap(),
): String = envelope(to) {
    put("type", type)
    put("to", to)
    if (sid != null) put("sid", sid)
    put("roomType", roomType)
    put("payload", payload)
    for ((k, v) in extra) put(k, v)
}

/**
 * Ask [to]'s publisher for an offer (subscribe to them). Its `data` has no `to`, `sid` or
 * payload, as the MCU expects.
 */
fun requestOffer(to: String, roomType: String = "video"): String = envelope(to) {
    put("type", "requestoffer")
    put("roomType", roomType)
}

private fun envelope(to: String, data: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String = buildJsonObject {
    put("type", "message")
    putJsonObject("message") {
        putJsonObject("recipient") {
            put("type", "session")
            put("sessionid", to)
        }
        putJsonObject("data", data)
    }
}.toString()

/**
 * Tells [to] that this side hung up on purpose, so a one-to-one call ends there at once instead
 * of waiting for the server's "left the call". `data` is only `{"type":"hangup"}`, as TalQ
 * desktop sends and reads it; other clients ignore it.
 */
fun hangupHint(to: String): String = envelope(to) { put("type", "hangup") }

/** End the session on purpose (only then: a dropped socket resumes instead). */
fun bye(): String = """{"type":"bye","bye":{}}"""
