package talq.app.ui.chatlist

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.LabelImportant
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import talq.app.appContainer
import talq.app.call.CallActivity
import talq.app.push.TalqNotifications
import talq.app.theme.TalqTheme
import talq.app.ui.chat.sayAnywhere
import talq.core.TalkError
import talq.core.model.NotificationLevel
import talq.core.model.Room
import talq.core.model.RoomType
import talq.core.model.canReadMembers
import talq.core.model.hasMemberList
import talq.core.model.leavingDeletes
import talq.core.model.statusAndCode
import talq.core.repo.RoomAction
import talq.core.repo.RoomRefusal
import talq.core.repo.TalkSession
import talq.core.repo.canBeLeft
import talq.core.repo.canBeMarkedRead
import talq.core.repo.canBeMarkedUnread
import talq.core.repo.classifyRoomRefusal

// The menu of one conversation in the list (touch and hold its row, or right-click it): TalQ for
// desktop's conversation context menu (MainWindow.cpp, `SidebarPainter::contextMenuRequested`) with its
// words and capability gates, as one sheet; "Leave" is the one of its conversation info window
// (ConversationInfoDialog `onLeaveClicked`), "Mark as read" and "Mark as unread" are Talk's own.
//  - one request at a time per conversation (`session.roomActions`): the row tapped shows the progress,
//    every other row is off, and the sheet closes when the server has answered. Closing it earlier does
//    not stop the request;
//  - the list's row changes when the server has taken it, as on desktop; a refusal is one line, said
//    wherever the user is by then;
//  - "Leave" first asks who is in the conversation (the row shows the progress): the server deletes one its
//    only member leaves, and the question then says so. Where that cannot be found out it is the plain one;
//  - the sheet keeps the conversation's TOKEN, never a list position, and closes when it leaves the list;
//  - a control whose capability the server lacks is not there.

/** Which conversation's menu, or whose "Leave" question, is open over the list. Saved tokens: turning the phone keeps it. */
@Stable
class RoomMenuState internal constructor(
    menu: MutableState<String?>,
    leave: MutableState<String?>,
    leaveDeletes: MutableState<Boolean>,
) {
    var menuFor: String? by menu
    var leaveFor: String? by leave

    /** Leaving [leaveFor] deletes it. */
    var leaveDeletes: Boolean by leaveDeletes
}

@Composable
fun rememberRoomMenu(): RoomMenuState {
    val menu = rememberSaveable { mutableStateOf<String?>(null) }
    val leave = rememberSaveable { mutableStateOf<String?>(null) }
    val leaveDeletes = rememberSaveable { mutableStateOf(false) }
    return remember { RoomMenuState(menu, leave, leaveDeletes) }
}

/**
 * Draws the sheet or the question [state] has open. [rooms]: the list as shown. [onMove] opens "Move to
 * folder" for a conversation; null while no account's folders are attached.
 */
@Composable
fun RoomMenuHost(state: RoomMenuState, session: TalkSession, rooms: List<Room>, onMove: ((token: String) -> Unit)?) {
    val context = LocalContext.current
    val actions = session.roomActions
    val running by actions.running.collectAsStateWithLifecycle()
    val calls = context.appContainer.calls
    // Known before a menu opens (asked once per session): a row must not slip in under the finger.
    val callsAvailable by produceState(false, session, state.menuFor == null) { value = calls.available(session) }

    state.menuFor?.let { token ->
        val room = rooms.firstOrNull { it.token == token }
        if (room == null) {
            LaunchedEffect(token) { state.menuFor = null }
        } else {
            key(token) {
                val canCall = remember { callsAvailable && room.isOneToOne && !room.isReadOnly && room.canStartCall && !calls.busy }
                RoomMenuSheet(
                    session = session,
                    room = room,
                    running = running[token],
                    onAction = { act(context, session, token, it) },
                    onCall = if (canCall) ({
                        state.menuFor = null
                        context.startActivity(
                            if (calls.busy) CallActivity.showIntent(context) else CallActivity.outgoingIntent(context, token, video = false),
                        )
                    }) else null,
                    onMove = onMove?.let {
                        {
                            state.menuFor = null
                            it(token)
                        }
                    },
                    onLeave = { deletes ->
                        state.menuFor = null
                        state.leaveDeletes = deletes
                        state.leaveFor = token
                    },
                    onDismiss = { state.menuFor = null },
                )
            }
        }
    }

    state.leaveFor?.let { token ->
        key(token) {
            var started by rememberSaveable { mutableStateOf(false) }
            // Gone from the list: left (here or elsewhere), nothing more to ask.
            if (rooms.none { it.token == token }) LaunchedEffect(Unit) { state.leaveFor = null }
            LaunchedEffect(started) {
                if (!started) return@LaunchedEffect
                actions.running.first { token !in it }
                state.leaveFor = null
            }
            LeaveDialog(
                name = remember { rooms.firstOrNull { it.token == token }?.displayName.orEmpty() },
                deletes = state.leaveDeletes,
                leaving = started,
                onLeave = { if (!started && act(context, session, token, RoomAction.Leave)) started = true },
                onDismiss = { state.leaveFor = null },
            )
        }
    }
}

/** How long "Leave" waits to know who is in the conversation. */
private const val MEMBERS_MILLIS = 4_000L

/** Leaving [room] deletes it; false also where that cannot be found out (in time). */
private suspend fun findLeavingDeletes(session: TalkSession, room: Room): Boolean {
    if (!hasMemberList(room) || !canReadMembers(room, System.currentTimeMillis() / 1000)) return false
    return try {
        withTimeoutOrNull(MEMBERS_MILLIS) {
            leavingDeletes(room, session.client.participants(room.token)) { session.isMe(it.actorType, it.actorId) }
        } ?: false
    } catch (e: TalkError) {
        false
    } catch (e: CancellationException) {
        // Not ours (the sheet is still there): the HTTP client gave the request up.
        currentCoroutineContext().ensureActive()
        false
    }
}

/** Start [action]; false when one runs for the conversation already. What went wrong is said in one line. */
private fun act(context: Context, session: TalkSession, token: String, action: RoomAction): Boolean {
    val app = context.applicationContext
    return session.roomActions.run(token, action) { failure ->
        withContext(Dispatchers.Main) {
            when {
                failure != null -> sayAnywhere(app, refusalLine(action, failure))
                // Its notifications on the phone are dealt with too (the server takes them back as well): all of them, or
                // what was marked. A message that came meanwhile is unread, and so is its notification.
                action == RoomAction.Leave || (action == RoomAction.MarkRead && session.rooms.room(token)?.isFederated != false) ->
                    TalqNotifications.cancelWhere(app) { about, _, _ -> about == token }
                action == RoomAction.MarkRead ->
                    TalqNotifications.cancelRead(app, token, topicId = null, upTo = session.unread(token).state.value.seenUpTo(-1L))
            }
        }
    }
}

/** The one line for a refused [action]; a code is never shown raw. */
private fun refusalLine(action: RoomAction, error: TalkError): String {
    val (status, code) = error.statusAndCode()
    val what = when (action) {
        RoomAction.MarkRead -> "Not marked as read"
        RoomAction.MarkUnread -> "Not marked as unread"
        is RoomAction.Favourite -> if (action.on) "Not added to favourites" else "Not removed from favourites"
        is RoomAction.Notify, is RoomAction.RingForCalls -> "Notifications not changed"
        is RoomAction.Archive -> if (action.on) "Not archived" else "Not unarchived"
        is RoomAction.Important -> "Not changed"
        RoomAction.Leave -> "Not left"
    }
    val why = when (classifyRoomRefusal(action, status, code)) {
        RoomRefusal.NoConnection -> "can't reach the server"
        RoomRefusal.Gone -> "the conversation is gone"
        RoomRefusal.LastModerator -> "you are its last moderator"
        RoomRefusal.Announcement -> "an announcement can't be left"
        RoomRefusal.Unknown -> if (status > 0) "the server refused it (HTTP $status)" else "the server refused it"
    }
    return "$what: $why."
}

/** The words of desktop's Notifications submenu. */
private val LEVELS = listOf(
    NotificationLevel.DEFAULT to "Default",
    NotificationLevel.ALWAYS to "All messages",
    NotificationLevel.MENTION to "Mentions only",
    NotificationLevel.NEVER to "Never",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomMenuSheet(
    session: TalkSession,
    room: Room,
    running: RoomAction?,
    /** Starts it; false when it did not start. */
    onAction: (RoomAction) -> Boolean,
    onCall: (() -> Unit)?,
    onMove: (() -> Unit)?,
    /** Ask whether to leave; [deletes]: that deletes it. */
    onLeave: (deletes: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TalqTheme.colors
    val caps = session.capabilities
    var levelsOpen by rememberSaveable { mutableStateOf(false) }
    var started by rememberSaveable { mutableStateOf(false) }
    // "Leave" was tapped, and who is in it is being asked.
    var asking by rememberSaveable { mutableStateOf(false) }
    val busy = started || asking || running != null
    // As it was when the sheet opened: neither a list reload nor the answer swaps or moves the rows under
    // the finger. A row that is behind asks for what it says, and the server has the last word.
    val shown = remember(busy) { room }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        session.roomActions.running.first { room.token !in it }
        onDismiss()
    }
    LaunchedEffect(asking) {
        if (asking) onLeave(findLeavingDeletes(session, room))
    }
    fun pick(action: RoomAction) {
        if (!busy && onAction(action)) started = true
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                room.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )

            @Composable
            fun Action(label: String, icon: ImageVector, action: RoomAction) =
                MenuRow(label, icon, enabled = !busy, working = running == action) { pick(action) }

            if (shown.canBeMarkedRead(caps)) {
                Action("Mark as read", Icons.Outlined.DoneAll, RoomAction.MarkRead)
            } else if (shown.canBeMarkedUnread(caps)) {
                Action("Mark as unread", Icons.Outlined.MarkChatUnread, RoomAction.MarkUnread)
            }
            if (caps.favorites) {
                if (shown.isFavorite) Action("Remove from favourites", Icons.Filled.Star, RoomAction.Favourite(false))
                else Action("Add to favourites", Icons.Outlined.StarBorder, RoomAction.Favourite(true))
            }
            if (caps.notificationLevels) {
                NotificationsRow(shown.notificationLevel, open = levelsOpen, enabled = !busy) { levelsOpen = !levelsOpen }
                if (levelsOpen) {
                    for ((level, label) in LEVELS) {
                        CheckRow(
                            label,
                            checked = shown.notificationLevel == level,
                            role = Role.RadioButton,
                            enabled = !busy,
                            working = running == RoomAction.Notify(level),
                            // The level it has already asks for nothing.
                        ) { if (shown.notificationLevel == level) onDismiss() else pick(RoomAction.Notify(level)) }
                    }
                    // A switch of its own: a chat on mentions only can still ring for calls.
                    if (caps.notificationCalls && shown.type in RoomType.ONE_TO_ONE..RoomType.PUBLIC) {
                        val ring = RoomAction.RingForCalls(!shown.notificationCalls)
                        HorizontalDivider(Modifier.padding(start = 60.dp, top = 4.dp, bottom = 4.dp), color = colors.divider)
                        CheckRow(
                            "Ring me for calls here",
                            checked = shown.notificationCalls,
                            role = Role.Checkbox,
                            enabled = !busy,
                            working = running == ring,
                        ) { pick(ring) }
                    }
                }
            }
            if (caps.archivedConversations) {
                if (shown.isArchived) Action("Unarchive", Icons.Outlined.Unarchive, RoomAction.Archive(false))
                else Action("Archive", Icons.Outlined.Archive, RoomAction.Archive(true))
            }
            if (caps.importantConversations) {
                val important = shown.isImportant
                Action(
                    if (important) "Not important" else "Mark as important",
                    Icons.AutoMirrored.Outlined.LabelImportant,
                    RoomAction.Important(!important),
                )
            }
            if (onCall != null) MenuRow("Call", Icons.Outlined.Call, enabled = !busy, onClick = onCall)
            if (onMove != null) MenuRow("Move to folder", Icons.AutoMirrored.Outlined.DriveFileMove, enabled = !busy, onClick = onMove)
            if (shown.canBeLeft) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.divider)
                MenuRow("Leave", Icons.AutoMirrored.Outlined.Logout, enabled = !busy, working = asking, tint = colors.danger) {
                    asking = true
                }
            }
        }
    }
}

/** An action of the sheet; [working]: its request runs, a ring takes the icon's place. */
@Composable
private fun MenuRow(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    working: Boolean = false,
    tint: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    val ink = if (tint == Color.Unspecified) TalqTheme.colors.textPrimary else tint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled || working) 1f else 0.38f)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(icon, contentDescription = null, tint = ink)
        }
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Notifications" with the conversation's level; a tap shows the levels under it. */
@Composable
private fun NotificationsRow(level: Int, open: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val colors = TalqTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .clickable(enabled = enabled, role = Role.Button, onClick = onToggle)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val off = level == NotificationLevel.NEVER
        Icon(if (off) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications, contentDescription = null, tint = colors.textPrimary)
        Spacer(Modifier.width(16.dp))
        Text("Notifications", style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Text(
            LEVELS.firstOrNull { it.first == level }?.second.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = colors.textSecondary)
    }
}

/** A choice under "Notifications", a check on the one that holds (the topic menu's level row, set in under its heading). */
@Composable
private fun CheckRow(label: String, checked: Boolean, role: Role, enabled: Boolean, working: Boolean, onClick: () -> Unit) {
    val tap = if (role == Role.Checkbox) Modifier.toggleable(checked, enabled = enabled, role = role) { onClick() }
    else Modifier.selectable(checked, enabled = enabled, role = role, onClick = onClick)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(tap)
            .alpha(if (enabled || working) 1f else 0.38f)
            .padding(start = 60.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = TalqTheme.colors.accentText, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * "Leave this conversation?" with its [name] (beside the list another one may be open), Leave and Cancel,
 * asked once; [deletes]: what that does is said too. While [leaving] it stays up and takes nothing more: Leave
 * shows progress, Cancel and a tap outside do nothing. The caller closes it when the server has answered.
 */
@Composable
private fun LeaveDialog(name: String, deletes: Boolean, leaving: Boolean, onLeave: () -> Unit, onDismiss: () -> Unit) {
    val colors = TalqTheme.colors
    AlertDialog(
        onDismissRequest = { if (!leaving) onDismiss() },
        title = { Text("Leave this conversation?") },
        text = {
            Column {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (deletes) {
                    Text(
                        "You are the only member: leaving deletes it.",
                        color = colors.danger,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onLeave, enabled = !leaving) {
                if (leaving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Leaving…")
                } else {
                    Text("Leave", color = colors.danger)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !leaving) { Text("Cancel") } },
    )
}
