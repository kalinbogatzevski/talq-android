package talq.app.ui.room

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import talq.app.appContainer
import talq.app.ui.chat.sayAnywhere
import talq.app.ui.chatlist.PeopleSearch
import talq.app.ui.chatlist.shownPeople
import talq.app.ui.common.userMessage
import talq.core.TalkError
import talq.core.model.MemberAction
import talq.core.model.MemberChange
import talq.core.model.PEOPLE_SEARCH_DEBOUNCE_MILLIS
import talq.core.model.PEOPLE_SEARCH_MIN_CHARS
import talq.core.model.PersonMatch
import talq.core.model.Room
import talq.core.model.RoomParticipant
import talq.core.model.afterMemberChange
import talq.core.model.membersForModeratorsOnly
import talq.core.model.sortMembers
import talq.core.model.statusAndCode
import talq.core.repo.TalkSession

// The members of a conversation, as TalQ for desktop's ConversationInfoDialog shows and changes them (the
// rules are in talq.core.model Members.kt):
//  - the list is read when the sheet opens and again after every change or refusal; only the newest
//    reading counts. A 404 closes the sheet (the user was taken out, or the conversation is gone);
//  - one change at a time, on the session's scope, so closing the sheet or the room cannot cut it off;
//    what it did is in the list at once, and the conversation list is reloaded. A refusal that comes after
//    the sheet was closed is said wherever the user is;
//  - people to add are searched as the new-conversation sheet does, with this conversation's token: the
//    server leaves out who is in it. A reply counts only for the newest search, and an add empties the box
//    only while it still holds the search that found that person;
//  - an answer for an account that is no longer signed in is dropped.

/** What runs now; its row shows the progress and nothing else may start. */
sealed interface MemberWork {
    /** Adding the search answer with this [PersonMatch.key]. */
    data class Adding(val key: String) : MemberWork

    /** Removing, promoting or demoting this attendee. */
    data class Changing(val attendeeId: Long) : MemberWork
}

/** A name in a line that says something about it is cut here, so that the line ends with what it says. */
private const val LINE_NAME_CHARS = 18

private const val NOT_IN_IT = "You are not in this conversation any more."

/** [name] in at most [chars] characters. */
internal fun cutName(name: String, chars: Int = LINE_NAME_CHARS): String =
    if (name.length > chars) name.take(chars - 1).trimEnd() + "…" else name

/** One line about the last change. */
data class MembersNote(val text: String, val failed: Boolean)

/** Why there is no list, one line; [retry]: asking again may help. */
data class MembersFailure(val text: String, val retry: Boolean)

/** The members sheet's state, kept while the phone is turned. Main thread only. */
class MembersViewModel(
    private val session: TalkSession,
    private val token: String,
    /** The application's context (never an activity's): for a line that comes after the sheet was closed. */
    private val appContext: Context,
) : ViewModel() {
    /** In the order shown; null until the first reading has answered. */
    var members by mutableStateOf<List<RoomParticipant>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var failure by mutableStateOf<MembersFailure?>(null)
        private set

    /** The user is not in this conversation any more: the sheet closes. */
    var gone by mutableStateOf(false)
        private set
    var work by mutableStateOf<MemberWork?>(null)
        private set
    var note by mutableStateOf<MembersNote?>(null)
        private set

    /** The change being asked about; it stays up, with progress, while it runs. */
    var asking by mutableStateOf<MemberChange?>(null)
        private set

    /** The text of the list's own filter. */
    var filter by mutableStateOf("")

    /** "Add people" is open in place of the list. */
    var adding by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var search by mutableStateOf<PeopleSearch>(PeopleSearch.Idle)
        private set

    /** Counts the adds that emptied the box: the cursor goes back into it. */
    var emptied by mutableIntStateOf(0)
        private set

    /** The sheet is open (also while the phone is being turned). */
    private var shown = false
    private var loads = 0
    private var searchJob: Job? = null

    /** The account this belongs to is the one signed in. */
    private val current: Boolean get() = appContext.appContainer.session === session

    /** The sheet is on screen: a fresh start, unless it was there before the phone was turned. */
    fun opened() {
        if (shown) return
        shown = true
        gone = false
        failure = null
        note = null
        filter = ""
        closeAdd()
        load()
    }

    /** The sheet was closed, or its conversation left. What runs goes on. */
    fun closed() {
        shown = false
        gone = false
        asking = null
        searchJob?.cancel()
    }

    override fun onCleared() = closed()

    /** The user is a moderator of [room]: by the conversation list, and by the own row once the members are read. */
    fun moderates(room: Room): Boolean {
        if (!room.isModerator) return false
        val own = ownRow()
        return own == null || own.isModerator
    }

    private fun ownRow(): RoomParticipant? = members?.firstOrNull { session.isMe(it.actorType, it.actorId) }

    /** No moderator (any more): nothing of theirs stays open. */
    fun rightLost() {
        closeAdd()
        if (work == null) asking = null
    }

    // ---------------------------------------------------------------- the list

    /** Read the members (again). */
    fun load() {
        val asked = ++loads
        loading = true
        viewModelScope.launch {
            var list: List<RoomParticipant>? = null
            var error: TalkError? = null
            try {
                val all = session.client.participants(token, includeStatus = true)
                list = withContext(Dispatchers.Default) { sortMembers(all.distinctBy { it.key }) }
            } catch (e: TalkError) {
                error = e
            }
            if (asked != loads) return@launch
            loading = false
            if (!current) return@launch
            if (list != null) {
                members = list
                failure = null
                // The conversation list does not know the user's right here yet (or still does).
                val own = ownRow()
                val room = session.rooms.room(token)
                if (own != null && room != null && own.isModerator != room.isModerator) reloadRooms()
            } else if (error != null) {
                notRead(error)
            }
        }
    }

    private fun notRead(e: TalkError) {
        val (status, _) = e.statusAndCode()
        val room = session.rooms.room(token)
        when {
            status == 404 -> {
                // The sheet closes: it says why where the user then is.
                if (shown) sayAnywhere(appContext, NOT_IN_IT)
                gone = true
                reloadRooms()
                return
            }
            // Desktop's line. Nothing of a list read before the right was lost stays on screen.
            status == 403 && room != null && membersForModeratorsOnly(room) -> {
                members = null
                failure = MembersFailure("Only moderators can see who is in a channel.", retry = false)
                reloadRooms()
                return
            }
            status == 412 -> {
                members = null
                failure = MembersFailure("Members show once the lobby opens.", retry = true)
                return
            }
        }
        if (e is TalkError.Unauthorized) appContext.appContainer.onUnauthorized()
        val line = if (e is TalkError.Network) e.userMessage() else "Couldn't load members."
        if (members == null) {
            failure = MembersFailure(line, retry = true)
        } else if (note == null) {
            // The list read before stays; what was just said is worth more than this line.
            say(line, failed = true)
        }
    }

    private fun reloadRooms() {
        session.launch { session.rooms.refresh() }
    }

    // ---------------------------------------------------------------- removing, promoting, demoting

    /** An item of a row's menu: making a moderator and removing are asked about first. */
    fun choose(change: MemberChange) {
        if (work != null) return
        if (change.action == MemberAction.Demote) carryOut(change) else asking = change
    }

    fun confirm() {
        asking?.let { carryOut(it) }
    }

    fun dismissAsking() {
        if (work == null) asking = null
    }

    private fun carryOut(change: MemberChange) {
        val id = change.person.attendeeId
        start(
            MemberWork.Changing(id),
            done = {
                asking = null
                members = members?.let { afterMemberChange(it, change) }
            },
            refused = { e ->
                asking = null
                changeRefusal(change, e, named = change.action == MemberAction.Remove || session.capabilities.has("promote-demote-owner"))
            },
        ) {
            when (change.action) {
                MemberAction.Promote -> session.client.promoteModerator(token, id)
                MemberAction.Demote -> session.client.demoteModerator(token, id)
                MemberAction.Remove -> session.client.removeAttendee(token, id)
            }
        }
    }

    // ---------------------------------------------------------------- adding

    fun openAdd() {
        if (work != null) return
        note = null
        adding = true
    }

    fun closeAdd() {
        adding = false
        emptySearch()
    }

    private fun emptySearch() {
        searchJob?.cancel()
        query = ""
        search = PeopleSearch.Idle
    }

    fun type(text: String) {
        if (text == query) return
        query = text
        searchJob?.cancel()
        val q = text.trim()
        when {
            q.isEmpty() -> search = PeopleSearch.Idle
            q.length < PEOPLE_SEARCH_MIN_CHARS -> search = PeopleSearch.TooShort
            else -> searchJob = viewModelScope.launch {
                delay(PEOPLE_SEARCH_DEBOUNCE_MILLIS)
                search = PeopleSearch.Searching(shownPeople(search))
                val answer: PeopleSearch = try {
                    PeopleSearch.Found(session.client.searchPeople(q, itemId = token))
                } catch (e: TalkError) {
                    PeopleSearch.Failed(if (e is TalkError.Network) e.userMessage() else "Search failed.")
                }
                if (current) search = answer
            }
        }
    }

    fun add(person: PersonMatch) {
        val asked = query
        start(
            MemberWork.Adding(person.key),
            done = {
                say("Added ${cutName(person.name)}.", failed = false)
                // Text typed meanwhile is the next name.
                if (query == asked) {
                    emptySearch()
                    emptied++
                }
            },
            refused = { e -> addRefusal(person, e, onSheet = shown) },
        ) {
            session.client.addParticipant(token, person.id, person.source)
        }
    }

    // ---------------------------------------------------------------- one change at a time

    private fun start(
        what: MemberWork,
        done: () -> Unit,
        refused: (TalkError) -> String,
        request: suspend () -> Unit,
    ) {
        if (work != null) return
        work = what
        note = null
        session.launch {
            var answered = false
            var error: TalkError? = null
            try {
                request()
                answered = true
            } catch (e: TalkError) {
                error = e
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { ended(answered, error, done, refused) }
            }
            // The list of conversations too: the user's own right, a conversation that was left.
            session.rooms.refresh()
        }
    }

    /** Neither [answered] nor an [error]: the session was closed meanwhile. */
    private fun ended(answered: Boolean, error: TalkError?, done: () -> Unit, refused: (TalkError) -> String) {
        work = null
        if (!current) return
        if (answered) done()
        if (error != null) {
            if (error is TalkError.Unauthorized) appContext.appContainer.onUnauthorized()
            say(refused(error), failed = true)
        }
        // As the server has it now, after a refusal too: this list was old, or the user's right is gone.
        if (shown && (answered || error != null)) load()
    }

    /** On the sheet; a failure also when it was closed meanwhile. */
    private fun say(text: String, failed: Boolean) {
        if (shown) note = MembersNote(text, failed) else if (failed) sayAnywhere(appContext, text)
    }
}

/** Why somebody was not added, one line; away from the sheet ([onSheet] false) it always says who. */
private fun addRefusal(person: PersonMatch, e: TalkError, onSheet: Boolean): String {
    val (status, code) = e.statusAndCode()
    val name = cutName(person.name)
    return when {
        e is TalkError.Network -> "$name not added: can't reach the server."
        status == 400 && code == "ban" -> if (onSheet) "Banned from this conversation." else "$name not added: banned from that conversation."
        status == 403 -> "Only moderators can add people."
        status == 404 && code == "new-participant" -> if (onSheet) "Not found." else "$name not added: not found."
        // Without a reason word it is the conversation, or the user's own place in it.
        status == 404 -> NOT_IN_IT
        else -> "Couldn't add $name."
    }
}

/** Why a removal, promotion or demotion was refused, one line. [named]: the server has a word for somebody who is gone. */
private fun changeRefusal(change: MemberChange, e: TalkError, named: Boolean): String {
    val (status, code) = e.statusAndCode()
    val name = cutName(change.person.name)
    val what = if (change.action == MemberAction.Remove) "$name not removed" else "$name not changed"
    return when {
        e is TalkError.Network -> "$what: can't reach the server."
        code == "owner" -> "$what: an owner stays."
        code == "last-moderator" -> "$what: the last moderator."
        status == 403 -> "Only moderators can do that."
        status == 404 && code == "participant" -> "$name is not in this conversation any more."
        // Else it is the conversation, or the user's own place in it (Talk 24 tells neither apart for a promotion).
        status == 404 -> if (named) NOT_IN_IT else "$what: not found."
        else -> "$what: the server refused it."
    }
}
