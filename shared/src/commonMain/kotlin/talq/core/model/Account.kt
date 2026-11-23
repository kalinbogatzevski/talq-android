package talq.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import talq.core.net.asText
import talq.core.net.bool
import talq.core.net.int
import talq.core.net.long
import talq.core.net.obj
import talq.core.net.str
import talq.core.net.strings

/** What Login Flow v2 hands back, and what the app must persist to stay signed in. */
@Serializable
data class Credentials(
    val server: String,
    val loginName: String,
    val appPassword: String,
) {
    override fun toString(): String = "Credentials(server=$server, loginName=$loginName, appPassword=***)"
}

/** Implemented by the app (Android Keystore backed). The core never stores credentials itself. */
interface CredentialStore {
    suspend fun load(): Credentials?
    suspend fun save(credentials: Credentials)
    suspend fun clear()
}

data class UserInfo(
    /** The account id. This, not the login name, is the `actorId` on the user's messages. */
    val userId: String,
    val displayName: String,
)

/** The parts of `/cloud/capabilities` the client acts on. */
data class TalkCapabilities(
    /** `spreed.features` */
    val features: Set<String>,
    /** `spreed.features-local`: not available in federated conversations. */
    val featuresLocal: Set<String>,
    /** `spreed.version`, e.g. "25.0.7". Empty when Talk is not installed. */
    val talkVersion: String,
    /** Nextcloud version string, e.g. "34.0.3". */
    val serverVersion: String,
    val serverMajor: Int,
    /** `spreed.config.chat.max-length` */
    val maxMessageLength: Int,
    /** `notifications.ocs-endpoints` */
    val notificationFeatures: Set<String>,
    /** `notifications.push` */
    val notificationPush: Set<String>,
    val raw: JsonObject,
) {
    fun has(feature: String): Boolean = feature in features

    val hasTalk: Boolean get() = features.isNotEmpty()

    /** Topics ("threads") are available. Gate every topic surface on this. */
    val threads: Boolean get() = has("threads")
    val reactions: Boolean get() = has("reactions")
    val readMarker: Boolean get() = has("chat-read-marker")
    val referenceId: Boolean get() = has("chat-reference-id")
    val keepNotifications: Boolean get() = has("chat-keep-notifications")

    /** A file share can carry a caption (`talkMetaData.caption`). */
    val mediaCaption: Boolean get() = has("media-caption")

    /**
     * Own messages can show whether everyone has read them: the server exposes the last message
     * read by every participant who shares their read status (`lastCommonReadMessage`,
     * `X-Chat-Last-Common-Read`).
     */
    val readStatus: Boolean get() = has("chat-read-status")

    /**
     * The user shares their own read status (`config.chat.read-privacy` 0). Only then does the
     * server tell them how far the others have read; shown only when both hold.
     */
    val sharesReadStatus: Boolean
        get() = (raw.obj("capabilities")?.obj("spreed")?.obj("config")?.obj("chat")?.int("read-privacy") ?: 0) == 0

    /** A ringing phone can ask whether to keep ringing (`GET call/{token}/notification-state`). */
    val callNotificationState: Boolean get() = has("call-notification-state-api")

    /**
     * `config.call.recording-consent`: 0 never asked, 1 always required, 2 per conversation.
     * When required, joining a call without consent answers 400.
     */
    val recordingConsent: Int
        get() = raw.obj("capabilities")?.obj("spreed")?.obj("config")?.obj("call")?.int("recording-consent") ?: 0

    /** `config.call.enabled`: calls are switched on for this user (true when the server says nothing). */
    val callsEnabled: Boolean
        get() = raw.obj("capabilities")?.obj("spreed")?.obj("config")?.obj("call")?.bool("enabled", true) ?: true

    /** The shared-items endpoints (`/chat/{token}/share...`) exist. */
    val sharedItems: Boolean get() = has("rich-object-list-media")

    /** `spreed.config.attachments`: whether and where this user may upload files for a chat. */
    val attachments: AttachmentConfig
        get() {
            val a = raw.obj("capabilities")?.obj("spreed")?.obj("config")?.obj("attachments")
            return AttachmentConfig(
                allowed = a?.bool("allowed", true) ?: true,
                folder = a?.str("folder").orEmpty().trim('/').ifEmpty { "Talk" },
                conversationSubfolders = a?.bool("conversation-subfolders") ?: false,
            )
        }

    /**
     * The notifications app knows the web push endpoints at all. Whether the admin has enabled
     * it is only learnt by trying to register ([talq.core.WebPushResult.Disabled]).
     */
    val webPushKnown: Boolean get() = notificationFeatures.any { it.contains("webpush", ignoreCase = true) } ||
        notificationPush.any { it.contains("webpush", ignoreCase = true) }

    companion object {
        fun fromOcsData(data: JsonObject): TalkCapabilities {
            val caps = data.obj("capabilities") ?: JsonObject(emptyMap())
            val spreed = caps.obj("spreed")
            val notifications = caps.obj("notifications")
            val version = data.obj("version")
            return TalkCapabilities(
                features = spreed?.strings("features")?.toSet() ?: emptySet(),
                featuresLocal = spreed?.strings("features-local")?.toSet() ?: emptySet(),
                talkVersion = spreed?.str("version") ?: "",
                serverVersion = version?.str("string") ?: "",
                serverMajor = version?.int("major") ?: 0,
                maxMessageLength = spreed?.obj("config")?.obj("chat")?.int("max-length", 32000) ?: 32000,
                notificationFeatures = notifications?.strings("ocs-endpoints")?.toSet() ?: emptySet(),
                notificationPush = notifications?.strings("push")?.toSet() ?: emptySet(),
                raw = data,
            )
        }
    }
}

/** Where uploads for a chat go (`spreed.config.attachments`). */
data class AttachmentConfig(
    /** False: the user may not upload into chats at all (no quota, guest, admin setting). */
    val allowed: Boolean = true,
    /** The user's attachment folder relative to their root, no slashes around it ("Talk"). */
    val folder: String = "Talk",
    /**
     * The server files uploads per conversation itself: the client asks it for a draft folder,
     * uploads there and posts the file with the attachment endpoint, instead of uploading into
     * [folder] and creating a share.
     */
    val conversationSubfolders: Boolean = false,
)

/** One entry of the notifications app (API v2). */
data class TalkNotification(
    val id: Long,
    /** `spreed` for Talk. */
    val app: String,
    val user: String,
    /** ISO 8601. */
    val datetime: String,
    /** For Talk: `chat`, `room`, `call`, ... */
    val objectType: String,
    /** For Talk chat notifications: `{roomToken}/{messageId}` (plus `/{threadId}` inside a topic). */
    val objectId: String,
    val subject: String,
    val message: String,
    val link: String,
    val subjectRich: String,
    val subjectRichParameters: Map<String, RichObject>,
    val messageRich: String,
    val messageRichParameters: Map<String, RichObject>,
    val icon: String,
    val raw: JsonObject,
) {
    val isTalk: Boolean get() = app == "spreed" || app == "talk"

    /** Room token for Talk notifications, else empty. */
    val roomToken: String get() = if (isTalk) objectId.substringBefore('/') else ""

    /** Message id for Talk chat notifications, else 0. */
    val messageId: Long get() = if (isTalk) objectId.split('/').getOrNull(1)?.toLongOrNull() ?: 0L else 0L

    /** Topic id when the object id carries one, else 0. */
    val threadId: Long get() = if (isTalk) objectId.split('/').getOrNull(2)?.toLongOrNull() ?: 0L else 0L

    companion object {
        private fun rich(o: JsonObject?): Map<String, RichObject> =
            o?.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to RichObject(k, it) } }?.toMap() ?: emptyMap()

        fun fromJson(o: JsonObject): TalkNotification = TalkNotification(
            id = o.long("notification_id"),
            app = o.str("app"),
            user = o.str("user"),
            datetime = o.str("datetime"),
            objectType = o.str("object_type"),
            objectId = o["object_id"].asText() ?: "",
            subject = o.str("subject"),
            message = o.str("message"),
            link = o.str("link"),
            subjectRich = o.str("subjectRich"),
            subjectRichParameters = rich(o.obj("subjectRichParameters")),
            messageRich = o.str("messageRich"),
            messageRichParameters = rich(o.obj("messageRichParameters")),
            icon = o.str("icon"),
            raw = o,
        )
    }
}
