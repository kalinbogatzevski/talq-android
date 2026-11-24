package talq.app.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import talq.app.appContainer
import talq.app.call.CallActivity
import talq.app.files.RecordingState
import talq.app.push.TalqNotifications
import talq.app.theme.TalqTheme
import talq.app.ui.room.RoomTab
import talq.app.ui.adaptive.LocalTwoPane
import talq.app.ui.common.ConnectionLine
import talq.app.ui.common.ConnectionState
import talq.app.ui.common.MessagePane
import talq.app.ui.common.connectionState
import talq.app.ui.common.userMessage
import talq.app.ui.files.FileOverlays
import talq.app.ui.files.rememberFileUi
import talq.app.ui.message.ChatItem
import talq.app.ui.message.DaySeparator
import talq.app.ui.message.MessageRow
import talq.app.ui.message.ReadMark
import talq.app.ui.message.NewMessagesDivider
import talq.app.ui.message.PendingFileRow
import talq.app.ui.message.PendingRow
import talq.app.ui.message.SystemMessageRow
import talq.app.ui.message.TopicChip
import talq.app.ui.message.buildChatItems
import talq.app.ui.message.previewText
import talq.core.TalkError
import talq.core.model.ChatMessage
import talq.core.model.RichObject
import talq.core.model.RoomType
import talq.core.model.VoicePlace
import talq.core.model.isSelectableMessage
import talq.core.repo.ChatScope
import talq.core.repo.TalkSession
import java.time.ZonedDateTime

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/** How long a message asked for in an open room may take to turn up before the jump is dropped. */
private const val JUMP_WAIT_MILLIS = 5_000L

/**
 * The content of one tab of an open conversation: the messages and the composer. The room
 * host (`RoomHostScreen`) owns the top bar and the topic strip and shows one pane at a time,
 * each inside its own saveable-state scope, so scroll position and "already positioned" are
 * per tab. Shows what is in memory at once and goes live while it is on screen.
 *
 * @param tabTitle the selected tab's name for the composer ("Message in ..."); null in the
 *   whole-chat view
 * @param targetMessageId the message to land on at the first show (from a notification)
 * @param jumpTo a message to scroll to now, asked for after the first show; [onJumped] gets its
 *   id once it was done, given up (not turned up within a few seconds) or interrupted
 * @param readingEnabled false while it is not yet known whether this room has topics: what is on
 *   screen in the whole-chat view must not mark every topic read
 * @param onOpenTopic a message's topic label was tapped in the whole-chat view
 * @param aboveComposer a slot between the messages and the composer; it is told whether the
 *   newest message is on screen (the user is at the bottom)
 * @param selection the room's picked messages (Copy / Forward): touch and hold offers "Select",
 *   then taps pick; the composer is hidden meanwhile. Null: no selecting.
 * @param onForward touch and hold's "Forward" on one message (with [selection] only): the room
 *   host picks it and opens the conversation picker, the selection bar's one forward path.
 */
@Composable
fun ChatPane(
    session: TalkSession,
    token: String,
    tab: RoomTab,
    tabTitle: String?,
    targetMessageId: Long?,
    readingEnabled: Boolean,
    snackbar: SnackbarHostState,
    onOpenTopic: (topicId: Long) -> Unit,
    aboveComposer: @Composable (atBottom: Boolean) -> Unit = {},
    jumpTo: Long? = null,
    onJumped: (messageId: Long) -> Unit = {},
    selection: MessageSelection? = null,
    onForward: ((ChatMessage) -> Unit)? = null,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val drafts = context.appContainer.drafts
    val vm: ChatViewModel = viewModel(key = "chat-$token-${tab.key}") {
        ChatViewModel(session, token, tab, appContext, drafts, createSavedStateHandle())
    }
    val colors = TalqTheme.colors
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val state by vm.repo.state.collectAsStateWithLifecycle()
    val liveError by vm.repo.liveError.collectAsStateWithLifecycle()
    val commonRead by vm.repo.commonRead.collectAsStateWithLifecycle()
    // The read distinction only where the server gives it and the user shares their own.
    val showRead = session.capabilities.readStatus && session.capabilities.sharesReadStatus
    val rooms by session.rooms.rooms.collectAsStateWithLifecycle()
    val roomsLoad by session.rooms.state.collectAsStateWithLifecycle()
    val topicList by vm.topics.topics.collectAsStateWithLifecycle()
    val room = remember(rooms) { rooms.firstOrNull { it.token == token } }
    val topicsById = remember(topicList) { topicList.associateBy { it.id } }

    // Live only while this tab is the one on screen (the host keeps the room's feed itself).
    LifecycleStartEffect(vm.repo) {
        vm.repo.start()
        onStopOrDispose { vm.repo.stop() }
    }
    // In front: its notifications come down and new ones for this tab stay quiet.
    LifecycleResumeEffect(token, vm.bucket) {
        TalqNotifications.chatOpened(context, token, vm.bucket)
        onPauseOrDispose { TalqNotifications.chatClosed(token, vm.bucket) }
    }
    LaunchedEffect(state.error, liveError) {
        if (state.error is TalkError.Unauthorized || liveError is TalkError.Unauthorized) context.appContainer.onUnauthorized()
    }

    // Shown on the pane's scope: clearing the notice restarts the effect, and a message shown
    // from inside it would go down with it.
    LaunchedEffect(vm.notice) {
        vm.notice?.let {
            vm.notice = null
            scope.launch { snackbar.showSnackbar(it) }
        }
    }

    // Files on their way into this tab (the whole-chat view shows the room's).
    val uploads = context.appContainer.uploads
    val allUploads by uploads.pending.collectAsStateWithLifecycle()
    val pendingFiles = remember(allUploads, token, vm.bucket) {
        allUploads.filter { it.token == token && (vm.bucket == null || it.threadId == vm.bucket) }
    }
    val uploadNotice by uploads.notice.collectAsStateWithLifecycle()
    LaunchedEffect(uploadNotice) {
        uploadNotice?.let {
            uploads.notice.value = null
            scope.launch { snackbar.showSnackbar(it) }
        }
    }
    // What is picked goes out at once, into this tab: the typed text is the first file's
    // caption and the reply target its quote. If nothing could be read the text comes back.
    // In the whole-chat view a quoted topic message takes the file into its topic, as a text would go.
    fun sendTarget(quote: ChatMessage?): Long = vm.bucket?.takeIf { it > 0 } ?: quote?.topicId ?: 0L
    fun takeCaption(): Pair<String, ChatMessage?> {
        val taken = vm.draft.trim() to vm.replyTo
        vm.draft = ""
        vm.replyTo = null
        return taken
    }
    val giveBack: (String) -> Unit = { caption -> if (vm.draft.isEmpty()) vm.draft = caption }
    val attach = rememberAttachActions(
        onPicked = { uris ->
            val (caption, quote) = takeCaption()
            uploads.sendUris(token, sendTarget(quote), uris, caption, quote, giveBack)
        },
        onPhotoTaken = { file ->
            val (caption, quote) = takeCaption()
            uploads.sendCameraPhoto(token, sendTarget(quote), file, caption, quote, giveBack)
        },
        onProblem = { vm.notice = it },
    )
    var attachOpen by remember { mutableStateOf(false) }
    // Voice messages: the microphone on the empty composer, only where the server keeps the
    // voice-message flag and the user may upload. A recording belongs to the ROOM where it
    // started (and the topic of the tab it started in); every tab of the room shows its controls.
    val voiceMessages = context.appContainer.voiceMessages
    val recordingState by voiceMessages.state.collectAsStateWithLifecycle()
    val recordingHere = (recordingState as? RecordingState.Recording)?.takeIf { it.place.token == token }
    val place = VoicePlace(token, vm.bucket?.takeIf { it > 0 } ?: 0L)
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Granted: the next press records (nothing starts by itself after the question).
        if (!granted) {
            scope.launch {
                val answer = snackbar.showSnackbar("Voice messages need the microphone.", actionLabel = "Settings", duration = SnackbarDuration.Short)
                if (answer == SnackbarResult.ActionPerformed) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
    val canRecord = session.capabilities.voiceMessages && session.capabilities.attachments.allowed
    val voiceUi = if (!canRecord && recordingHere == null) null else VoiceRecordUi(
        recording = recordingHere,
        onStart = { holding ->
            if (!CallActivity.granted(context, Manifest.permission.RECORD_AUDIO)) {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                false
            } else {
                val refused = voiceMessages.start(session, place, room?.displayName.orEmpty(), tabTitle.orEmpty(), holding)
                if (refused != null) vm.notice = refused
                refused == null
            }
        },
        onLock = voiceMessages::lock,
        onSend = { voiceMessages.stop(place) },
        onCancel = voiceMessages::cancel,
    )
    // Send options (touch and hold the send button), each only where the server has it.
    val canSendSilently = session.capabilities.silentSend
    val canSendLater = session.capabilities.scheduledMessages
    var sendOptions by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    // A crossed-out bell on the send button for a moment after a silent send (as on desktop).
    var sentSilently by remember { mutableStateOf(false) }
    LaunchedEffect(sentSilently) {
        if (sentSilently) {
            delay(600)
            sentSilently = false
        }
    }
    // Reply (and Edit) put the cursor in the box and bring up the keyboard, as TalQ for desktop focuses
    // its composer on Reply. A count of the taps, not "a reply is set": a reply kept with the draft must
    // not raise the keyboard each time the tab opens. Not saved: a rotation asks nothing again.
    var replyFocus by remember { mutableIntStateOf(0) }
    fun startReply(m: ChatMessage) {
        vm.replyTo = m
        replyFocus++
    }
    fun startEdit(m: ChatMessage): Boolean {
        val started = vm.startEdit(m)
        if (started) replyFocus++
        return started
    }
    val editing = vm.editing
    // Back ends an open edit first (Esc in the box does too); not while its save runs, nor while
    // messages are being picked (the box is hidden then and Back ends the picking).
    BackHandler(enabled = editing != null && !vm.saving && selection?.active != true) { vm.cancelEdit() }

    val dividerAfter = vm.dividerAfter
    val items = remember(state.messages, state.outbox, pendingFiles, dividerAfter) {
        buildChatItems(
            messages = state.messages,
            outbox = state.outbox,
            isMe = vm::isMe,
            dividerAfter = dividerAfter,
            hideTopicNotices = vm.scope is ChatScope.Topic,
            files = pendingFiles,
        ).asReversed()
    }
    val listState = rememberLazyListState()
    var positioned by rememberSaveable { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<Long?>(null) }
    // The list as it is now, for code that waits (a coroutine keeps the composition it began in).
    val latestItems by rememberUpdatedState(items)

    suspend fun scrollToMessage(id: Long, animate: Boolean): Boolean {
        val index = latestItems.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        if (index < 0) return false
        val viewport = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        // Reversed list: a negative offset lifts the row off the bottom edge.
        if (animate) listState.animateScrollToItem(index, -(viewport * 0.4f).toInt())
        else listState.scrollToItem(index, -(viewport * 0.4f).toInt())
        highlight = id
        return true
    }

    // First show: at the message a notification pointed to, else at the "New messages"
    // divider, else at the newest message. Done as soon as what is in memory is enough to
    // know where that is (the messages taken over from the whole chat hold the wanted message,
    // or reach back to the last read one), so a tab opens in place instead of jumping once its
    // own load answers. Otherwise it waits for that load.
    val memoryReaches = if (targetMessageId != null) state.messages.any { it.id == targetMessageId }
    else dividerAfter > 0L && state.messages.firstOrNull()?.let { it.id <= dividerAfter } == true
    // The read marker comes with the rooms list; without it the divider is not known yet.
    val markerKnown = vm.roomKnown || roomsLoad.loaded
    val canPosition = items.isNotEmpty() && markerKnown && (state.loaded || memoryReaches)
    LaunchedEffect(canPosition) {
        if (positioned || !canPosition) return@LaunchedEffect
        try {
            val wanted = targetMessageId
            if (wanted != null && scrollToMessage(wanted, animate = false)) return@LaunchedEffect
            val divider = items.indexOf(ChatItem.NewMessages)
            if (divider > 0) {
                val viewport = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
                listState.scrollToItem(divider, -(viewport * 0.75f).toInt())
            }
        } finally {
            // Only now may "the newest message is on screen" count as reading.
            positioned = true
        }
    }
    // A message asked for with the room already open. It may still be on its way: the tab's feed
    // starts again when the app comes back to the front.
    val jumpDone by rememberUpdatedState(onJumped)
    LaunchedEffect(jumpTo, positioned) {
        val id = jumpTo ?: return@LaunchedEffect
        if (!positioned) return@LaunchedEffect
        try {
            withTimeoutOrNull(JUMP_WAIT_MILLIS) {
                snapshotFlow { latestItems.any { it is ChatItem.Message && it.message.id == id } }.first { it }
            }
            scrollToMessage(id, animate = true)
        } finally {
            // Also when interrupted (the tab left, the scroll touched): never later, out of the blue.
            jumpDone(id)
        }
    }
    LaunchedEffect(highlight) {
        if (highlight != null) {
            delay(1600)
            highlight = null
        }
    }

    val atBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 48 }
    }
    // The newest message whose end (its bottom edge) is on screen, 0 when none is. In the reversed
    // list a row's offset is its bottom edge, counted up from the bottom of the list.
    val newestOnScreen by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            var newest = 0L
            for (row in info.visibleItemsInfo) {
                if (row.offset < info.viewportStartOffset || row.offset >= info.viewportEndOffset) continue
                val id = messageIdOfKey(row.key)
                if (id > newest) newest = id
            }
            newest
        }
    }

    // Reading = what has been on screen while the app is in front, read up to where the user
    // stopped: everything up to the newest message on screen (the ones above it were passed on the
    // way down). At the bottom: the tab's newest message and the room's last entry (also a reaction
    // or a system notice, per the conversation list), at once; elsewhere once the list rests.
    // Only after the first positioning: before it the list sits on the newest message.
    LaunchedEffect(vm, lifecycleOwner, positioned, readingEnabled) {
        if (!readingEnabled || !positioned) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow {
                if (atBottom) {
                    val roomLast = rooms.firstOrNull { it.token == vm.token }?.lastMessage?.id ?: 0L
                    Seen(state.messages.lastOrNull()?.id ?: 0L, roomLast, settle = false)
                } else {
                    Seen(newestOnScreen, 0L, settle = true)
                }
            }.collect { seen -> if (seen.upTo > 0L) vm.onSeen(seen.upTo, seen.roomLast, seen.settle) }
        }
    }

    // Stay at the bottom when something new arrives there, and always follow our own messages.
    val newestItem = items.firstOrNull()
    LaunchedEffect(newestItem?.key) {
        if (!positioned || newestItem == null) return@LaunchedEffect
        val own = newestItem is ChatItem.Pending || newestItem is ChatItem.PendingFile || (newestItem is ChatItem.Message && newestItem.mine)
        if (own || listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }

    // Older pages as the top comes near.
    LaunchedEffect(listState, vm) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            (info.totalItemsCount > 0 && last >= info.totalItemsCount - 8) to info.totalItemsCount
        }.collect { (nearTop, _) -> if (nearTop) vm.loadOlder() }
    }

    // Picked messages follow edits and drop out when deleted.
    LaunchedEffect(selection, state.messages) { selection?.sync(state.messages) }
    val selecting = selection?.active == true

    // Drops as the user scrolls down through them. Read only by the jump button, so a change
    // recomposes that button, not the pane.
    val unseenBelow by remember(vm) {
        derivedStateOf {
            val seen = vm.seenUpTo
            if (seen == 0L) 0 else state.messages.count { it.id > seen && !it.isSystem && !it.isUpdateSignal && !vm.isMe(it) }
        }
    }

    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    // Received files: the viewer swipes through the pictures loaded in this tab, oldest first.
    val fileUi = rememberFileUi(snackbar)
    val images = remember(state.messages) {
        state.messages.flatMap { m -> if (m.deleted) emptyList() else m.files.filter { it.isImage && it.previewAvailable } }
    }

    val oneToOne = room?.type == RoomType.ONE_TO_ONE || room?.type == RoomType.FORMER_ONE_TO_ONE ||
        room?.type == RoomType.NOTE_TO_SELF
    val readOnly = room?.isReadOnly == true

    // The one connection line under the header (the list's words and rules); beside the list (wide
    // layout) the list's own line says it. Failing = the live feed, or the first load (an older
    // page that failed once says nothing while the feed works).
    val link by context.appContainer.network.link.collectAsStateWithLifecycle()
    val failing = liveError is TalkError.Network || (!state.loaded && state.error is TalkError.Network)
    val connection = connectionState(link, failing)
    val twoPane = LocalTwoPane.current

    Column(Modifier.fillMaxSize()) {
        if (connection != null && items.isNotEmpty() && !twoPane) ConnectionLine(connection)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val error = state.error
            when {
                // Offline: it loads by itself once the network is back (the retry is woken).
                items.isEmpty() && !state.loaded && error != null && connection == ConnectionState.Offline ->
                    MessagePane(title = "No connection", detail = "Messages load when you're back online.")
                items.isEmpty() && !state.loaded && error != null -> MessagePane(
                    title = "Couldn't load the messages",
                    detail = error.userMessage(),
                    action = "Try again",
                    onAction = vm::retryLoad,
                )
                items.isEmpty() && !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
                }
                items.isEmpty() -> MessagePane(
                    title = if (vm.scope == ChatScope.General) "Nothing outside the topics yet" else "No messages yet",
                    detail = if (readOnly) "This conversation is read-only." else "Write the first one below.",
                )
                else -> LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 6.dp),
                ) {
                    items(items, key = { it.key }, contentType = { it::class }) { item ->
                        when (item) {
                            is ChatItem.Day -> DaySeparator(item.label)
                            ChatItem.NewMessages -> NewMessagesDivider()
                            is ChatItem.System -> SystemMessageRow(item.message)
                            is ChatItem.Pending -> PendingRow(
                                outbox = item.outbox,
                                text = drafts.readable(item.outbox.text),
                                onRetry = { vm.repo.retry(item.outbox.referenceId) },
                                onDiscard = { vm.repo.discard(item.outbox.referenceId) },
                            )
                            is ChatItem.PendingFile -> PendingFileRow(
                                upload = item.upload,
                                caption = drafts.readable(item.upload.caption),
                                onRetry = { uploads.retry(item.upload.id) },
                                onDiscard = { uploads.discard(item.upload.id) },
                            )
                            is ChatItem.Message -> {
                                val m = item.message
                                val chip = if (vm.scope == ChatScope.All && m.topicId != 0L && item.startsGroup) {
                                    val known = topicsById[m.topicId]
                                    TopicChip(
                                        id = m.topicId,
                                        title = known?.title ?: m.threadTitle,
                                        replies = if (m.isTopicRoot) known?.thread?.numReplies ?: m.threadReplies else null,
                                    )
                                } else null
                                MessageRow(
                                    item = item,
                                    session = session,
                                    readMark = when {
                                        !item.mine -> null
                                        showRead && commonRead > 0 && m.id <= commonRead -> ReadMark.Read
                                        else -> ReadMark.Delivered
                                    },
                                    showSender = !oneToOne,
                                    topicChip = chip,
                                    highlighted = highlight == m.id,
                                    // Not while an edit is open: its bar takes the reply bar's place.
                                    canReply = !readOnly && m.isReplyable && editing == null,
                                    onReply = { startReply(m) },
                                    onLongPress = { if (!selecting) actionsFor = m },
                                    selecting = selecting,
                                    selected = selection?.isSelected(m.id) == true,
                                    onToggleSelect = { selection?.toggle(m) },
                                    onQuoteClick = { id ->
                                        scope.launch {
                                            if (!scrollToMessage(id, animate = true)) {
                                                vm.notice = "That message is further back. Scroll up to load it."
                                            }
                                        }
                                    },
                                    onOpenTopic = onOpenTopic,
                                    onOpenImage = { fileUi.openImage(it, images) },
                                    onOpenFile = fileUi::open,
                                    onReaction = { emoji -> vm.toggleReaction(m.id, emoji) },
                                )
                            }
                        }
                    }
                    if (state.loadingOlder) {
                        item(key = "loading-older") {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = !atBottom && items.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 10.dp),
            ) {
                Box {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        containerColor = colors.bgSurface,
                        contentColor = colors.textPrimary,
                        shape = CircleShape,
                    ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Newest messages") }
                    if (unseenBelow > 0) {
                        Text(
                            text = if (unseenBelow > 99) "99+" else unseenBelow.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.inkOn(colors.countFill),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .defaultMinSize(minWidth = 18.dp)
                                .clip(CircleShape)
                                .background(colors.countFill)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        aboveComposer(atBottom)
        if (selecting && recordingHere == null) {
            // Picking messages: no composer, as on desktop (the top bar has the actions).
            // A recording keeps its controls: the microphone is never open without them.
        } else if (readOnly && recordingHere == null) {
            Text(
                "This conversation is read-only.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.fillMaxWidth().background(colors.bgSecondary).padding(16.dp),
            )
        } else {
            // While an edit is open the box is the edit: no file, no voice message, no send options
            // (desktop refuses them too); a recording that runs keeps its controls.
            val plain = editing == null
            Composer(
                text = if (plain) vm.draft else vm.editText,
                onTextChange = { if (vm.editing == null) vm.draft = it.take(vm.maxLength) else vm.editText = it.take(vm.maxLength) },
                replyTo = vm.replyTo,
                onCancelReply = { vm.replyTo = null },
                onSend = { vm.send() },
                placeholder = if (tabTitle.isNullOrBlank()) "Message" else "Message in $tabTitle",
                onAttach = if (plain && session.capabilities.attachments.allowed) ({ attachOpen = true }) else null,
                onSendOptions = if (plain && (canSendSilently || canSendLater)) ({ sendOptions = true }) else null,
                busy = vm.scheduling || vm.saving,
                sentSilently = sentSilently,
                voice = if (plain || recordingHere != null) voiceUi else null,
                // Alt+Enter on a hardware keyboard (desktop's silent send).
                onSendSilently = if (plain && canSendSilently) ({
                    vm.send(silent = true)
                    sentSilently = true
                }) else null,
                focusTick = replyFocus,
                editing = editing,
                onCancelEdit = vm::cancelEdit,
                // Up in an empty box: the newest message of this tab the user may still edit.
                onEditLast = { state.messages.lastOrNull { vm.canEdit(it) }?.let { startEdit(it) } == true },
                mentions = vm.mentions,
            )
        }
    }

    actionsFor?.let { m ->
        MessageActionsSheet(
            message = m,
            canReply = !readOnly && m.isReplyable && !m.deleted && editing == null,
            canReact = session.capabilities.reactions && !readOnly && !m.deleted,
            showOpenTopic = vm.scope == ChatScope.All && m.topicId != 0L,
            onDismiss = { actionsFor = null },
            onReply = {
                actionsFor = null
                startReply(m)
            },
            onCopy = {
                copyToClipboard(context, m.plainText)
                actionsFor = null
            },
            onReaction = { emoji ->
                vm.toggleReaction(m.id, emoji)
                actionsFor = null
            },
            onOpenTopic = {
                actionsFor = null
                onOpenTopic(m.topicId)
            },
            onForward = if (selection != null && onForward != null && isSelectableMessage(m)) ({
                actionsFor = null
                onForward(m)
            }) else null,
            onSelect = if (selection != null && isSelectableMessage(m)) ({
                actionsFor = null
                selection.start(m)
            }) else null,
            // Own messages the server still takes it for. Not during a recording here (its row hides
            // the box), a send later or a save on its way (the box is read-only then).
            onEdit = if (vm.canEdit(m) && recordingHere == null && !vm.scheduling && !vm.saving) ({
                actionsFor = null
                startEdit(m)
            }) else null,
            onDelete = if (vm.canDelete(m)) ({
                actionsFor = null
                vm.confirmDelete = m
            }) else null,
            file = m.file.takeIf { !m.deleted },
            onSave = { file ->
                actionsFor = null
                fileUi.save(file)
            },
            onShare = { file ->
                actionsFor = null
                fileUi.share(file)
            },
        )
    }
    if (sendOptions) {
        SendOptionsSheet(
            silent = canSendSilently,
            later = canSendLater,
            onSilent = {
                sendOptions = false
                vm.send(silent = true)
                sentSilently = true
            },
            onLater = { choice ->
                sendOptions = false
                val at = choice.at.toEpochSecond()
                vm.schedule(at, scheduleTimeLabel(context, at))
            },
            onPickTime = {
                sendOptions = false
                pickTime = true
            },
            onDismiss = { sendOptions = false },
        )
    }
    if (pickTime) {
        // TalQ for desktop's custom time starts an hour from when it is opened.
        DateTimePickerDialogs(
            initial = remember { ZonedDateTime.now().plusHours(1) },
            onPicked = { picked ->
                pickTime = false
                val at = picked.toEpochSecond()
                vm.schedule(at, scheduleTimeLabel(context, at))
            },
            onDismiss = { pickTime = false },
        )
    }
    vm.confirmDelete?.let { m ->
        DeleteMessageDialog(
            deleting = vm.deleting,
            onDelete = { vm.delete(m) },
            onDismiss = { vm.confirmDelete = null },
        )
    }
    if (attachOpen) AttachSheet(attach, uploads.prefs, onDismiss = { attachOpen = false })
    FileOverlays(fileUi, session)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    message: ChatMessage,
    canReply: Boolean,
    canReact: Boolean,
    showOpenTopic: Boolean,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onReaction: (String) -> Unit,
    onOpenTopic: () -> Unit,
    onForward: (() -> Unit)?,
    onSelect: (() -> Unit)?,
    file: RichObject?,
    onSave: (RichObject) -> Unit,
    onShare: (RichObject) -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val colors = TalqTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                text = previewText(message),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp),
            )
            if (canReact) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for (emoji in QUICK_REACTIONS) {
                        val own = emoji in message.reactionsSelf
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(if (own) colors.accentSoft else colors.bgSecondary)
                                .clickable { onReaction(emoji) },
                            contentAlignment = Alignment.Center,
                        ) { Text(emoji, fontSize = 22.sp) }
                    }
                }
            }
            if (canReply) SheetAction("Reply", { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) }, onReply)
            if (file != null) {
                SheetAction("Save", { Icon(Icons.Outlined.FileDownload, contentDescription = null) }) { onSave(file) }
                SheetAction("Share", { Icon(Icons.Outlined.Share, contentDescription = null) }) { onShare(file) }
            }
            // A file without a caption has no text to copy.
            if (!message.deleted && (file == null || message.caption.isNotBlank())) {
                SheetAction("Copy text", { Icon(Icons.Outlined.ContentCopy, contentDescription = null) }, onCopy)
            }
            // This one message to another conversation (desktop's right-click Forward).
            if (onForward != null) SheetAction("Forward", { Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null) }, onForward)
            if (showOpenTopic) SheetAction("Open topic", { Icon(Icons.Outlined.Forum, contentDescription = null) }, onOpenTopic)
            // The user's own messages, in desktop's order (Edit, Delete, then Select).
            if (onEdit != null) SheetAction("Edit", { Icon(Icons.Outlined.Edit, contentDescription = null) }, onEdit)
            if (onDelete != null) {
                SheetActionRow(
                    "Delete",
                    { Icon(Icons.Outlined.Delete, contentDescription = null, tint = colors.danger) },
                    colors.danger,
                    onDelete,
                )
            }
            // Several messages at once, for Copy and Forward.
            if (onSelect != null) SheetAction("Select", { Icon(Icons.Outlined.CheckCircle, contentDescription = null) }, onSelect)
        }
    }
}

@Composable
private fun SheetAction(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) =
    SheetActionRow(label, icon, Color.Unspecified, onClick)

/** A row of the long-press sheet; [color] for its label (the danger ink for Delete). */
@Composable
private fun SheetActionRow(label: String, icon: @Composable () -> Unit, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** What the reading effect hands to [ChatViewModel.onSeen]. */
private data class Seen(val upTo: Long, val roomLast: Long, val settle: Boolean)

/** The message id of a list key ("m<id>", see ChatItem), 0 for any other row (day, divider, outbox). */
private fun messageIdOfKey(key: Any): Long {
    val k = key as? String ?: return 0L
    return if (k.length > 1 && k[0] == 'm') k.substring(1).toLongOrNull() ?: 0L else 0L
}

internal fun copyToClipboard(context: Context, text: String, label: String = "Message") {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * One line said wherever the user is now (a toast; main thread): for an answer that arrives
 * after the screen that asked for it was left.
 */
internal fun sayAnywhere(context: Context, text: String) {
    Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
}
