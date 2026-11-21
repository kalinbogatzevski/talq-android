package talq.app.push

import org.json.JSONException
import org.json.JSONObject

/**
 * What the server's notifications app (8.0.1) puts in a web push, after the connector has
 * decrypted it. Shapes are from `Push.php` (`encodeNotif`, `encodeDeleteNotifs`) and
 * `WebPushController.php` (`registerWP`):
 *
 * - `{"activationToken": "<uuid>"}`: the first push after registering
 * - `{"nid": 12, "app": "spreed", "subject": "...", "type": "chat", "id": "<object id>"}`
 * - `{"nid": 12, "delete": true}`
 * - `{"nids": [1, 2, ...], "delete-multiple": true}` (at most 10 per push)
 * - `{"delete-all": true}`
 */
sealed interface PushPayload {
    data class Activation(val token: String) : PushPayload

    /**
     * A new notification. [subject] is the server-rendered subject line (already translated,
     * cut to fit the push), [objectType] the notification's object type (`chat`, `room`,
     * `call`, ...) and [objectId] its object id.
     */
    data class Notify(
        val nid: Long,
        val app: String,
        val subject: String,
        val objectType: String,
        val objectId: String,
    ) : PushPayload {
        /** Talk's own notifications; `admin_notification_talk` is an admin broadcast, not a chat. */
        val isTalk: Boolean get() = app == "spreed" || app == "talk"
    }

    data class Delete(val nids: List<Long>) : PushPayload

    data object DeleteAll : PushPayload

    companion object {
        /** Null when [content] is not one of the shapes above. */
        fun parse(content: ByteArray): PushPayload? {
            val o = try {
                JSONObject(String(content, Charsets.UTF_8))
            } catch (_: JSONException) {
                return null
            }
            val activation = o.optString("activationToken", "")
            if (activation.isNotEmpty()) return Activation(activation)
            if (o.optBoolean("delete-all", false)) return DeleteAll
            if (o.optBoolean("delete-multiple", false)) {
                val array = o.optJSONArray("nids") ?: return null
                val ids = (0 until array.length()).map { array.optLong(it, 0L) }.filter { it > 0 }
                return Delete(ids)
            }
            val nid = o.optLong("nid", 0L)
            if (nid <= 0) return null
            if (o.optBoolean("delete", false)) return Delete(listOf(nid))
            return Notify(
                nid = nid,
                app = o.optString("app", ""),
                subject = o.optString("subject", ""),
                objectType = o.optString("type", ""),
                objectId = o.optString("id", ""),
            )
        }
    }
}
