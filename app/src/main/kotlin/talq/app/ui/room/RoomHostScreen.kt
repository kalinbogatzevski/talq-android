package talq.app.ui.room

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Notifications
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Forum
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import talq.app.theme.TalqTheme
import talq.app.ui.chat.ChatPane
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
import talq.app.ui.topics.topicUnread
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import talq.core.TalkError
import talq.core.model.NotificationLevel
import talq.core.model.TalkThreadInfo
import talq.core.model.VoicePlace
import talq.core.model.copiedMessages
import talq.core.repo.OutboxStatus
import talq.core.repo.TalkSession
import talq.core.shift.ShiftState

/** How often the conversation list (and with it the presence) is reloaded while a one-to-one chat is in front. */
private const val PRESENCE_REFRESH_MILLIS = 30_000L

/**
 * A top bar narrower than this keeps only Call and More as buttons and puts Video call, Mute
 * topic and All topics in More, so the name keeps its room. Five buttons take 240 dp, avatar and
 * insets about 60 dp more, a back arrow 48 dp: at 600 dp a typical 16-letter name still fits
 * beside all of them at a large font setting. Every phone held upright is below it, and so is
 * a tablet's conversation beside the list when the tablet is upright.
 */
private val FullBarMinWidth = 600.dp

/**
 * An open conversation: ONE screen (one back-stack entry) for all of it. The top bar always
 * shows the room; under it the topic strip (when the room has topics) switches the content in
 * place between the whole chat, General and the topics. Back leaves the room from any tab.
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
 * "Show in chat"): switch to [tab] (null: stay) and scroll to [messageId] there (null: none).
 */
data class TabRequest(val token: String, val tab: RoomTab?, val messageId: Long? = null)

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
        RoomHostViewModel(session, route, appContext.appContainer.roomTabs, appContext, createSavedStateHandle())
    }
    val colors = TalqTheme.colors
    LaunchedEffect(tabRequest) {
        val request = tabRequest?.takeIf { it.token == token } ?: return@LaunchedEffect
        val to = request.tab
        // The tab already shown stays as it is (select() would drop a topic being started).
        if (to != null && (to != vm.tab || vm.draft != null)) vm.select(to)
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
    val shifts by context.appContainer.shift.statuses.collectAsStateWithLifecycle()
    val shift = direct?.let { shifts[it.partnerUserId] }
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
    // Does this room have topics? The loaded list is the answer. Before it has loaded, go by
    // what is known: the room was seen to have topics, a tab was remembered for it (only
    // rooms with a strip get one), or it is being opened on a topic.
    val hasTopics = session.supportsTopics && when {
        topicList.isNotEmpty() -> true
        topicsLoad.loaded -> false
        else -> token in withTopics || vm.rememberedAtOpen != null || wanted is RoomTab.Topic
    }
    // Known for sure, either way. Until then the whole-chat view must not count as reading:
    // in a room that turns out to have topics that would mark every topic read.
    // A failed load is not an answer, unless the server itself said no (not a lost connection
    // or a server fault, which the next refresh retries).
    val topicsRefused = topicsLoad.error.let { it is TalkError.Forbidden || it is TalkError.NotFound || it is TalkError.Http }
    val topicsKnown = !session.supportsTopics || hasTopics || topicsLoad.loaded || topicsRefused
    // A room without topics is the whole chat with no strip, whatever tab was asked for.
    val shown: RoomTab? = if (hasTopics) wanted else RoomTab.All

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
    fun topicTitle(id: Long): String =
        topicsById[id]?.title?.takeIf { it.isNotBlank() }
            ?: whole.messages.firstOrNull { it.topicId == id }?.threadTitle?.takeIf { it.isNotBlank() }
            ?: "Topic"

    val chips = remember(vm.order, topicList, unread, lastRead, shown, whole.messages.size) {
        buildList {
            add(StripChip(RoomTab.All, "All"))
            add(StripChip(RoomTab.General, "General", generalUnread(unread)))
            // A topic that is open but not in the list (older than the 50 the server lists).
            val open = (shown as? RoomTab.Topic)?.id
            if (open != null && open !in topicsById) add(StripChip(RoomTab.Topic(open), topicTitle(open)))
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

    val roomName = room?.displayName ?: ""
    val draft = vm.draft
    // Starting a topic hides the tab's composer: a recording here ends and goes where it started.
    LaunchedEffect(draft != null) { if (draft != null) voiceMessages.roomLeft(token) }
    val draftSending = draft?.referenceId?.let { ref ->
        whole.outbox.any { it.referenceId == ref && it.status == OutboxStatus.SENDING }
    } == true
    val tabName = when (shown) {
        null -> ""
        RoomTab.All -> if (hasTopics) "All messages" else ""
        RoomTab.General -> "General"
        is RoomTab.Topic -> topicTitle(shown.id)
    }
    // The tab the strip shows as selected; null while there is no strip (a room without topics
    // and no topic being started).
    val stripTab = shown?.takeIf { hasTopics || draft != null }
    val openTopic = (shown as? RoomTab.Topic)?.takeIf { draft == null }?.let { topicsById[it.id] }
    val canStartTopic = session.supportsTopics && !readOnly
    val maxLength = session.capabilities.maxMessageLength.takeIf { it > 0 } ?: 32_000

    // The next tab with something unread, going right from the open one and round the strip.
    // Never the open tab, never All (it is every tab at once), never a topic set to Never.
    val nextUnread = remember(chips, shown) {
        val at = chips.indexOfFirst { it.tab == shown }
        if (shown == null || shown == RoomTab.All || at < 0) null
        else (chips.drop(at + 1) + chips.take(at)).firstOrNull { it.tab != RoomTab.All && it.unread.any && !it.muted }
    }

    val scope = rememberCoroutineScope()
    // "@" suggestions in a draft topic's box (a tab's own box has its view model's).
    val draftMentions = remember(session, token) { MentionSuggester(session, token, scope, context.appContainer.drafts) }
    val holder = rememberSaveableStateHolder()
    var menuOpen by remember { mutableStateOf(false) }
    var newTopic by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<RoomTab?>(null) }
    var renameFor by remember { mutableStateOf<Long?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var renameBusy by remember { mutableStateOf(false) }
    var scheduledOpen by remember { mutableStateOf(false) }
    var forwardOpen by remember { mutableStateOf(false) }
    // The picker was opened by touch and hold's Forward on one message: closing it without a
    // pick drops that one-message selection again, as if nothing had been selected.
    var forwardFromMenu by remember { mutableStateOf(false) }
    val selection = vm.selection

    // Back ends the selection first (not while a forward runs with it).
    BackHandler(enabled = selection.active && !vm.forwarding) { selection.clear() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selection.active) {
                SelectionTopBar(
                    count = selection.count,
                    forwarding = vm.forwarding,
                    onCopy = {
                        if (!selection.locked) {
                            val text = copiedMessages(selection.messages(), ::copyStamp)
                            if (text.isNotEmpty()) copyToClipboard(context, text, label = "Messages")
                            selection.clear()
                            vm.notice = "Copied"
                        }
                    },
                    onForward = {
                        if (!vm.forwarding) {
                            forwardFromMenu = false
                            forwardOpen = true
                        }
                    },
                    onCancel = { selection.clear() },
                )
            } else {
                // The tab's name only where nothing else on screen says it: the strip's selected
                // chip already does. A topic being started keeps "New topic:", which says it does
                // not exist yet.
                val subtitle = when {
                    draft != null -> "New topic: ${draft.title}"
                    stripTab != null && chips.any { it.tab == stripTab } -> ""
                    else -> tabName
                }
                val statusShown = direct != null && hasStatusLine(direct, shift)
                // On a large font setting the name and the lines under it can be taller than the
                // standard bar, which would cut the last one.
                val linesUnderName = (if (statusShown) 1 else 0) + (if (subtitle.isNotEmpty()) 1 else 0)
                val barHeight = with(LocalDensity.current) { (22 + 16 * linesUnderName).sp.toDp() + 8.dp }
                    .coerceAtLeast(TopAppBarDefaults.TopAppBarExpandedHeight)
                // The bar's own width, not the window's: beside the list the room has only part
                // of it.
                BoxWithConstraints {
                    val narrow = maxWidth < FullBarMinWidth
                    TopAppBar(
                        expandedHeight = barHeight,
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                                        url = session.client.roomAvatarUrl(token, room?.avatarVersion ?: "", !TalqTheme.colors.isLight),
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
                                    if (statusShown && direct != null) StatusLine(direct, shift)
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
                                            newTopic = true
                                        },
                                    )
                                }
                                // The schedule endpoints answer only where the user may write.
                                if (session.capabilities.scheduledMessages && !readOnly) {
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
                                        vm.refresh(byUser = true, shown = shown?.takeIf { draft == null })
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
                    // A room that has no topics yet shows only the one being started.
                    chips = if (hasTopics) chips else emptyList(),
                    selected = stripTab,
                    onSelect = vm::select,
                    onNew = if (canStartTopic) ({ newTopic = true }) else null,
                    onLongPress = { tab -> if (tab != RoomTab.All) menuFor = tab },
                    draftTitle = draft?.title,
                    // Once the first message is on its way the topic is being created: no
                    // close mark that would only hide it.
                    onDiscardDraft = (vm::discardDraft).takeIf { !draftSending },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (draft != null) {
                    DraftTopicPane(
                        title = draft.title,
                        text = vm.draftText,
                        onTextChange = { vm.draftText = it.take(maxLength) },
                        sending = draft.referenceId != null,
                        pending = draft.referenceId?.let { ref -> whole.outbox.firstOrNull { it.referenceId == ref } },
                        onSend = vm::sendDraft,
                        onRetry = vm::retryDraft,
                        onEditAgain = vm::editDraftAgain,
                        mentions = draftMentions,
                    )
                } else if (shown != null) {
                    // One saveable scope per tab: scroll position, "already positioned" and so
                    // on belong to the tab, not to whichever tab happens to be showing.
                    holder.SaveableStateProvider(shown.key) {
                        ChatPane(
                            session = session,
                            token = token,
                            tab = shown,
                            tabTitle = if (hasTopics && shown != RoomTab.All) tabName else null,
                            // The message a notification pointed to, in the tab the room opened on.
                            targetMessageId = route.messageId?.takeIf { !hasTopics || shown == vm.openedOn },
                            // One asked for later, with the room already open.
                            jumpTo = vm.jump?.takeIf { !hasTopics || it.first == shown }?.second,
                            // Only this jump: a newer one may have taken its place meanwhile.
                            onJumped = { id -> if (vm.jump?.second == id) vm.jump = null },
                            // Nor before the room itself (its read marker) is known.
                            readingEnabled = topicsKnown && room != null,
                            snackbar = snackbar,
                            selection = selection,
                            // One message: the same forward as the selection bar's, with it picked.
                            onForward = { m ->
                                if (!vm.forwarding && !selection.active) {
                                    selection.start(m)
                                    if (selection.active) {
                                        forwardFromMenu = true
                                        forwardOpen = true
                                    }
                                }
                            },
                            onOpenTopic = { id -> vm.select(RoomTab.Topic(id)) },
                            aboveComposer = { atBottom ->
                                if (atBottom && hasTopics && nextUnread != null) {
                                    NextUnreadPill(nextUnread) { vm.select(nextUnread.tab) }
                                }
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

    if (scheduledOpen) {
        ScheduledMessagesSheet(session = session, token = token, onDismiss = { scheduledOpen = false })
    }

    if (sheetOpen) {
        TopicsSheet(
            session = session,
            topics = topicList,
            unread = unread,
            lastRead = lastRead,
            generalLast = whole.messages.lastOrNull { it.topicId == 0L && !it.isUpdateSignal },
            selected = shown.takeIf { draft == null },
            onPick = { tab ->
                sheetOpen = false
                vm.select(tab)
            },
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
