package talq.app.ui.room

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import talq.app.theme.TalqTheme
import talq.app.ui.chatlist.ChatSearchField
import talq.app.ui.chatlist.PeopleSearch
import talq.app.ui.chatlist.PersonAvatar
import talq.app.ui.chatlist.searchFold
import talq.app.ui.chatlist.shownPeople
import talq.app.ui.common.StatusAvatar
import talq.app.ui.common.onSecondaryClick
import talq.core.model.MemberAction
import talq.core.model.MemberChange
import talq.core.model.ParticipantType
import talq.core.model.PersonMatch
import talq.core.model.Room
import talq.core.model.RoomParticipant
import talq.core.model.isMember
import talq.core.model.memberActions
import talq.core.model.takesMembers
import talq.core.model.takesOnlyPeople
import talq.core.repo.TalkSession

/** With more people than this the list has a field to find somebody by name. */
private const val FILTER_OVER = 12

/** A name in a question is cut here, so the question stays readable. */
private const val QUESTION_NAME_CHARS = 40

/**
 * Who is in [room] (a group or public conversation of the user's server): name, presence, role. Its
 * moderators add people, groups and teams, remove somebody, and make or unmake moderators (⋮, touch and
 * hold or a right-click on a row). The state, the requests and the rules are in [MembersViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersSheet(session: TalkSession, room: Room, onDismiss: () -> Unit) {
    val colors = TalqTheme.colors
    val appContext = LocalContext.current.applicationContext
    val vm: MembersViewModel = viewModel(key = "members-${room.token}") { MembersViewModel(session, room.token, appContext) }
    val activity = LocalActivity.current
    DisposableEffect(vm) {
        vm.opened()
        // Turning the phone is not closing.
        onDispose { if (activity?.isChangingConfigurations != true) vm.closed() }
    }
    LaunchedEffect(vm.gone) { if (vm.gone) onDismiss() }
    // Read again with every list answer: the user's right here may change.
    val moderator = vm.moderates(room)
    LaunchedEffect(moderator) { if (!moderator) vm.rightLost() }
    val adding = vm.adding && moderator

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Back leaves "Add people" first.
        BackHandler(enabled = adding) { vm.closeAdd() }
        Column(Modifier.fillMaxWidth().fillMaxHeight().navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (adding) 4.dp else 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (adding) {
                    IconButton(onClick = { vm.closeAdd() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(
                    when {
                        adding -> "Add people"
                        else -> vm.members?.let { "Members · ${it.size}" } ?: "Members"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!adding && moderator && takesMembers(room)) {
                    TextButton(onClick = { vm.openAdd() }, enabled = vm.work == null) {
                        Icon(Icons.Outlined.GroupAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add people")
                    }
                }
            }
            vm.note?.let {
                Text(
                    it.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.failed) colors.danger else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (adding) {
                AddPeople(session, room, vm, Modifier.weight(1f))
            } else {
                MemberList(session, vm, moderator, Modifier.weight(1f))
            }
        }
    }

    vm.asking?.let { change ->
        ChangeDialog(
            change = change,
            running = vm.work != null,
            onConfirm = { vm.confirm() },
            onDismiss = { vm.dismissAsking() },
        )
    }
}

@Composable
private fun MemberList(session: TalkSession, vm: MembersViewModel, moderator: Boolean, modifier: Modifier) {
    val colors = TalqTheme.colors
    val list = vm.members
    val failure = vm.failure
    Column(modifier.fillMaxWidth()) {
        when {
            list == null && failure != null -> Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    failure.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (failure.retry) {
                    TextButton(onClick = { vm.load() }, enabled = !vm.loading) { Text(if (vm.loading) "Loading…" else "Retry") }
                }
            }
            list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
            }
            else -> {
                val filtering = list.size > FILTER_OVER
                val needle = if (filtering) searchFold(vm.filter.trim()) else ""
                val rows = remember(list, needle) {
                    if (needle.isEmpty()) list else list.filter { needle in searchFold(it.name) }
                }
                if (filtering) {
                    ChatSearchField(
                        query = vm.filter,
                        onQueryChange = { vm.filter = it },
                        placeholder = "Search members…",
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
                val work = vm.work
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                    // "x:" keys cannot meet a member's ("users/…", "groups/…").
                    if (rows.isEmpty()) {
                        item(key = "x:none") { Hint("No results.", failed = false) }
                    }
                    items(rows, key = { it.key }) { p ->
                        val me = session.isMe(p.actorType, p.actorId)
                        MemberRow(
                            session = session,
                            person = p,
                            isMe = me,
                            actions = memberActions(p, me, moderator),
                            running = work == MemberWork.Changing(p.attendeeId),
                            enabled = work == null,
                            onAction = { vm.choose(MemberChange(it, p)) },
                        )
                    }
                }
            }
        }
    }
}

/** One quiet line in a list: nothing found, searching, or (in red) what failed. */
@Composable
private fun Hint(text: String, failed: Boolean) {
    val colors = TalqTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) colors.danger else colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** One of the members. With [actions]: ⋮ (a spinner while [running]), also by touch and hold and a right-click. */
@Composable
private fun MemberRow(
    session: TalkSession,
    person: RoomParticipant,
    isMe: Boolean,
    actions: List<MemberAction>,
    running: Boolean,
    enabled: Boolean,
    onAction: (MemberAction) -> Unit,
) {
    val colors = TalqTheme.colors
    val haptics = LocalHapticFeedback.current
    val offered = enabled && actions.isNotEmpty()
    // Closed for good when it stops being offered.
    var menu by remember(offered) { mutableStateOf(false) }
    val hold = if (offered) {
        Modifier
            .onSecondaryClick({ menu = true })
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
            }
    } else {
        Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(hold)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One item for a screen reader, the menu button its own.
        Row(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(session, person)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(person.name, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val role = roleLine(person, isMe)
                if (role.isNotEmpty()) {
                    Text(role, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (running) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else if (actions.isNotEmpty()) {
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${person.name}")
                }
                DropdownMenu(expanded = menu && offered, onDismissRequest = { menu = false }) {
                    for (action in actions) {
                        DropdownMenuItem(
                            text = { Text(actionLabel(action)) },
                            onClick = {
                                menu = false
                                onAction(action)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** "You", "Owner" or "Moderator", and what it is when it is no person with an account; a plain member says nothing. */
private fun roleLine(p: RoomParticipant, isMe: Boolean): String {
    val role = when {
        p.participantType == ParticipantType.OWNER -> "Owner"
        p.isModerator -> "Moderator"
        else -> ""
    }
    val kind = when {
        p.actorType == PersonMatch.GROUPS -> "(group)"
        p.isCollective -> "(team)"
        p.isGuest -> "(guest)"
        else -> ""
    }
    val who = listOf(if (isMe) "You" else "", role).filter { it.isNotEmpty() }.joinToString(" · ")
    return listOf(who, kind).filter { it.isNotEmpty() }.joinToString(" ")
}

private fun actionLabel(action: MemberAction): String = when (action) {
    MemberAction.Promote -> "Make moderator"
    MemberAction.Demote -> "Remove as moderator"
    MemberAction.Remove -> "Remove from conversation"
}

/**
 * A person with the presence disc (one for every state the server sent, none where it sent none); a group
 * or a team its glyph.
 */
@Composable
private fun MemberAvatar(session: TalkSession, person: RoomParticipant) {
    val colors = TalqTheme.colors
    if (person.isCollective) {
        val source = if (person.actorType == PersonMatch.GROUPS) PersonMatch.GROUPS else PersonMatch.CIRCLES
        PersonAvatar(session, PersonMatch(person.actorId, person.name, source), 40.dp)
    } else {
        StatusAvatar(
            url = session.client.actorAvatarUrl(person.actorType, person.actorId, 128, !colors.isLight),
            authorization = session.client.authorizationHeader,
            name = person.name,
            // The sheet's own ground.
            ring = MaterialTheme.colorScheme.surfaceContainerLow,
            size = 40.dp,
            presence = person.status,
            everyState = true,
        )
    }
}

/** The search for people, groups and teams to add: a tap adds, its row shows the progress. */
@Composable
private fun AddPeople(session: TalkSession, room: Room, vm: MembersViewModel, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    // Again when an add emptied the box: ready for the next name.
    LaunchedEffect(vm.emptied) { runCatching { focus.requestFocus() } }
    val members = vm.members.orEmpty()
    val onlyPeople = takesOnlyPeople(room)
    val people = remember(vm.search, members, onlyPeople) {
        shownPeople(vm.search).filter { (it.isUser || !onlyPeople) && !isMember(it, members) }
    }
    Column(modifier.fillMaxWidth()) {
        ChatSearchField(
            query = vm.query,
            onQueryChange = { vm.type(it) },
            placeholder = "Type a name or username…",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).focusRequester(focus),
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            // "x:" keys cannot meet a person's ("users:…", "groups:…", "circles:…").
            val hint: Pair<String, Boolean>? = when (val s = vm.search) {
                PeopleSearch.Idle -> null
                PeopleSearch.TooShort -> "Type at least 2 characters." to false
                is PeopleSearch.Searching -> if (people.isEmpty()) "Searching…" to false else null
                is PeopleSearch.Found -> if (people.isEmpty()) "No results." to false else null
                is PeopleSearch.Failed -> s.text to true
            }
            if (hint != null) {
                item(key = "x:hint") { Hint(hint.first, failed = hint.second) }
            }
            items(people, key = { it.key }) { p ->
                AddRow(
                    session = session,
                    person = p,
                    running = vm.work == MemberWork.Adding(p.key),
                    enabled = vm.work == null,
                    onClick = { vm.add(p) },
                )
            }
        }
    }
}

@Composable
private fun AddRow(session: TalkSession, person: PersonMatch, running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = TalqTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Add", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonAvatar(session, person, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(person.name, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (person.source) {
                    PersonMatch.GROUPS -> "Group"
                    PersonMatch.CIRCLES -> "Team"
                    else -> person.id
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/**
 * Asked once before somebody is made a moderator or removed. While it is [running] the dialog stays up
 * and takes nothing more.
 */
@Composable
private fun ChangeDialog(change: MemberChange, running: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = TalqTheme.colors
    val remove = change.action == MemberAction.Remove
    val name = cutName(change.person.name, QUESTION_NAME_CHARS)
    // The server takes the members out with their group: not the moderators, nor who is in another one of it here.
    val more = if (remove && change.person.isCollective) "Its members are removed too, moderators stay." else null
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(if (remove) "Remove $name?" else "Make $name a moderator?") },
        text = more?.let { line -> { Text(line) } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !running) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (remove) "Removing…" else "Saving…")
                } else if (remove) {
                    Text("Remove", color = colors.danger)
                } else {
                    Text("Make moderator")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("Cancel") } },
    )
}
