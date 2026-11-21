package talq.core.model

import kotlinx.serialization.json.JsonObject
import talq.core.net.asObject
import talq.core.net.asText
import talq.core.net.bool
import talq.core.net.int
import talq.core.net.long
import talq.core.net.obj
import talq.core.net.str
import talq.core.net.strings

/**
 * One entry of `messageParameters`: a mention, a shared file, a poll, the actor of a system
 * message... The placeholder `{key}` in [ChatMessage.message] stands for this object.
 */
data class RichObject(val key: String, val raw: JsonObject) {
    /** `user`, `call`, `group`, `guest`, `file`, `talk-poll`, `geo-location`, ... */
    val type: String get() = raw.str("type")
    val id: String get() = raw.str("id")
    val name: String get() = raw.str("name")
    val link: String get() = raw.str("link")

    val isMention: Boolean
        get() = key.startsWith("mention-") || type == "call" || type == "user" || type == "group" ||
            type == "guest" || type == "circle" || type == "email"
    val isFile: Boolean get() = type == "file"

    // File objects only.
    val mimetype: String get() = raw.str("mimetype")
    val path: String get() = raw.str("path")
    val size: Long get() = raw.long("size")
    val previewAvailable: Boolean get() = raw.str("preview-available") == "yes"
    val width: Int get() = raw.int("width")
    val height: Int get() = raw.int("height")
    val blurhash: String get() = raw.str("blurhash")
    val isImage: Boolean get() = isFile && mimetype.startsWith("image/")
    val isVideo: Boolean get() = isFile && mimetype.startsWith("video/")
    val isAudio: Boolean get() = isFile && mimetype.startsWith("audio/")

    /** The Nextcloud file id (what previews and the download lookup take), 0 when not a file. */
    val fileId: Long get() = if (isFile) id.toLongOrNull() ?: 0L else 0L

    /** Server ETag of the file: changes when the content does, so it can key a local cache. */
    val etag: String get() = raw.str("etag")

    fun value(field: String): String = raw.str(field)
}

/**
 * A chat message, kept lossless: [message] is the raw text with `{placeholders}` and
 * [parameters] are the objects they stand for. Nothing is converted to HTML here.
 */
data class ChatMessage(
    val id: Long,
    val token: String,
    val actorType: String,
    val actorId: String,
    val actorDisplayName: String,
    /** Seconds, UTC. */
    val timestamp: Long,
    val message: String,
    val parameters: Map<String, RichObject>,
    /** `comment`, `comment_deleted`, `system`, `command`, `voice-message`, ... */
    val messageType: String,
    /** Empty for a normal message, otherwise the untranslated system message kind. */
    val systemMessage: String,
    val markdown: Boolean,
    val referenceId: String,
    val isReplyable: Boolean,
    val deleted: Boolean,
    /** emoji -> count */
    val reactions: Map<String, Int>,
    val reactionsSelf: List<String>,
    /** The quoted message, when this is a reply. */
    val parent: ChatMessage?,
    /** True when this is a reply to a message that has since been deleted. */
    val parentDeleted: Boolean,
    /**
     * Raw `threadId` as the server sent it. NOT enough to tell topic membership on its own,
     * use [topicId].
     */
    val threadId: Long,
    /** Raw `isThread`: the message belongs to a topic (root or reply). */
    val isThread: Boolean,
    /** Title of the topic; the server sends it on the topic's root message. */
    val threadTitle: String,
    val threadReplies: Int,
    val silent: Boolean,
    val lastEditTimestamp: Long,
    val lastEditActorDisplayName: String,
    val expirationTimestamp: Long,
    /** The untouched server object, for anything not modelled above. */
    val raw: JsonObject,
) {
    /** Id of the topic this message belongs to, or 0 when it is in no topic ("General"). */
    val topicId: Long get() = if (isThread) threadId else 0L

    /** True for the first post of a topic. */
    val isTopicRoot: Boolean get() = isThread && threadId == id

    val isSystem: Boolean get() = systemMessage.isNotEmpty() || messageType == "system"

    /**
     * The message to draw as a reply quote, or null. The server sets [parent] to the topic's
     * root on EVERY message posted into a topic, even when the user quoted nothing, so a
     * parent that is the topic root is not a quote.
     */
    val quoted: ChatMessage? get() = parent?.takeIf { !(isThread && it.id == threadId) }

    /** The "X created thread ..." notice the server posts right after a topic's root. */
    val isTopicCreatedNotice: Boolean get() = systemMessage == "thread_created"

    /**
     * System messages that only exist to tell clients "this other message changed". They are
     * never shown; [parent] carries the new state of the message they are about.
     */
    val isUpdateSignal: Boolean get() = systemMessage in UPDATE_SIGNALS

    /**
     * The shared file of a file message, or null. Only the server-built `file` parameter
     * counts. A rich object that a user posts lands under the `object` key and can claim type
     * "file" with any path, so it is never treated as a file to download with our credentials.
     */
    val file: RichObject? get() = parameters["file"]?.takeIf { it.isFile }

    /** The message's downloadable files: at most the one real file share. */
    val files: List<RichObject> get() = listOfNotNull(file)

    /** A voice message: an audio file sent with message type `voice-message`. Play it inline. */
    val isVoiceMessage: Boolean get() = messageType == "voice-message" && file != null
    val isImage: Boolean get() = file?.isImage == true
    val isVideo: Boolean get() = file?.isVideo == true

    /** An audio file that is not a voice message (a song, a recording somebody attached). */
    val isAudio: Boolean get() = file?.isAudio == true
    val fileName: String get() = file?.name.orEmpty()
    val fileSize: Long get() = file?.size ?: 0L
    val fileMimetype: String get() = file?.mimetype.orEmpty()

    /** Path of the file in the READER's own files (may be empty); see `TalkFiles.download`. */
    val filePath: String get() = file?.path.orEmpty()

    /** What the sender wrote with the file; empty when the message is the file alone. */
    val caption: String get() = if (file == null || message.trim() == "{file}") "" else message

    /** [message] with placeholders replaced by plain names: for previews and notifications. */
    val plainText: String
        get() {
            if (parameters.isEmpty()) return message
            var out = message
            for ((key, obj) in parameters) {
                val text = if (obj.isMention) "@${obj.name}" else obj.name
                out = out.replace("{$key}", text)
            }
            return out
        }

    companion object {
        val UPDATE_SIGNALS = setOf(
            "reaction", "reaction_deleted", "reaction_revoked",
            "message_deleted", "message_edited", "poll_voted",
        )

        fun fromJson(o: JsonObject, allowParent: Boolean = true): ChatMessage {
            val params = o.obj("messageParameters")?.mapNotNull { (k, v) ->
                v.asObject()?.let { k to RichObject(k, it) }
            }?.toMap() ?: emptyMap()
            val reactions = o.obj("reactions")?.mapValues { (_, v) -> v.asText()?.toIntOrNull() ?: 0 } ?: emptyMap()
            val parentObj = if (allowParent) o.obj("parent") else null
            val parentIsStub = parentObj != null && !parentObj.containsKey("message")
            return ChatMessage(
                id = o.long("id"),
                token = o.str("token"),
                actorType = o.str("actorType"),
                actorId = o.str("actorId"),
                actorDisplayName = o.str("actorDisplayName"),
                timestamp = o.long("timestamp"),
                message = o.str("message"),
                parameters = params,
                messageType = o.str("messageType"),
                systemMessage = o.str("systemMessage"),
                markdown = o.bool("markdown"),
                referenceId = o.str("referenceId"),
                isReplyable = o.bool("isReplyable"),
                deleted = o.bool("deleted") || o.str("messageType") == "comment_deleted",
                reactions = reactions,
                reactionsSelf = o.strings("reactionsSelf"),
                parent = if (parentObj != null && !parentIsStub) fromJson(parentObj, allowParent = false) else null,
                parentDeleted = parentIsStub && parentObj.bool("deleted"),
                threadId = o.long("threadId"),
                isThread = o.bool("isThread"),
                threadTitle = o.str("threadTitle").ifEmpty { o.obj("metaData")?.str("threadTitle") ?: "" },
                threadReplies = o.int("threadReplies"),
                silent = o.bool("silent"),
                lastEditTimestamp = o.long("lastEditTimestamp"),
                lastEditActorDisplayName = o.str("lastEditActorDisplayName"),
                expirationTimestamp = o.long("expirationTimestamp"),
                raw = o,
            )
        }

        internal fun fromJsonOrNull(o: JsonObject?): ChatMessage? =
            if (o == null || o.isEmpty() || !o.containsKey("message")) null else fromJson(o)
    }
}

/** One page of chat messages, oldest first. */
data class ChatPage(
    val messages: List<ChatMessage>,
    /** `X-Chat-Last-Given`: pass as `lastKnownMessageId` to get the next page. Null on 304. */
    val lastGiven: Long?,
    val lastCommonRead: Long?,
) {
    val isEmpty: Boolean get() = messages.isEmpty()
}

/** Who reacted with one emoji (reactions detail endpoint). */
data class ReactionActor(
    val actorType: String,
    val actorId: String,
    val actorDisplayName: String,
    val timestamp: Long,
)
