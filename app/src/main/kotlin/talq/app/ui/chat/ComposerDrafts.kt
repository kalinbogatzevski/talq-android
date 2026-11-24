package talq.app.ui.chat

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import talq.app.SessionState
import talq.core.model.ChatMessage
import talq.core.model.readableMentions
import talq.core.repo.TalkSession

/**
 * Unsent composer text of the signed-in session, per conversation tab. Ported from TalQ for desktop's
 * drafts (`MainWindow::stashComposerDraftOnLeave` / `takeDraftOnEnter`, and `isUnsentText`,
 * `mergeRestoredText`, `dropDraftsForUnlistedRooms` in `src/core/UpdateInstallGatePolicy.h`).
 *
 * - One entry per account + conversation + tab (`RoomTab.key`): the text, the message it replies to,
 *   and whether a "send later" request for it is on its way. It is the truth for the open tab as
 *   well (`ChatViewModel` reads and writes it here), so an answer that arrives after the room was
 *   left, or opened again, acts on the box the user sees, and the reopened box stays read-only while
 *   the request runs.
 * - In memory only, as on desktop, which keeps drafts out of storage on purpose (unsent text would
 *   outlive the session there). The open tab's text also rides in its saved state, so the room on
 *   screen keeps it when Android ends the process in the background; other rooms' drafts do not.
 * - Emptied at sign-out (both paths in `AppContainer`), as desktop does on logout.
 * - A conversation that is no longer in the list loses its drafts, but only after a list answer
 *   (never before the first one) and never while one of its tabs is open: an open box is not touched.
 * - Spaces and line breaks alone stay in the box but are no draft for the list (`isUnsentText`).
 * - Decided difference: the reply target is kept with the text. Desktop drops it on leaving, because
 *   its one composer would carry it into the next room; here every tab has its own box.
 * - An edit of a sent message ([Edit]) is held BESIDE the draft, never in it: while it is open the box
 *   shows the edit's text and the draft (text and reply) waits untouched; Cancel or a saved edit
 *   brings it back. So the list's "Draft:" line and the saved state only ever see the user's own
 *   unsent text, never an old message. Decided difference: desktop drops an open edit on leaving
 *   the room (`stashDraftOnLeave`, binding Edit); here it waits in its tab like the reply target.
 * - Mentions stay in the text in the form the server reads, `@"<id>"`; the names they are shown with
 *   (the box, the list's "Draft:" line, the bubble on its way) are kept beside it ([mentionName]),
 *   learnt from an "@" suggestion or from the message an edit began with. Session-wide, emptied at
 *   sign-out with the drafts; an id without a known name shows as `@<id>`.
 *
 * Main thread only.
 */
class ComposerDrafts {
    data class Key(val account: String, val token: String, val tab: Long)

    /** A sent message being edited in a box: the message as it was when the edit began, the box's text, the save on its way. */
    data class Edit(val message: ChatMessage, val text: String, val saving: Boolean = false)

    private data class Entry(
        val text: String = "",
        val replyTo: ChatMessage? = null,
        val scheduling: Boolean = false,
        /** When the text last changed: the list shows the newest of a room's drafts. */
        val stamp: Long = 0L,
        val edit: Edit? = null,
    )

    private val entries = mutableStateMapOf<Key, Entry>()

    /** Mention id -> the name it is shown with (see the header). */
    private val mentionNames = mutableStateMapOf<String, String>()

    /**
     * Open tabs per conversation (account to token). Counted, not flagged: a reopened room's new tab
     * can exist before the old one is cleared. Not reset at sign-out, so late releases stay balanced.
     */
    private val open = HashMap<Pair<String, String>, Int>()
    private var clock = 0L

    fun text(key: Key): String = entries[key]?.text.orEmpty()

    fun replyTo(key: Key): ChatMessage? = entries[key]?.replyTo

    /** A "send later" request for this box is on its way. */
    fun scheduling(key: Key): Boolean = entries[key]?.scheduling == true

    fun has(key: Key): Boolean = key in entries

    fun setText(key: Key, text: String) = update(key) { if (it.text == text) it else it.copy(text = text, stamp = ++clock) }

    fun setReplyTo(key: Key, message: ChatMessage?) = update(key) { it.copy(replyTo = message) }

    /** Turning it off for an entry that is gone (signed out meanwhile) changes nothing. */
    fun setScheduling(key: Key, on: Boolean) = update(key) { it.copy(scheduling = on) }

    /** The edit open in this box, or null. */
    fun edit(key: Key): Edit? = entries[key]?.edit

    /** Edit [message] in this box, starting from [text]; the draft waits beside it. Not while a save runs. */
    fun startEdit(key: Key, message: ChatMessage, text: String) = update(key) {
        if (it.edit?.saving == true) it else it.copy(edit = Edit(message, text))
    }

    /** The edit's box changed. Read-only while its save runs. */
    fun setEditText(key: Key, text: String) = update(key) { e ->
        val edit = e.edit
        if (edit == null || edit.saving || edit.text == text) e else e.copy(edit = edit.copy(text = text))
    }

    /** The save of the edit of [messageId] started or ended; nothing when that edit is no longer open. */
    fun setEditSaving(key: Key, messageId: Long, on: Boolean) = update(key) { e ->
        val edit = e.edit
        if (edit == null || edit.message.id != messageId) e else e.copy(edit = edit.copy(saving = on))
    }

    /** Close the edit (of [messageId] only, when given): the draft is the box again. */
    fun endEdit(key: Key, messageId: Long? = null) = update(key) { e ->
        if (e.edit == null || (messageId != null && e.edit.message.id != messageId)) e else e.copy(edit = null)
    }

    private fun update(key: Key, change: (Entry) -> Entry) {
        val before = entries[key]
        val next = change(before ?: Entry())
        if (next.text.isEmpty() && next.replyTo == null && !next.scheduling && next.edit == null) {
            if (before != null) entries.remove(key)
        } else if (next != before) {
            entries[key] = next
        }
    }

    /** A tab of [key]'s conversation is on screen (its `ChatViewModel` exists): its drafts are kept. */
    fun opened(key: Key) {
        val room = key.account to key.token
        open[room] = (open[room] ?: 0) + 1
    }

    fun closed(key: Key) {
        val room = key.account to key.token
        val left = (open[room] ?: 0) - 1
        if (left > 0) open[room] = left else open.remove(room)
    }

    /**
     * The list's line per conversation of [account]: the newest draft that holds more than spaces,
     * on one line. [except]: the conversation open beside the list, whose box is on screen anyway.
     */
    fun previews(account: String, except: String?): Map<String, String> {
        val newest = HashMap<String, Entry>()
        for ((key, entry) in entries) {
            if (key.account != account || key.token == except || !isUnsentText(entry.text)) continue
            val held = newest[key.token]
            if (held == null || entry.stamp > held.stamp) newest[key.token] = entry
        }
        return newest.mapValues { oneLine(readable(it.value.text)) }
    }

    /** Signed out: everything goes, as on desktop. */
    fun clear() {
        entries.clear()
        mentionNames.clear()
    }

    /** How the mention `@"<id>"` is shown, without the '@': its name, "all" for everyone, else the id. */
    fun mentionName(id: String): String = if (id == "all") "all" else mentionNames[id] ?: id

    /** [id] is shown as [name] from now on (a suggestion picked, an edit begun). */
    fun nameMentions(names: Map<String, String>) {
        for ((id, name) in names) if (id != "all" && name.isNotBlank() && mentionNames[id] != name) mentionNames[id] = name
    }

    /** [text] with its mentions as names, for a line that only shows it (the bubble on its way). */
    fun readable(text: String): String = readableMentions(text, ::mentionName)

    /** Desktop's `dropDraftsForUnlistedRooms`, sparing every conversation with an open tab. */
    private fun keepListed(account: String, listed: Set<String>) {
        val gone = entries.keys.filter { key ->
            (key.account != account || key.token !in listed) && (open[key.account to key.token] ?: 0) == 0
        }
        gone.forEach { entries.remove(it) }
    }

    /**
     * Follows the session: after every list answer, drafts of conversations no longer in it are
     * dropped. Runs on the container's (main) scope. The list is only replaced by a successful
     * reload, and before the first one it is empty and proves nothing.
     */
    suspend fun run(sessions: StateFlow<SessionState>) {
        sessions.collectLatest { state ->
            val session = (state as? SessionState.SignedIn)?.session ?: return@collectLatest
            val account = accountOf(session)
            session.rooms.rooms.collect { list ->
                if (session.rooms.state.value.loaded) keepListed(account, list.mapTo(HashSet()) { it.token })
            }
        }
    }

    companion object {
        fun accountOf(session: TalkSession): String = session.credentials.server + "|" + session.user.userId

        /** Desktop's `isUnsentText`: spaces and line breaks alone are not something to lose. */
        fun isUnsentText(text: String): Boolean = text.isNotBlank()

        /**
         * Desktop's `mergeRestoredText`: text handed back after a failed request goes after what is
         * typed now, behind a blank line; an empty box simply takes it.
         */
        fun mergeRestoredText(current: String, restored: String): String = when {
            restored.isBlank() -> current
            current.isBlank() -> restored
            else -> current.trimEnd() + "\n\n" + restored
        }

        private val SPACES = Regex("\\s+")

        private fun oneLine(text: String): String = text.trim().replace(SPACES, " ").take(200)
    }
}
