package talq.app.ui.chatlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.TextButton
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalFocusManager
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
import talq.app.SessionState
import talq.app.appContainer
import talq.app.appName
import talq.app.folders.folderResultText
import talq.app.status.OwnStatusUi
import talq.app.theme.TalqColors
import talq.app.theme.TalqTheme
import talq.app.ui.adaptive.LocalTwoPane
import talq.app.ui.common.Avatar
import talq.app.ui.common.ConnectionLine
import talq.app.ui.common.ConnectionState
import talq.app.ui.common.connectionState
import talq.app.ui.common.MessagePane
import talq.app.ui.common.SavedLine
import talq.app.ui.common.StatusAvatar
import talq.app.ui.common.WAITING_FOR_SERVER
import talq.app.ui.common.firstName
import talq.app.ui.common.onSecondaryClick
import talq.app.ui.common.relativeTime
import talq.app.ui.common.savedState
import talq.app.ui.common.userMessage
import talq.app.ui.message.previewText
import talq.app.ui.update.UpdateBanner
import talq.app.ui.update.showsUpdateBanner
import talq.app.ui.message.topicColor
import talq.app.ui.chat.ComposerDrafts
import talq.app.ui.chat.sayAnywhere
import talq.app.ui.room.RoomTab
import talq.app.ui.status.OwnStatusSheet
import talq.core.TalkError
import talq.core.model.NotificationLevel
import talq.core.model.Room
import talq.core.model.RoomFilter
import talq.core.model.RoomType
import talq.core.repo.OutboxMessage
import talq.core.repo.TalkSession
import talq.core.shift.ShiftState
import talq.core.folders.FolderRollup
import talq.core.folders.FolderSection
import talq.core.folders.addToRollup
import talq.core.folders.buildRoomSections
import talq.core.folders.roomFolderMember
import talq.core.repo.listedRoom

/** How often the list is reloaded while it is on screen. */
private const val REFRESH_MILLIS = 12_000L

/** On a phone the list has made way for the conversation that was tapped by then. */
private const val LEAVING_MILLIS = 1_000L

/**
 * [search]: the search field's text, and [newChat]: the "+" sheet's model, both held by the caller
 * so both places that draw this list share them (see ChatSearch.kt, NewConversationSheet.kt).
 * [selectedToken]: the conversation open beside the list (wide layout), drawn selected.
 *
 * Until the server's list is there the rows are the saved ones (`AppContainer.saved`), also while [session] is
 * not checked yet: they sort, filter, search and open like any others, and nothing else is offered on them.
 * Their unread numbers, the presence and "Archived"'s and the folders' sums come with the server's list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    session: TalkSession,
    search: MutableState<String>,
    newChat: NewConversationModel,
    /** [draftTab]: the tab that holds the row's "Not sent:" or "Draft:" text (a `RoomTab` key or KEY_NEW_TOPIC); null without one. */
    onOpenRoom: (token: String, draftTab: Long?) -> Unit,
    onOpenTopic: (token: String, topicId: Long) -> Unit,
    onOpenSettings: () -> Unit,
    /** Settings, scrolled to its Notifications section (the notice's Fix when push is not set up). */
    onOpenPushSettings: () -> Unit,
    onUnauthorized: () -> Unit,
    selectedToken: String? = null,
) {
    val container = LocalContext.current.appContainer
    val serverRooms by session.rooms.rooms.collectAsStateWithLifecycle()
    val load by session.rooms.state.collectAsStateWithLifecycle()
    val start by container.saved.collectAsStateWithLifecycle()
    val saved = start?.takeIf { !load.loaded && ComposerDrafts.accountOf(it.session) == ComposerDrafts.accountOf(session) }
    val live = saved == null
    val rooms = saved?.rooms ?: serverRooms
    val withTopics by session.roomsWithTopics.collectAsStateWithLifecycle()
    // What of a chat's unread sits in topics on Never: its grey number, beside the coloured one. What was
    // looked at here while a muted topic holds the read marker is in neither.
    val silenced by session.silencedUnread.figures.collectAsStateWithLifecycle()
    val unreadHere by session.unreadHere.collectAsStateWithLifecycle()
    // "Sort by" and "Show" (ListSort.kt, ListFilter.kt), kept on the device. The list waits for them.
    val listSort = LocalContext.current.appContainer.listSort
    val sort by listSort.sort.collectAsStateWithLifecycle()
    val filter by listSort.filter.collectAsStateWithLifecycle()
    val orderKnown by listSort.known.collectAsStateWithLifecycle()
    val beside = LocalTwoPane.current
    // A phone's list fades out under the conversation that was tapped: for that moment it is the open one.
    var leavingFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(leavingFor) {
        if (leavingFor == null) return@LaunchedEffect
        delay(LEAVING_MILLIS)
        leavingFor = null
    }
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
    // Touch and hold (or right-click) a conversation: its menu (RoomMenu.kt).
    val roomMenu = rememberRoomMenu()
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

    // Reload while visible and at once on every return to the screen. Not before the account is checked.
    LifecycleResumeEffect(session) {
        if (session.checked) session.rooms.startAutoRefresh(REFRESH_MILLIS)
        onPauseOrDispose { session.rooms.stopAutoRefresh() }
    }
    LaunchedEffect(load.error) {
        if (load.error is TalkError.Unauthorized) onUnauthorized()
    }

    val listed = remember(rooms, silenced, unreadHere) { rooms.map { listedRoom(it, silenced[it.token], unreadHere[it.token]) } }
    val counted = remember(listed) { listed.map { it.room } }
    val mutedOf = remember(listed) { listed.associate { it.room.token to it.muted } }
    // Archived chats are left out of the list, as in TalQ for desktop and Talk on the web; an
    // "Archived" row at the end shows them on request (not saved: the list opens without them). It is there
    // while all conversations are shown; "Show" can show them alone.
    val listing = rememberRoomListing(counted, sort, filter, mutedOf, openToken = selectedToken ?: leavingFor, beside = beside)
    // Unread numbers are the server's: the saved ones only give a row its place.
    val sorted = remember(listing, live) { if (live) listing.main else listing.main.map { it.withoutUnread() } }
    val archived = remember(listing, live) { if (live) listing.archived else listing.archived.map { it.withoutUnread() } }
    val mutedShown = if (live) mutedOf else emptyMap()
    var showArchived by rememberSaveable { mutableStateOf(false) }
    // What is shown anew starts at its top, like a search.
    val show: (RoomFilter) -> Unit = {
        listSort.set(it)
        listState.requestScrollToItem(0)
    }
    // The search (rules in ChatSearch.kt): names only, the rooms already held, as you type.
    var query by search
    val needle = remember(query) { searchFold(query.trim()) }
    val searching = needle.isNotEmpty()
    val slide = rememberRowsSlide(sort, filter, searching)
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
        onOpenRoom(newChatDone.token, null)
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
    // the launcher number: what was muted adds nothing to its folder's coloured badge; it has a grey
    // one. A search shows every folder open; it and "Show" leave out the ones without a match.
    val filtering = searching || filter != RoomFilter.All
    val sections = remember(shown, folderUi.state, folderUi.grouped, searching, filtering, mutedShown) {
        if (folderUi.grouped) {
            buildRoomSections(shown, folderUi.state, countMuted = false, searching = searching, filterActive = filtering) {
                mutedShown[it.token] ?: 0
            }
        } else {
            null
        }
    }
    // The one line in place of the rows: a search that found nothing, or nothing in what is shown.
    val nothing: String? = when {
        shown.isNotEmpty() || shownArchived.isNotEmpty() -> null
        searching -> "No conversations match \"${query.trim()}\""
        filter != RoomFilter.All -> emptyLine(filter)
        else -> null
    }
    // The "Archived" header counts like a folder's.
    val archivedRollup = remember(shownArchived, mutedShown) {
        shownArchived.fold(FolderRollup()) { r, room ->
            val m = roomFolderMember(room, countMuted = false, mutedPart = mutedShown[room.token] ?: 0)
            addToRollup(r, m.unread, m.mention, m.directMention, m.muted)
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
    // While the rows are the saved ones, what they wait for (the check of the account, then the list) says it all.
    val check by container.sessionState.collectAsStateWithLifecycle()
    val savedLine = if (live) null else savedState(link, (check as? SessionState.Unreachable)?.error ?: load.error)
    val notifyNotices = LocalContext.current.appContainer.notifyNotices
    val noticeAccount = remember(session) { ComposerDrafts.accountOf(session) }
    val notifyProblem = rememberNotifyProblem(LocalContext.current.appContainer.pushRegistrar, notifyNotices, noticeAccount)
    val notifyShown = notifyProblem != null && connection == null && live && !searching
    // Only an error the connection line does not already tell gets the row in the list.
    val listError = load.error?.takeIf { it !is TalkError.Network && live }
    val drawn = rooms.isNotEmpty() && orderKnown
    // The key of the list's first row, in the order the list below draws them (null: no list).
    val topKey: String? = when {
        !drawn -> null
        updateBanner && !searching -> "update"
        pairShown -> "pair"
        listError != null -> "error"
        nothing != null -> "x:nomatch"
        sections == null -> shown.firstOrNull()?.token
        else -> sections.firstOrNull()?.let { sectionKey(it.section) }
    } ?: if (drawn && shownArchived.isNotEmpty()) "x:archived" else null
    // The first conversation the list draws (in folders: the first one of the first open folder).
    val topRoom: String? = when {
        !drawn -> null
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
    val rowsDrawn = remember(shown, sections) { rowKeys(shown, sections) }
    SideEffect {
        // In "Unread" the first one also goes because it was read. What moves up then is no arrival.
        val readAway = filter == RoomFilter.Unread && !searching
        fun moved(now: String?, before: String?): Boolean = now != before &&
            !(readAway && now != null && before != null && before !in rowsDrawn && now in shownTop.rows)
        val changed = moved(topKey, shownTop.key) || moved(topRoom, shownTop.room)
        val known = shownTop.key != null
        shownTop.key = topKey
        shownTop.room = topRoom
        shownTop.rows = rowsDrawn
        if (changed && known && topKey != null &&
            listState.firstVisibleItemIndex <= 2 && !listState.isScrollInProgress
        ) {
            listState.requestScrollToItem(0)
        } else {
            // ... nor does it go along with the row at the top of the screen when that one moves away.
            val lead = listOf(updateBanner && !searching, pairShown, listError != null).count { it }
            listState.keepRowsInPlace(rowsDrawn, lead)
        }
    }
    // The conversation open beside the list (wide layout) in a closed folder opens that folder,
    // as desktop does for a chat that is opened. Once per opening, not on the folders: closing
    // that folder afterwards keeps it closed, also when the device is turned (hence saved).
    var revealedFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(session, selectedToken, serverRooms.isEmpty()) {
        val token = selectedToken
        if (token == null) {
            revealedFor = null
            return@LaunchedEffect
        }
        if (token == revealedFor || session.rooms.room(token) == null) return@LaunchedEffect
        revealedFor = token
        // While searching every folder already shows open: nothing is toggled (desktop's
        // revealToken), so a pick from the results leaves the folders as they were. Nor for one that
        // is not in the list ("Show" hides it, or it is archived): it would show nothing.
        if (!searching && listShows(session, filter, token)) folders.reveal(token)
    }
    // Unsent text waiting in a conversation: its row says "Draft:" instead of the last message.
    // Not for the one open beside the list (its box is on screen): typing there changes nothing here.
    val drafts = LocalContext.current.appContainer.drafts
    val draftAccount = remember(session) { ComposerDrafts.accountOf(session) }
    val draftLines by remember(drafts, draftAccount, selectedToken) {
        derivedStateOf { drafts.previews(draftAccount, except = selectedToken) }
    }
    // A text that was sent off and is not sent (waiting for the network, or for its Retry): the row says
    // "Not sent:". It wins over "Draft:", and shows beside its open room too (it may be in another tab).
    val unsent by session.unsent.collectAsStateWithLifecycle()
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
                    // Before the status is asked for: "…" where it will be, and nothing to tap.
                    status = if (statusUi.available) statusUi else OwnStatusUi(available = session.capabilities.userStatus),
                    onSetStatus = if (!statusUi.available) null else ({
                        // Not read yet (or the presets are missing): ask again while the sheet opens.
                        if (!statusUi.loaded || statusUi.predefined.isEmpty()) ownStatus.refresh()
                        editingStatus = true
                    }),
                ) {
                    IconButton(
                        enabled = session.checked,
                        onClick = {
                            newChat.reset()
                            startingChat = true
                        },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "New conversation")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
                // Always visible, as on desktop: one tap to type, and it shows that search exists.
                // Beside it "Sort by" and "Show", under the gear; a changed order starts at the top, like a search.
                Row(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChatSearchField(query = query, onQueryChange = { query = it }, modifier = Modifier.weight(1f))
                    SortButton(
                        sort = sort,
                        filter = filter,
                        filters = offeredFilters(session.capabilities, filter),
                        onSort = {
                            listSort.set(it)
                            listState.requestScrollToItem(0)
                        },
                        onFilter = show,
                    )
                }
                // Also during a search, which looks inside what is shown.
                if (filter != RoomFilter.All) FilterLine(filter, onShowAll = { show(RoomFilter.All) })
                CallInProgressBar()
                SavedLine(savedLine)
                if (live && connection != null && rooms.isNotEmpty()) {
                    ConnectionLine(connection)
                } else if (notifyShown) {
                    NotificationsNotice(
                        problem = notifyProblem,
                        onFix = { fixNotifications(context, notifyProblem, onOpenPushSettings) },
                        onDismiss = { notifyNotices.dismiss(noticeAccount, notifyProblem.state) },
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
                    if (session.checked) session.rooms.refresh() else container.checkNow().join()
                    pulling = false
                }
            },
            // Edge to edge, the keyboard does not shrink the window: the results end above it.
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
        ) {
            val error = load.error
            when {
                !orderKnown || (rooms.isEmpty() && !load.loaded && error == null) ->
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
                    if (nothing != null) {
                        // "x:" cannot meet a room token ([a-z0-9] only). At the top: the keyboard
                        // covers the middle of the list.
                        item(key = "x:nomatch") {
                            NothingShown(nothing, onShowAll = if (filter == RoomFilter.All) null else ({ show(RoomFilter.All) }))
                        }
                    }
                    // Opening a conversation puts the keyboard away (it would cover the room beside
                    // the list on a wide window).
                    val roomItem: @Composable (Room) -> Unit = { room ->
                        val failed = unsent[room.token]
                        RoomRow(
                            session, room, now,
                            hasTopics = room.token in withTopics || (!live && room.lastMessageTopicId != 0L),
                            shift = shifts[room.partnerUserId]?.state ?: ShiftState.Unknown,
                            mutedCount = mutedShown[room.token] ?: 0,
                            selected = room.token == selectedToken,
                            draft = draftLines[room.token]?.text,
                            notSent = failed?.let { drafts.preview(it.text) },
                            onClick = {
                                focusManager.clearFocus()
                                if (!beside) leavingFor = room.token
                                // A row that says "Not sent:" or "Draft:" opens where that text waits.
                                onOpenRoom(
                                    room.token,
                                    failed?.let { unsentTab(it, drafts, draftAccount, room.token) } ?: draftLines[room.token]?.tab,
                                )
                            },
                            onOpenTopic = { id ->
                                focusManager.clearFocus()
                                if (!beside) leavingFor = room.token
                                onOpenTopic(room.token, id)
                            },
                            // All of it is the server's to do.
                            onLongClick = { if (live) roomMenu.menuFor = room.token else say(WAITING_FOR_SERVER) },
                        )
                    }
                    if (sections == null) {
                        items(shown, key = { it.token }) { room ->
                            // Only here: in folders the rows would slide across the headers.
                            Box(if (slide) Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null) else Modifier) { roomItem(room) }
                        }
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
                                        onLongPress = { if (live) overlays.menuFor = section.folder.id else say(WAITING_FOR_SERVER) },
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

    // What was open over the list when the app was ended is back with the server's list.
    if (live) {
        RoomMenuHost(
            state = roomMenu,
            session = session,
            rooms = rooms,
            // In the flat list too: it is where the first folder is made.
            onMove = if (folderUi.attached) ({ token -> overlays.moveFor = token }) else null,
        )
        FolderOverlayHost(
            overlays = overlays,
            folders = folders,
            ui = folderUi,
            roomName = { token -> session.rooms.room(token)?.displayName },
            say = say,
        )
    }
    if (editingStatus && statusUi.available) OwnStatusSheet(ownStatus) { editingStatus = false }
    if (startingChat && session.checked) NewConversationSheet(session, newChat) { startingChat = false }
}

/** [this] as a saved row shows it. */
private fun Room.withoutUnread(): Room = copy(unreadMessages = 0, unreadMention = false, unreadMentionDirect = false)

/** The first row's key and the first conversation the list showed, as of the last composition. Not state. */
private class TopRow(var key: String? = null, var room: String? = null) {
    /** The rows it showed ([rowKeys]); not saved. */
    var rows: List<String> = emptyList()

    companion object {
        val Saver = listSaver<TopRow, String?>(save = { listOf(it.key, it.room) }, restore = { TopRow(it[0], it[1]) })
    }
}

/** A folder section's header key. "folder:" keys cannot meet a room token ([a-z0-9] only). */
internal fun sectionKey(section: FolderSection): String = when (section) {
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
 * [notSent]: a text of it that was sent off and is not sent (one line); it wins over [draft].
 * [onLongClick]: the conversation's menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomRow(
    session: TalkSession,
    room: Room,
    nowMillis: Long,
    hasTopics: Boolean,
    shift: ShiftState,
    /** The part of the unread number that was muted: grey, the rest keeps the colours. */
    mutedCount: Int,
    selected: Boolean,
    draft: String?,
    notSent: String?,
    onClick: () -> Unit,
    onOpenTopic: (topicId: Long) -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = TalqTheme.colors
    val context = LocalContext.current
    val dark = !TalqTheme.colors.isLight
    val muted = room.notificationLevel == NotificationLevel.NEVER
    val unread = room.unreadMessages > 0
    val loudCount = room.unreadMessages - mutedCount
    // Nothing unread here that was not muted: a muted chat, or one with news in muted topics only.
    val quiet = muted || (mutedCount > 0 && loudCount <= 0)
    // The newest unread message sits in a topic: name the topic as a pill that opens it.
    val last = room.lastMessage
    val unreadTopic = last?.takeIf {
        unread && it.topicId != 0L && it.threadTitle.isNotBlank() && it.id > room.lastReadMessage &&
            !it.isSystem && !session.isMe(it.actorType, it.actorId) &&
            // Already read in that topic this session (the room's marker is held back by an
            // older unread message somewhere else): the pill would open a tab with nothing new.
            (session.unreadIfCreated(room.token)?.state?.value?.seenUpTo(it.topicId) ?: 0L) < it.id
    }
    val preview = remember(room.lastMessage, colors, unreadTopic != null, draft, notSent) {
        when {
            notSent != null -> labelled("Not sent:", colors.danger, notSent)
            draft != null -> labelled("Draft:", colors.accentText, draft)
            else -> previewLine(session, room, colors, withTopic = unreadTopic == null)
        }
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
                onLongClickLabel = "Conversation menu",
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
                    color = if (unread && !quiet) colors.accentText else colors.textMuted,
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
                // mention of everyone, the accent otherwise; then, in grey, the count of what was
                // muted (a muted chat, muted topics; only a direct "@" still shows its colour there).
                // Desktop shows no number for what was muted.
                if (room.unreadMention || unread) {
                    val fill = colors.unreadFill(room.unreadMention, room.unreadMentionDirect)
                    Row(
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = unreadWords(loudCount, room.unreadMention, room.unreadMentionDirect, muted = mutedCount)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (room.unreadMention) {
                            Spacer(Modifier.width(6.dp))
                            Badge("@", muted = quiet && !room.unreadMentionDirect, fill = fill)
                        }
                        if (loudCount > 0) {
                            Spacer(Modifier.width(6.dp))
                            Badge(countText(loudCount), muted = false, fill = fill)
                        }
                        if (mutedCount > 0) {
                            Spacer(Modifier.width(6.dp))
                            Badge(countText(mutedCount), muted = true)
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
 * attention, grey ([TalqColors.mutedFill]) for what was muted. The ink is chosen against the fill it
 * sits on ([TalqColors.inkOn]), so the number reads on every theme.
 */
@Composable
internal fun Badge(text: String, muted: Boolean, fill: Color? = null) {
    val colors = TalqTheme.colors
    val ground = if (muted) colors.mutedFill else fill ?: colors.countFill
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
            color = colors.inkOn(ground),
            maxLines = 1,
        )
    }
}

/** A count as its pill shows it: at most four characters, so a busy chat's number cannot widen its row's badges. */
internal fun countText(count: Int): String = if (count > 999) "999+" else count.toString()

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

/** What the unread pills say to a screen reader (the colour is not the only sign either: "@"); [muted]: the grey one. */
internal fun unreadWords(unread: Int, mention: Boolean, direct: Boolean, muted: Int = 0): String {
    val count = when {
        unread > 0 && muted > 0 -> "$unread unread, $muted muted"
        muted > 0 -> "$muted unread, muted"
        unread > 0 -> "$unread unread"
        else -> null
    }
    val who = when {
        direct -> "you are mentioned"
        mention -> "with a mention"
        else -> null
    }
    return listOfNotNull(count, who).joinToString(", ").replaceFirstChar { it.uppercase() }
}

/** "Draft: text" / "Not sent: text": the [label] in its own ink, the typed text as any preview. */
private fun labelled(label: String, ink: Color, text: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = ink, fontWeight = FontWeight.Medium)) { append(label) }
    append(" ")
    append(text)
}

/** The tab an unsent text is shown in: its topic, the topic it starts, else General (the whole chat in a room without topics). */
private fun unsentTab(o: OutboxMessage, drafts: ComposerDrafts, account: String, token: String): Long = when {
    o.threadId > 0 -> o.threadId
    drafts.newTopic(ComposerDrafts.Key(account, token, RoomTab.KEY_NEW_TOPIC))?.sending == o.referenceId -> RoomTab.KEY_NEW_TOPIC
    else -> RoomTab.KEY_GENERAL
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
