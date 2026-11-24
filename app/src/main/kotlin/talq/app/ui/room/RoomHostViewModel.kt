package talq.app.ui.room

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import talq.app.data.RoomTabStore
import talq.app.nav.RoomRoute
import talq.app.ui.chat.MessageSelection
import talq.app.ui.chat.forwardResultText
import talq.app.ui.chat.sayAnywhere
import talq.app.ui.common.userMessage
import talq.app.ui.topics.topicUnread
import talq.core.TalkError
import talq.core.UploadedFile
import talq.core.model.ForwardPlan
import talq.core.model.TalkThreadInfo
import talq.core.model.forwardPlan
import talq.core.repo.ChatRepository
import talq.core.repo.ChatScope
import talq.core.repo.OutboxStatus
import talq.core.repo.TalkSession
import talq.core.repo.TopicsRepository
import talq.core.repo.UnreadTracker

/**
 * One open conversation: which tab is selected, the order of the strip, and when the room's
 * lists are reloaded. The tabs' own state (draft, reply target, divider) is in one
 * `ChatViewModel` per tab; the messages, topics and unread counts are in the core repositories.
 *
 * This is the only place that changes the selected tab ([select]); the host screen records it
 * as the remembered one.
 */
class RoomHostViewModel(
    val session: TalkSession,
    val route: RoomRoute,
    private val tabStore: RoomTabStore,
    /** The application's context (never an activity's): for a forward's line after the room was left. */
    private val appContext: Context,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    val token: String = route.token
    val topics: TopicsRepository = session.topics(token)
    val unread: UnreadTracker = session.unread(token)

    /** The whole chat: the host keeps it started so the room's live feed never stops between tabs. */
    val wholeChat: ChatRepository = session.chat(token, ChatScope.All)

    private val account: String = session.credentials.server + "|" + session.user.userId

    /** What was stored for this room when it opened; non-null also means "it had topics then". */
    var rememberedAtOpen: Long? = tabStore.peek(account, token)
        private set

    /**
     * The tab the user wants. Null only for the moment the remembered tab is still being read
     * from disk. A room without topics shows the whole chat whatever this says.
     */
    var tab: RoomTab? by mutableStateOf(
        RoomTab.fromKey(saved.get<Long>(KEY_TAB))
            ?: RoomTab.fromKey(route.tab)
            ?: if (tabStore.isLoaded) RoomTab.fromKey(rememberedAtOpen) ?: RoomTab.General else null,
    )
        private set

    /** The tab this room was opened on: the one a notification's message is scrolled to in. */
    var openedOn: RoomTab? = tab
        private set

    /**
     * A message to scroll to in a tab, asked for while the room was already open (a
     * notification, "Show in chat"); the tab's pane clears it once done.
     */
    var jump: Pair<RoomTab, Long>? by mutableStateOf(null)

    /** Topic ids in strip order. Fixed once taken; later topics join at the front. */
    var order: List<Long> by mutableStateOf(emptyList())
        private set
    private var orderTaken = false
    private var orderSettled = false
    private var userSwitched = false

    var pulling by mutableStateOf(false)
        private set

    /** A one-line message for the snackbar; the screen clears it once shown. */
    var notice by mutableStateOf<String?>(null)

    /**
     * A topic that has a title but no first message yet. It exists only here: nothing is sent
     * until [sendDraft], and switching tabs or leaving the room drops it.
     *
     * @param referenceId the outbox key of the first message once it has been sent off
     */
    data class DraftTopic(val title: String, val referenceId: String? = null)

    var draft: DraftTopic? by mutableStateOf(null)
        private set

    /** The first message being typed for [draft]. */
    var draftText by mutableStateOf("")

    private var refreshJob: Job? = null

    /** Messages picked in the tab on screen, for Copy and Forward. Switching tabs ends it. */
    val selection = MessageSelection()

    /** A forward is on its way: the Forward button shows progress and takes nothing more. */
    var forwarding by mutableStateOf(false)
        private set

    init {
        if (tab == null) {
            viewModelScope.launch {
                tabStore.awaitLoaded()
                rememberedAtOpen = tabStore.peek(account, token)
                if (tab == null) {
                    tab = RoomTab.fromKey(rememberedAtOpen) ?: RoomTab.General
                    openedOn = tab
                }
            }
        }
        viewModelScope.launch { topics.topics.collect { syncOrder(it) } }
        // The server confirmed a draft's first message: the draft is now that topic.
        viewModelScope.launch {
            wholeChat.createdTopics.collect { created ->
                val id = draft?.referenceId?.let { created[it] } ?: return@collect
                select(RoomTab.Topic(id))
            }
        }
    }

    /** Switch the strip to [to], in place. */
    fun select(to: RoomTab) {
        userSwitched = true
        // An unsent draft is dropped. One whose first message is on its way goes on being
        // sent (the topic then joins the strip). One whose first message failed is given up,
        // and said so: left in the whole chat's outbox it would sit where nobody looks.
        draft?.let { leaving ->
            val failed = leaving.referenceId?.let { ref ->
                wholeChat.state.value.outbox.firstOrNull { it.referenceId == ref && it.status == OutboxStatus.FAILED }
            }
            if (failed != null) {
                wholeChat.discard(failed.referenceId)
                notice = "The topic \u201C${leaving.title}\u201D was not created: its first message could not be sent."
            }
        }
        draft = null
        draftText = ""
        selection.clear()
        if (jump?.first != to) jump = null
        tab = to
        saved[KEY_TAB] = to.key
    }

    /** The strip is showing [shown]: it is the tab this room opens on next time. */
    fun remember(shown: RoomTab) = tabStore.set(account, token, shown.key)

    /** The room turned out to have no topics: nothing to remember. */
    fun forget() = tabStore.clear(account, token)

    private fun lastRead(): Long = session.rooms.room(token)?.lastReadMessage ?: 0L

    /** Unread first, then by last activity, newest first. */
    private fun snapshot(list: List<TalkThreadInfo>): List<Long> {
        val state = unread.state.value
        val lastRead = lastRead()
        return list.sortedWith(
            compareByDescending<TalkThreadInfo> { topicUnread(session, state, lastRead, it).any }
                .thenByDescending { it.thread.lastActivity },
        ).map { it.id }
    }

    private fun syncOrder(list: List<TalkThreadInfo>) {
        if (list.isEmpty()) return
        if (!orderTaken) {
            orderTaken = true
            order = snapshot(list)
            return
        }
        val known = order.toSet()
        val fresh = list.filter { it.id !in known }.map { it.id }
        if (fresh.isNotEmpty()) order = fresh + order
    }

    /**
     * Reload rooms, topics and unread counts, in that order: the read marker and the room's
     * newest message must be current for the unread scan. [byUser] also reloads [shown], the
     * tab on screen (the whole chat in a room without topics, whatever [tab] says).
     */
    fun refresh(byUser: Boolean = false, shown: RoomTab? = null) {
        if (refreshJob?.isActive == true && !byUser) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (byUser) pulling = true
            try {
                session.rooms.refresh()
                if (session.supportsTopics) topics.refresh()
                unread.refresh()
                // The first answer after opening: the counts are now real, so take the order
                // again, unless the user has already started using the strip as it was.
                if (!orderSettled) {
                    orderSettled = true
                    if (!userSwitched && topics.topics.value.isNotEmpty()) {
                        orderTaken = true
                        order = snapshot(topics.topics.value)
                    }
                }
                if (byUser && shown != null) session.chat(token, shown.scope).refresh()
            } finally {
                pulling = false
            }
        }
    }

    // ---------------------------------------------------------------- draft topic

    /** "+" was given a title: show the draft chip. Nothing is sent yet. */
    fun startDraft(title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        selection.clear()
        draft = DraftTopic(clean)
        draftText = ""
    }

    /** Drop the draft. A first message that already failed to send is given up too. */
    fun discardDraft() {
        draft?.referenceId?.let { ref ->
            if (wholeChat.state.value.outbox.any { it.referenceId == ref && it.status == OutboxStatus.FAILED }) wholeChat.discard(ref)
        }
        draft = null
        draftText = ""
    }

    /** Send the draft's first message: this is what creates the topic. */
    fun sendDraft() {
        val current = draft ?: return
        val text = draftText.trim()
        if (text.isEmpty() || current.referenceId != null) return
        draft = current.copy(referenceId = wholeChat.send(text, threadTitle = current.title))
        draftText = ""
    }

    fun retryDraft() {
        draft?.referenceId?.let { wholeChat.retry(it) }
    }

    /** Give up the failed first message but keep the draft, with the text back in the field. */
    fun editDraftAgain() {
        val current = draft ?: return
        val ref = current.referenceId ?: return
        draftText = wholeChat.state.value.outbox.firstOrNull { it.referenceId == ref }?.text.orEmpty()
        wholeChat.discard(ref)
        draft = current.copy(referenceId = null)
    }

    // ---------------------------------------------------------------- forward

    /**
     * Forward what is picked into the conversation [target], one message after another in chat
     * order, as TalQ for desktop does (`MainWindow::forwardOneMessage`): a file is shared again,
     * a text is posted again with its "Forwarded from" line (`talq.core.model.forwardPlan`).
     * Stops at the first refusal so nothing arrives out of order; says in one line how it went
     * and ends the selection. Runs on the session's scope, the result line included: leaving the
     * room does not cut it off, and the line is then said app-wide. The session ending (signed
     * out) under it cancels it, and then nothing is said.
     */
    fun forward(target: String) {
        if (forwarding) return
        val picked = selection.messages()
        if (picked.isEmpty()) {
            selection.clear()
            return
        }
        forwarding = true
        selection.locked = true
        val run = session.launch {
            var sent = 0
            var failure: TalkError? = null
            for (m in picked) {
                try {
                    when (val plan = forwardPlan(m)) {
                        is ForwardPlan.File -> session.files.share(target, UploadedFile(plan.path, plan.name, draft = false))
                        is ForwardPlan.Text -> session.client.sendMessage(target, plan.body)
                        ForwardPlan.Empty -> continue
                    }
                    sent++
                } catch (e: TalkError) {
                    failure = e
                    break
                }
            }
            val line = forwardResultText(picked.size, sent, failure)
            withContext(Dispatchers.Main) {
                if (cleared) {
                    sayAnywhere(appContext, line)
                } else {
                    selection.clear()
                    notice = line
                }
            }
        }
        viewModelScope.launch {
            try {
                run.join()
            } finally {
                forwarding = false
                selection.locked = false
            }
        }
    }

    /** This view is gone (the room was left): a forward's line is said app-wide. Main thread. */
    private var cleared = false

    override fun onCleared() {
        cleared = true
    }

    // ---------------------------------------------------------------- chip menu

    fun setNotificationLevel(id: Long, level: Int) {
        viewModelScope.launch {
            try {
                topics.setNotificationLevel(id, level)
            } catch (e: TalkError) {
                notice = "The notification setting wasn't saved. " + e.userMessage()
            }
        }
    }

    /** Rename a topic. Returns null when done, else what to tell the user (the server decides who may). */
    suspend fun rename(id: Long, title: String): String? = try {
        topics.rename(id, title)
        null
    } catch (e: TalkError) {
        when {
            e is TalkError.Forbidden -> "The server refused: only the topic's author or a moderator can rename it."
            e is TalkError.Http && e.status == 400 -> "The server refused this title."
            else -> e.userMessage()
        }
    }

    /** "Mark as read" on a chip: everything in that tab counts as read, without opening it. */
    fun markRead(target: RoomTab) {
        if (target == RoomTab.All) return
        val newest = (target as? RoomTab.Topic)?.let { t -> topics.topic(t.id)?.let { maxOf(it.thread.lastMessageId, it.id) } } ?: 0L
        // On the session's lifetime: leaving right after must still count.
        session.launch { unread.markAllSeen(target.scope, newest) }
    }

    private companion object {
        const val KEY_TAB = "tab"
    }
}
