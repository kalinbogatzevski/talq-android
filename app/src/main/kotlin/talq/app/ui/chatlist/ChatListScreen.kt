package talq.app.ui.chatlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.TextButton
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import talq.app.shift.ErpLink
import talq.app.Brand
import talq.app.ui.call.CallInProgressBar
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import talq.app.appContainer
import talq.app.appName
import talq.app.folders.folderResultText
import talq.app.theme.TalqColors
import talq.app.theme.TalqTheme
import talq.app.ui.common.Avatar
import talq.app.ui.common.ConnectionLine
import talq.app.ui.common.ConnectionState
import talq.app.ui.common.connectionState
import talq.app.ui.common.MessagePane
import talq.app.ui.common.StatusAvatar
import talq.app.ui.common.firstName
import talq.app.ui.common.onSecondaryClick
import talq.app.ui.common.relativeTime
import talq.app.ui.common.userMessage
import talq.app.ui.message.previewText
import talq.app.ui.update.UpdateBanner
import talq.app.ui.update.showsUpdateBanner
import talq.app.ui.message.topicColor
import talq.app.ui.chat.ComposerDrafts
import talq.app.ui.chat.sayAnywhere
import talq.app.ui.status.OwnStatusSheet
import talq.core.TalkError
import talq.core.model.NotificationLevel
import talq.core.model.Room
import talq.core.model.RoomType
import talq.core.repo.TalkSession
import talq.core.shift.ShiftState
import talq.core.folders.FolderRollup
import talq.core.folders.FolderSection
import talq.core.folders.addToRollup
import talq.core.folders.buildRoomSections
import talq.core.folders.roomFolderMember

/** How often the list is reloaded while it is on screen. */
private const val REFRESH_MILLIS = 12_000L

/**
 * [search]: the search field's text, and [newChat]: the "+" sheet's model, both held by the caller
 * so both places that draw this list share them (see ChatSearch.kt, NewConversationSheet.kt).
 * [selectedToken]: the conversation open beside the list (wide layout), drawn selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    session: TalkSession,
    search: MutableState<String>,
    newChat: NewConversationModel,
    onOpenRoom: (token: String) -> Unit,
    onOpenTopic: (token: String, topicId: Long) -> Unit,
    onOpenSettings: () -> Unit,
    /** Settings, scrolled to its Notifications section (the notice's Fix when push is not set up). */
    onOpenPushSettings: () -> Unit,
    onUnauthorized: () -> Unit,
    selectedToken: String? = null,
) {
    val rooms by session.rooms.rooms.collectAsStateWithLifecycle()
    val load by session.rooms.state.collectAsStateWithLifecycle()
    val withTopics by session.roomsWithTopics.collectAsStateWithLifecycle()
    // Shift state of colleagues, as the app-level service knows it. Reading only: the
    // service decides by itself when to ask the ERP.
    val shiftService = LocalContext.current.appContainer.shift
    val shifts by shiftService.statuses.collectAsStateWithLifecycle()
    // The branded build expects the phone to be paired with the ERP: say so while it is not.
    val erpLink by shiftService.link.collectAsStateWithLifecycle()
    val erpLinkKnown by shiftService.linkKnown.collectAsStateWithLifecycle()
    val pairReminder = rememberPairReminder()
    // The branded build's own update offer (nothing in the generic build).
    val updates = LocalContext.current.appContainer.updates
    val update by updates.state.collectAsStateWithLifecycle()
    val updateDismissed by updates.dismissed.collectAsStateWithLifecycle()
    val updateBanner = updates.enabled && showsUpdateBanner(update, updateDismissed)
    // The update banner, like any new first row, is brought into view by the topKey rule below.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var pulling by remember { mutableStateOf(false) }
    // Conversation folders (TalQ for desktop's "Group by Folders"), from the app-level controller
    // like shift and updates, so both places that show this list have them without a parameter.
    val folders = LocalContext.current.appContainer.folders
    val folderUi by folders.ui.collectAsStateWithLifecycle()
    val overlays = rememberFolderOverlays()
    val appLabel = appName()
    // Your own status, shown and set from the header (TalQ for desktop's profile bar).
    val ownStatus = LocalContext.current.appContainer.ownStatus
    val statusUi by ownStatus.ui.collectAsStateWithLifecycle()
    var editingStatus by rememberSaveable { mutableStateOf(false) }
    // "New conversation" (+): [newChat] is held by the caller (NewConversationSheet.kt).
    var startingChat by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val say: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }

    // Reload while visible and at once on every return to the screen.
    LifecycleResumeEffect(session) {
        session.rooms.startAutoRefresh(REFRESH_MILLIS)
        onPauseOrDispose { session.rooms.stopAutoRefresh() }
    }
    LaunchedEffect(load.error) {
        if (load.error is TalkError.Unauthorized) onUnauthorized()
    }

    // Archived chats are left out of the list, as in TalQ for desktop and Talk on the web; an
    // "Archived" row at the end shows them on request (not saved: the list opens without them).
    val sortedAll = remember(rooms) {
        rooms.sortedWith(compareByDescending<Room> { it.isFavorite }.thenByDescending { it.lastActivity })
    }
    val sorted = remember(sortedAll) { sortedAll.filter { !it.isArchived } }
    val archived = remember(sortedAll) { sortedAll.filter { it.isArchived } }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    // The search (rules in ChatSearch.kt): names only, the rooms already held, as you type.
    var query by search
    val needle = remember(query) { searchFold(query.trim()) }
    val searching = needle.isNotEmpty()
    val foldedNames = remember(rooms) { rooms.associate { it.token to searchFold(it.displayName) } }
    val shown = remember(sorted, foldedNames, needle) {
        if (needle.isEmpty()) sorted else sorted.filter { foldedNames[it.token]?.contains(needle) == true }
    }
    val shownArchived = remember(archived, foldedNames, needle) {
        if (needle.isEmpty()) archived else archived.filter { foldedNames[it.token]?.contains(needle) == true }
    }
    val focusManager = LocalFocusManager.current
    // What the sheet made or found opens here, once the screen is in front (a tap would count
    // for nothing before that).
    val lifeState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val inFront = lifeState.isAtLeast(Lifecycle.State.RESUMED)
    val newChatDone = newChat.done
    LaunchedEffect(newChatDone, inFront) {
        if (newChatDone == null || !inFront) return@LaunchedEffect
        newChat.consumeDone()
        startingChat = false
        focusManager.clearFocus()
        onOpenRoom(newChatDone.token)
        newChatDone.notice?.let { sayAnywhere(context, it) }
    }
    // Back empties the field first (the keyboard, when up, takes the first Back itself).
    BackHandler(enabled = query.isNotEmpty()) {
        query = ""
        focusManager.clearFocus()
    }
    // A changed search starts at the top; coming back to the list keeps its place.
    var listedFor by rememberSaveable { mutableStateOf(needle) }
    LaunchedEffect(needle) {
        if (needle != listedFor) {
            listedFor = needle
            listState.scrollToItem(0)
        }
    }
    // In folders only when "Show folders" is on and a folder exists; flat as before otherwise.
    // Each folder takes its chats in the flat list's order (favourites stay on top inside it).
    // countMuted = false is desktop's default ("Count muted chats and topics" off) and the rule of
    // the launcher number: a chat set to Never adds nothing to its closed folder's badge. A search
    // shows every folder open and leaves out the ones without a match.
    val sections = remember(shown, folderUi.state, folderUi.grouped, searching) {
        if (folderUi.grouped) buildRoomSections(shown, folderUi.state, countMuted = false, searching = searching) else null
    }
    // The "Archived" header counts like a folder's (a chat on Never adds nothing).
    val archivedRollup = remember(shownArchived) {
        shownArchived.fold(FolderRollup()) { r, room ->
            val m = roomFolderMember(room, countMuted = false)
            addToRollup(r, m.unread, m.mention, m.directMention)
        }
    }
    val unpaired = erpLink is ErpLink.NotPaired || erpLink is ErpLink.Rejected
    val pairShown = Brand.isBranded && erpLinkKnown && unpaired && pairReminder.due && !searching
    // Under the header, one line at most: the connection (no network, or the server not answering;
    // the list's failed reloads say nothing else then), else "notifications are off". With no list
    // yet the empty pane says it instead. Offline, push looks broken too: its line waits.
    val network = LocalContext.current.appContainer.network
    val link by network.link.collectAsStateWithLifecycle()
    val networkFailing = load.error is TalkError.Network
    val connection = connectionState(link, networkFailing)
    val notifyProblem = rememberNotifyProblem(LocalContext.current.appContainer.pushRegistrar)
    val noticeDismissed by LocalContext.current.appContainer.notifyNoticeDismissed.collectAsStateWithLifecycle()
    val noticeAccount = remember(session) { ComposerDrafts.accountOf(session) }
    val notifyShown = notifyProblem != null && connection == null && !searching && noticeDismissed != noticeAccount
    // Only an error the connection line does not already tell gets the row in the list.
    val listError = load.error?.takeIf { it !is TalkError.Network }
    // The key of the list's first row, in the order the list below draws them (null: no list).
    val topKey: String? = when {
        rooms.isEmpty() -> null
        updateBanner && !searching -> "update"
        pairShown -> "pair"
        listError != null -> "error"
        searching && shown.isEmpty() && shownArchived.isEmpty() -> "x:nomatch"
        sections == null -> shown.firstOrNull()?.token
        else -> sections.firstOrNull()?.let { sectionKey(it.section) }
    } ?: if (rooms.isNotEmpty() && shownArchived.isNotEmpty()) "x:archived" else null
    // The first conversation the list draws (in folders: the first one of the first open folder).
    val topRoom: String? = when {
        rooms.isEmpty() -> null
        sections == null -> shown.firstOrNull()?.token
        else -> sections.firstOrNull { !it.closed && it.members.isNotEmpty() }?.members?.first()?.token
    }
    // New arrivals at the top come into view. A keyed list holds the row it showed first, so a chat
    // that just got a message (or a notice, or the update banner) put above it would sit just above
    // the screen. When the first row or the first conversation changes while the list is at or near
    // its top (first visible row 0..2, the update banner's old rule), the list goes to its top
    // instead. Decided before the new list is measured (a SideEffect runs before the layout of its
    // frame), never against a finger or a fling. Scrolled further down: nothing moves. Saved, so
    // coming back to the list (or turning the phone) compares with what was shown before.
    val shownTop = rememberSaveable(saver = TopRow.Saver) { TopRow() }
    SideEffect {
        val changed = topKey != shownTop.key || topRoom != shownTop.room
        val known = shownTop.key != null
        shownTop.key = topKey
        shownTop.room = topRoom
        if (changed && known && topKey != null &&
            listState.firstVisibleItemIndex <= 2 && !listState.isScrollInProgress
        ) {
            listState.requestScrollToItem(0)
        }
    }
    // The conversation open beside the list (wide layout) in a closed folder opens that folder,
    // as desktop does for a chat that is opened. Once per opening, not on the folders: closing
    // that folder afterwards keeps it closed, also when the device is turned (hence saved).
    var revealedFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedToken, rooms.isEmpty()) {
        val token = selectedToken
        if (token == null) {
            revealedFor = null
            return@LaunchedEffect
        }
        if (token == revealedFor || session.rooms.room(token) == null) return@LaunchedEffect
        revealedFor = token
        // While searching every folder already shows open: nothing is toggled (desktop's
        // revealToken), so a pick from the results leaves the folders as they were.
        if (!searching) folders.reveal(token)
    }
    // Unsent text waiting in a conversation: its row says "Draft:" instead of the last message.
    // Not for the one open beside the list (its box is on screen): typing there changes nothing here.
    val drafts = LocalContext.current.appContainer.drafts
    val draftAccount = remember(session) { ComposerDrafts.accountOf(session) }
    val draftLines by remember(drafts, draftAccount, selectedToken) {
        derivedStateOf { drafts.previews(draftAccount, except = selectedToken) }
    }
    // Keeps "12m" honest between reloads.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    Scaffold(
        topBar = {
            Column {
                ProfileBar(
                    session = session,
                    status = statusUi,
                    onSetStatus = {
                        // Not read yet (or the presets are missing): ask again while the sheet opens.
                        if (!statusUi.loaded || statusUi.predefined.isEmpty()) ownStatus.refresh()
                        editingStatus = true
                    },
                ) {
                    IconButton(onClick = {
                        newChat.reset()
                        startingChat = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "New conversation")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
                // Always visible, as on desktop: one tap to type, and it shows that search exists.
                ChatSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                CallInProgressBar()
                if (connection != null && rooms.isNotEmpty()) {
                    ConnectionLine(connection)
                } else if (notifyShown) {
                    NotificationsNotice(
                        problem = notifyProblem,
                        onFix = { fixNotifications(context, notifyProblem, onOpenPushSettings) },
                        onDismiss = { context.appContainer.notifyNoticeDismissed.value = noticeAccount },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = pulling,
            onRefresh = {
                scope.launch {
                    pulling = true
                    session.rooms.refresh()
                    pulling = false
                }
            },
            // Edge to edge, the keyboard does not shrink the window: the results end above it.
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
        ) {
            val error = load.error
            when {
                rooms.isEmpty() && !load.loaded && error == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }

                // Offline: it loads by itself once the network is back (the reload timer is woken).
                rooms.isEmpty() && error != null && connection == ConnectionState.Offline ->
                    MessagePane(title = "No connection", detail = "Your conversations load when you're back online.")

                rooms.isEmpty() && error != null ->
                    MessagePane(
                        title = "Couldn't load your conversations",
                        detail = error.userMessage(),
                        action = "Try again",
                        onAction = { scope.launch { session.rooms.refresh() } },
                    )

                rooms.isEmpty() ->
                    MessagePane(
                        title = "No conversations yet",
                        detail = "Conversations you join on your server show up here.",
                    )

                else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    // While searching, only the results (and the stale-list notice).
                    // The order of these first rows is also [topKey]'s: keep the two together.
                    if (updateBanner && !searching) {
                        item(key = "update") { UpdateBanner(update, updates) }
                    }
                    if (pairShown) {
                        item(key = "pair") {
                            PairBanner(
                                rejected = erpLink is ErpLink.Rejected,
                                onPair = onOpenSettings,
                                onLater = pairReminder::snooze,
                            )
                        }
                    }
                    if (listError != null) {
                        item(key = "error") { StaleBanner(listError.userMessage()) }
                    }
                    if (searching && shown.isEmpty() && shownArchived.isEmpty()) {
                        // "x:" cannot meet a room token ([a-z0-9] only). At the top: the keyboard
                        // covers the middle of the list.
                        item(key = "x:nomatch") {
                            Text(
                                text = "No conversations match \"${query.trim()}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TalqTheme.colors.textSecondary,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                            )
                        }
                    }
                    // Opening a conversation puts the keyboard away (it would cover the room beside
                    // the list on a wide window).
                    val roomItem: @Composable (Room) -> Unit = { room ->
                        RoomRow(
                            session, room, now,
                            hasTopics = room.token in withTopics,
                            shift = shifts[room.partnerUserId]?.state ?: ShiftState.Unknown,
                            selected = room.token == selectedToken,
                            draft = draftLines[room.token],
                            onClick = {
                                focusManager.clearFocus()
                                onOpenRoom(room.token)
                            },
                            onOpenTopic = { id ->
                                focusManager.clearFocus()
                                onOpenTopic(room.token, id)
                            },
                            // "Move to folder", in the flat list too: it is where the first folder is made.
                            onLongClick = if (folderUi.attached) ({ overlays.moveFor = room.token }) else null,
                        )
                    }
                    if (sections == null) {
                        items(shown, key = { it.token }) { room -> roomItem(room) }
                    } else {
                        // "folder:" keys cannot meet a room token ([a-z0-9] only). A key twice would
                        // crash the list; folder ids are unique in every state the store holds, so
                        // this only guards.
                        val keys = HashSet<String>()
                        for (rows in sections) {
                            val section = rows.section
                            val key = sectionKey(section)
                            if (!keys.add(key)) continue
                            item(key = key) {
                                when (section) {
                                    // While searching every folder shows open: a tap would change
                                    // a stored state nobody sees, so it does nothing (as on desktop).
                                    is FolderSection.InFolder -> FolderHeader(
                                        name = section.folder.name,
                                        rollup = rows.rollup,
                                        closed = rows.closed,
                                        onToggle = if (searching) null else ({
                                            folderResultText(folders.toggle(section.folder.id), folderUi.readOnly, appLabel)?.let(say)
                                        }),
                                        onLongPress = { overlays.menuFor = section.folder.id },
                                    )
                                    FolderSection.Other -> FolderHeader(
                                        name = OTHER_SECTION_NAME,
                                        rollup = rows.rollup,
                                        closed = rows.closed,
                                        onToggle = if (searching) null else ({
                                            folderResultText(folders.toggleOther(), folderUi.readOnly, appLabel)?.let(say)
                                        }),
                                        onLongPress = null,
                                    )
                                }
                            }
                            if (!rows.closed) {
                                items(rows.members, key = { it.token }) { room -> roomItem(room) }
                            }
                        }
                    }
                    // While searching: only the archived conversations that match, behind the row
                    // as always (desktop leaves them out of a search unless asked for archived).
                    if (shownArchived.isNotEmpty()) {
                        // "x:" cannot meet a room token ([a-z0-9] only).
                        item(key = "x:archived") {
                            FolderHeader(
                                name = "Archived",
                                rollup = archivedRollup,
                                closed = !showArchived,
                                onToggle = { showArchived = !showArchived },
                                onLongPress = null,
                            )
                        }
                        if (showArchived) {
                            items(shownArchived, key = { "a:" + it.token }) { room -> roomItem(room) }
                        }
                    }
                }
            }
        }
    }

    FolderOverlayHost(
        overlays = overlays,
        folders = folders,
        ui = folderUi,
        roomName = { token -> session.rooms.room(token)?.displayName },
        say = say,
    )
    if (editingStatus && statusUi.available) OwnStatusSheet(ownStatus) { editingStatus = false }
    if (startingChat) NewConversationSheet(session, newChat) { startingChat = false }
}

/** The first row's key and the first conversation the list showed, as of the last composition. Not state. */
private class TopRow(var key: String? = null, var room: String? = null) {
    companion object {
        val Saver = listSaver<TopRow, String?>(save = { listOf(it.key, it.room) }, restore = { TopRow(it[0], it[1]) })
    }
}

/** A folder section's header key. "folder:" keys cannot meet a room token ([a-z0-9] only). */
private fun sectionKey(section: FolderSection): String = when (section) {
    is FolderSection.InFolder -> "folder:${section.folder.id}"
    FolderSection.Other -> "folder:other"
}

/**
 * The branded build's "this phone is not paired with the ERP" notice: Pair opens Settings
 * (Shift status), Not now hides it for a week. Without pairing no shift light appears and
 * nothing else says why.
 */
@Composable
private fun PairBanner(rejected: Boolean, onPair: () -> Unit, onLater: () -> Unit) {
    val colors = TalqTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bgSecondary)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
    ) {
        Text("Shift status is off", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
        Text(
            if (rejected) "The ERP no longer accepts this phone. Pair it again to see who is on shift."
            else "This phone is not paired with the ERP. Pair it once to see who is on shift.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, end = 8.dp),
        )
        Row {
            TextButton(onClick = onPair) { Text("Pair") }
            TextButton(onClick = onLater) { Text("Not now", color = colors.textSecondary) }
        }
    }
}

/** "Not now" on the pairing notice, remembered on the phone for a week. */
private class PairReminder(private val prefs: android.content.SharedPreferences) {
    var due by mutableStateOf(System.currentTimeMillis() >= prefs.getLong(KEY, 0L))
        private set

    fun snooze() {
        prefs.edit().putLong(KEY, System.currentTimeMillis() + WEEK_MILLIS).apply()
        due = false
    }

    private companion object {
        const val KEY = "pair_notice_snoozed_until"
        const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

@Composable
private fun rememberPairReminder(): PairReminder {
    val context = LocalContext.current
    return remember { PairReminder(context.applicationContext.getSharedPreferences("notices", android.content.Context.MODE_PRIVATE)) }
}

/** The list is old because the last reload failed. Quiet: the data is still useful. */
@Composable
private fun StaleBanner(message: String) {
    Text(
        text = "$message Showing the last list.",
        style = MaterialTheme.typography.bodySmall,
        color = TalqTheme.colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .background(TalqTheme.colors.bgSecondary)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * [draft]: unsent text waiting in this conversation (one line), shown in place of the last message.
 * [onLongClick]: "Move to folder" (null while no account's folders are attached).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomRow(
    session: TalkSession,
    room: Room,
    nowMillis: Long,
    hasTopics: Boolean,
    shift: ShiftState,
    selected: Boolean,
    draft: String?,
    onClick: () -> Unit,
    onOpenTopic: (topicId: Long) -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = TalqTheme.colors
    val context = LocalContext.current
    val dark = !TalqTheme.colors.isLight
    val muted = room.notificationLevel == NotificationLevel.NEVER
    val unread = room.unreadMessages > 0
    // The newest unread message sits in a topic: name the topic as a pill that opens it.
    val last = room.lastMessage
    val unreadTopic = last?.takeIf {
        unread && it.topicId != 0L && it.threadTitle.isNotBlank() && it.id > room.lastReadMessage &&
            !it.isSystem && !session.isMe(it.actorType, it.actorId) &&
            // Already read in that topic this session (the room's marker is held back by an
            // older unread message somewhere else): the pill would open a tab with nothing new.
            (session.unreadIfCreated(room.token)?.state?.value?.seenUpTo(it.topicId) ?: 0L) < it.id
    }
    val preview = remember(room.lastMessage, colors, unreadTopic != null, draft) {
        if (draft != null) draftLine(draft, colors) else previewLine(session, room, colors, withTopic = unreadTopic == null)
    }

    // The open conversation (wide layout) is highlighted; its pills then take the list's own
    // ground, which would otherwise be the same colour as the highlight. Under a mouse pointer
    // the row takes the hover ground (desktop's sidebar: bgSelected, else bgHover).
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val ground = when {
        selected -> colors.bgSelected
        hovered -> colors.bgHover
        else -> MaterialTheme.colorScheme.background
    }
    val chipFill = if (selected) MaterialTheme.colorScheme.background else colors.bgSelected
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ground)
            // Not `selected`: TalkBack would read every other row as an unchecked toggle.
            .then(if (selected) Modifier.semantics { stateDescription = "Open" } else Modifier)
            .hoverable(hover)
            // A right-click opens what touch and hold opens.
            .onSecondaryClick(onLongClick)
            .combinedClickable(
                onLongClickLabel = if (onLongClick != null) "Move to folder" else null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (room.isOneToOne) {
            // Presence disc bottom right, shift square bottom left.
            StatusAvatar(
                url = session.client.roomAvatarUrl(room.token, room.avatarVersion, dark),
                authorization = session.client.authorizationHeader,
                name = room.displayName,
                ring = ground,
                size = 52.dp,
                presence = room.status,
                shift = shift,
            )
        } else {
            Avatar(
                url = session.client.roomAvatarUrl(room.token, room.avatarVersion, dark),
                authorization = session.client.authorizationHeader,
                name = room.displayName,
                size = 52.dp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = room.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (room.isFavorite) {
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.Filled.Star, contentDescription = "Favourite", tint = colors.amber, modifier = Modifier.size(14.dp))
                    }
                    if (muted) {
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            Icons.Filled.NotificationsOff,
                            contentDescription = "Muted",
                            tint = colors.textMuted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = relativeTime(context, room.lastActivity, nowMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread && !muted) colors.accentText else colors.textMuted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The badges are measured first; the pill and the preview share the rest, so a
                // long topic title in a narrow list cannot squeeze the unread count away. The pill
                // takes at most PILL_SHARE of that line (its title ellipsized), so the message keeps
                // the larger part; a wide list gives both more room.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (unreadTopic != null) {
                        TopicPill(
                            unreadTopic.topicId, unreadTopic.threadTitle.trim(), chipFill,
                            modifier = Modifier.maxWidthShare(PILL_SHARE),
                        ) { onOpenTopic(unreadTopic.topicId) }
                        Spacer(Modifier.width(6.dp))
                    } else if (hasTopics) {
                        Icon(
                            Icons.Outlined.Forum,
                            contentDescription = "Has topics",
                            tint = colors.textMuted,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (muted) colors.textMuted else colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Who needs you: "@" + the count in red-orange when you were named, amber for a
                // mention of everyone, the accent otherwise; a muted chat stays quiet (only a direct
                // "@" still shows its colour). Desktop shows a muted chat no badge at all.
                if (room.unreadMention || unread) {
                    val fill = colors.unreadFill(room.unreadMention, room.unreadMentionDirect)
                    Row(
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = unreadWords(room.unreadMessages, room.unreadMention, room.unreadMentionDirect)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (room.unreadMention) {
                            Spacer(Modifier.width(6.dp))
                            Badge("@", muted = muted && !room.unreadMentionDirect, mutedFill = chipFill, fill = fill)
                        }
                        if (unread) {
                            Spacer(Modifier.width(6.dp))
                            Badge(if (room.unreadMessages > 999) "999+" else room.unreadMessages.toString(), muted, mutedFill = chipFill, fill = fill)
                        }
                    }
                }
            }
        }
    }
}

/** The most of a row's second line the topic pill may take; the preview keeps the rest. */
private const val PILL_SHARE = 0.45f

/** At most [share] of the width this is offered (a Row offers its first child the whole line). */
private fun Modifier.maxWidthShare(share: Float): Modifier = layout { measurable, constraints ->
    val cap = if (constraints.hasBoundedWidth) {
        (constraints.maxWidth * share).roundToInt().coerceAtLeast(constraints.minWidth)
    } else {
        constraints.maxWidth
    }
    val placeable = measurable.measure(constraints.copy(maxWidth = cap))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** The topic of the newest unread message: tap it to open the conversation on that topic. */
@Composable
private fun TopicPill(topicId: Long, title: String, fill: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = TalqTheme.colors
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(fill)
            .clickable(role = Role.Button, onClickLabel = "Open topic $title", onClick = onClick)
            .padding(start = 5.dp, end = 9.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(topicColor(topicId)))
        Spacer(Modifier.width(5.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Unread pill: [fill] (see [unreadFill]; [TalqColors.countFill] when null) when it wants
 * attention, the quiet [mutedFill] for a muted conversation. The ink is chosen against the fill it
 * sits on ([TalqColors.inkOn]), so the number reads on every theme.
 */
@Composable
internal fun Badge(text: String, muted: Boolean, mutedFill: Color, fill: Color? = null) {
    val colors = TalqTheme.colors
    val ground = if (muted) mutedFill else fill ?: colors.countFill
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .clip(CircleShape)
            .background(ground)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (muted) colors.textSecondary else colors.inkOn(ground),
            maxLines = 1,
        )
    }
}

/**
 * TalQ for desktop's three unread levels (SidebarPainter `paintUnreadBadge`): someone mentioned you
 * directly = danger, a mention of everyone = amber, plain unread = the accent (as
 * [TalqColors.countFill]). The one rule for the row badges and the folder headers. A server without
 * `direct-mention-flag` never sets [direct], so every mention stays amber there.
 */
internal fun TalqColors.unreadFill(mention: Boolean, direct: Boolean): Color = when {
    direct -> danger
    mention -> amber
    else -> countFill
}

/** What the unread pills say to a screen reader (the colour is not the only sign either: "@"). */
internal fun unreadWords(unread: Int, mention: Boolean, direct: Boolean): String {
    val count = if (unread > 0) "$unread unread" else null
    val who = when {
        direct -> "you are mentioned"
        mention -> "with a mention"
        else -> null
    }
    return listOfNotNull(count, who).joinToString(", ").replaceFirstChar { it.uppercase() }
}

/** "Draft: text", the label in the accent ink (as text), the typed text as any preview. */
private fun draftLine(text: String, colors: TalqColors): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = colors.accentText, fontWeight = FontWeight.Medium)) { append("Draft:") }
    append(" ")
    append(text)
}

/**
 * "Sender: text", or "Topic · Sender: text" when the last message sits in a topic (the server
 * repeats the topic title on its messages; [withTopic] false when the row shows the topic as a pill). One-to-one chats skip the other person's name.
 */
private fun previewLine(session: TalkSession, room: Room, colors: TalqColors, withTopic: Boolean = true): AnnotatedString {
    val last = room.lastMessage ?: return AnnotatedString("")
    val text = previewText(last)
    if (last.isSystem) {
        return buildAnnotatedString { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text) } }
    }
    val mine = session.isMe(last.actorType, last.actorId)
    val sender = when {
        mine -> "You"
        room.type == RoomType.ONE_TO_ONE || room.type == RoomType.NOTE_TO_SELF ||
            room.type == RoomType.FORMER_ONE_TO_ONE || room.type == RoomType.CHANGELOG -> ""
        else -> firstName(last.actorDisplayName)
    }
    return buildAnnotatedString {
        if (withTopic && last.topicId != 0L && last.threadTitle.isNotBlank()) {
            withStyle(SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.Medium)) {
                append(last.threadTitle.trim())
            }
            append(" · ")
        }
        if (sender.isNotEmpty()) {
            append(sender)
            append(": ")
        }
        append(if (last.deleted) "Message deleted" else text)
    }
}
