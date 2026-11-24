package talq.app.ui.chatlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import talq.app.theme.TalqTheme
import talq.app.ui.chat.SelectMark
import talq.app.ui.common.Avatar
import talq.app.ui.common.userMessage
import talq.core.TalkError
import talq.core.model.PEOPLE_SEARCH_DEBOUNCE_MILLIS
import talq.core.model.PEOPLE_SEARCH_MIN_CHARS
import talq.core.model.PersonMatch
import talq.core.model.RoomType
import talq.core.repo.TalkSession

// A new conversation, as TalQ for desktop's New chat button (MainWindow.cpp `openNewChatDialog`,
// the Note to self entry on its context menu) and NewChatDialog.cpp:
//  - people are searched with the server's autocomplete (TalkClient.searchPeople: desktop's
//    parameters), from 2 characters, 250 ms after the last key; one person: a tap opens the
//    one-to-one conversation (the server answers the existing one if there is one); only people
//    are listed for that, groups and circles only in "New group";
//  - "New group": a name is all Create needs (desktop allows a group of one); picked people,
//    groups and circles are added one after the other with their own source. A refused add does
//    not undo the group: it opens anyway and one line says how many were left out;
//  - "Note to self" (capability note-to-self) asks the server for that conversation, which makes
//    it the first time;
//  - then the list is reloaded and the conversation opens.
// One request at a time: every row and button is off while one runs and the one that started it
// shows a spinner; the sheet cannot be closed meanwhile. The work runs on the session's scope,
// so turning the phone or closing the sheet cannot cut a group off before its people are added.
// Not ported: the Talk 24 conversation types (presets, voice rooms) of desktop's group tab.

/** What runs now; the row or button that started it shows the progress. */
sealed interface NewChatWork {
    data class Person(val key: String) : NewChatWork
    data object NoteToSelf : NewChatWork
    data object Group : NewChatWork
}

/** The people search under the field. */
sealed interface PeopleSearch {
    /** Nothing typed. */
    data object Idle : PeopleSearch

    /** Fewer than [PEOPLE_SEARCH_MIN_CHARS] characters: nothing is asked. */
    data object TooShort : PeopleSearch

    /** Asking; the previous answer stays on screen meanwhile. */
    data class Searching(val previous: List<PersonMatch>) : PeopleSearch

    data class Found(val people: List<PersonMatch>) : PeopleSearch

    data class Failed(val text: String) : PeopleSearch
}

/** A conversation made or found: open [token]; [notice] is one line to say once it is open. */
data class NewChatDone(val token: String, val notice: String? = null)

/**
 * The sheet's state. A ViewModel so a turned phone keeps a request that is running (and its
 * disabled buttons); [reset] when the sheet opens. ONE instance, made by TalqNavHost in the
 * activity's store and passed to both places the list is drawn, so an unfold or a resize while a
 * request runs neither loses its answer nor offers a second, idle sheet; [signedOut] at sign-out.
 * Holds no session: each call gets it. Main thread only.
 */
class NewConversationModel : ViewModel() {
    var query by mutableStateOf("")
        private set
    var search by mutableStateOf<PeopleSearch>(PeopleSearch.Idle)
        private set
    var group by mutableStateOf(false)
        private set
    var groupName by mutableStateOf("")
        private set
    var picked by mutableStateOf<List<PersonMatch>>(emptyList())
        private set
    var work by mutableStateOf<NewChatWork?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Set when a conversation is ready to open; the list opens it and calls [consumeDone]. */
    var done by mutableStateOf<NewChatDone?>(null)
        private set

    /** True while a request runs or its conversation is still to be opened: nothing else may start. */
    val busy: Boolean get() = work != null || done != null

    private var searchJob: Job? = null

    /** Bumped at sign-out: work still running for the account that left shows nothing. */
    private var account = 0

    /** A fresh sheet. What runs (and what it made) is kept, and while it runs the sheet stays as it is. */
    fun reset() {
        if (work != null) return
        searchJob?.cancel()
        query = ""
        search = PeopleSearch.Idle
        group = false
        groupName = ""
        picked = emptyList()
        error = null
    }

    fun consumeDone() {
        done = null
    }

    /** Signed out: everything is forgotten; a conversation made for that account never opens. */
    fun signedOut() {
        account++
        work = null
        done = null
        reset()
    }

    fun typeQuery(session: TalkSession, text: String) {
        val typed = text.replace('\n', ' ')
        if (typed == query) return
        query = typed
        searchJob?.cancel()
        val q = typed.trim()
        when {
            q.isEmpty() -> search = PeopleSearch.Idle
            q.length < PEOPLE_SEARCH_MIN_CHARS -> search = PeopleSearch.TooShort
            else -> searchJob = viewModelScope.launch {
                delay(PEOPLE_SEARCH_DEBOUNCE_MILLIS)
                search = PeopleSearch.Searching(shownPeople(search))
                search = try {
                    PeopleSearch.Found(session.client.searchPeople(q))
                } catch (e: TalkError) {
                    PeopleSearch.Failed(if (e is TalkError.Network) e.userMessage() else "Search failed.")
                }
            }
        }
    }

    /** "New group" on or off; the picks are dropped either way (as desktop's tabs do), the name stays. */
    fun switchGroup(on: Boolean) {
        if (busy || group == on) return
        group = on
        picked = emptyList()
        error = null
    }

    fun nameGroup(text: String) {
        if (busy) return
        // The server takes up to 255 characters.
        groupName = text.replace('\n', ' ').take(255)
        error = null
    }

    fun toggle(person: PersonMatch) {
        if (busy) return
        picked = if (picked.any { it.key == person.key }) picked.filter { it.key != person.key } else picked + person
        error = null
    }

    fun openOneToOne(session: TalkSession, person: PersonMatch) {
        if (!person.isUser) return
        start(session, NewChatWork.Person(person.key)) {
            NewChatDone(session.client.createRoom(RoomType.ONE_TO_ONE, invite = person.id))
        }
    }

    fun openNoteToSelf(session: TalkSession) {
        start(session, NewChatWork.NoteToSelf) { NewChatDone(session.client.noteToSelf().token) }
    }

    fun createGroup(session: TalkSession) {
        val name = groupName.trim()
        if (name.isEmpty()) return
        val members = picked
        start(session, NewChatWork.Group) {
            val token = session.client.createRoom(RoomType.GROUP, roomName = name)
            // From here the group exists: a refused add is counted, never a reason to try again.
            var refused = 0
            for ((i, m) in members.withIndex()) {
                try {
                    session.client.addParticipant(token, m.id, m.source)
                } catch (e: TalkError) {
                    refused++
                    // No connection: each of the others would wait out its own timeout while the
                    // sheet stays shut, so they are counted as not added.
                    if (e is TalkError.Network) {
                        refused += members.size - i - 1
                        break
                    }
                }
            }
            NewChatDone(
                token,
                when (refused) {
                    0 -> null
                    1 -> "Group created. 1 could not be added."
                    else -> "Group created. $refused could not be added."
                },
            )
        }
    }

    private fun start(session: TalkSession, what: NewChatWork, block: suspend () -> NewChatDone) {
        if (busy) return
        work = what
        error = null
        val forAccount = account
        session.launch {
            var made: NewChatDone? = null
            var failure: String? = null
            try {
                made = block()
                // The list knows the conversation before it opens.
                session.rooms.refresh()
            } catch (e: TalkError) {
                failure = failureText(what, e)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    // Signed out meanwhile: [signedOut] already cleared it (a new account may be busy).
                    if (forAccount != account) return@withContext
                    work = null
                    if (made != null) done = made else error = failure
                }
            }
        }
    }

    private fun failureText(what: NewChatWork, e: TalkError): String = when {
        e is TalkError.Forbidden -> "This account may not start conversations."
        e is TalkError.NotFound && what is NewChatWork.Person -> "That person was not found."
        else -> e.userMessage()
    }
}

/** The answer on screen (the previous one while asking again). */
private fun shownPeople(search: PeopleSearch): List<PersonMatch> = when (search) {
    is PeopleSearch.Found -> search.people
    is PeopleSearch.Searching -> search.previous
    else -> emptyList()
}

/**
 * The "+" sheet of the profile bar (rules at the top of this file). [model] lives in the list's
 * ViewModel store; the list opens what it makes ([NewConversationModel.done]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationSheet(session: TalkSession, model: NewConversationModel, onDismiss: () -> Unit) {
    val colors = TalqTheme.colors
    val busy = model.busy
    val group = model.group
    // Nothing closes the sheet while a request runs: what it makes opens when it is done.
    val confirm = remember(model) { { value: SheetValue -> value != SheetValue.Hidden || !model.busy } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirm)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Direct chats list people only; a group also takes groups and circles (desktop's filter).
    val people = remember(model.search, group) {
        shownPeople(model.search).let { all -> if (group) all else all.filter { it.isUser } }
    }

    ModalBottomSheet(onDismissRequest = { if (!model.busy) onDismiss() }, sheetState = sheetState) {
        // Back leaves "New group" first.
        BackHandler(enabled = group && !busy) { model.switchGroup(false) }
        Column(Modifier.fillMaxWidth().fillMaxHeight().navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (group) 4.dp else 20.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (group) {
                    IconButton(onClick = { model.switchGroup(false) }, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(
                    if (group) "New group" else "New conversation",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (group) {
                    // At the top: it stays above the keyboard.
                    Button(onClick = { model.createGroup(session) }, enabled = !busy && model.groupName.isNotBlank()) {
                        if (model.work == NewChatWork.Group) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Create group")
                    }
                }
            }
            model.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (group) {
                OutlinedTextField(
                    value = model.groupName,
                    onValueChange = model::nameGroup,
                    placeholder = { Text("Name your group") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                )
                if (model.picked.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    ) {
                        items(model.picked, key = { it.key }) { p ->
                            InputChip(
                                selected = false,
                                onClick = { model.toggle(p) },
                                enabled = !busy,
                                label = {
                                    Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                                },
                                avatar = { PersonAvatar(session, p, 24.dp) },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
            }
            ChatSearchField(
                query = model.query,
                onQueryChange = { model.typeQuery(session, it) },
                placeholder = "Type a name or username…",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).focusRequester(focus),
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                // "x:" keys cannot meet a person's key ("users:…", "groups:…", "circles:…").
                if (!group && model.query.isBlank()) {
                    if (session.capabilities.noteToSelf) {
                        item(key = "x:note") {
                            ActionRow(Icons.Outlined.BookmarkBorder, "Note to self", running = model.work == NewChatWork.NoteToSelf, enabled = !busy) {
                                model.openNoteToSelf(session)
                            }
                        }
                    }
                    item(key = "x:group") {
                        ActionRow(Icons.Outlined.GroupAdd, "New group", running = false, enabled = !busy) { model.switchGroup(true) }
                    }
                }
                val hint: Pair<String, Boolean>? = when (val s = model.search) {
                    PeopleSearch.Idle -> null
                    PeopleSearch.TooShort -> "Type at least 2 characters." to false
                    is PeopleSearch.Searching -> if (people.isEmpty()) "Searching…" to false else null
                    is PeopleSearch.Found -> if (people.isEmpty()) "No results." to false else null
                    is PeopleSearch.Failed -> s.text to true
                }
                if (hint != null) {
                    item(key = "x:hint") {
                        Text(
                            hint.first,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (hint.second) colors.danger else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
                items(people, key = { it.key }) { p ->
                    PersonRow(
                        session = session,
                        person = p,
                        group = group,
                        picked = group && model.picked.any { it.key == p.key },
                        running = model.work == NewChatWork.Person(p.key),
                        enabled = !busy,
                        onClick = { if (group) model.toggle(p) else model.openOneToOne(session, p) },
                    )
                }
            }
        }
    }
}

/** Note to self, New group: an icon in a soft disc, the words, a spinner while it runs. */
@Composable
private fun ActionRow(icon: ImageVector, text: String, running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = TalqTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.accentSoftInk, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, modifier = Modifier.weight(1f))
        if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/**
 * One search answer: avatar, name, and under it the user id, or "Group" / "Circle". Direct: a tap
 * opens the chat (spinner while it runs). Group: a tap picks or drops it (the round mark).
 */
@Composable
private fun PersonRow(
    session: TalkSession,
    person: PersonMatch,
    group: Boolean,
    picked: Boolean,
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = TalqTheme.colors
    val tap = if (group) {
        Modifier.toggleable(value = picked, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    Row(
        Modifier.fillMaxWidth().then(tap).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonAvatar(session, person, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(person.name, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (person.source) {
                    PersonMatch.GROUPS -> "Group"
                    PersonMatch.CIRCLES -> "Circle"
                    else -> person.id
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (group) {
            SelectMark(selectable = true, selected = picked)
        } else if (running) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/** A person's own picture; a group or circle gets a glyph in a quiet disc (they have no picture here). */
@Composable
private fun PersonAvatar(session: TalkSession, person: PersonMatch, size: Dp) {
    val colors = TalqTheme.colors
    if (person.isUser) {
        Avatar(
            url = session.client.userAvatarUrl(person.id, 128, !colors.isLight),
            authorization = session.client.authorizationHeader,
            name = person.name,
            size = size,
        )
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(colors.bgSecondary), contentAlignment = Alignment.Center) {
            Icon(
                if (person.source == PersonMatch.GROUPS) Icons.Outlined.Groups else Icons.Outlined.Workspaces,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}
