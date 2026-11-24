package talq.app.ui.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import talq.app.ui.room.RoomTab
import talq.app.ui.common.userMessage
import talq.core.TalkError
import talq.core.model.ChatMessage
import talq.core.model.MessageRefusal
import talq.core.model.MessageRequest
import talq.core.model.canDeleteMessage
import talq.core.model.canEditMessage
import talq.core.model.classifyMessageRefusal
import talq.core.model.editableText
import talq.core.model.mentionNamesOf
import talq.core.model.classifyScheduleFailure
import talq.core.model.keepReplyAfterRefusal
import talq.core.model.statusAndCode
import talq.core.repo.ChatRepository
import talq.core.repo.ChatScope
import talq.core.repo.TalkSession
import talq.core.repo.TopicsRepository
import talq.core.repo.UnreadTracker

/**
 * State of one tab of an open conversation (whole chat, General or a topic) that is not
 * already held by the core repositories: how far the user has read, and the tab's box. One per
 * tab, keyed `chat-<token>-<tab key>` in the room's navigation entry. The draft and the reply
 * target live in the session's [ComposerDrafts], so they wait in the tab when the room is left.
 */
class ChatViewModel(
    val session: TalkSession,
    val token: String,
    val tab: RoomTab,
    /** The application's context (never an activity's): for answers that arrive after the room was left. */
    private val appContext: Context,
    private val drafts: ComposerDrafts,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    val scope: ChatScope = tab.scope
    val repo: ChatRepository = session.chat(token, scope)
    val topics: TopicsRepository = session.topics(token)
    val unread: UnreadTracker = session.unread(token)

    /** Topic id for a topic view, 0 for General, null for the whole chat. */
    val bucket: Long? = tab.bucket

    /**
     * The last message that was read when this tab was first shown: the "New messages" divider
     * goes right after it. Fixed for the life of the tab so the divider does not jump around.
     */
    var dividerAfter by mutableLongStateOf(dividerFor(session.rooms.room(token)?.lastReadMessage ?: 0L))
        private set

    /**
     * The room (and so its read marker) is known. False only when the tab was opened before
     * the rooms list had answered (a notification tap on a cold start): the divider is set
     * when it does, and the pane waits for that before it settles on a position.
     */
    var roomKnown by mutableStateOf(session.rooms.room(token) != null)
        private set

    /** Newest message the user has had on screen ([onSeen]); drives the jump-to-bottom count. */
    var seenUpTo by mutableLongStateOf(dividerAfter)
        private set

    private fun dividerFor(lastRead: Long): Long =
        if (lastRead == 0L) 0L else maxOf(lastRead, unread.state.value.seenUpTo(bucket ?: -1L))

    init {
        if (!roomKnown) {
            viewModelScope.launch {
                val room = session.rooms.rooms.mapNotNull { list -> list.firstOrNull { it.token == token } }.first()
                dividerAfter = dividerFor(room.lastReadMessage)
                if (seenUpTo < dividerAfter) seenUpTo = dividerAfter
                roomKnown = true
            }
        }
    }

    private val draftKey = ComposerDrafts.Key(ComposerDrafts.accountOf(session), token, tab.key)

    init {
        drafts.opened(draftKey)
        // Android ended the process while the room was open: the text comes back from the saved
        // state, unless the session already holds a draft for the tab.
        if (!drafts.has(draftKey)) saved.get<String>(KEY_DRAFT)?.takeIf { it.isNotEmpty() }?.let { drafts.setText(draftKey, it) }
        // The saved copy follows the box, also when an answer empties it (a scheduled text must not come back).
        viewModelScope.launch { snapshotFlow { drafts.text(draftKey) }.collect { saved[KEY_DRAFT] = it } }
    }

    /** What is typed but not sent, kept in its tab until it is sent (see [ComposerDrafts]). */
    var draft: String
        get() = drafts.text(draftKey)
        set(value) = drafts.setText(draftKey, value)

    var replyTo: ChatMessage?
        get() = drafts.replyTo(draftKey)
        set(value) = drafts.setReplyTo(draftKey, value)

    /** The "@" suggestions of this tab's box (see Mentions.kt). */
    val mentions = MentionSuggester(session, token, viewModelScope, drafts)

    /** A one-line message for the snackbar; the screen clears it once shown. */
    var notice by mutableStateOf<String?>(null)

    val maxLength: Int = session.capabilities.maxMessageLength.takeIf { it > 0 } ?: 32_000

    fun isMe(m: ChatMessage): Boolean = session.isMe(m.actorType, m.actorId)

    /** [silent]: no one is notified (only where the server has `silent-send`). While editing this saves the edit. */
    fun send(silent: Boolean = false) {
        if (editing != null) {
            saveEdit()
            return
        }
        if (scheduling) return
        val text = draft.trim()
        if (text.isEmpty()) return
        repo.send(text, replyTo, silent = silent && session.capabilities.silentSend)
        draft = ""
        replyTo = null
    }

    /**
     * A "send later" request is on its way: the send button shows progress and takes nothing more.
     * Held with the draft, so the tab opened again while it runs is read-only too.
     */
    val scheduling: Boolean get() = drafts.scheduling(draftKey)

    /**
     * "Send later": have the server post what is in the box at [sendAt] (Unix seconds); [whenLabel]
     * is that time as the user was shown it. Ported from TalQ for desktop's ComposerWidget::scheduleAt
     * and MessageListModel::scheduleMessage, with one difference: the box and the reply are NOT
     * emptied first and handed back on a refusal. They stay as they are while the request runs (the
     * box is read-only then) and are cleared only once the server has taken the message, so a
     * refusal leaves the user's text where it was and says why in one line. A refusal about the
     * reply itself drops the reply, as desktop does (the same reply would be refused again).
     * Never scheduled silently, as on desktop. The request and its answer outlive this view: they act
     * on the tab's stored box, and once the room was left the answer is said app-wide; a refused text
     * is then still the tab's draft (desktop's RestoreTarget::RoomDraft).
     */
    fun schedule(sendAt: Long, whenLabel: String) {
        if (scheduling || editing != null) return
        val text = draft.trim()
        val refused = when {
            !session.capabilities.scheduledMessages -> "Not scheduled: the server doesn't offer it."
            text.isEmpty() -> "Not scheduled: type a message first."
            text.length > maxLength -> "Not scheduled: the message is too long."
            sendAt < System.currentTimeMillis() / 1000 + MIN_LEAD_SECONDS -> "Not scheduled: pick a time at least a minute ahead."
            else -> null
        }
        if (refused != null) {
            notice = refused
            return
        }
        val quote = replyTo
        val topic = repo.topicFor(quote)
        drafts.setScheduling(draftKey, true)
        // On the session's scope, the answer included: leaving the room must neither cut the
        // request off halfway nor lose what the server said. The session ending (signed out)
        // under it cancels it, and then nothing is said. The flag is the request's own: it ends
        // with the request, not with this view.
        session.launch {
            try {
                var failure: TalkError? = null
                try {
                    session.client.scheduleMessage(token, text, sendAt, replyTo = quote?.id ?: 0L, threadId = topic)
                } catch (e: TalkError) {
                    failure = e
                }
                withContext(Dispatchers.Main) { scheduled(text, quote, whenLabel, failure) }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { drafts.setScheduling(draftKey, false) }
            }
        }
    }

    /** This view is gone (the room was left): answers that arrive later are said app-wide. Main thread. */
    private var cleared = false

    override fun onCleared() {
        cleared = true
        drafts.closed(draftKey)
        // Left before the list had rested: what was on screen still counts (viewModelScope is
        // already cancelled here, the pass runs on the session's).
        if (passPending) pass()
    }

    /**
     * The server's answer to [schedule] (main thread): [failure] null = scheduled. It acts on the
     * stored box whether or not this view is still there.
     */
    private fun scheduled(text: String, quote: ChatMessage?, whenLabel: String, failure: TalkError?) {
        if (failure == null) {
            if (draft.trim() == text) draft = ""
            if (replyTo == quote) replyTo = null
            val line = "Scheduled for $whenLabel"
            if (cleared) sayAnywhere(appContext, line) else notice = line
            return
        }
        val (status, code) = failure.statusAndCode()
        val why = classifyScheduleFailure(status, code)
        val line = scheduleRefusalText(why, status)
        // The box was read-only meanwhile, so the text is normally still there; if not, it goes
        // back after what the box holds now (desktop's mergeRestoredText).
        if (draft.trim() != text) draft = ComposerDrafts.mergeRestoredText(draft, text)
        if (!keepReplyAfterRefusal(why) && replyTo == quote) replyTo = null
        // Desktop: "Your message was kept as a draft in that conversation."
        if (cleared) sayAnywhere(appContext, "$line Kept as a draft.") else notice = line
    }

    // ---------------------------------------------------------------- editing and deleting own messages
    //
    // Ported from TalQ for desktop's message menu (Edit / Delete on own messages), beginEditingMessage,
    // the composer's editing bar and MessageListModel::editMessage / deleteMessage; rules in
    // talq.core.model MessageEdits.kt. Both requests run on the session's scope and act on the tab's
    // stored box, as "send later" does: leaving the room neither cuts them off nor loses the answer.

    /** The sent message being edited in this tab's box, or null. */
    val editing: ChatMessage? get() = drafts.edit(draftKey)?.message

    /** The box while [editing]: the edit's own text (the draft waits beside it). */
    var editText: String
        get() = drafts.edit(draftKey)?.text.orEmpty()
        set(value) = drafts.setEditText(draftKey, value)

    /** The edit's save is on its way: the box is read-only and Save shows progress. */
    val saving: Boolean get() = drafts.edit(draftKey)?.saving == true

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    /** "Edit" may be offered for [m]: the user's own text, still within the server's time. */
    fun canEdit(m: ChatMessage): Boolean {
        val room = session.rooms.room(token) ?: return false
        return canEditMessage(m, isMe(m), session.capabilities, room.type, room.isReadOnly, nowSeconds())
    }

    /** "Delete" may be offered for [m]. */
    fun canDelete(m: ChatMessage): Boolean {
        val room = session.rooms.room(token) ?: return false
        return canDeleteMessage(m, isMe(m), session.capabilities, room.isReadOnly, nowSeconds())
    }

    /**
     * Open [m] in the box: its raw text with the mentions put back (desktop's rawBodyFor), the
     * cursor at the end. The draft and its reply wait. The same message again keeps what was typed;
     * another edit that was changed and not saved is never replaced (one line says so; an unchanged
     * one is). False when nothing was opened (not editable, or a send later / save is on its way).
     */
    fun startEdit(m: ChatMessage): Boolean {
        if (scheduling || saving) return false
        val open = drafts.edit(draftKey)
        if (open?.message?.id == m.id) return true
        if (open != null && open.text.trim() != editableText(open.message)?.trim()) {
            notice = "Finish or cancel the edit first."
            return false
        }
        val text = editableText(m) ?: return false
        // Its mentions show by name in the box, as in the message.
        drafts.nameMentions(mentionNamesOf(m))
        drafts.startEdit(draftKey, m, text)
        return true
    }

    /** Cancel / Esc / Back: the draft is the box again. Not while the save runs. */
    fun cancelEdit() {
        if (!saving) drafts.endEdit(draftKey)
    }

    /**
     * Save the edit (`PUT /chat/{token}/{id}`). The box stays as it is, read-only, until the server
     * answers: saved, the edit closes and the message shows its new text in every tab; refused, the
     * text stays in the box and one line says why. Unchanged text closes the edit without a request.
     */
    fun saveEdit() {
        val edit = drafts.edit(draftKey) ?: return
        if (edit.saving) return
        val m = edit.message
        val text = edit.text.trim()
        if (text.isEmpty()) return
        if (text == editableText(m)?.trim()) {
            drafts.endEdit(draftKey, m.id)
            return
        }
        if (text.length > maxLength) {
            notice = messageRefusalText(MessageRequest.Edit, MessageRefusal.TooLong, 413)
            return
        }
        drafts.setEditSaving(draftKey, m.id, true)
        session.launch {
            try {
                val failure = repo.edit(m.id, text)
                withContext(Dispatchers.Main) { edited(m, failure) }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { drafts.setEditSaving(draftKey, m.id, false) }
            }
        }
    }

    private fun edited(m: ChatMessage, failure: TalkError?) {
        if (failure == null) {
            drafts.endEdit(draftKey, m.id)
            return
        }
        val (status, code) = failure.statusAndCode()
        val line = messageRefusalText(MessageRequest.Edit, classifyMessageRefusal(MessageRequest.Edit, status, code), status)
        // The edit stays open in its tab with the text, also when the room was left.
        if (cleared) sayAnywhere(appContext, line) else notice = line
    }

    /** The message "Delete this message?" is asked about, null when not asked. Kept across a rotation. */
    var confirmDelete by mutableStateOf<ChatMessage?>(null)

    /** The delete is on its way: the question stays up with its button showing progress. */
    var deleting by mutableStateOf(false)
        private set

    /** Delete [m] (`DELETE /chat/{token}/{id}`); once the server has, it reads "Message deleted" in every tab. */
    fun delete(m: ChatMessage) {
        if (deleting) return
        deleting = true
        session.launch {
            try {
                val failure = repo.delete(m.id)
                withContext(Dispatchers.Main) { deletedAnswer(m, failure) }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    deleting = false
                    confirmDelete = null
                }
            }
        }
    }

    private fun deletedAnswer(m: ChatMessage, failure: TalkError?) {
        if (failure == null) {
            // Nothing left to save or to reply to.
            drafts.endEdit(draftKey, m.id)
            if (replyTo?.id == m.id) replyTo = null
            return
        }
        val (status, code) = failure.statusAndCode()
        val line = messageRefusalText(MessageRequest.Delete, classifyMessageRefusal(MessageRequest.Delete, status, code), status)
        if (cleared) sayAnywhere(appContext, line) else notice = line
    }

    /** The newest room entry [onSeen] was given (see there). */
    private var seenRoomLast = 0L

    /** What was last handed to [UnreadTracker.markSeen], so a pass that adds nothing sends nothing. */
    private var passedUpTo = 0L
    private var passedRoomLast = 0L

    /** A pass waits for the list to rest ([READ_SETTLE_MILLIS]); true while one waits. */
    private var passPending = false
    private var passJob: Job? = null

    /**
     * Messages of this tab up to [upTo] have been on screen (the ones above it were passed on the
     * way down: they count too). Only forward. The jump-to-bottom count follows at once; the read
     * marker follows once the list has rested a moment ([settle], while scrolling through a backlog)
     * or at once (at the bottom). [roomLastId], at the bottom only, is the room's last entry as the
     * conversation list says: a reaction or system notice arriving while the chat is open at the
     * bottom changes only that (the newest message on screen stays the same), and in a one-to-one
     * room Talk counts it as unread.
     */
    fun onSeen(upTo: Long, roomLastId: Long = 0L, settle: Boolean = false) {
        if (upTo <= seenUpTo && roomLastId <= seenRoomLast) return
        if (upTo > seenUpTo) seenUpTo = upTo
        seenRoomLast = maxOf(seenRoomLast, roomLastId)
        passJob?.cancel()
        passJob = null
        if (!settle) {
            pass()
            return
        }
        passPending = true
        passJob = viewModelScope.launch {
            delay(READ_SETTLE_MILLIS)
            pass()
        }
    }

    /** Count [seenUpTo] read in the tracker and, as far as allowed, on the server. */
    private fun pass() {
        passPending = false
        val upTo = seenUpTo
        if (upTo <= passedUpTo && seenRoomLast <= passedRoomLast) return
        passedUpTo = upTo
        passedRoomLast = seenRoomLast
        // On the session's lifetime, not this view's: leaving right after reading must still count.
        session.launch { unread.markSeen(scope, upTo) }
    }

    fun loadOlder() {
        viewModelScope.launch { repo.loadOlder() }
    }

    fun retryLoad() {
        viewModelScope.launch { repo.refresh() }
    }

    fun toggleReaction(messageId: Long, emoji: String) {
        viewModelScope.launch {
            repo.toggleReaction(messageId, emoji)?.let { notice = "The reaction wasn't saved. " + it.userMessage() }
        }
    }

    private companion object {
        const val KEY_DRAFT = "draft"

        /** A scheduled time must be at least this far ahead (TalQ for desktop's time picker minimum). */
        const val MIN_LEAD_SECONDS = 60L

        /** How long the list must rest before what is on screen is passed on as read. */
        const val READ_SETTLE_MILLIS = 1_000L
    }
}
