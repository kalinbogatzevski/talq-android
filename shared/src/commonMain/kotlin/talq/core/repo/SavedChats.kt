package talq.core.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import talq.core.model.ChatMessage
import kotlin.concurrent.Volatile

/** Keeps what one account's conversations held for the next run of the app (the app writes sealed files). */
interface MessageKeeper {
    val account: String

    /** What is in memory already, read a moment ago. Never waits. */
    fun held(token: String): SavedChat?

    /** What an earlier run kept of [token]; null when there is nothing that can be read. */
    suspend fun read(token: String): SavedChat?

    /**
     * In place of what was kept of [token]; null = nothing is. False: nothing is kept of it now (it could not be
     * written, or is not among [retain]'s).
     */
    suspend fun keep(token: String, chat: SavedChat.Encoded?): Boolean

    /** [token] was used: others go first when there are too many. */
    suspend fun used(token: String)

    /** Nothing is kept but of these conversations, from here on. */
    suspend fun retain(tokens: Set<String>)

    /** Messages that have disappeared by now go from what is kept. */
    suspend fun sweep()
}

/**
 * What the phone keeps of a session's conversations ([SavedChat] says what of them): read once when a conversation is
 * first asked for, handed to its views ([ChatRepository]), and written from what they hold once the server has
 * answered. The views only say that something changed ([note]); written is what they hold then, so what was edited,
 * deleted, reacted to, or is past a gap that was found, is as it is on screen.
 *
 * A moment after a change, then at most every [LATER_MILLIS]; at once when something is gone or was changed
 * ([hurry]) or the app leaves the screen ([flush]). Only once the conversation list has been loaded: what is not in it
 * (left, removed), is marked sensitive or is another server's is deleted, and never written.
 */
internal class SavedChats(private val session: TalkSession) {
    /** [reading]: the file is being read. [chat]: what was in it, kept up for the views this session has not loaded. */
    private class Kept(val reading: Boolean, val chat: SavedChat? = null)

    @Volatile
    private var keeper: MessageKeeper? = null
    private val kept = MutableStateFlow<Map<String, Kept>>(emptyMap())
    private val changed = MutableStateFlow<Set<String>>(emptySet())
    private val urgent = MutableStateFlow<Set<String>>(emptySet())

    /** Length and hash of what each conversation's file holds, written by this session. The writer's. */
    private val written = HashMap<String, Pair<Int, Int>>()

    /** Once, before anything asks for a chat of this session. */
    fun start(keeper: MessageKeeper) {
        this.keeper = keeper
        session.launch {
            session.rooms.state.first { it.loaded }
            keeper.retain(listed())
            keeper.sweep()
            session.launch { write(keeper) }
            session.rooms.rooms.map { listed() }.distinctUntilChanged().collect { tokens ->
                kept.update { all -> all.mapValues { (token, k) -> if (token in tokens) k else Kept(reading = false) } }
                keeper.retain(tokens)
            }
        }
    }

    private fun listed(): Set<String> =
        session.rooms.rooms.value.filter { SavedChat.mayBeKept(it) }.mapTo(HashSet()) { it.token }

    private fun now(): Long = session.wall() / 1000

    // ---------------------------------------------------------------- reading

    /** A view of [token] was made: what was kept of it is read, once. What is in memory is there at once. */
    fun open(token: String) {
        val keeper = keeper ?: return
        if (token in kept.value || session.rooms.room(token)?.let { SavedChat.mayBeKept(it) } == false) return
        val held = keeper.held(token)
        var first = false
        kept.update { all ->
            first = token !in all
            if (first) all + (token to Kept(reading = held == null, chat = held)) else all
        }
        if (!first || held != null) return
        session.launch {
            val chat = keeper.read(token)
            // Not when it is known by now that nothing of it may be shown.
            kept.update { all -> if (all[token]?.reading == true) all + (token to Kept(reading = false, chat = chat)) else all }
            // The whole chat first: the others take from it.
            if (chat != null) session.chatsOf(token).sortedBy { it.chatScope.key }.forEach { it.seedKnown() }
        }
    }

    fun view(token: String, scope: ChatScope): SavedView? = kept.value[token]?.chat?.view(scope, now())

    fun commonRead(token: String): Long = kept.value[token]?.chat?.commonRead ?: 0L

    fun topics(token: String): List<Pair<Long, String>> = kept.value[token]?.chat?.topics.orEmpty()

    // ---------------------------------------------------------------- what happens to it

    /** A view of [token] holds something else than before. */
    fun note(token: String) {
        if (keeper != null) changed.update { it + token }
    }

    /** The same, and it must not stay on the phone for longer than need be. */
    fun hurry(token: String) {
        if (keeper == null) return
        changed.update { it + token }
        urgent.update { it + token }
    }

    /** The app leaves the screen: what waits is written now. */
    fun flush() {
        val keeper = keeper ?: return
        urgent.update { it + changed.value }
        session.launch { keeper.sweep() }
    }

    /** Messages of [token] changed, as the server says: also in views that were not opened in this session. */
    fun update(token: String, messages: List<ChatMessage>) {
        if (messages.isEmpty() || keeper == null) return
        session.launch {
            kept.first { it[token]?.reading != true }
            change(token) { chat -> messages.fold(chat) { c, m -> c.with(m) } }
            if (messages.any { it.deleted || it.lastEditTimestamp > 0L }) hurry(token) else note(token)
        }
    }

    /** The history of [token] was deleted, as message [id] says (a page shows that notice for as long as it is in it). */
    fun cleared(token: String, id: Long) {
        if (keeper == null) return
        session.launch {
            if (kept.value[token]?.reading == true) {
                kept.first { it[token]?.reading != true }
                // It may just have been put into views that have not heard of it.
                session.chatsOf(token).forEach { it.dropBefore(id) }
            }
            change(token) { it.from(id) }
            hurry(token)
        }
    }

    /** The server refuses [scope] of [token] to the user: the whole conversation, or a topic that is gone. */
    fun refused(token: String, scope: ChatScope) {
        change(token) { if (scope is ChatScope.Topic) it.without(scope) else null }
        hurry(token)
    }

    /** What is still being read is not taken any more. */
    private fun change(token: String, f: (SavedChat) -> SavedChat?) = kept.update { all ->
        val k = all[token] ?: return@update all
        all + (token to Kept(reading = false, chat = k.chat?.let(f)))
    }

    // ---------------------------------------------------------------- writing

    private suspend fun write(keeper: MessageKeeper) {
        var last = -LATER_MILLIS
        while (true) {
            changed.first { it.isNotEmpty() }
            val wait = if (session.now() - last < LATER_MILLIS) LATER_MILLIS else SOON_MILLIS
            withTimeoutOrNull(wait) { combine(changed, urgent) { c, u -> c.any { it in u } }.first { it } }
            val tokens = changed.getAndUpdate { emptySet() }
            urgent.update { it - tokens }
            for (token in tokens) save(keeper, token)
            keeper.sweep()
            last = session.now()
        }
    }

    private suspend fun save(keeper: MessageKeeper, token: String) {
        val room = session.rooms.room(token) ?: return
        if (!SavedChat.mayBeKept(room)) return
        // What the views of an earlier run hold goes on.
        kept.first { it[token]?.reading != true }
        val chats = session.chatsOf(token)
        val encoded = withContext(Dispatchers.Default) {
            val commonRead = chats.firstOrNull()?.commonRead?.value?.takeIf { it > 0L } ?: commonRead(token)
            SavedChat.encode(keeper.account, token, commonRead, chats.mapNotNull { it.held() }, kept.value[token]?.chat, now())
        }
        // The views that are loaded have their own by now.
        val loaded = chats.filter { it.held() != null }.map { it.chatScope }
        kept.update { all -> all[token]?.chat?.let { all + (token to Kept(reading = false, chat = it.beside(loaded))) } ?: all }
        val stamp = encoded?.text?.let { it.length to it.hashCode() }
        if (stamp != null && written[token] == stamp) {
            keeper.used(token)
        } else {
            val done = keeper.keep(token, encoded)
            if (done && stamp != null) written[token] = stamp else written.remove(token)
        }
    }

    private companion object {
        const val SOON_MILLIS = 2_000L
        const val LATER_MILLIS = 10_000L
    }
}
