package talq.core.repo

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import talq.core.TalkClient
import talq.core.TalkError
import talq.core.TalkFiles
import talq.core.folders.FolderDav
import talq.core.model.ChatMessage
import talq.core.model.CredentialStore
import talq.core.model.Credentials
import talq.core.model.Room
import talq.core.model.TalkCapabilities
import talq.core.model.UserInfo
import talq.core.net.newTalkHttpClient

/** Loading state shared by the list repositories. */
data class LoadState(
    val loading: Boolean = false,
    /** The last refresh failed with this; cleared by the next success. Old data stays visible. */
    val error: TalkError? = null,
    /** At least one refresh has succeeded, so an empty list really is empty. */
    val loaded: Boolean = false,
)

/**
 * A signed-in account: the client, what the server can do, who we are, and the repositories
 * the UI observes. Create with [open]; call [close] (or [logout]) when done.
 */
class TalkSession private constructor(
    val credentials: Credentials,
    val client: TalkClient,
    val capabilities: TalkCapabilities,
    val user: UserInfo,
) {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Ends the waits of failed polls and loads when the network is usable again ([networkBack]). */
    internal val waker = Waker()

    val rooms = RoomsRepository(this)

    /** Uploads, downloads and shared items of this account. */
    val files = TalkFiles(client, user.userId, capabilities.attachments)

    /**
     * The folder sync's WebDAV client (the user's own /.talq-folders.json). Made on first use: an
     * account that never turns folder sync on never builds it.
     */
    val folderDav: FolderDav by lazy { FolderDav(client, user.userId) }

    private val fileOutboxes = MutableStateFlow<Map<String, FileOutbox>>(emptyMap())

    /** The files being sent into one room (all its topics). Lives as long as the session. */
    fun fileOutbox(token: String): FileOutbox = getOrCreate(fileOutboxes, token) { FileOutbox(this, token) }

    internal fun fileOutboxIfCreated(token: String): FileOutbox? = fileOutboxes.value[token]

    /** Hand messages fetched outside the live feed to every open view of the room. */
    internal fun absorb(token: String, messages: List<ChatMessage>) {
        for ((key, repo) in chats.value) if (key.first == token) repo.absorb(messages)
    }

    /** A message of the room changed (an edit or delete answer): every view of the room that holds it. */
    internal fun applyChange(token: String, updated: ChatMessage) {
        for ((key, repo) in chats.value) if (key.first == token) repo.applyChange(updated)
    }

    private val feeds = MutableStateFlow<Map<String, RoomFeed>>(emptyMap())
    private val chats = MutableStateFlow<Map<Pair<String, ChatScope>, ChatRepository>>(emptyMap())
    private val topicRepos = MutableStateFlow<Map<String, TopicsRepository>>(emptyMap())
    private val unreadTrackers = MutableStateFlow<Map<String, UnreadTracker>>(emptyMap())

    private val _roomsWithTopics = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Tokens of the rooms known to have topics. The room object does not say, so this is
     * collected from what passes by: a topic list that was loaded, a last message that is in
     * a topic, the topics the user follows. A room missing here may still have topics.
     */
    val roomsWithTopics: StateFlow<Set<String>> = _roomsWithTopics.asStateFlow()

    internal fun noteTopics(token: String, has: Boolean) {
        if (token.isEmpty()) return
        _roomsWithTopics.update { if (has) it + token else it - token }
    }

    /**
     * Told the user's notification level ([talq.core.model.NotificationLevel]) of topics
     * whenever the server reports them: a topic list that loaded, the followed topics, a level
     * the user just changed. Map: topic id -> level. Called on whatever thread the answer
     * arrived on. The app persists these so a push can be judged without asking the server.
     */
    var topicLevelListener: ((token: String, levels: Map<Long, Int>) -> Unit)? = null

    internal fun noteTopicLevels(token: String, levels: Map<Long, Int>) {
        if (token.isEmpty() || levels.isEmpty()) return
        topicLevelListener?.invoke(token, levels)
    }

    /** Topics are available on this server. When false, never show topic UI. */
    val supportsTopics: Boolean get() = capabilities.threads

    /** True when [actorId] is the signed-in user (for "my message" styling). */
    fun isMe(actorType: String, actorId: String): Boolean = actorType == "users" && actorId == user.userId

    /**
     * The chat of one room in one [scope]: the whole chat, one topic, or "General" (everything
     * that is in no topic). The same instance is returned for the same arguments, so state
     * survives navigation.
     */
    fun chat(token: String, scope: ChatScope = ChatScope.All): ChatRepository =
        getOrCreate(chats, token to scope) { ChatRepository(this, token, scope, feed(token)) }

    internal fun chatIfCreated(token: String, scope: ChatScope): ChatRepository? = chats.value[token to scope]

    /** The topics of one room. */
    fun topics(token: String): TopicsRepository = getOrCreate(topicRepos, token) { TopicsRepository(this, token) }

    internal fun topicsIfCreated(token: String): TopicsRepository? = topicRepos.value[token]

    /** Per-topic unread counts of one room (the server only counts per room). */
    fun unread(token: String): UnreadTracker = getOrCreate(unreadTrackers, token) { UnreadTracker(this, token) }

    /** The room's unread tracker if something already made one; never creates it. */
    fun unreadIfCreated(token: String): UnreadTracker? = unreadTrackers.value[token]

    private fun feed(token: String): RoomFeed = getOrCreate(feeds, token) { RoomFeed(client, token, scope, waker) }

    /**
     * The phone's network is usable again (the app's network watch). Every room feed that waits
     * after a failure polls now (a poll on its way is asked again), a first load that failed tries
     * again, and the conversation list's reload timer, while it runs, reloads now. Anything that
     * works carries on as it was.
     */
    fun networkBack() = waker.wake()

    /** Rooms being brought up to date by [prefetchChat]: token -> a push came meanwhile (go once more). */
    private val prefetching = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /**
     * A message for [token] is on its way to the user (a chat push): load the room's newest page
     * now, so a tap on the notification opens on it from memory instead of a spinner. Its tabs
     * loaded earlier that miss something of that page are brought up to date too (a tab not made
     * yet takes the whole chat's messages when it is made). A room on screen is live already and
     * is left alone. One run per room at a time; a push during it asks for one more after it.
     *
     * Every load goes through [ChatRepository.refresh], which pins the room's poll cursor before
     * its page (the no-gap rule in [RoomFeed]); opening the room pins it afresh.
     */
    fun prefetchChat(token: String) {
        if (token.isEmpty()) return
        var first = false
        prefetching.update { m ->
            first = token !in m
            m + (token to !first)
        }
        if (!first) return
        scope.launch {
            var done = false
            try {
                while (!done) {
                    bringUpToDate(token)
                    prefetching.update { m ->
                        done = m[token] != true
                        if (done) m - token else m + (token to false)
                    }
                }
            } finally {
                if (!done) prefetching.update { it - token }
            }
        }
    }

    private suspend fun bringUpToDate(token: String) {
        val whole = chat(token)
        if (whole.isLive || !whole.refresh()) return
        val fresh = whole.state.value.messages
        for ((key, repo) in chats.value) {
            if (key.first != token || key.second == ChatScope.All) continue
            if (!repo.isLive && repo.isBehind(fresh)) repo.refresh()
        }
    }

    private inline fun <K, V> getOrCreate(map: MutableStateFlow<Map<K, V>>, key: K, create: () -> V): V {
        while (true) {
            val current = map.value
            current[key]?.let { return it }
            val created = create()
            if (map.compareAndSet(current, current + (key to created))) return created
        }
    }

    /**
     * Sign out: drop the push subscription, revoke the app password on the server, clear the
     * stored credentials, stop everything. Server errors are ignored on purpose, signing out
     * must always work locally.
     */
    suspend fun logout(store: CredentialStore? = null) {
        try { client.webPushUnregister() } catch (_: TalkError) {}
        try { client.revokeAppPassword() } catch (_: TalkError) {}
        store?.clear()
        close()
    }

    /**
     * Run [block] for as long as the session lives, e.g. a write that must finish even though
     * the screen that started it is gone.
     */
    fun launch(block: suspend () -> Unit): Job = scope.launch { block() }

    /** Stop all polling and release the HTTP client. The session is unusable afterwards. */
    fun close() {
        scope.cancel()
        client.close()
    }

    companion object {
        /**
         * Validate [credentials] against the server and build a session. Throws
         * [TalkError.Unauthorized] when the app password no longer works, [TalkError.Network]
         * when offline.
         */
        suspend fun open(credentials: Credentials, http: HttpClient = newTalkHttpClient()): TalkSession {
            val client = TalkClient(credentials, http)
            try {
                val capabilities = client.capabilities()
                val user = client.currentUser()
                return TalkSession(credentials.copy(server = client.serverUrl), client, capabilities, user)
            } catch (e: Throwable) {
                client.close()
                throw e
            }
        }

        /** [open] with whatever [store] holds; null when nobody is signed in. */
        suspend fun restore(store: CredentialStore): TalkSession? = store.load()?.let { open(it) }
    }
}

/** The conversation list. */
class RoomsRepository internal constructor(private val session: TalkSession) {
    private val _rooms = MutableStateFlow<List<Room>>(emptyList())
    private val _state = MutableStateFlow(LoadState())
    private var autoJob: Job? = null
    private var askedFollowedTopics = false

    // Reloads can be answered out of order (the auto refresh, push-triggered reloads, a pull), and
    // a "mark read" echoed while one is on its way is newer than its answer. One clock orders
    // them all: a reload's list is shown only if no later-sent reload's list is, and keeps the
    // read echoes made after it was sent; its error is shown only if nothing sent later has
    // answered, and a success clears only an error from a reload sent before it. Without this
    // the open conversation beside the list (wide layout) showed its unread badge again after
    // it had been read, and a stale answer could hide a newer failure (a 401) or show one.
    private val clock = MutableStateFlow(0L)
    /** Guards the step from "is this answer the newest" to showing it, and [shownFrom]/[errorFrom]. */
    private val publish = Mutex()
    /** [clock] when the reload whose list is shown was sent. */
    private var shownFrom = -1L
    /** [clock] when the reload whose error is in [state] was sent. */
    private var errorFrom = -1L
    /** Read echoes not yet known to be in a reload's answer, by token. */
    private val echoes = MutableStateFlow<Map<String, ReadEcho>>(emptyMap())

    /** All conversations, most recent activity first. */
    val rooms: StateFlow<List<Room>> = _rooms.asStateFlow()
    val state: StateFlow<LoadState> = _state.asStateFlow()

    fun room(token: String): Room? = _rooms.value.firstOrNull { it.token == token }

    /** Reload from the server. Returns false (and sets [state].error) on failure. */
    suspend fun refresh(): Boolean {
        _state.update { it.copy(loading = true) }
        val sentAt = clock.updateAndGet { it + 1 }
        return try {
            // With the other person's presence on one-to-one rooms (noStatusUpdate stays on:
            // loading the list must not make this account look online).
            val list = session.client.rooms(includeStatus = true).sortedByDescending { it.lastActivity }
            val newest = publish.withLock {
                if (sentAt < shownFrom) {
                    // A reload sent later has already been answered: this answer is older and
                    // has nothing to add (not even clearing an error).
                    _state.update { it.copy(loading = false) }
                    return@withLock false
                }
                shownFrom = sentAt
                // Echoes from before this reload was sent are in its answer; later ones are not.
                echoes.update { all -> all.filterValues { it.at > sentAt } }
                _rooms.update {
                    val newer = echoes.value
                    list.map { room -> newer[room.token]?.let { room.withRead(it.marker, it.left) } ?: room }
                }
                // An error from a reload sent after this one is newer than this success: kept.
                _state.update { if (sentAt > errorFrom) LoadState(loaded = true) else it.copy(loaded = true, loading = false) }
                true
            }
            if (newest) noteTopics(list)
            true
        } catch (e: TalkError) {
            publish.withLock {
                if (sentAt > shownFrom && sentAt > errorFrom) {
                    errorFrom = sentAt
                    _state.update { it.copy(loading = false, error = e) }
                } else {
                    // A reload sent later has already answered (or failed): this failure is old news.
                    _state.update { it.copy(loading = false) }
                }
            }
            false
        }
    }

    /** Refresh now and then every [intervalMillis] until [stopAutoRefresh]. Idempotent. */
    fun startAutoRefresh(intervalMillis: Long = 30_000) {
        if (autoJob?.isActive == true) return
        autoJob = session.scope.launch {
            while (isActive) {
                val wakes = session.waker.mark()
                refresh()
                // At once when the network is usable again (also while the reload was on its way).
                session.waker.pause(intervalMillis, since = wakes)
            }
        }
    }

    /** Feed [TalkSession.roomsWithTopics] from the list, and once from the followed topics. */
    private fun noteTopics(list: List<Room>) {
        if (!session.supportsTopics) return
        for (room in list) if (room.lastMessageTopicId != 0L) session.noteTopics(room.token, true)
        if (askedFollowedTopics) return
        askedFollowedTopics = true
        session.scope.launch {
            try {
                // One request for all rooms; a room per room question would be one each.
                val followed = session.client.subscribedThreads()
                followed.forEach { session.noteTopics(it.thread.roomToken, true) }
                followed.groupBy { it.thread.roomToken }.forEach { (token, list) ->
                    session.noteTopicLevels(token, list.associate { it.id to it.attendee.notificationLevel })
                }
            } catch (e: TalkError) {
                askedFollowedTopics = false
            }
        }
    }

    fun stopAutoRefresh() {
        autoJob?.cancel()
        autoJob = null
    }

    /**
     * Local echo of a successful "mark read", so the badge drops without a round trip. [left]: the
     * server's own unread numbers right after the move (its answer), for a room read only partly.
     */
    internal fun applyRead(token: String, lastReadMessage: Long, left: ReadLeft? = null) {
        val at = clock.updateAndGet { it + 1 }
        echoes.update { all ->
            val earlier = all[token]
            val echo = when {
                earlier == null || lastReadMessage > earlier.marker -> ReadEcho(lastReadMessage, at, left)
                lastReadMessage == earlier.marker -> ReadEcho(lastReadMessage, at, left ?: earlier.left)
                else -> earlier.copy(at = at)
            }
            all + (token to echo)
        }
        _rooms.update { list -> list.map { if (it.token == token) it.withRead(lastReadMessage, left) else it } }
    }

    /**
     * Caught up: nothing unread. Partly read: the server's numbers from [left] where it gave them,
     * never more than shown (only ever lower; the next reload has the truth). Else only the marker.
     */
    private fun Room.withRead(marker: Long, left: ReadLeft? = null): Room {
        if (lastReadMessage >= marker) return this
        val caughtUp = marker >= (lastMessage?.id ?: 0L)
        return when {
            caughtUp -> copy(lastReadMessage = marker, unreadMessages = 0, unreadMention = false, unreadMentionDirect = false)
            left != null -> copy(
                lastReadMessage = marker,
                unreadMessages = minOf(unreadMessages, maxOf(0, left.unread)),
                unreadMention = unreadMention && left.mention,
                unreadMentionDirect = unreadMentionDirect && left.direct,
            )
            else -> copy(lastReadMessage = marker)
        }
    }
}

/** What is still unread in a room right after its read marker moved, as the server answered the move. */
internal data class ReadLeft(val unread: Int, val mention: Boolean, val direct: Boolean)

/** A "mark read" echo: the marker, [RoomsRepository]'s clock when it was made, and the numbers after it. */
private data class ReadEcho(val marker: Long, val at: Long, val left: ReadLeft?)
