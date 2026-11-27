package talq.app.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import talq.app.ui.common.SAVED_LINE_MILLIS
import talq.app.ui.common.SavedLine
import talq.app.ui.common.connectionState
import talq.app.ui.common.onSecondaryClick
import talq.app.ui.common.savedState
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
import talq.core.model.Room
import talq.core.model.RoomType
import talq.core.model.VoicePlace
import talq.core.model.captionOver
import talq.core.model.isSelectableMessage
import talq.core.model.messageOver
import talq.core.repo.ChatScope
import talq.core.repo.OutboxMessage
import talq.core.repo.OutboxStop
import talq.core.repo.TalkSession
import java.time.ZonedDateTime

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/** How long a message that was found may take to turn up among the rows. */
private const val JUMP_WAIT_MILLIS = 5_000L

/** Reading what the phone kept takes less than this. */
internal const val SPINNER_MILLIS = 300L

/** How many loads that show nothing are made by themselves, one after another (General: up to 500 messages each). */
private const val LOOK_BACK_LOADS = 4

/**
 * The content of one tab of an open conversation: the messages and the composer. The room
 * host (`RoomHostScreen`) owns the top bar and the topic strip and shows one pane at a time,
 * each inside its own saveable-state scope, so scroll position and "already positioned" are
 * per tab. Shows what is in memory at once and goes live while it is on screen.
 *
 * What an earlier run of the app kept of it (`ChatState.saved`) is drawn before the server has answered, and read,
 * answered and written under as any message; but nothing of it counts as seen, and it is not yet the place the tab
 * opens on (the "New messages" line, the newest message): that comes with the answer. Rows the user has scrolled to
 * meanwhile stay where they are, unless something was missing after them (`ChatState.cuts`).
 *
 * [tab] changes under a pane in one case: a conversation shown as one whole chat gets its first topic and
 * the same pane goes on as General (the list keeps its place by message, the box is the same one).
 *
 * A message that is not loaded (a quote's, a notification's, "Show in chat") is shown in a window of its own in
 * the list's place; see JumpToMessage.kt and the view model's `seek`, the one way there.
 *
 * @param tabTitle the selected tab's name for the composer ("Message in ..."); null in the
 *   whole-chat view
 * @param targetMessageId the message to land on at the first show (from a notification)
 * @param jumpTo a message to go to now, asked for with the room open; [onJumped] gets its id once it is
 *   being looked for
 * @param onOpenMessage a message that was looked for is in another tab: in a topic, or (0) in none
 * @param readingEnabled false while it is not yet known whether this room has topics: what is on
 *   screen in the whole-chat view must not mark every topic read
 * @param onOpenTopic a message's topic label was tapped in the whole-chat view
 * @param aboveComposer a slot between the messages and the composer; it is told whether the
 *   newest message is on screen (the user is at the bottom)
 * @param selection the room's picked messages (Copy / Forward / Delete): touch and hold offers "Select",
 *   then taps pick; the composer is hidden meanwhile. Null: no selecting.
 * @param onForward touch and hold's "Forward" on one message (with [selection] only): the room
 *   host picks it and opens the conversation picker, the selection bar's one forward path.
 * @param alsoUnsent texts not sent that show here too (General: a topic's first message that no topic
 *   being started holds; a room with topics has no tab of the whole chat where it would show)
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
    onOpenMessage: (topicId: Long, messageId: Long) -> Unit = { _, _ -> },
    aboveComposer: @Composable (atBottom: Boolean) -> Unit = {},
    jumpTo: Long? = null,
    onJumped: (messageId: Long) -> Unit = {},
    selection: MessageSelection? = null,
    onForward: ((ChatMessage) -> Unit)? = null,
    alsoUnsent: List<OutboxMessage> = emptyList(),
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val drafts = context.appContainer.drafts
    val typing = context.appContainer.typing
    val vm: ChatViewModel = viewModel(key = "chat-$token-${tab.key}") {
        ChatViewModel(session, token, tab, appContext, drafts, createSavedStateHandle())
    }
    val colors = TalqTheme.colors
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val state by vm.repo.state.collectAsStateWithLifecycle()
    // The messages around one that is further back, in the place of the tab's own.
    val window = vm.window
    val windowHeld = if (window == null) null else key(window) { window.state.collectAsStateWithLifecycle() }
    val shownMessages = windowHeld?.value?.messages ?: state.messages
    val liveError by vm.repo.liveError.collectAsStateWithLifecycle()
    val liveCommonRead by vm.repo.commonRead.collectAsStateWithLifecycle()
    val commonRead = if (liveCommonRead > 0L) liveCommonRead else state.savedCommonRead
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
    LaunchedEffect(state.error, state.olderError, liveError) {
        if (listOf(state.error, state.olderError, liveError).any { it is TalkError.Unauthorized }) context.appContainer.onUnauthorized()
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
    val pendingFiles = remember(allUploads, token, vm.bucket, state.messages) {
        val here = allUploads.filter { it.token == token && (vm.bucket == null || it.threadId == vm.bucket) }
        // One whose message is there is sent (an earlier run may have kept the message before it heard of it).
        if (here.isEmpty()) here else state.messages.mapTo(HashSet()) { it.referenceId }.let { refs -> here.filter { it.referenceId.isEmpty() || it.referenceId !in refs } }
    }
    // That one's record and copy go too: they would be told of as not sent, and could be sent again.
    LaunchedEffect(uploads, token, allUploads, state.messages, state.loaded, state.saved) {
        if (!state.loaded || state.saved) return@LaunchedEffect
        val left = allUploads.filter { it.token == token && it.referenceId.isNotEmpty() && !it.state.active }
        if (left.isEmpty()) return@LaunchedEffect
        val refs = state.messages.mapTo(HashSet()) { it.referenceId }
        uploads.arrived(token, left.mapTo(HashSet()) { it.referenceId }.apply { retainAll(refs) })
    }
    val uploadNotice by uploads.notice.collectAsStateWithLifecycle()
    LaunchedEffect(uploadNotice) {
        uploadNotice?.let {
            uploads.notice.value = null
            scope.launch { snackbar.showSnackbar(it) }
        }
    }
    // What is picked, dropped or pasted waits in this tab's tray above the box (AttachTrays) until Send: the
    // typed text is then the first file's caption and the reply target its quote.
    // In the whole-chat view a quoted topic message takes the file into its topic, as a text would go.
    fun sendTarget(quote: ChatMessage?): Long = vm.bucket?.takeIf { it > 0 } ?: quote?.topicId ?: 0L
    val trays = uploads.trays
    val trayKey = vm.trayKey
    val tray = trays.items(trayKey)
    // Never into a conversation on another server: the attachment routes (and the classic share) are local only.
    val filesAllowed = session.capabilities.attachments.allowed && room?.isFederated != true
    fun sendTray(silent: Boolean): Boolean {
        if (vm.scheduling) return false
        if (!filesAllowed) {
            vm.notice = "Files can't be sent here."
            return false
        }
        val files = trays.take(trayKey) ?: return false
        val text = vm.draft.trim()
        val quote = vm.replyTo
        // A server without captions drops them: there the text stays a message of its own, after the files,
        // and keeps the reply.
        val asCaption = text.isEmpty() || session.capabilities.mediaCaption
        val into = sendTarget(quote)
        if (asCaption) {
            vm.draft = ""
            vm.replyTo = null
        }
        uploads.enqueue(session, token, into, files, if (asCaption) text else "", quote.takeIf { asCaption }, silent = silent)
        if (!asCaption) vm.send(silent)
        return true
    }
    // The button, Enter and the send options: an open edit is saved, never sent with the files. False when
    // files that are still being copied kept it all here.
    fun send(silent: Boolean = false): Boolean {
        typing.typed(session, token, hasText = false)
        if (vm.editing == null && trays.items(trayKey).isNotEmpty()) return sendTray(silent)
        vm.send(silent)
        return true
    }
    // Never refused: what the user picked is kept, whatever the box is doing by now.
    val pickers = LocalAttachPickers.current
    val attach = remember(pickers, vm) { pickers.into(vm.trayKey) { vm.notice = it } }
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
    val canRecord = session.capabilities.voiceMessages && filesAllowed
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
    // Files dropped, pasted or handed over by the keyboard: into the tray, or one line why not (desktop's
    // refusals). [done] always runs, once nothing more is read from them.
    fun receive(files: IncomingFiles, done: () -> Unit = {}): Boolean {
        val refused = when {
            !filesAllowed -> "Files can't be sent here."
            vm.editing != null -> "Not attached: finish or cancel the edit first."
            recordingHere != null -> "Not attached: finish the recording first."
            vm.scheduling -> "Not attached: a message is being scheduled."
            else -> null
        }
        if (refused != null) {
            vm.notice = refused
            done()
            return false
        }
        trays.add(trayKey, files.uris, files.mime, done)
        return true
    }
    // True when the clipboard held files (also when they were refused: their names are not for the box).
    fun paste(): Boolean {
        val files = clipboardFiles(context) ?: return false
        receive(files)
        return true
    }
    // Send options (touch and hold the send button), each only where the server has it (a silent file needs
    // its caption metadata; files are never scheduled: the schedule takes a text and nothing else).
    val canSendSilently = session.capabilities.silentSend && (tray.isEmpty() || session.capabilities.mediaCaption)
    // Not in a federated conversation (the schedule routes are local only), nor before that is known.
    val canSendLater = session.capabilities.scheduledMessages && room?.isFederated == false && tray.isEmpty()
    fun schedule(sendAt: Long) {
        // Files may have arrived while the time was being picked.
        if (trays.items(trayKey).isNotEmpty()) vm.notice = "Not scheduled: files can't be sent later."
        else {
            typing.typed(session, token, hasText = false)
            vm.schedule(sendAt, scheduleTimeLabel(context, sendAt))
        }
    }
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
        // Desktop's showEditingBar: the box is the files' caption until they are sent.
        if (trays.items(trayKey).isNotEmpty()) {
            vm.notice = "Send or remove the files first."
            return false
        }
        val started = vm.startEdit(m)
        if (started) replyFocus++
        return started
    }
    val editing = vm.editing
    // Back from a window goes to where its quote was tapped. One that was opened from elsewhere has no such place:
    // Back leaves the room then, but beside the list (Back would hide the app there, and Esc is Back). What is
    // open over it (an edit, the picking) ends first.
    BackHandler(enabled = window != null && (vm.windowFromQuote || LocalTwoPane.current) && selection?.active != true) { vm.leaveWindow() }
    // Back ends an open edit first (Esc in the box does too); not while its save runs, nor while
    // messages are being picked (the box is hidden then and Back ends the picking).
    BackHandler(enabled = editing != null && !vm.saving && selection?.active != true) { vm.cancelEdit() }

    val dividerAfter = vm.dividerAfter
    // The first message of a topic being started is in the whole chat's outbox; its own pane shows it
    // (Retry there looks for it in the chat first), so it is not a second time here.
    val startingRef = drafts.newTopic(ComposerDrafts.Key(ComposerDrafts.accountOf(session), token, RoomTab.KEY_NEW_TOPIC))?.sending
    // Once each: for a frame after the room got its first topic `state` is still the whole chat's, which has them all.
    val unsent = remember(state.outbox, alsoUnsent, startingRef) {
        (state.outbox + alsoUnsent).distinctBy { it.referenceId }.filterNot { it.referenceId == startingRef }
    }
    // The tab's own rows, and those of a window in their place: no rows on their way out, no "New messages" line.
    val ownItems = remember(state.messages, unsent, pendingFiles, dividerAfter) {
        buildChatItems(
            messages = state.messages,
            outbox = unsent,
            isMe = vm::isMe,
            dividerAfter = dividerAfter,
            hideTopicNotices = vm.scope is ChatScope.Topic,
            files = pendingFiles,
        ).asReversed()
    }
    val windowItems = remember(windowHeld?.value?.messages) {
        windowHeld?.value?.messages?.let {
            buildChatItems(it, emptyList(), vm::isMe, dividerAfter = 0L, hideTopicNotices = vm.scope is ChatScope.Topic).asReversed()
        }
    }
    val items = windowItems ?: ownItems
    // Each has its own place: the tab's own is kept while a window is shown.
    val ownList = rememberLazyListState()
    val listState = if (window == null) ownList else key(window) { rememberLazyListState() }
    // The window's newer end is a row of its own before the messages.
    val lead = if (window == null) 0 else 1
    var positioned by rememberSaveable { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<Long?>(null) }
    // The list as it is now, for code that waits (a coroutine keeps the composition it began in).
    val latestItems by rememberUpdatedState(items)
    val latestOwn by rememberUpdatedState(ownItems)

    suspend fun scrollToMessage(id: Long, animate: Boolean) {
        val index = latestItems.indexOfFirst { messageIdOfKey(it.key) == id }
        if (index < 0) return
        val viewport = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        // Reversed list: a negative offset lifts the row off the bottom edge.
        if (animate) listState.animateScrollToItem(lead + index, -(viewport * 0.4f).toInt())
        else listState.scrollToItem(lead + index, -(viewport * 0.4f).toInt())
        highlight = id
    }

    // While what the phone has of it is shown: a message that was gone to; that this was so, and the list was scrolled.
    var onSaved by rememberSaveable { mutableLongStateOf(0L) }
    var fromPhone by rememberSaveable { mutableStateOf(false) }
    var scrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm, state.saved) {
        if (!state.saved) return@LaunchedEffect
        fromPhone = true
        snapshotFlow { ownList.isScrollInProgress }.collect { if (it) scrolled = true }
    }
    // Something was missing between them and the newest messages: nothing is left to hold a place by.
    var cutsSeen by rememberSaveable(vm.tab.key) { mutableIntStateOf(vm.repo.state.value.cuts) }
    LaunchedEffect(vm, state.cuts) {
        if (state.cuts > cutsSeen) {
            scrolled = false
            positioned = false
        }
        // Counted from the start of the process.
        cutsSeen = state.cuts
    }
    // The server has answered: the message gone to is still there, or it is looked for further back.
    LaunchedEffect(vm, state.saved, state.loaded) {
        val id = onSaved
        if (id == 0L || state.saved || !state.loaded) return@LaunchedEffect
        onSaved = 0L
        if (vm.repo.holds(id)) positioned = true else vm.seek(id)
    }

    // The newest messages, whatever is shown or being looked for.
    fun toNewest() {
        vm.leaveWindow()
        if (!state.saved) positioned = true
        ownList.requestScrollToItem(0)
    }

    // The read marker comes with the rooms list; without it the divider is not known yet.
    val markerKnown = vm.roomKnown || roomsLoad.loaded
    // A message to go to: asked for with the room open, or the one this tab opens on (once, also over the end of
    // the process). What is in memory answers at once; anything else once the tab's own load has answered.
    var openOn by rememberSaveable { mutableLongStateOf(targetMessageId ?: 0L) }
    val asked = jumpTo ?: openOn.takeIf { it != 0L }
    val canSeek = asked != null && markerKnown && (state.loaded || window != null || state.messages.any { it.id == asked })
    val jumpDone by rememberUpdatedState(onJumped)
    LaunchedEffect(vm, asked, canSeek) {
        if (asked == null || !canSeek) return@LaunchedEffect
        openOn = 0L
        vm.seek(asked)
        if (asked == jumpTo) jumpDone(asked)
    }
    // Left (not turned): what was still being looked for is dropped, it must not come up later, out of the blue.
    val activity = LocalActivity.current
    DisposableEffect(vm) { onDispose { if (activity?.isChangingConfigurations != true) vm.seekLeft() } }
    // In another tab: the room host opens that one on it.
    val openMessage by rememberUpdatedState(onOpenMessage)
    LaunchedEffect(vm, vm.elsewhere) {
        vm.elsewhere?.let { (topicId, messageId) ->
            vm.elsewhere = null
            openMessage(topicId, messageId)
        }
    }
    val seekFailed = vm.seekFailed
    LaunchedEffect(vm, seekFailed) {
        if (seekFailed == null) return@LaunchedEffect
        vm.seekFailed = null
        scope.launch {
            val answer = snackbar.showSnackbar("Couldn't load that message.", actionLabel = "Try again", duration = SnackbarDuration.Long)
            if (answer == SnackbarResult.ActionPerformed) vm.seek(seekFailed.messageId, seekFailed.from)
        }
    }
    val bring = vm.bringUp
    LaunchedEffect(vm, bring, window) {
        if (bring == null) return@LaunchedEffect
        try {
            // What was just loaded may be a frame away from the list.
            withTimeoutOrNull(JUMP_WAIT_MILLIS) {
                snapshotFlow { latestItems.any { messageIdOfKey(it.key) == bring.messageId } }.first { it }
            }
            scrollToMessage(bring.messageId, animate = bring.animate && (positioned || window != null))
        } finally {
            // Also when the scroll was touched; not when the phone was turned meanwhile.
            if (isActive) {
                if (window == null) {
                    if (vm.repo.state.value.saved) onSaved = bring.messageId else positioned = true
                }
                vm.broughtUp(bring)
            }
        }
    }

    // First show without such a message: at the "New messages" divider, else at the newest message. Done as
    // soon as what is in memory is enough to know where that is (the messages taken over from the whole chat
    // reach back to the last read one), so a tab opens in place instead of jumping once its own load answers.
    // Otherwise it waits for that load, and always with what an earlier run kept. Also after a window that this
    // tab was opened on.
    val memoryReaches = dividerAfter > 0L && state.messages.firstOrNull()?.let { it.id <= dividerAfter } == true
    val canPosition = ownItems.isNotEmpty() && markerKnown && (state.loaded || (memoryReaches && !state.saved)) &&
        onSaved == 0L && state.cuts == cutsSeen &&
        asked == null && vm.seeking == null && bring == null && vm.elsewhere == null && window == null
    suspend fun toDivider(divider: Int) {
        val viewport = snapshotFlow { ownList.layoutInfo.viewportSize.height }.first { it > 0 }
        ownList.scrollToItem(divider, -(viewport * 0.75f).toInt())
    }
    // Placed by what an earlier visit left in memory, before this one's load has answered, and not touched since.
    val upToDate by vm.repo.upToDate.collectAsStateWithLifecycle()
    var early by remember(vm) { mutableStateOf(false) }
    LaunchedEffect(canPosition) {
        if (positioned || !canPosition) return@LaunchedEffect
        try {
            val divider = latestOwn.indexOf(ChatItem.NewMessages)
            when {
                // The user has begun to read further up in what was kept: the list holds that place.
                scrolled -> Unit
                divider > 0 -> toDivider(divider)
                // The list holds on to the newest of what was kept; the newest message is below it now.
                fromPhone -> ownList.requestScrollToItem(0)
                else -> early = !upToDate
            }
        } finally {
            // Only now may "the newest message is on screen" count as reading.
            positioned = true
        }
    }
    // What the load brings is below the list's lowest row then (a row or two it follows by itself).
    LaunchedEffect(vm, early, upToDate) {
        if (!early) return@LaunchedEffect
        if (upToDate) {
            // Laid out with what came.
            withFrameNanos { }
            withFrameNanos { }
            val divider = latestOwn.indexOf(ChatItem.NewMessages)
            if (divider > 0 && ownList.firstVisibleItemIndex > 2 && !ownList.isScrollInProgress) toDivider(divider)
        } else {
            snapshotFlow { ownList.isScrollInProgress }.first { it }
        }
        // Last: it ends this effect.
        early = false
    }
    LaunchedEffect(highlight) {
        if (highlight != null) {
            delay(1600)
            highlight = null
        }
    }

    val atBottom by remember {
        derivedStateOf { ownList.firstVisibleItemIndex == 0 && ownList.firstVisibleItemScrollOffset < 48 }
    }
    // The newest message whose end (its bottom edge) is on screen, 0 when none is. In the reversed
    // list a row's offset is its bottom edge, counted up from the bottom of the list.
    val newestOnScreen by remember(ownList) {
        derivedStateOf {
            val info = ownList.layoutInfo
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
    // Only after the first positioning: before it the list sits on the newest message. Never in a window.
    //
    // The whole chat of a room whose topic list is empty is read only up to a message that has come since
    // and is in a topic all the same: that topic is newer than the list, the room is about to get its tabs,
    // and the message is read in its own.
    val heldAtStart = remember(vm) { vm.repo.state.value.messages.lastOrNull()?.id ?: 0L }
    fun readable(upTo: Long): Long {
        if (vm.scope != ChatScope.All || !session.supportsTopics || !vm.topics.state.value.loaded) return upTo
        val known = maxOf(vm.dividerAfter, heldAtStart)
        val stop = state.messages.firstOrNull { it.topicId != 0L && it.id > known }?.id ?: return upTo
        return state.messages.lastOrNull { it.id < stop && it.id <= upTo }?.id ?: 0L
    }
    // The newest message is in the list as it is laid out. Not yet in the frame in which messages come: the keyed list
    // then holds its lowest row where it is, with the new ones below the screen.
    val drawnNewest by rememberUpdatedState(state.messages.lastOrNull()?.id ?: 0L)
    fun showsNewest(): Boolean {
        if (!atBottom || drawnNewest != (state.messages.lastOrNull()?.id ?: 0L)) return false
        val lowest = latestOwn.firstOrNull()?.key ?: return false
        return ownList.layoutInfo.visibleItemsInfo.any { it.index == 0 && it.key == lowest }
    }
    LaunchedEffect(vm, lifecycleOwner, positioned, readingEnabled, window == null, state.saved) {
        // Nothing the phone kept counts as seen.
        if (!readingEnabled || !positioned || window != null || state.saved) return@LaunchedEffect
        // The list as it is laid out now, not as it was before a window.
        withFrameNanos { }
        withFrameNanos { }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow {
                if (showsNewest()) {
                    val roomLast = rooms.firstOrNull { it.token == vm.token }?.lastMessage?.id ?: 0L
                    Seen(readable(state.messages.lastOrNull()?.id ?: 0L), roomLast, settle = false)
                } else {
                    Seen(readable(newestOnScreen), 0L, settle = true)
                }
            }.collect { seen -> if (seen.upTo > 0L) vm.onSeen(seen.upTo, seen.roomLast, seen.settle) }
        }
    }

    // Stay at the bottom when something new arrives there, and always follow our own messages.
    val newestItem = ownItems.firstOrNull()
    // The newest message so far. An older one in its place is no news: the messages of the room's first
    // topic went to their own tab, and the list stays where it is.
    var newestSoFar by remember { mutableLongStateOf(0L) }
    val newestKey = remember(vm) { arrayOfNulls<String>(1) }
    LaunchedEffect(newestItem?.key) {
        val newest = state.messages.lastOrNull()?.id ?: 0L
        val older = newest < newestSoFar && (newestItem is ChatItem.Message || newestItem is ChatItem.System)
        newestSoFar = newest
        val before = newestKey[0]
        newestKey[0] = newestItem?.key
        if (newestItem == null || older || window != null) return@LaunchedEffect
        // Not yet in place: only what the user writes now, under what was kept, is followed.
        val written = before != null && (newestItem is ChatItem.Pending || newestItem is ChatItem.PendingFile)
        if (!positioned && !(state.saved && written)) return@LaunchedEffect
        val own = newestItem is ChatItem.Pending || newestItem is ChatItem.PendingFile || (newestItem is ChatItem.Message && newestItem.mine)
        if (own || ownList.firstVisibleItemIndex <= 2) ownList.animateScrollToItem(0)
    }
    // What the user sends from a window goes to the end of the chat, and the list with it.
    val goingOut = remember(vm) { arrayOfNulls<Set<String>>(1) }
    LaunchedEffect(vm, ownItems) {
        val now = ownItems.takeWhile { it is ChatItem.Pending || it is ChatItem.PendingFile }.mapTo(HashSet()) { it.key }
        val before = goingOut[0]
        goingOut[0] = now
        if (window != null && before != null && now.any { it !in before }) toNewest()
    }
    // The history it belonged to was deleted.
    LaunchedEffect(window, shownMessages.isEmpty()) { if (window != null && shownMessages.isEmpty()) vm.leaveWindow() }

    // Older pages as the top comes near; loads in a row that brought nothing to show are counted, and after a few
    // of them it is the user's word.
    var quietOlder by remember(vm, window) { mutableIntStateOf(0) }
    var quietNewer by remember(vm, window) { mutableIntStateOf(0) }
    LaunchedEffect(vm, window, listState) {
        fun count() = window?.state?.value?.messages?.size ?: vm.repo.state.value.messages.size
        readOn(
            wanted = {
                val w = windowHeld?.value
                vm.bringUp == null && quietOlder < LOOK_BACK_LOADS && listState.nearOlderEnd() &&
                    if (w != null) w.hasOlder && !w.loadingOlder && w.olderError == null
                    else state.loaded && state.hasOlder && !state.loadingOlder && state.olderError == null
            },
            load = {
                val before = count()
                vm.loadOlder().join()
                quietOlder = if (count() == before) quietOlder + 1 else 0
            },
        )
    }
    // In a window also towards the newest. Read down to the tab's own messages it becomes one list with them, in
    // place: once the list rests, its lowest row keeps where it is on screen (the two lists end differently).
    val ownBottom = with(LocalDensity.current) { OwnListBottom.roundToPx() }
    LaunchedEffect(vm, window, listState) {
        if (window == null || windowHeld == null) return@LaunchedEffect
        readOn(
            wanted = {
                val w = windowHeld.value
                vm.bringUp == null && listState.nearNewerEnd() &&
                    (w.meets || (w.hasNewer && quietNewer < LOOK_BACK_LOADS && !w.loadingNewer && w.newerError == null))
            },
            load = {
                if (windowHeld.value.meets) {
                    snapshotFlow { listState.isScrollInProgress }.first { !it }
                    if (listState.nearNewerEnd() && vm.repo.join(window)) {
                        fun anchor() = listState.layoutInfo.visibleItemsInfo.firstOrNull { messageIdOfKey(it.key) != 0L }
                        val held = anchor()?.key
                        withTimeoutOrNull(JUMP_WAIT_MILLIS) { snapshotFlow { latestOwn.any { it.key == held } }.first { it || held == null } }
                        anchor()?.let { row ->
                            val at = latestOwn.indexOfFirst { it.key == row.key }
                            val lower = listState.layoutInfo.beforeContentPadding - ownBottom
                            if (at >= 0) ownList.requestScrollToItem(at, -(row.offset + lower))
                        }
                        positioned = true
                        vm.windowJoined(window)
                    }
                } else {
                    val before = window.state.value.messages.size
                    vm.loadNewer(window).join()
                    quietNewer = if (window.state.value.messages.size == before) quietNewer + 1 else 0
                }
            },
        )
    }

    // Nothing of this tab among the newest messages (General, where they all are in topics): there is no list
    // whose top could come near, so it is read on here, so far at first.
    val lookingBack = window == null && items.isEmpty() && state.loaded && state.hasOlder
    var lookedBack by remember(vm) { mutableStateOf(false) }
    LaunchedEffect(vm, lookingBack, lookedBack) {
        if (!lookingBack || lookedBack) return@LaunchedEffect
        var loads = 0
        // Not on this thread: pages are large.
        while (loads++ < LOOK_BACK_LOADS && withContext(Dispatchers.Default) { vm.repo.loadOlder() }) {
            if (vm.repo.state.value.let { it.messages.isNotEmpty() || !it.hasOlder }) break
        }
        lookedBack = true
    }

    // Picked messages follow edits and drop out when deleted.
    LaunchedEffect(selection, state.messages, shownMessages) {
        selection?.sync(state.messages)
        selection?.sync(shownMessages)
    }
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
    val images = remember(shownMessages) {
        shownMessages.flatMap { m -> if (m.deleted) emptyList() else m.files.filter { it.isImage && it.previewAvailable } }
    }

    // As the saved list has it until the list is there: what is drawn from the phone does not change its look then.
    val oneToOne = drawnWithoutNames(room ?: context.appContainer.savedRoom(session, token))
    val readOnly = room?.isReadOnly == true

    // The one connection line under the header (the list's words and rules); beside the list (wide
    // layout) the list's own line says it. Failing = the live feed, or the first load (an older
    // page that failed once says nothing while the feed works).
    val link by context.appContainer.network.link.collectAsStateWithLifecycle()
    val failing = liveError is TalkError.Network || (!state.loaded && state.error is TalkError.Network)
    val connection = connectionState(link, failing)
    val twoPane = LocalTwoPane.current
    // While the messages are the ones the phone kept, the line says so instead (beside the list too: the list's is about
    // the list), but only when the answer takes a while.
    var savedLong by remember(vm) { mutableStateOf(false) }
    LaunchedEffect(vm, state.saved) {
        savedLong = false
        if (state.saved) {
            delay(SAVED_LINE_MILLIS)
            savedLong = true
        }
    }
    val savedLine = if (state.saved && savedLong) savedState(link, state.error ?: liveError) else null
    // Nothing turns for the moment it takes to read what the phone kept.
    var late by remember(vm) { mutableStateOf(false) }
    LaunchedEffect(vm) {
        delay(SPINNER_MILLIS)
        late = true
    }
    // The network is back: a page that failed is asked for again where its end is still in view.
    val wasCut = remember { booleanArrayOf(false) }
    LaunchedEffect(connection) {
        if (connection == null && wasCut[0]) vm.forgetPageErrors()
        wasCut[0] = connection != null
    }

    // The box as it is measured: what is typed (or the edit), as a message or as the files' caption.
    val boxText = if (editing == null) vm.draft else vm.editText
    val over = remember(boxText, editing == null, tray.isEmpty(), vm.maxLength) {
        val text = boxText.trim()
        if (editing == null && tray.isNotEmpty() && session.capabilities.mediaCaption) captionOver(text, vm.maxLength)
        else messageOver(text, vm.maxLength)
    }
    val canAttach = filesAllowed && !readOnly && !selecting
    // With a keyboard at hand the cursor goes to the box after a drop, so Enter sends.
    val hardKeyboard = LocalConfiguration.current.let {
        it.keyboard == Configuration.KEYBOARD_QWERTY && it.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    }
    val drop = rememberFileDrop(enabled = canAttach) { files, done ->
        receive(files, done).also { taken -> if (taken && hardKeyboard) replyFocus++ }
    }
    val pasteMenu = rememberPasteMenu(enabled = canAttach) { paste() }

    Column(Modifier.fillMaxSize().then(drop.modifier).then(pasteMenu)) {
        SavedLine(savedLine, messages = true)
        if (connection != null && items.isNotEmpty() && !twoPane && !state.saved) ConnectionLine(connection)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val error = state.error ?: state.olderError
            // The message this tab opens on is being looked for: what else there is comes when it is not found.
            val opening = window == null && !positioned && vm.seeking != null
            when {
                opening -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
                }
                // Offline: it loads by itself once the network is back (the retry is woken).
                items.isEmpty() && !state.loaded && error != null && connection == ConnectionState.Offline ->
                    MessagePane(title = "No connection", detail = "Messages load when you're back online.")
                items.isEmpty() && !state.loaded && error != null -> MessagePane(
                    title = "Couldn't load the messages",
                    detail = error.userMessage(),
                    action = "Try again",
                    onAction = vm::retryLoad,
                )
                items.isEmpty() && (!state.loaded || (lookingBack && !lookedBack)) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (late) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
                }
                lookingBack -> MessagePane(
                    title = if (error != null) "Couldn't load the messages" else "No messages so far",
                    detail = error?.userMessage() ?: "Older ones may be further back.",
                    action = if (error != null) "Try again" else "Look further back",
                    onAction = { lookedBack = false },
                )
                items.isEmpty() -> MessagePane(
                    title = if (vm.scope == ChatScope.General) "Nothing outside the topics yet" else "No messages yet",
                    detail = if (readOnly) "This conversation is read-only." else "Write the first one below.",
                )
                else -> MessageList(
                    of = window,
                    state = listState,
                    // A window is seen once it is on its message.
                    visible = window == null || bring?.animate != false || items.none { messageIdOfKey(it.key) == bring.messageId },
                ) {
                    windowHeld?.value?.let { w ->
                        item(key = "newer-end") {
                            if (w.hasNewer || w.meets) HistoryEnd(
                                line = when {
                                    w.newerError != null -> COULD_NOT_LOAD_MORE
                                    quietNewer >= LOOK_BACK_LOADS && !w.meets -> "Look further on"
                                    else -> null
                                },
                                onClick = {
                                    quietNewer = 0
                                    window?.forgetErrors()
                                },
                            )
                        }
                    }
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
                                // Where a retry may never go through: the typed text must not be stuck there.
                                onEdit = if (item.outbox.stop == OutboxStop.REFUSED || item.outbox.stop == OutboxStop.UNCLEAR) ({
                                    if (vm.editUnsent(item.outbox)) replyFocus++
                                }) else null,
                            )
                            is ChatItem.PendingFile -> PendingFileRow(
                                upload = item.upload,
                                caption = drafts.readable(item.upload.caption),
                                onRetry = { uploads.retry(item.upload.id) },
                                onOriginal = { uploads.sendOriginal(item.upload.id) },
                                onDiscard = {
                                    if (vm.scheduling) {
                                        vm.notice = "Wait: a message is being scheduled."
                                    } else {
                                        uploads.discard(item.upload.id)?.let { back ->
                                            vm.takeBack(back.caption, back.replyTo ?: state.messages.firstOrNull { it.id == back.replyToId })
                                        }
                                    }
                                },
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
                                        // A private answer quotes a message of another conversation (its copy
                                        // here is never shown).
                                        val there = m.quoted?.token.orEmpty()
                                        if (there.isNotEmpty() && there != m.token) vm.notice = "That message is in another conversation."
                                        else vm.seek(id, from = m.id)
                                    },
                                    quoteLoading = vm.seeking?.from == m.id,
                                    onOpenTopic = onOpenTopic,
                                    onOpenImage = { fileUi.openImage(it, images) },
                                    onOpenFile = fileUi::open,
                                    onReaction = { emoji -> vm.toggleReaction(m.id, emoji) },
                                )
                            }
                        }
                    }
                    val w = windowHeld?.value
                    val olderError = if (w != null) w.olderError else state.olderError
                    if (if (w != null) w.hasOlder else state.loaded && state.hasOlder) {
                        item(key = "older-end") {
                            HistoryEnd(
                                line = when {
                                    olderError != null -> COULD_NOT_LOAD_MORE
                                    quietOlder >= LOOK_BACK_LOADS -> "Look further back"
                                    else -> null
                                },
                                onClick = {
                                    quietOlder = 0
                                    vm.forgetPageErrors()
                                },
                            )
                        }
                    }
                }
            }
            if (window != null || opening) {
                NewestPill(unseenBelow, ::toNewest, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
            }
            if (vm.seeking?.from == 0L && !opening) SeekingDisc(Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
            androidx.compose.animation.AnimatedVisibility(
                visible = !atBottom && items.isNotEmpty() && window == null && !opening,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 10.dp),
            ) {
                Box {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { ownList.animateScrollToItem(0) } },
                        containerColor = colors.bgSurface,
                        contentColor = colors.textPrimary,
                        shape = CircleShape,
                    ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Newest messages") }
                    if (unseenBelow > 0) UnseenCount(unseenBelow, Modifier.align(Alignment.TopEnd))
                }
            }
            if (drop.hovering) DropHint(Modifier.fillMaxSize())
        }
        aboveComposer(atBottom && window == null)
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
            // (desktop refuses them too); a recording that runs keeps its controls. The tray waits
            // meanwhile, as the draft does.
            val plain = editing == null
            val captioned = plain && tray.isNotEmpty() && session.capabilities.mediaCaption
            ReceiveFromKeyboard(filesAllowed, ::receive) {
                Composer(
                    text = boxText,
                    // Never cut: a text over the server's limit says so and waits (desktop's rule).
                    onTextChange = {
                        if (vm.editing == null) {
                            vm.draft = it
                            typing.typed(session, token, hasText = it.isNotBlank())
                        } else {
                            vm.editText = it
                        }
                    },
                    replyTo = vm.replyTo,
                    onCancelReply = { vm.replyTo = null },
                    onSend = { send() },
                    placeholder = if (captioned) "Add a caption" else if (tabTitle.isNullOrBlank()) "Message" else "Message in $tabTitle",
                    onAttach = if (plain && filesAllowed) ({ attachOpen = true }) else null,
                    onSendOptions = if (plain && (canSendSilently || canSendLater)) ({ sendOptions = true }) else null,
                    busy = vm.scheduling || vm.saving,
                    sentSilently = sentSilently,
                    // No microphone with files waiting: the empty box is their caption and the button sends them.
                    voice = if ((plain && tray.isEmpty()) || recordingHere != null) voiceUi else null,
                    // Alt+Enter on a hardware keyboard (desktop's silent send).
                    onSendSilently = if (plain && canSendSilently) ({ if (send(silent = true)) sentSilently = true }) else null,
                    focusTick = replyFocus,
                    editing = editing,
                    onCancelEdit = vm::cancelEdit,
                    // Up in an empty box: the newest message of this tab the user may still edit. Not over
                    // files waiting (desktop's requestEditLast).
                    onEditLast = { tray.isEmpty() && shownMessages.lastOrNull { vm.canEdit(it) }?.let { startEdit(it) } == true },
                    mentions = vm.mentions,
                    tray = if (plain && tray.isNotEmpty()) ComposerTray(
                        ready = tray.all(trays::ready),
                        preparing = tray.any { !trays.ready(it) && !trays.blocked(it) },
                        content = { AttachTray(trays, trayKey, onAdd = if (filesAllowed) ({ attachOpen = true }) else null) },
                    ) else null,
                    over = over,
                    onPaste = { paste() },
                    onEscape = if (window != null) vm::leaveWindow else null,
                )
            }
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
            // Own messages the server still takes it for (Delete: a moderator's on others' too). Edit not
            // during a recording here (its row hides the box), a send later or a save on its way (the box
            // is read-only then).
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
                if (send(silent = true)) sentSilently = true
            },
            onLater = { choice ->
                sendOptions = false
                schedule(choice.at.toEpochSecond())
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
                schedule(picked.toEpochSecond())
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
    if (attachOpen) {
        val pasteOffered = remember { clipboardMayHoldFiles(context) }
        AttachSheet(
            attach,
            onPaste = if (pasteOffered) ({ if (!paste()) vm.notice = "The clipboard holds no file." }) else null,
            onDismiss = { attachOpen = false },
        )
    }
    FileOverlays(fileUi, session)
}

/** A conversation of two, or one's own: the bubbles say whose they are by their side. */
internal fun drawnWithoutNames(room: Room?): Boolean =
    room?.type == RoomType.ONE_TO_ONE || room?.type == RoomType.FORMER_ONE_TO_ONE || room?.type == RoomType.NOTE_TO_SELF

/** "Copy text" is in a message's menu: a file without a caption has none. */
internal fun copyable(message: ChatMessage): Boolean =
    !message.deleted && (message.file == null || message.caption.isNotBlank())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActionsSheet(
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
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
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
                                .onSecondaryClick { onReaction(emoji) }
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
            if (copyable(message)) SheetAction("Copy text", { Icon(Icons.Outlined.ContentCopy, contentDescription = null) }, onCopy)
            // This one message to another conversation (desktop's right-click Forward).
            if (onForward != null) SheetAction("Forward", { Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null) }, onForward)
            if (showOpenTopic) SheetAction("Open topic", { Icon(Icons.Outlined.Forum, contentDescription = null) }, onOpenTopic)
            // In desktop's order (Edit, Delete, then Select).
            if (onEdit != null) SheetAction("Edit", { Icon(Icons.Outlined.Edit, contentDescription = null) }, onEdit)
            if (onDelete != null) {
                SheetActionRow(
                    "Delete",
                    { Icon(Icons.Outlined.Delete, contentDescription = null, tint = colors.danger) },
                    colors.danger,
                    onDelete,
                )
            }
            // Several messages at once, for Copy, Forward and Delete.
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
