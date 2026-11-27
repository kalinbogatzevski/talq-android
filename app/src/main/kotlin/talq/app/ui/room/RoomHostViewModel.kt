package talq.app.ui.room

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import talq.app.data.RoomTabStore
import talq.app.nav.RoomRoute
import talq.app.ui.chat.ComposerDrafts
import talq.app.ui.chat.MessageSelection
import talq.app.ui.chat.deleteOffered
import talq.app.ui.chat.deleteStoppedText
import talq.app.ui.chat.forwardResultText
import talq.app.ui.chat.mayDelete
import talq.app.ui.chat.resolveMentionNames
import talq.app.ui.chat.sayAnywhere
import talq.app.ui.common.userMessage
import talq.app.ui.topics.topicUnread
import talq.core.TalkError
import talq.core.UploadedFile
import talq.core.model.ChatMessage
import talq.core.model.ForwardPlan
import talq.core.model.TalkThreadInfo
import talq.core.model.editableText
import talq.core.model.forwardPlan
import talq.core.model.messageOver
import talq.core.repo.ChatRepository
import talq.core.repo.ChatScope
import talq.core.repo.OutboxMessage
import talq.core.repo.OutboxStatus
import talq.core.repo.OutboxStop
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
 *
 * A topic being started ([draft]) is kept in the session's `ComposerDrafts` like any unsent text: with
 * its title and its first message it waits through Back, another chat, a tap on another chip ([onDraft]
 * tells whether it is what the room shows), a rotation, and (through [saved], for the open room) the
 * end of the process. Nothing is created until its first message is sent. One that FAILED keeps its
 * failed message and Retry, or goes again by itself like any text (the outbox reads the chat for the
 * earlier attempt first, also in the app's next run); text edited after a failure is sent only once the
 * chat was read for it ([ComposerDrafts.NewTopic.tried]), so a lost answer never makes a second topic.
 */
class RoomHostViewModel(
    val session: TalkSession,
    val route: RoomRoute,
    private val tabStore: RoomTabStore,
    private val drafts: ComposerDrafts,
    /** The application's context (never an activity's): for a line that comes after the room was left. */
    private val appContext: Context,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    val token: String = route.token
    val topics: TopicsRepository = session.topics(token)
    val unread: UnreadTracker = session.unread(token)

    /** The whole chat: the host keeps it started so the room's live feed never stops between tabs. */
    val wholeChat: ChatRepository = session.chat(token, ChatScope.All)

    private val account: String = ComposerDrafts.accountOf(session)

    /** Where the topic being started is kept. */
    private val topicKey = ComposerDrafts.Key(account, token, RoomTab.KEY_NEW_TOPIC)

    /** What was stored for this room when it opened; non-null also means "it had topics then". */
    var rememberedAtOpen: Long? = tabStore.peek(account, token)
        private set

    /**
     * The tab the user wants. Null only for the moment the remembered tab is still being read
     * from disk. A room without topics shows the whole chat whatever this says; it is never the
     * whole chat itself ([RoomTab.fromKey]).
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
     * A message to go to in a tab, asked for while the room was already open (a notification, "Show in
     * chat", a message that turned out to be in another tab); the tab's pane clears it once it looks for it.
     */
    var jump: Pair<RoomTab, Long>? by mutableStateOf(null)

    /** Topic ids in strip order. Fixed once taken; later topics join at the front. */
    var order: List<Long> by mutableStateOf(emptyList())
        private set
    private var orderTaken = false
    private var orderSettled = false
    private var userSwitched = false

    /**
     * The topic list was read while this room is open and held none: it has been one whole chat here. When
     * it gets its first topic, General goes on in the same pane (the place in the chat, the keyboard, the
     * box) instead of opening afresh. Kept for the open room, as the pane's own saved state is.
     */
    var wasWholeChat: Boolean by mutableStateOf(saved.get<Boolean>(KEY_WHOLE_CHAT) == true)
        private set

    var pulling by mutableStateOf(false)
        private set

    /** A one-line message for the snackbar; the screen clears it once shown. */
    var notice by mutableStateOf<String?>(null)

    /** A topic that has a title but no confirmed first message yet (see the header). */
    val draft: ComposerDrafts.NewTopic? get() = drafts.newTopic(topicKey)

    /** The draft is what the room shows (else it waits as a chip beside the tabs). */
    val onDraft: Boolean get() = draft?.selected == true

    /** The first message being typed for [draft]. */
    var draftText: String
        get() = drafts.text(topicKey)
        set(value) {
            if (draft != null) drafts.setText(topicKey, value)
        }

    /** How far the first message is over the server's limit: it waits then, and is never cut. */
    val draftOver: Int get() = messageOver(draftText.trim(), session.capabilities.maxMessageLength.takeIf { it > 0 } ?: 32_000)

    /** The chat is being read for an earlier attempt before the first message goes out again. */
    val draftBusy: Boolean get() = drafts.scheduling(topicKey)

    private var refreshJob: Job? = null

    /** One [settleTopics] at a time. */
    private val settling = Mutex()

    /** Messages picked in the tab on screen, for Copy, Forward and Delete. Switching tabs ends it. */
    val selection = MessageSelection()

    /** A forward is on its way: the Forward button shows progress and takes nothing more. */
    var forwarding by mutableStateOf(false)
        private set

    init {
        drafts.opened(topicKey)
        // The first time this entry is shown (not the same one back after the process or the screen was ended).
        val fresh = saved.get<Boolean>(KEY_OPENED) != true
        saved[KEY_OPENED] = true
        val given = route.tab != RoomRoute.REMEMBERED && route.tab != RoomTab.KEY_NEW_TOPIC
        // Made while the room was closed: no draft any more. The room opens on it when nothing else was asked for.
        drafts.madeTopic(topicKey)?.let { made ->
            drafts.endTopic(topicKey)
            if (fresh && made.selected && !given) {
                tab = RoomTab.Topic(made.createdId)
                openedOn = tab
                saved[KEY_TAB] = made.createdId
            }
        }
        // Android ended the process with the draft in this room. Its first message, if it was on its way
        // or had failed, is kept in the outbox and goes by its rules; when it is not there the text is back
        // in the box and the key is kept, so the chat is read for it first.
        if (!drafts.has(topicKey)) saved.get<String>(KEY_NEW_TITLE)?.let { title ->
            drafts.unpackNames(saved.get<ArrayList<String>>(KEY_NEW_NAMES))
            val shown = saved.get<Boolean>(KEY_NEW_SHOWN) != false
            drafts.startTopic(
                topicKey,
                ComposerDrafts.NewTopic(title, selected = shown, tried = saved.get<String>(KEY_NEW_REF)),
                saved.get<String>(KEY_NEW_TEXT).orEmpty(),
            )
        }
        fun held(ref: String) = ref in wholeChat.createdTopics.value || wholeChat.state.value.outbox.any { it.referenceId == ref }
        draft?.tried?.let { ref ->
            if (held(ref)) {
                drafts.changeTopic(topicKey) { it.copy(sending = ref, tried = null) }
                noteCreated(ref)
            }
        }
        draft?.sending?.let { ref -> if (!held(ref)) drafts.changeTopic(topicKey) { it.copy(sending = null, tried = ref) } }
        // Opened from its "Draft:" line: on the draft. On a tab that was asked for: on that. Else as it was left.
        if (fresh && route.tab == RoomTab.KEY_NEW_TOPIC) drafts.changeTopic(topicKey) { it.copy(selected = true) }
        if (fresh && given) drafts.changeTopic(topicKey) { it.copy(selected = false) }
        viewModelScope.launch {
            snapshotFlow { draft to draftText }.collect { (topic, text) ->
                saved[KEY_NEW_TITLE] = topic?.title
                saved[KEY_NEW_TEXT] = ComposerDrafts.savable(text)
                saved[KEY_NEW_REF] = topic?.let { it.sending ?: it.tried }
                saved[KEY_NEW_SHOWN] = topic?.selected != false
                saved[KEY_NEW_NAMES] = drafts.packNames(text)
            }
        }
        // A mention in it whose name is not known: asked for once.
        viewModelScope.launch {
            snapshotFlow { drafts.unnamed(listOf(draftText)).isNotEmpty() }.first { it }
            resolveMentionNames(session, token, drafts, listOf(draftText))
        }
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
        // Other topics than before, also when the list was reloaded by itself (the live feed said that the
        // history was deleted): as after a reload of ours.
        viewModelScope.launch {
            topics.topics.map { list -> list.map { it.id }.toSet() }.distinctUntilChanged().drop(1).collect {
                if (topics.state.value.loaded) settleTopics()
            }
        }
        // The server confirmed a draft's first message: the draft is now that topic.
        viewModelScope.launch {
            snapshotFlow { drafts.madeTopic(topicKey) }.collect { made ->
                if (made == null) return@collect
                drafts.endTopic(topicKey)
                if (made.selected) select(RoomTab.Topic(made.createdId))
            }
        }
        // An earlier attempt turns up in the chat: it had reached the server.
        viewModelScope.launch {
            combine(snapshotFlow { draft?.tried }, wholeChat.state) { ref, s ->
                if (ref == null) null else s.messages.firstOrNull { it.referenceId == ref }
            }.collect { if (it != null) arrived(it) }
        }
    }

    /** Switch the strip to [to], in place. A topic being started waits as its chip (see the header). */
    fun select(to: RoomTab) {
        userSwitched = true
        drafts.changeTopic(topicKey) { it.copy(selected = false) }
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
                if (session.supportsTopics && topics.refresh()) settleTopics()
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

    /** What opening the room did not get: its list of topics, and what else of [refresh] is missing. Not on the caller's thread. */
    suspend fun loadMissing() {
        if (!session.rooms.state.value.loaded) withContext(Dispatchers.Default) { session.rooms.refresh() }
        if (session.supportsTopics && withContext(Dispatchers.Default) { topics.refresh() }) settleTopics()
        if (!unread.state.value.loaded) withContext(Dispatchers.Default) { unread.refresh() }
    }

    /**
     * A list of topics has come (see also [wasWholeChat]). A topic this room no longer has is let go of, by the
     * server's word (404): a room open on it shows General, and what was typed there, or sent there and refused,
     * goes into the room's own box. One that is only past the 50 the server lists, or newer than the list, stays.
     * A reply in it is not kept: its message went with the topic.
     */
    private suspend fun settleTopics() {
        settling.withLock {
            if (topics.topics.value.isEmpty()) {
                wasWholeChat = true
                saved[KEY_WHOLE_CHAT] = true
            }
            val held = listOfNotNull((tab as? RoomTab.Topic)?.id) +
                drafts.textTabs(account, token).mapNotNull { (RoomTab.fromKey(it) as? RoomTab.Topic)?.id } +
                failedInTopics().map { it.threadId }
            for (id in held.distinct()) {
                if (topics.topic(id) != null || !topicGone(id)) continue
                val moved = moveToRoom(id)
                val where = if (topics.topics.value.isEmpty()) "this chat" else "General"
                val open = tab == RoomTab.Topic(id)
                if (moved) notice = "That topic is gone; its draft is in $where." else if (open) notice = "That topic is gone."
                if (open) {
                    val draftShown = onDraft
                    // Not the user's doing: the strip's order is still taken again once the counts are in.
                    val switched = userSwitched
                    select(RoomTab.General)
                    userSwitched = switched
                    if (draftShown) showDraft()
                }
            }
        }
    }

    /** The room's texts that were not sent into a topic (the whole chat's outbox has every text of the room). */
    private fun failedInTopics(): List<OutboxMessage> =
        wholeChat.state.value.outbox.filter { it.status == OutboxStatus.FAILED && it.threadId != 0L }

    /** What topic [id] (gone) still holds goes into the room's own box. False when nothing was moved. */
    private fun moveToRoom(id: Long): Boolean {
        val box = draftKey(RoomTab.General)
        // A send later has that box now: with the next list.
        if (drafts.scheduling(box)) return false
        var moved = false
        for (unsent in failedInTopics()) {
            if (unsent.threadId != id) continue
            // On its way again, or found in the chat by now: it stays what it is.
            if (!wholeChat.discard(unsent.referenceId)) continue
            drafts.setText(box, ComposerDrafts.mergeRestoredText(drafts.text(box), unsent.text))
            moved = true
        }
        val from = draftKey(RoomTab.Topic(id))
        if (drafts.holds(from)) {
            drafts.moveText(from, box)
            if (!drafts.holds(from)) moved = true
        }
        return moved
    }

    /** The server says there is no such topic (`thread`; without that word it is the conversation that is not there). */
    private suspend fun topicGone(id: Long): Boolean = try {
        session.client.thread(token, id)
        false
    } catch (e: TalkError) {
        e is TalkError.NotFound && e.detail == "thread"
    }

    private fun draftKey(tab: RoomTab) = ComposerDrafts.Key(account, token, tab.boxKey)

    // ---------------------------------------------------------------- draft topic

    /** "+" was given a title: show the draft chip. Nothing is sent yet. One per room: an earlier one is shown instead. */
    fun startDraft(title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        selection.clear()
        if (draft != null) drafts.changeTopic(topicKey) { it.copy(selected = true) }
        else drafts.startTopic(topicKey, ComposerDrafts.NewTopic(clean))
    }

    /** The draft's chip was tapped: back to it. */
    fun showDraft() {
        selection.clear()
        drafts.changeTopic(topicKey) { it.copy(selected = true) }
    }

    /** Drop the draft, text and all (its X). A first message that already failed to send is given up too. */
    fun discardDraft() {
        val current = draft ?: return
        if (draftBusy) return
        // On its way: it can no longer be dropped.
        current.sending?.let { ref -> if (!wholeChat.discard(ref)) return }
        drafts.endTopic(topicKey)
    }

    /** Send the draft's first message: this is what creates the topic. */
    fun sendDraft() {
        val current = draft ?: return
        if (draftText.isBlank() || draftOver > 0 || current.sending != null || draftBusy) return
        if (current.tried == null) sendOff() else afterReadingTheChat { loaded ->
            when {
                // Not created, or no longer there: the earlier attempt arrived, or the room was left.
                draft?.tried != current.tried -> {}
                loaded -> sendOff()
                else -> say(notSent())
            }
        }
    }

    private fun sendOff() {
        val current = draft ?: return
        val text = draftText.trim()
        if (text.isEmpty() || current.sending != null) return
        val ref = wholeChat.send(text, threadTitle = current.title)
        drafts.changeTopic(topicKey) { it.copy(sending = ref, tried = null) }
        noteCreated(ref)
    }

    /** Once the first message sent as [ref] has made its topic, the draft is that topic. */
    private fun noteCreated(ref: String) {
        // On the session's scope: the topic may be made after the room was left (a Retry keeps the key).
        // It waits for as long as the message is not made, so it must not hold this view.
        val repo = wholeChat
        val store = drafts
        val key = topicKey
        session.launch {
            val id = repo.createdTopics.mapNotNull { it[ref] }.first()
            withContext(Dispatchers.Main) { store.topicCreated(key, ref, id) }
        }
    }

    /** Try the failed first message again (the outbox first reads the chat for it). */
    fun retryDraft() {
        val ref = draft?.sending ?: return
        if (!draftBusy) wholeChat.retry(ref)
    }

    /**
     * Read the room's newest page, look in it for an earlier attempt of the first message, then run
     * [then] (main thread; whether the page was read). The box is read-only meanwhile. On the session's
     * scope: leaving the room does not cut it off.
     */
    private fun afterReadingTheChat(then: (loaded: Boolean) -> Unit) {
        drafts.setScheduling(topicKey, true)
        session.launch {
            try {
                val loaded = wholeChat.refresh()
                withContext(Dispatchers.Main) {
                    drafts.setScheduling(topicKey, false)
                    draft?.tried?.let { ref -> wholeChat.state.value.messages.firstOrNull { it.referenceId == ref } }?.let { arrived(it) }
                    then(loaded)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { drafts.setScheduling(topicKey, false) }
            }
        }
    }

    /** [m] is an earlier attempt of the first message: the draft is that topic; text changed since goes into its box. */
    private fun arrived(m: ChatMessage) {
        val ref = draft?.tried ?: return
        if (m.referenceId != ref) return
        val id = m.topicId.takeIf { it != 0L } ?: m.id
        val typed = draftText
        if (typed.trim() != editableText(m)?.trim()) {
            val box = draftKey(RoomTab.Topic(id))
            drafts.setText(box, ComposerDrafts.mergeRestoredText(drafts.text(box), typed))
        }
        drafts.topicCreated(topicKey, ref, id)
    }

    /** Give up the failed first message but keep the draft, with the text back in the field. */
    fun editDraftAgain() {
        val ref = draft?.sending ?: return
        if (draftBusy) return
        val entry = wholeChat.state.value.outbox.firstOrNull { it.referenceId == ref && it.status == OutboxStatus.FAILED } ?: return
        if (!wholeChat.discard(ref)) return
        // Refused outright: nothing arrived. Anything else may have, and only its answer was lost (a 400
        // can follow a message that was stored).
        val refused = entry.stop == OutboxStop.REFUSED && (entry.error as? TalkError.Http)?.status != 400
        drafts.changeTopic(topicKey) { it.copy(sending = null, tried = if (refused) null else ref) }
    }

    /** The chat could not be read first: the first message stays where it is. */
    private fun notSent(): String =
        if (wholeChat.state.value.error is TalkError.Network) "Not sent: can't reach the server." else "Not sent: the chat could not be read."

    /** One line about the draft, in the room or (left meanwhile) app-wide. Main thread. */
    private fun say(line: String) {
        if (cleared) sayAnywhere(appContext, line) else notice = line
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
        if (forwarding || deleting) return
        val picked = selection.messages()
        if (picked.isEmpty()) {
            selection.clear()
            return
        }
        forwarding = true
        selection.locked = true
        session.roomActions.wroteIn(target)
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

    // ---------------------------------------------------------------- delete what is picked

    /** "Delete N messages?" is asked about what is picked. Kept across a rotation. */
    var confirmDelete by mutableStateOf(false)

    /** The picked messages are being deleted: the Delete button shows progress and nothing else starts. */
    var deleting by mutableStateOf(false)
        private set

    /** The selection bar has a Delete in this conversation. */
    val hasDelete: Boolean get() = deleteOffered(session, token)

    /** Every picked message may be deleted by this user. */
    val canDeletePicked: Boolean get() = selection.messages().let { it.isNotEmpty() && it.all { m -> mayDelete(session, token, m) } }

    /**
     * Delete what is picked (the question was answered with Delete), one message after another in chat
     * order, as the message menu deletes one (desktop's selection bar sends them all at once). Each shows
     * "Message deleted" and drops out of the picked ones as the server answers. Stops at the first refusal
     * and says in one line how far it got; what is left stays picked. On the session's scope, the line
     * included: leaving the room does not cut it off (the line is then said app-wide).
     */
    fun deletePicked() {
        confirmDelete = false
        if (deleting || forwarding || selection.locked) return
        val picked = selection.messages()
        if (picked.isEmpty()) return
        if (!canDeletePicked) {
            // Too old by now, or the user's rights changed while the question was up.
            notice = "Not deleted: some of these can't be deleted."
            return
        }
        deleting = true
        selection.locked = true
        val run = session.launch {
            var done = 0
            for (m in picked) {
                val failure = wholeChat.delete(m.id)
                if (failure != null) {
                    val line = deleteStoppedText(picked.size, done, failure)
                    withContext(Dispatchers.Main) { say(line) }
                    return@launch
                }
                done++
                // Nothing left to save or to reply to.
                withContext(Dispatchers.Main) { drafts.messageGone(account, token, m.id) }
            }
            withContext(Dispatchers.Main) { if (!cleared) selection.clear() }
        }
        viewModelScope.launch {
            try {
                run.join()
            } finally {
                deleting = false
                selection.locked = false
            }
        }
    }

    /** This view is gone (the room was left): a forward's or a delete's line is said app-wide. Main thread. */
    private var cleared = false

    override fun onCleared() {
        cleared = true
        drafts.closed(topicKey)
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
        val newest = (target as? RoomTab.Topic)?.let { t -> topics.topic(t.id)?.let { maxOf(it.thread.lastMessageId, it.id) } } ?: 0L
        // On the session's lifetime: leaving right after must still count.
        session.launch { unread.markAllSeen(target.scope, newest) }
    }

    private companion object {
        const val KEY_TAB = "tab"
        const val KEY_WHOLE_CHAT = "whole-chat"
        const val KEY_OPENED = "opened"
        const val KEY_NEW_TITLE = "new-topic-title"
        const val KEY_NEW_TEXT = "new-topic-text"
        const val KEY_NEW_REF = "new-topic-ref"
        const val KEY_NEW_SHOWN = "new-topic-shown"
        const val KEY_NEW_NAMES = "new-topic-names"
    }
}
