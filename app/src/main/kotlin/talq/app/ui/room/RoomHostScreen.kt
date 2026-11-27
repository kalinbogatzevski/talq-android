package talq.app.ui.room

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Notifications
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import talq.app.appContainer
import talq.app.call.CallActivity
import talq.app.files.RecordingState
import talq.app.nav.RoomRoute
import talq.app.net.NetLink
import talq.app.theme.TalqTheme
import talq.app.ui.chat.ChatPane
import talq.app.ui.chat.ChatViewModel
import talq.app.ui.chat.DeleteMessageDialog
import talq.app.ui.chat.ForwardSheet
import talq.app.ui.chat.KeepScreenOn
import talq.app.ui.chat.MentionSuggester
import talq.app.ui.chat.ScheduledMessagesSheet
import talq.app.ui.chat.SelectionTopBar
import talq.app.ui.chat.copyStamp
import talq.app.ui.chat.copyToClipboard
import talq.app.ui.common.Avatar
import talq.app.ui.common.StatusAvatar
import talq.app.ui.common.StatusLine
import talq.app.ui.common.hasStatusLine
import talq.app.ui.topics.TopicsSheet
import talq.app.ui.topics.generalUnread
import talq.app.ui.topics.scannedUnread
import talq.app.ui.topics.topicUnread
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import talq.core.TalkError
import talq.core.model.NotificationLevel
import talq.core.model.TalkThreadInfo
import talq.core.model.VoicePlace
import talq.core.model.canReadMembers
import talq.core.model.copiedMessages
import talq.core.model.hasMemberList
import talq.core.repo.OutboxStatus
import talq.core.repo.TalkSession
import talq.core.shift.ShiftState

/** How often the conversation list (and with it the presence) is reloaded while a one-to-one chat is in front. */
private const val PRESENCE_REFRESH_MILLIS = 30_000L

/** A topic list that could not be loaded is asked for again while its room is in front: after this, then less and less often. */
private const val TOPICS_RETRY_MILLIS = 10_000L
private const val TOPICS_RETRY_MAX_MILLIS = 60_000L

/**
 * A top bar narrower than this keeps only Call and More as buttons and puts Video call, Mute
 * topic and All topics in More, so the name keeps its room. Five buttons take 240 dp, avatar and
 * insets about 60 dp more, a back arrow 48 dp: at 600 dp a typical 16-letter name still fits
 * beside all of them at a large font setting. Every phone held upright is below it, and so is
 * a tablet's conversation beside the list when the tablet is upright.
 */
internal val FullBarMinWidth = 600.dp

/**
 * An open conversation: ONE screen (one back-stack entry) for all of it. The top bar always
 * shows the room; under it the topic strip (when the room has topics) switches the content in
 * place between General and the topics; a room without topics is its whole chat. Back leaves the
 * room from any tab.
 *
 * Layout, top to bottom: top bar, [TopicStrip], then the selected tab's [ChatPane] (messages
 * and composer). Each tab has its own `ChatViewModel` and its own saveable UI state.
 *
 * The host keeps the room's whole-chat repository started for as long as the room is on
 * screen (one live feed across all tab switches, and General/topics have something to show at
 * once), and reloads rooms, topics and unread counts each time it comes to the front.
 */
/**
 * Asked from outside the open conversation [token] (the wide layout's list, a notification,
 * "Show in chat"): switch to [tab] (null: stay) and scroll to [messageId] there (null: none), or go to
 * the topic being started ([newTopic]).
 */
data class TabRequest(val token: String, val tab: RoomTab?, val messageId: Long? = null, val newTopic: Boolean = false)

/**
 * [showBack] false: no back arrow (beside the conversation list, which is already on screen).
 * A [tabRequest] for this room is applied once, then [onTabRequestDone] is called.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomHostScreen(
    session: TalkSession,
    route: RoomRoute,
    onBack: () -> Unit,
    onOpenSharedFiles: () -> Unit,
    showBack: Boolean = true,
    tabRequest: TabRequest? = null,
    onTabRequestDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val token = route.token
    val vm: RoomHostViewModel = viewModel(key = "room-$token") {
        RoomHostViewModel(
            session, route, appContext.appContainer.roomTabs, appContext.appContainer.drafts, appContext, createSavedStateHandle(),
        )
    }
    // The whole chat's model is made in every room, shown or not: its saved state holds the room's own box
    // (General's and the whole chat's are one, RoomTab.boxKey), so text typed before the room had topics is
    // back after Android ended the process.
    val wholeModel = viewModel<ChatViewModel>(key = "chat-$token-${RoomTab.All.key}") {
        ChatViewModel(session, token, RoomTab.All, appContext, appContext.appContainer.drafts, createSavedStateHandle())
    }
    val colors = TalqTheme.colors
    LaunchedEffect(tabRequest) {
        val request = tabRequest?.takeIf { it.token == token } ?: return@LaunchedEffect
        val to = request.tab
        // The tab already shown stays as it is.
        if (request.newTopic) vm.showDraft() else if (to != null && (to != vm.tab || vm.onDraft)) vm.select(to)
        val at = to ?: vm.tab
        if (request.messageId != null && at != null) vm.jump = at to request.messageId
        onTabRequestDone()
    }

    val rooms by session.rooms.rooms.collectAsStateWithLifecycle()
    val topicList by vm.topics.topics.collectAsStateWithLifecycle()
    val topicsLoad by vm.topics.state.collectAsStateWithLifecycle()
    val unread by vm.unread.state.collectAsStateWithLifecycle()
    val withTopics by session.roomsWithTopics.collectAsStateWithLifecycle()
    val whole by vm.wholeChat.state.collectAsStateWithLifecycle()
    val room = remember(rooms) { rooms.firstOrNull { it.token == token } }
    val lastRead = room?.lastReadMessage ?: 0L
    val readOnly = room?.isReadOnly == true
    // A one-to-one conversation shows the other person's presence and shift in the top bar.
    val direct = room?.takeIf { it.isOneToOne }
    // A group or public conversation's name (and More > Members) opens the list of its people, where the
    // server gives it to the user. An open one stays and says why when that ends.
    val memberRoom = room?.takeIf { hasMemberList(it) }
    val withMembers = memberRoom?.takeIf { canReadMembers(it, System.currentTimeMillis() / 1000) }
    val shifts by context.appContainer.shift.statuses.collectAsStateWithLifecycle()
    val shift = direct?.let { shifts[it.partnerUserId] }
    val typists = rememberTypists(session, token)
    // Calls: one-to-one conversations on a server with a call backend.
    val calls = context.appContainer.calls
    val callsAvailable by produceState(false, session) { value = calls.available(session) }
    val canCall = direct != null && !readOnly && callsAvailable
    // Presence comes with the conversation list, which nobody reloads while a chat is open
    // (apart from a push): reload it now and then while this one is in front.
    var inFront by remember { mutableStateOf(false) }
    LifecycleResumeEffect(token) {
        inFront = true
        onPauseOrDispose { inFront = false }
    }
    LaunchedEffect(session, direct != null, inFront) {
        if (direct == null || !inFront) return@LaunchedEffect
        while (true) {
            delay(PRESENCE_REFRESH_MILLIS)
            session.rooms.refresh()
        }
    }

    LifecycleStartEffect(vm) {
        vm.wholeChat.start()
        onStopOrDispose { vm.wholeChat.stop() }
    }
    // A voice message stops when the chat is left, and pauses while the app is not in front.
    val voice = context.appContainer.voice
    val activity = LocalActivity.current
    // Turning the phone is not leaving.
    DisposableEffect(voice) { onDispose { if (activity?.isChangingConfigurations != true) voice.stop() } }
    LifecycleStartEffect(voice) { onStopOrDispose { voice.pause() } }
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(topicsLoad.error) {
        if (topicsLoad.error is TalkError.Unauthorized) context.appContainer.onUnauthorized()
    }
    val snackbar = remember { SnackbarHostState() }
    val noticeScope = rememberCoroutineScope()
    LaunchedEffect(vm.notice) {
        vm.notice?.let {
            vm.notice = null
            // Not from inside this effect: clearing the notice restarts it.
            noticeScope.launch { snackbar.showSnackbar(it) }
        }
    }

    val wanted = vm.tab
    // A failed load of the list is not an answer, unless the server itself said no: not allowed, not there, or
    // (406) not for a conversation of another server. Anything else passes and is asked again: a lost connection,
    // a server fault, "too many requests", a lobby, another server that does not answer (422).
    val topicsRefused = topicsLoad.error.let {
        it is TalkError.Forbidden || it is TalkError.NotFound || (it is TalkError.Http && it.status == 406)
    }
    val topicInChat = remember(whole.messages) { whole.messages.any { it.topicId != 0L } }
    // What the saved list says of it, until the list is there: its name and picture.
    val listed = room ?: context.appContainer.savedRoom(session, token)
    // Does this room have topics? The loaded list is the answer; an empty one until something newer (a
    // message in a topic) says otherwise, which each list that loads settles again. Before it has loaded, go by
    // what is known: the room was seen to have topics, a tab was remembered for it (only
    // rooms with a strip get one), it is being opened on a topic, or its chat holds a message in one.
    // Where the server gives no list there is no strip to reach them by: the whole chat shows
    // everything, and a topic opened from it has General as its way back.
    val hasTopics = session.supportsTopics && when {
        topicList.isNotEmpty() -> true
        topicsLoad.loaded -> token in withTopics
        topicsRefused -> wanted is RoomTab.Topic
        else -> token in withTopics || vm.rememberedAtOpen != null || wanted is RoomTab.Topic || topicInChat
    }
    // Known for sure, either way. Until then the whole-chat view must not count as reading:
    // in a room that turns out to have topics that would mark every topic read.
    val topicsKnown = !session.supportsTopics || hasTopics || topicsLoad.loaded || topicsRefused
    // A room without topics is the whole chat with no strip, whatever tab was asked for; one with topics
    // never shows it (no tab that can be asked for is the whole chat, see RoomTab.fromKey).
    val shown: RoomTab? = if (hasTopics) wanted else RoomTab.All
    // The whole chat's pane says its own lines. Once the room has topics it is gone, and the answer to what was
    // asked for in it (a send later, an edit) is said here.
    LaunchedEffect(wholeModel.notice, shown) {
        val line = wholeModel.notice
        if (line != null && shown != RoomTab.All) {
            wholeModel.notice = null
            noticeScope.launch { snackbar.showSnackbar(line) }
        }
    }
    // A room with topics is nothing but General until its list has come: asked for again while in front.
    val listMissing = session.supportsTopics && !topicsLoad.loaded && !topicsRefused
    val network = context.appContainer.network
    LaunchedEffect(vm, inFront, listMissing) {
        var wait = TOPICS_RETRY_MILLIS
        while (inFront && listMissing) {
            delay(wait)
            network.link.first { it != NetLink.None }
            vm.loadMissing()
            wait = (wait * 2).coerceAtMost(TOPICS_RETRY_MAX_MILLIS)
        }
    }

    // A voice message being recorded belongs to the room where it started (VoiceMessages): this
    // room opening ends one that started in another room, and this room's screen going away (not
    // a turn of the phone) or the app leaving the front ends one that started here; each is sent
    // where it started. While it records the screen stays on.
    val voiceMessages = context.appContainer.voiceMessages
    val recordingState by voiceMessages.state.collectAsStateWithLifecycle()
    val recordingHere = (recordingState as? RecordingState.Recording)?.place?.token == token
    LaunchedEffect(voiceMessages, token) { voiceMessages.roomShown(token) }
    DisposableEffect(voiceMessages, token) {
        onDispose { if (activity?.isChangingConfigurations != true) voiceMessages.roomLeft(token) }
    }
    val onScreen by rememberUpdatedState(VoicePlace(token, (shown as? RoomTab.Topic)?.id ?: 0L))
    DisposableEffect(voiceMessages, activity) {
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) voiceMessages.appLeft(onScreen)
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    if (recordingHere) KeepScreenOn()

    // The strip's tab is the one this room opens on next time. Only the host records it.
    LaunchedEffect(hasTopics, shown, topicsLoad.loaded) {
        if (hasTopics && shown != null) vm.remember(shown)
        else if (!hasTopics && topicsLoad.loaded) vm.forget()
    }

    val topicsById = remember(topicList) { topicList.associateBy { it.id } }
    // Offline there is no list: the topics the phone has messages of.
    val keptTopics = remember(topicsLoad) {
        if (topicsLoad.loaded || topicsLoad.error !is TalkError.Network) emptyMap()
        else session.keptTopics(token).toMap() + whole.messages.asReversed().filter { it.topicId != 0L }.associate { it.topicId to it.threadTitle }
    }
    fun topicTitle(id: Long): String =
        topicsById[id]?.title?.takeIf { it.isNotBlank() }
            ?: whole.messages.firstOrNull { it.topicId == id }?.threadTitle?.takeIf { it.isNotBlank() }
            ?: keptTopics[id]?.takeIf { it.isNotBlank() }
            ?: "Topic"

    val chips = remember(vm.order, topicList, keptTopics, unread, lastRead, shown, whole.messages.size) {
        buildList {
            add(StripChip(RoomTab.General, "General", generalUnread(unread)))
            // Topics that are not in the list (the server lists 50): the open one, and those with something
            // unread, so that there is a tab to read it in.
            val unlisted = (listOfNotNull((shown as? RoomTab.Topic)?.id) + unread.byTopic.keys + keptTopics.keys).distinct()
            for (id in unlisted) {
                if (id != 0L && id !in topicsById) add(StripChip(RoomTab.Topic(id), topicTitle(id), scannedUnread(unread, id)))
            }
            for (id in vm.order) {
                val info = topicsById[id] ?: continue
                add(
                    StripChip(
                        tab = RoomTab.Topic(id),
                        title = info.title.ifBlank { "Untitled topic" },
                        unread = topicUnread(session, unread, lastRead, info),
                        muted = info.attendee.notificationLevel == NotificationLevel.NEVER,
                        mentionsOnly = info.attendee.notificationLevel == NotificationLevel.MENTION,
                    ),
                )
            }
        }
    }

    val roomName = listed?.displayName ?: ""
    // Through derived state: a key typed in any box must not draw this whole screen again.
    val draft = remember(vm) { derivedStateOf { vm.draft } }.value
    val draftBusy = remember(vm) { derivedStateOf { vm.draftBusy } }.value
    // The topic being started is what the room shows; else it is only a chip beside the tabs.
    val onDraft = draft?.selected == true
    // Starting a topic hides the tab's composer: a recording here ends and goes where it started.
    LaunchedEffect(onDraft) { if (onDraft) voiceMessages.roomLeft(token) }
    val draftPending = draft?.sending?.let { ref -> whole.outbox.firstOrNull { it.referenceId == ref } }
    val draftFailed = draftPending?.status == OutboxStatus.FAILED && !draftBusy
    // On its way, or (no longer in the outbox) just made.
    val draftSending = draft?.sending != null && !draftFailed
    val tabName = when (shown) {
        // The whole chat has no name beside the room's.
        null, RoomTab.All -> ""
        RoomTab.General -> "General"
        is RoomTab.Topic -> topicTitle(shown.id)
    }
    // The tab the strip shows as selected; null while there is no strip (a room without topics
    // and no topic being started). Without topics its one chip is General, the way back to the chat.
    val stripTab: RoomTab? = when {
        hasTopics -> shown
        draft != null -> RoomTab.General
        else -> null
    }
    val openTopic = (shown as? RoomTab.Topic)?.takeIf { !onDraft }?.let { topicsById[it.id] }
    // Not where the server takes no post of the user's (read-only, no chat permission, behind a lobby).
    val canStartTopic = session.supportsTopics && room?.takesPosts(session.capabilities, System.currentTimeMillis() / 1000) != false

    // The next tab with something unread, going right from the open one and round the strip.
    // Never the open tab, never a topic set to Never.
    val nextUnread = remember(chips, shown) {
        val at = chips.indexOfFirst { it.tab == shown }
        if (at < 0) null else (chips.drop(at + 1) + chips.take(at)).firstOrNull { it.unread.any && !it.muted }
    }

    val scope = rememberCoroutineScope()
    // "@" suggestions in a draft topic's box (a tab's own box has its view model's).
    val draftMentions = remember(session, token) { MentionSuggester(session, token, scope, context.appContainer.drafts) }
    val holder = rememberSaveableStateHolder()
    var menuOpen by remember { mutableStateOf(false) }
    // Saved: the title being typed survives a rotation with its dialog.
    var newTopic by rememberSaveable { mutableStateOf(false) }
    // "+", All topics > New topic and More > New topic. One at a time: the one being started comes back.
    fun startTopic() {
        if (vm.draft != null) vm.showDraft() else newTopic = true
    }
    var sheetOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<RoomTab?>(null) }
    var renameFor by remember { mutableStateOf<Long?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var renameBusy by remember { mutableStateOf(false) }
    // Saved, both: a turned phone shows them again, with what was running there.
    var scheduledOpen by rememberSaveable { mutableStateOf(false) }
    var membersOpen by rememberSaveable { mutableStateOf(false) }
    var forwardOpen by remember { mutableStateOf(false) }
    // The picker was opened by touch and hold's Forward on one message: closing it without a
    // pick drops that one-message selection again, as if nothing had been selected.
    var forwardFromMenu by remember { mutableStateOf(false) }
    val selection = vm.selection

    // Back ends the selection first (not while a forward or a delete runs with it).
    BackHandler(enabled = selection.active && !vm.forwarding && !vm.deleting) { selection.clear() }

    Scaffold(
        // Above the keyboard: some of its lines can only come while that is up.
        snackbarHost = { SnackbarHost(snackbar, Modifier.windowInsetsPadding(WindowInsets.ime.exclude(ScaffoldDefaults.contentWindowInsets))) },
        topBar = {
            if (selection.active) {
                SelectionTopBar(
                    count = selection.count,
                    forwarding = vm.forwarding,
                    deleting = vm.deleting,
                    canDelete = vm.canDeletePicked,
                    onCopy = {
                        if (!selection.locked) {
                            val text = copiedMessages(selection.messages(), ::copyStamp)
                            if (text.isNotEmpty()) copyToClipboard(context, text, label = "Messages")
                            selection.clear()
                            vm.notice = "Copied"
                        }
                    },
                    onForward = {
                        if (!selection.locked) {
                            forwardFromMenu = false
                            forwardOpen = true
                        }
                    },
                    // Read again with every list answer: the rights of the user here may change.
                    onDelete = if (room != null && vm.hasDelete) ({ if (!selection.locked) vm.confirmDelete = true }) else null,
                    onCancel = { if (!selection.locked) selection.clear() },
                )
            } else {
                // The tab's name only where nothing else on screen says it: the strip's selected
                // chip already does. A topic being started keeps "New topic:", which says it does
                // not exist yet.
                val subtitle = when {
                    draft != null && onDraft -> "New topic: ${draft.title}"
                    stripTab != null && chips.any { it.tab == stripTab } -> ""
                    else -> tabName
                }
                val statusShown = direct != null && hasStatusLine(direct, shift)
                val typingShown = direct != null && typists.isNotEmpty()
                // On a large font setting the name and the lines under it can be taller than the
                // standard bar, which would cut the last one.
                val linesUnderName = (if (statusShown || typingShown) 1 else 0) + (if (subtitle.isNotEmpty()) 1 else 0)
                val barHeight = with(LocalDensity.current) { (22 + 16 * linesUnderName).sp.toDp() + 8.dp }
                    .coerceAtLeast(TopAppBarDefaults.TopAppBarExpandedHeight)
                // The bar's own width, not the window's: beside the list the room has only part
                // of it.
                BoxWithConstraints {
                    val narrow = maxWidth < FullBarMinWidth
                    TopAppBar(
                        expandedHeight = barHeight,
                        title = {
                            Row(
                                if (withMembers == null) Modifier else Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(role = Role.Button, onClickLabel = "Members") { membersOpen = true }
                                    .heightIn(min = 48.dp)
                                    .padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (direct != null) {
                                    StatusAvatar(
                                        url = session.client.roomAvatarUrl(token, direct.avatarVersion, !TalqTheme.colors.isLight),
                                        authorization = session.client.authorizationHeader,
                                        name = roomName,
                                        ring = MaterialTheme.colorScheme.surface,
                                        size = 36.dp,
                                        presence = direct.status,
                                        shift = shift?.state ?: ShiftState.Unknown,
                                    )
                                } else {
                                    Avatar(
                                        url = session.client.roomAvatarUrl(token, listed?.avatarVersion ?: "", !TalqTheme.colors.isLight),
                                        authorization = session.client.authorizationHeader,
                                        name = roomName,
                                        size = 36.dp,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(roomName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    // One-to-one: line 2 is the person (shift, presence); a third
                                    // line, when there is one, goes under it so the shift is never lost.
                                    if (typingShown) TypingHeaderLine() else if (statusShown) StatusLine(direct, shift)
                                    if (subtitle.isNotEmpty()) {
                                        Text(
                                            subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            if (showBack) {
                                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                            }
                        },
                        actions = {
                            // During a call these return to it instead of placing another one.
                            fun call(video: Boolean) = context.startActivity(
                                if (calls.busy) CallActivity.showIntent(context) else CallActivity.outgoingIntent(context, token, video),
                            )
                            // One tap mutes the open topic (Never) or unmutes it (the usual setting);
                            // the long-press menu keeps the finer levels.
                            fun toggleMute(topic: TalkThreadInfo) {
                                val id = topic.id
                                val level = topic.attendee.notificationLevel
                                val muted = level == NotificationLevel.NEVER
                                vm.setNotificationLevel(id, if (muted) NotificationLevel.DEFAULT else NotificationLevel.NEVER)
                                noticeScope.launch {
                                    val answer = snackbar.showSnackbar(
                                        message = if (muted) "Topic unmuted" else "Topic muted: it sends no notifications",
                                        actionLabel = "Undo",
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (answer == SnackbarResult.ActionPerformed) vm.setNotificationLevel(id, level)
                                }
                            }
                            val muted = openTopic?.attendee?.notificationLevel == NotificationLevel.NEVER
                            // On a narrow bar Video call, Mute topic and All topics are the first
                            // items of More instead: the name gets the room.
                            if (canCall) {
                                IconButton(onClick = { call(video = false) }) {
                                    Icon(Icons.Outlined.Call, contentDescription = "Call")
                                }
                                if (!narrow) {
                                    IconButton(onClick = { call(video = true) }) {
                                        Icon(Icons.Outlined.Videocam, contentDescription = "Video call")
                                    }
                                }
                            }
                            if (!narrow && openTopic != null) {
                                IconButton(onClick = { toggleMute(openTopic) }) {
                                    Icon(
                                        if (muted) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                                        contentDescription = if (muted) "Unmute topic" else "Mute topic",
                                        tint = if (muted) colors.textMuted else LocalContentColor.current,
                                    )
                                }
                            }
                            if (!narrow && hasTopics) {
                                IconButton(onClick = { sheetOpen = true }) {
                                    Icon(Icons.AutoMirrored.Outlined.ListAlt, contentDescription = "All topics")
                                }
                            }
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (narrow && canCall) {
                                    DropdownMenuItem(
                                        text = { Text("Video call") },
                                        leadingIcon = { Icon(Icons.Outlined.Videocam, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            call(video = true)
                                        },
                                    )
                                }
                                if (narrow && openTopic != null) {
                                    DropdownMenuItem(
                                        text = { Text(if (muted) "Unmute topic" else "Mute topic") },
                                        leadingIcon = {
                                            Icon(if (muted) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications, contentDescription = null)
                                        },
                                        onClick = {
                                            menuOpen = false
                                            toggleMute(openTopic)
                                        },
                                    )
                                }
                                if (narrow && hasTopics) {
                                    DropdownMenuItem(
                                        text = { Text("All topics") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ListAlt, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            sheetOpen = true
                                        },
                                    )
                                }
                                if (openTopic != null) {
                                    DropdownMenuItem(
                                        text = { Text("Topic notifications") },
                                        onClick = {
                                            menuOpen = false
                                            menuFor = RoomTab.Topic(openTopic.id)
                                        },
                                    )
                                }
                                // With a strip, "+" on it starts a topic; without one, this does.
                                if (canStartTopic && !hasTopics) {
                                    DropdownMenuItem(
                                        text = { Text("New topic") },
                                        leadingIcon = { Icon(Icons.Outlined.Forum, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            startTopic()
                                        },
                                    )
                                }
                                if (withMembers != null) {
                                    DropdownMenuItem(
                                        text = { Text("Members") },
                                        leadingIcon = { Icon(Icons.Outlined.Groups, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            membersOpen = true
                                        },
                                    )
                                }
                                // The schedule endpoints answer only where the user may write, and not
                                // in a federated conversation (406).
                                if (session.capabilities.scheduledMessages && !readOnly && room?.isFederated == false) {
                                    DropdownMenuItem(
                                        text = { Text("Scheduled messages") },
                                        leadingIcon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            scheduledOpen = true
                                        },
                                    )
                                }
                                if (session.capabilities.sharedItems) {
                                    DropdownMenuItem(
                                        text = { Text("Shared files") },
                                        leadingIcon = { Icon(Icons.Outlined.PermMedia, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            onOpenSharedFiles()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Refresh") },
                                    onClick = {
                                        menuOpen = false
                                        vm.refresh(byUser = true, shown = shown?.takeIf { !onDraft })
                                    },
                                )
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (stripTab != null) {
                TopicStrip(
                    // A room that has no topics yet shows the one being started, and General, the way back to the chat.
                    chips = if (hasTopics) chips else chips.take(1),
                    selected = stripTab,
                    onSelect = vm::select,
                    onNew = if (canStartTopic) ({ startTopic() }) else null,
                    onLongPress = if (hasTopics) ({ tab: RoomTab -> menuFor = tab }) else null,
                    draft = draft?.let { DraftChipState(it.title, onDraft, sending = draftSending || draftBusy, failed = draftFailed) },
                    onSelectDraft = vm::showDraft,
                    // Once the first message is on its way the topic is being created: no
                    // close mark that would only hide it.
                    onDiscardDraft = (vm::discardDraft).takeIf { !draftSending && !draftBusy },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (draft != null && onDraft) {
                    DraftTopicPane(
                        title = draft.title,
                        text = vm.draftText,
                        // Never cut, as in a tab's own box.
                        onTextChange = { vm.draftText = it },
                        sending = draft.sending != null,
                        pending = draftPending,
                        busy = draftBusy,
                        onSend = vm::sendDraft,
                        onRetry = vm::retryDraft,
                        onEditAgain = vm::editDraftAgain,
                        mentions = draftMentions,
                        over = vm.draftOver,
                    )
                } else if (shown != null) {
                    // One saveable scope per tab: scroll position, "already positioned" and so
                    // on belong to the tab, not to whichever tab happens to be showing. General of a room
                    // that was one whole chat while open goes on in that chat's pane (they are never both there).
                    val jump = vm.jump?.takeIf { !hasTopics || it.first == shown }
                    holder.SaveableStateProvider(if (shown == RoomTab.General && vm.wasWholeChat) RoomTab.All.key else shown.key) {
                        ChatPane(
                            session = session,
                            token = token,
                            tab = shown,
                            tabTitle = if (hasTopics) tabName else null,
                            // The message a notification pointed to, in the tab the room opened on.
                            targetMessageId = route.messageId?.takeIf { !hasTopics || shown == vm.openedOn },
                            // One asked for later, with the room already open.
                            jumpTo = jump?.second,
                            // Only this jump: a newer one may have taken its place meanwhile.
                            onJumped = { if (vm.jump == jump) vm.jump = null },
                            // In another tab than where it was asked for: that one opens on it.
                            onOpenMessage = { topicId, messageId ->
                                val to = if (topicId > 0L) RoomTab.Topic(topicId) else RoomTab.General
                                vm.select(to)
                                vm.jump = to to messageId
                            },
                            // Nor before the room itself (its read marker) is known.
                            readingEnabled = topicsKnown && room != null,
                            snackbar = snackbar,
                            selection = selection,
                            // One message: the same forward as the selection bar's, with it picked.
                            onForward = { m ->
                                if (!selection.locked && !selection.active) {
                                    selection.start(m)
                                    if (selection.active) {
                                        forwardFromMenu = true
                                        forwardOpen = true
                                    }
                                }
                            },
                            onOpenTopic = { id -> vm.select(RoomTab.Topic(id)) },
                            // A topic's first message that no topic being started holds has no tab of its own (the
                            // whole chat shows it by itself).
                            alsoUnsent = if (shown != RoomTab.General) emptyList()
                            else whole.outbox.filter { it.threadId == 0L && it.threadTitle.isNotEmpty() },
                            aboveComposer = { atBottom ->
                                if (atBottom && hasTopics && nextUnread != null) {
                                    NextUnreadPill(nextUnread) { vm.select(nextUnread.tab) }
                                }
                                if (direct == null) TypingLine(typists)
                            },
                        )
                    }
                }
            }
        }
    }

    if (newTopic) {
        TopicTitleDialog(
            heading = "New topic",
            confirmLabel = "Create",
            onConfirm = { title ->
                newTopic = false
                vm.startDraft(title)
            },
            onDismiss = { newTopic = false },
        )
    }

    if (forwardOpen) {
        if (!selection.active) {
            LaunchedEffect(Unit) { forwardOpen = false }
        } else {
            ForwardSheet(
                session = session,
                fromToken = token,
                count = selection.count,
                onPick = { room ->
                    forwardOpen = false
                    forwardFromMenu = false
                    vm.forward(room.token)
                },
                onDismiss = {
                    forwardOpen = false
                    if (forwardFromMenu && !vm.forwarding) selection.clear()
                    forwardFromMenu = false
                },
            )
        }
    }

    if (vm.confirmDelete) {
        // Nothing picked any more (all of it was deleted elsewhere meanwhile): nothing to ask.
        if (!selection.active) {
            LaunchedEffect(Unit) { vm.confirmDelete = false }
        } else {
            DeleteMessageDialog(
                deleting = false,
                count = selection.count,
                onDelete = vm::deletePicked,
                onDismiss = { vm.confirmDelete = false },
            )
        }
    }

    if (scheduledOpen) {
        ScheduledMessagesSheet(session = session, token = token, onDismiss = { scheduledOpen = false })
    }

    if (membersOpen) {
        if (memberRoom != null) {
            MembersSheet(session = session, room = memberRoom, onDismiss = { membersOpen = false })
        } else if (rooms.isNotEmpty()) {
            // The user was taken out of it, or it is gone (an empty list is one not loaded yet).
            LaunchedEffect(Unit) { membersOpen = false }
        }
    }

    if (sheetOpen) {
        TopicsSheet(
            session = session,
            topics = topicList,
            unread = unread,
            lastRead = lastRead,
            generalLast = whole.messages.lastOrNull { it.topicId == 0L && !it.isUpdateSignal },
            selected = shown.takeIf { !onDraft },
            onPick = { tab ->
                sheetOpen = false
                vm.select(tab)
            },
            // The chip's menu, over this sheet.
            onMenu = { tab -> menuFor = tab },
            // The sheet closes first: the title dialog, or the topic being started, takes its place.
            onNew = if (canStartTopic) ({
                sheetOpen = false
                startTopic()
            }) else null,
            onDismiss = { sheetOpen = false },
        )
    }

    menuFor?.let { target ->
        val info = (target as? RoomTab.Topic)?.let { topicsById[it.id] }
        ChipMenuSheet(
            title = if (target is RoomTab.Topic) topicTitle(target.id) else "General",
            level = info?.attendee?.notificationLevel,
            onLevel = { level ->
                menuFor = null
                if (info != null) vm.setNotificationLevel(info.id, level)
            },
            onRename = if (info != null) ({
                menuFor = null
                renameError = null
                renameFor = info.id
            }) else null,
            onMarkRead = {
                menuFor = null
                vm.markRead(target)
            },
            onDismiss = { menuFor = null },
        )
    }

    renameFor?.let { id ->
        TopicTitleDialog(
            heading = "Rename topic",
            confirmLabel = "Rename",
            initial = topicsById[id]?.title.orEmpty(),
            error = renameError,
            busy = renameBusy,
            onConfirm = { title ->
                scope.launch {
                    renameBusy = true
                    renameError = vm.rename(id, title)
                    renameBusy = false
                    if (renameError == null) renameFor = null
                }
            },
            onDismiss = { if (!renameBusy) renameFor = null },
        )
    }
}
