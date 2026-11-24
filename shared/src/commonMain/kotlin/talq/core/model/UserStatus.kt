package talq.core.model

import kotlinx.serialization.json.JsonObject
import talq.core.net.bool
import talq.core.net.long
import talq.core.net.str

/** The status types of the Nextcloud user status app, as they travel (`statusType`, `status`). */
object StatusType {
    const val ONLINE = "online"
    const val AWAY = "away"
    const val DND = "dnd"

    /** Only where the server's `user_status.supports_busy` capability says so. */
    const val BUSY = "busy"
    const val INVISIBLE = "invisible"
    const val OFFLINE = "offline"
}

/**
 * The signed-in user's own status (`GET apps/user_status/api/v1/user_status`), the one TalQ for
 * desktop and the Nextcloud web show. The same shape comes back from the writes.
 */
data class OwnStatus(
    /** A [StatusType]; `offline` when the user has no status at all yet. */
    val status: String = StatusType.OFFLINE,
    val message: String = "",
    /** One emoji, or empty. */
    val icon: String = "",
    /** The predefined message's id; empty for a custom message (`call` is the one Talk sets during a call). */
    val messageId: String = "",
    /** Unix seconds when the message is cleared; 0 = never. */
    val clearAt: Long = 0,
    /** The user chose the status (the server never changes such an Away / Do not disturb / Invisible itself). */
    val userDefined: Boolean = false,
) {
    val hasMessage: Boolean get() = message.isNotBlank() || icon.isNotBlank() || messageId.isNotEmpty()

    companion object {
        fun fromJson(o: JsonObject): OwnStatus = OwnStatus(
            status = o.str("status").ifEmpty { StatusType.OFFLINE },
            message = o.str("message"),
            icon = o.str("icon"),
            messageId = o.str("messageId"),
            clearAt = o.long("clearAt"),
            userDefined = o.bool("statusIsUserDefined"),
        )
    }
}

/** One of the server's predefined status messages (`GET apps/user_status/api/v1/predefined_statuses`). */
data class PredefinedStatus(val id: String, val icon: String, val message: String) {
    companion object {
        /** Null for an entry without an id, or one the server marks as not to be offered (`visible: false`). */
        fun fromJson(o: JsonObject): PredefinedStatus? {
            val id = o.str("id")
            if (id.isEmpty() || !o.bool("visible", true)) return null
            return PredefinedStatus(id, o.str("icon"), o.str("message"))
        }
    }
}
