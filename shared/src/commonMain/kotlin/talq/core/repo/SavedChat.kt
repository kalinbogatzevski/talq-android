package talq.core.repo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import talq.core.model.ChatMessage
import talq.core.model.Room
import talq.core.net.TalkJson
import talq.core.net.arr
import talq.core.net.asObject
import talq.core.net.int
import talq.core.net.long
import talq.core.net.obj
import talq.core.net.str

/** What a chat held of one scope when it was kept: [messages] without a gap, oldest first. */
internal class SavedView(
    val messages: List<ChatMessage>,
    /** Everything up to this id was known (also what is not shown): what is newer may lie past a gap. */
    val top: Long,
)

/**
 * The newest messages of one conversation as the last run of the app left them: what it opens on, also offline,
 * before the server has answered. The app keeps it as one text ([encode], [decode]). TalQ for desktop's
 * `MessageCache` (SQLite, 200 messages a room) is the authority for that there is one; the rules are ours:
 *
 *  - Something to look at, never a source: nothing is counted, marked as read, announced or sent because of it, and
 *    the server's answer takes its place by id ([ChatRepository.refresh]): what it does not have any more goes,
 *    and where something may be missing in between all of it goes.
 *  - Each view of the room that was loaded (the whole chat, General, a topic) keeps its newest [MAX_MESSAGES],
 *    a conversation at most [MAX_TOPICS] topics and [MAX_CHARS] characters. A message is written once, from its
 *    fields as they are shown (the server's object knows neither the user's own reactions nor later edits). A quote
 *    is written as the quoted message is now (deleted: without its text), one that expired not at all.
 *  - Never kept: conversations the server marks sensitive, and those of another server ([mayBeKept]), notices of
 *    calls, a private answer (it quotes another conversation; nothing before it either, or the rest would look whole)
 *    and what expired. Nothing is kept of a file but what the server says about it.
 */
class SavedChat private constructor(
    val account: String,
    val token: String,
    /** The newest message every participant had read, as the server last said; 0 = not known. */
    val commonRead: Long,
    private val rows: Map<Long, ChatMessage>,
    private val views: Map<Long, View>,
) {
    /** Message ids, ascending; [top] as [SavedView.top]. */
    private class View(val ids: List<Long>, val top: Long)

    /**
     * What there is of [scope]: its own messages with the whole chat's newer ones, unless there is a gap between the
     * two (the newer ones then). Null when there is nothing.
     */
    internal fun view(scope: ChatScope, nowSeconds: Long): SavedView? {
        val own = views[scope.key]
        val whole = if (scope == ChatScope.All) null else views[ChatScope.All.key]
        val newer = whole?.ids.orEmpty().filter { id -> rows[id]?.let(scope::accepts) == true }
        val meets = own != null && whole != null && own.top >= whole.ids.first()
        // A view's own ids too: a message kept there may have moved since (a topic's root, see ChatRepository.withBatch).
        val ownIds = own?.ids.orEmpty().filter { id -> rows[id]?.let(scope::accepts) == true }
        val ids = when {
            own == null -> newer
            newer.isEmpty() || meets -> (ownIds + newer).distinct().sorted()
            else -> newer
        }
        val messages = ids.mapNotNull { rows[it] }.mapNotNull { it.unexpired(nowSeconds) }
        if (messages.isEmpty()) return null
        val top = when {
            own == null || (newer.isNotEmpty() && !meets) -> whole?.top ?: 0L
            meets -> maxOf(own.top, whole.top)
            else -> own.top
        }
        return SavedView(messages, top)
    }

    /** The messages of [scope], oldest first, without what has expired by now. */
    fun messages(scope: ChatScope, nowSeconds: Long): List<ChatMessage> = view(scope, nowSeconds)?.messages.orEmpty()

    /** The topics messages are kept of, by id with their titles, the one written in last first. */
    val topics: List<Pair<Long, String>> by lazy {
        rows.values.filter { it.topicId != 0L }.groupBy { it.topicId }
            .map { (id, list) -> Triple(id, list.firstNotNullOfOrNull { m -> m.threadTitle.ifBlank { null } }.orEmpty(), list.maxOf { it.id }) }
            .sortedByDescending { it.third }
            .map { it.first to it.second }
    }

    /** Message [updated] as it is now, where it is held, and where it is quoted. */
    internal fun with(updated: ChatMessage): SavedChat = SavedChat(
        account, token, commonRead,
        rows.mapValues { (id, m) -> if (id == updated.id) m.changedTo(updated) else m.quoting(updated) },
        views,
    )

    /** The history was deleted, as message [id] says. */
    internal fun from(id: Long): SavedChat = filtered { it.id >= id }

    /** Without [scope], a topic that is gone, and its messages. */
    internal fun without(scope: ChatScope): SavedChat =
        SavedChat(account, token, commonRead, rows, views - scope.key).filtered { scope == ChatScope.All || !scope.accepts(it) }

    /** Only the views that are not of [scopes]; null when there are none. */
    internal fun beside(scopes: Collection<ChatScope>): SavedChat? {
        val left = views - scopes.map { it.key }.toSet()
        if (left.isEmpty()) return null
        val ids = left.values.flatMapTo(HashSet()) { it.ids }
        return SavedChat(account, token, commonRead, rows.filterKeys { it in ids }, left)
    }

    private fun filtered(keep: (ChatMessage) -> Boolean): SavedChat {
        val left = rows.filterValues(keep)
        return SavedChat(
            account, token, commonRead, left,
            views.mapValues { (_, v) -> View(v.ids.filter { it in left }, v.top) }.filterValues { it.ids.isNotEmpty() },
        )
    }

    /** One view of a conversation as it is held now; see [SavedView]. */
    internal class Held(val scope: ChatScope, val messages: List<ChatMessage>, val top: Long)

    /** [expires]: when the first message in [text] disappears (seconds, UTC), 0 = none does. */
    class Encoded(val text: String, val expires: Long)

    companion object {
        private const val VERSION = 1
        private const val MAX_MESSAGES = 100
        private const val MAX_TOPICS = 6
        private const val MAX_CHARS = 400_000

        /** A parameter's value longer than this is bytes (the picture in a contact card), nothing a chat shows. */
        private const val MAX_VALUE = 2_000

        /** May be kept at all. Only asked of a conversation as the server's list has it. */
        internal fun mayBeKept(room: Room): Boolean = !room.isSensitive && !room.isFederated

        /**
         * [held], and of the views that are not among them what [older] has, as text. Null when nothing is left.
         */
        internal fun encode(
            account: String, token: String, commonRead: Long, held: List<Held>, older: SavedChat?, nowSeconds: Long,
        ): Encoded? {
            val rows = HashMap<Long, ChatMessage>()
            older?.rows?.let { rows.putAll(it) }
            // The whole chat's last: it is on screen whenever one of its tabs is.
            for (view in held.sortedByDescending { it.scope.key }) for (m in view.messages) rows[m.id] = m
            fun ids(of: List<ChatMessage>): List<Long> = of.takeLastWhile { !it.answersElsewhere }
                .filter { !it.isUpdateSignal && !it.isCallNotice && !it.expired(nowSeconds) }
                .takeLast(MAX_MESSAGES).map { it.id }

            val whole = held.firstOrNull { it.scope == ChatScope.All }
            // A tab that is not on screen goes on with what the whole chat has had since, unless something is missing in between.
            fun view(scope: ChatScope, messages: List<ChatMessage>, top: Long): View {
                if (whole == null || scope == ChatScope.All || whole.messages.isEmpty() || top < whole.messages.first().id) return View(ids(messages), top)
                val all = (messages + whole.messages.filter(scope::accepts)).associateBy { it.id }.values.sortedBy { it.id }
                return View(ids(all), maxOf(top, whole.top))
            }
            val views = HashMap<Long, View>()
            older?.views?.forEach { (key, view) -> views[key] = view(scopeOf(key), view.ids.mapNotNull { rows[it] }, view.top) }
            for (view in held) views[view.scope.key] = view(view.scope, view.messages, view.top)
            // The whole chat and General first, then the topics, newest first: where there is no room left, the rest stays out.
            val order = views.filterValues { it.ids.isNotEmpty() }.entries
                .sortedWith(compareBy<Map.Entry<Long, View>> { if (it.key > 0L) 1 else 0 }.thenByDescending { if (it.key > 0L) it.value.ids.last() else -it.key })
            val written = LinkedHashMap<Long, String>()
            val kept = ArrayList<Pair<Long, View>>()
            var chars = 0
            var topics = 0
            for ((key, view) in order) {
                if (key > 0L && topics++ == MAX_TOPICS) break
                val fitting = ArrayList<Long>()
                for (id in view.ids.asReversed()) {
                    if (id !in written) {
                        val text = kept(rows.getValue(id), rows, nowSeconds).toString()
                        if (chars + text.length > MAX_CHARS) break
                        chars += text.length
                        written[id] = text
                    }
                    fitting.add(id)
                }
                if (fitting.isNotEmpty()) kept.add(key to View(fitting.asReversed(), view.top))
            }
            if (kept.isEmpty()) return null
            val shown = written.keys.map { rows.getValue(it) }
            val expires = (shown + shown.mapNotNull { it.quoted }).map { it.expirationTimestamp }.filter { it > nowSeconds }.minOrNull() ?: 0L
            val head = buildJsonObject {
                put("v", VERSION)
                put("account", account)
                put("token", token)
                put("read", commonRead)
                put("views", buildJsonArray {
                    for ((key, view) in kept) add(buildJsonObject {
                        put("k", key)
                        put("top", view.top)
                        put("ids", JsonArray(view.ids.map { JsonPrimitive(it) }))
                    })
                })
            }.toString()
            val text = buildString(head.length + chars + written.size + 16) {
                append(head, 0, head.length - 1).append(",\"rows\":[")
                var first = true
                for (row in written.values) {
                    if (!first) append(',')
                    first = false
                    append(row)
                }
                append("]}")
            }
            return Encoded(text, expires)
        }

        /** [chat] again, without what has expired by now. */
        fun encode(chat: SavedChat, nowSeconds: Long): Encoded? =
            encode(chat.account, chat.token, chat.commonRead, emptyList(), chat, nowSeconds)

        /** Null for anything but a conversation [encode] of this version made. */
        fun decode(text: String): SavedChat? {
            val o = try {
                TalkJson.parseToJsonElement(text).asObject()
            } catch (_: Exception) {
                null
            }
            if (o == null || o.int("v") != VERSION) return null
            val rows = o.arr("rows").mapNotNull { it.asObject() }.map { ChatMessage.fromJson(it) }.filter { it.id > 0L }.associateBy { it.id }
            val views = o.arr("views").mapNotNull { it.asObject() }.associate { v ->
                val ids = v.arr("ids").mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }.filter { it in rows }.distinct().sorted()
                v.long("k") to View(ids, v.long("top"))
            }.filterValues { it.ids.isNotEmpty() }
            val chat = SavedChat(o.str("account"), o.str("token"), o.long("read"), rows, views)
            return chat.takeIf { it.account.isNotEmpty() && it.token.isNotEmpty() && it.views.isNotEmpty() }
        }

        // ------------------------------------------------------------ one message

        /** A private answer: it quotes a message of another conversation, which is not this one's to keep. */
        private val ChatMessage.answersElsewhere: Boolean
            get() = parent?.let { it.token.isNotEmpty() && token.isNotEmpty() && it.token != token } == true ||
                raw.obj("metaData")?.str("replyToConversationToken").orEmpty().isNotEmpty()

        /** What [ChatMessage.fromJson] reads, from the fields. [rows]: the messages a quote may be one of. */
        private fun kept(m: ChatMessage, rows: Map<Long, ChatMessage>, nowSeconds: Long): JsonObject = buildJsonObject {
            put("id", m.id)
            put("token", m.token)
            put("actorType", m.actorType)
            put("actorId", m.actorId)
            put("actorDisplayName", m.actorDisplayName)
            put("timestamp", m.timestamp)
            put("message", m.message)
            put("messageType", m.messageType)
            if (m.parameters.isNotEmpty()) putJsonObject("messageParameters") {
                for ((key, value) in m.parameters) put(key, keptParameter(value.raw))
            }
            if (m.systemMessage.isNotEmpty()) put("systemMessage", m.systemMessage)
            if (m.markdown) put("markdown", true)
            if (m.referenceId.isNotEmpty()) put("referenceId", m.referenceId)
            if (m.isReplyable) put("isReplyable", true)
            if (m.deleted) put("deleted", true)
            if (m.reactions.isNotEmpty()) putJsonObject("reactions") { for ((emoji, count) in m.reactions) put(emoji, count) }
            if (m.reactionsSelf.isNotEmpty()) put("reactionsSelf", JsonArray(m.reactionsSelf.map { JsonPrimitive(it) }))
            if (m.threadId != 0L) put("threadId", m.threadId)
            if (m.isThread) put("isThread", true)
            if (m.threadTitle.isNotEmpty()) put("threadTitle", m.threadTitle)
            if (m.threadReplies != 0) put("threadReplies", m.threadReplies)
            if (m.silent) put("silent", true)
            if (m.lastEditTimestamp != 0L) put("lastEditTimestamp", m.lastEditTimestamp)
            if (m.lastEditActorDisplayName.isNotEmpty()) put("lastEditActorDisplayName", m.lastEditActorDisplayName)
            if (m.expirationTimestamp != 0L) put("expirationTimestamp", m.expirationTimestamp)
            // A topic's root, which is every message's parent in it, is no quote and is not written each time.
            val quote = m.quoted?.let { rows[it.id] ?: it }
            if (quote != null && !quote.expired(nowSeconds)) {
                put("parent", kept(quote.copy(parent = null, parentDeleted = false, reactions = emptyMap(), reactionsSelf = emptyList()), emptyMap(), nowSeconds))
            } else if (quote != null || m.parentDeleted) {
                putJsonObject("parent") {
                    put("id", quote?.id ?: 0L)
                    put("deleted", true)
                }
            }
        }

        private fun keptParameter(raw: JsonObject): JsonObject = JsonObject(
            raw.filterValues { it is JsonPrimitive && it !is JsonNull && it.content.length <= MAX_VALUE },
        )


    }
}

/** The number a view goes by in what is kept (the app's tab keys). */
internal val ChatScope.key: Long
    get() = when (this) {
        ChatScope.All -> -1L
        ChatScope.General -> 0L
        is ChatScope.Topic -> id
    }

private fun scopeOf(key: Long): ChatScope = when (key) {
    ChatScope.All.key -> ChatScope.All
    ChatScope.General.key -> ChatScope.General
    else -> ChatScope.Topic(key)
}

/** The message disappeared for everyone (seconds, UTC); the server sends no notice of that. */
internal fun ChatMessage.expired(nowSeconds: Long): Boolean = expirationTimestamp in 1..nowSeconds

/** Null once expired; a quote that is, is gone from it. */
internal fun ChatMessage.unexpired(nowSeconds: Long): ChatMessage? = when {
    expired(nowSeconds) -> null
    quoted?.expired(nowSeconds) == true -> copy(parent = null, parentDeleted = true)
    else -> this
}

/** Without what has expired by now; the same list when nothing has. */
internal fun List<ChatMessage>.unexpired(nowSeconds: Long): List<ChatMessage> =
    if (none { it.expired(nowSeconds) || it.quoted?.expired(nowSeconds) == true }) this else mapNotNull { it.unexpired(nowSeconds) }

/** A notice of a call or of its recording: the app keeps no history of calls. */
internal val ChatMessage.isCallNotice: Boolean
    get() = systemMessage.startsWith("call_") || "recording" in systemMessage
