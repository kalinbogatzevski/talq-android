package talq.core.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import talq.core.TalkError
import talq.core.model.ChatMessage
import talq.core.model.TalkThreadInfo

/**
 * The topics of one room, most recent activity first. The list comes from the server's own
 * threads endpoint (never from scanning messages). Get instances from [TalkSession.topics].
 */
class TopicsRepository internal constructor(
    private val session: TalkSession,
    val token: String,
) {
    private val client get() = session.client
    private val _topics = MutableStateFlow<List<TalkThreadInfo>>(emptyList())
    private val _state = MutableStateFlow(LoadState())

    val topics: StateFlow<List<TalkThreadInfo>> = _topics.asStateFlow()
    val state: StateFlow<LoadState> = _state.asStateFlow()

    fun topic(id: Long): TalkThreadInfo? = _topics.value.firstOrNull { it.id == id }

    /** Reload. Returns false (and sets [state].error) on failure; an old list stays visible. */
    suspend fun refresh(): Boolean {
        if (!session.supportsTopics) {
            _state.value = LoadState(loaded = true)
            return true
        }
        _state.update { it.copy(loading = true) }
        return try {
            _topics.value = sorted(client.recentThreads(token, MAX_TOPICS))
            _state.value = LoadState(loaded = true)
            session.noteTopics(token, _topics.value.isNotEmpty())
            session.noteTopicLevels(token, _topics.value.associate { it.id to it.attendee.notificationLevel })
            true
        } catch (e: TalkError) {
            _state.update { it.copy(loading = false, error = e) }
            false
        }
    }

    /**
     * A topic was just started by posting [root] with [title] (`ChatRepository.send` with a
     * `threadTitle`): list it at once, before the server's own list has been asked again.
     */
    internal fun onCreated(root: ChatMessage, title: String) {
        if (topic(root.id) == null) put(TalkThreadInfo.local(root, title))
    }

    /** Rename a topic (its author or a moderator only). Throws [TalkError]. */
    suspend fun rename(id: Long, title: String): TalkThreadInfo =
        client.renameThread(token, id, title.trim()).also { put(it, levelKnown = true) }

    /** Set the user's notification level for one topic. Throws [TalkError]. */
    suspend fun setNotificationLevel(id: Long, level: Int): TalkThreadInfo =
        client.setThreadNotificationLevel(token, id, level).also { put(it, levelKnown = true) }

    private fun put(info: TalkThreadInfo, levelKnown: Boolean = false) {
        _topics.update { list -> sorted(list.filterNot { it.id == info.id } + info) }
        session.noteTopics(token, true)
        if (levelKnown) session.noteTopicLevels(token, mapOf(info.id to info.attendee.notificationLevel))
    }

    /** Keep the list current from the room's live feed, without a request per message. */
    internal fun onLiveMessages(batch: List<ChatMessage>) {
        if (!_state.value.loaded) return
        var needRefresh = false
        for (m in batch) {
            if (m.systemMessage == "thread_renamed" || m.systemMessage == "thread_created") needRefresh = true
            if (m.isUpdateSignal || m.topicId == 0L) continue
            val known = topic(m.topicId)
            if (known == null) {
                needRefresh = true
            } else if (m.id > known.thread.lastMessageId && m.id != known.id) {
                put(
                    known.copy(
                        thread = known.thread.copy(
                            lastMessageId = m.id,
                            lastActivity = maxOf(m.timestamp, known.thread.lastActivity),
                            numReplies = known.thread.numReplies + if (m.isSystem) 0 else 1,
                        ),
                        last = m,
                    ),
                )
            }
        }
        if (needRefresh) session.scope.launch { refresh() }
    }

    private fun sorted(list: List<TalkThreadInfo>): List<TalkThreadInfo> =
        list.sortedWith(compareByDescending<TalkThreadInfo> { it.thread.lastActivity }.thenByDescending { maxOf(it.thread.lastMessageId, it.id) })

    private companion object {
        /** The endpoint's own maximum. */
        const val MAX_TOPICS = 50
    }
}
