package talq.app.files

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import talq.app.MainActivity
import talq.app.SessionState
import talq.app.appContainer
import talq.app.appName
import talq.app.nav.DeepLink
import talq.app.theme.TalqTheme
import talq.app.ui.common.Avatar
import talq.app.ui.common.LoadingScreen
import talq.app.ui.common.MessagePane
import talq.app.ui.common.userMessage
import talq.app.ui.message.topicColor
import talq.core.model.Room
import talq.core.repo.ChatScope
import talq.core.repo.TalkSession

/**
 * "Share" in another app, then TalQ: pick a chat (search, most recent first), pick where in
 * it (General or a topic, when the chat has topics), add a message, send. Files then go the
 * same way as from the paperclip (`UploadManager.enqueue`); shared text goes as a message.
 *
 * Exported, so the intent is not trusted: only `content://` items are read (never a file
 * path, never our own FileProvider), and they are copied into the cache while this screen
 * holds the sending app's permission. Signed out: the user is sent to the app to sign in.
 */
class ShareActivity : ComponentActivity() {
    private val vm: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.begin(intent)
        setContent {
            TalqTheme {
                ShareScreen(
                    vm = vm,
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                    onSent = { token, threadId ->
                        startActivity(DeepLink.toIntent(this, token, threadId.takeIf { it > 0 }))
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }
}

/** What was shared, and its copies in the cache. Outlives a rotation. */
class ShareViewModel(app: Application) : AndroidViewModel(app) {
    private val uploads = app.appContainer.uploads
    private var started = false
    private var handedOff = false
    private var staging: Job? = null

    /** Send was tapped: it works once. */
    var sent by mutableStateOf(false)
        private set

    /** The shared text: the message, or the caption of the first file. Editable. */
    var text by mutableStateOf("")

    /** How many items the other app offered that may be read. */
    var offered by mutableStateOf(0)
        private set

    /** The copies; null while they are still being made. */
    var staged by mutableStateOf<List<StagedFile>?>(null)
        private set

    fun begin(intent: Intent?) {
        if (started) return
        started = true
        val app = getApplication<Application>()
        val uris = sharedUris(intent).filter { Staging.isAcceptable(app, it) }
        offered = uris.size
        text = runCatching { intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull().orEmpty().take(MAX_TEXT)
        val original = uploads.prefs.sendOriginal
        staging = viewModelScope.launch {
            // Nothing is copied for someone who is not signed in: any app can start this screen.
            app.appContainer.sessionState.first { it is SessionState.SignedIn }
            val result = uploads.stageAll(uris.size) { Staging.stage(app, uris[it], original) }
            unprepared = result.unprepared
            staged = result.files
        }
    }

    /** Photos left out because they could not be made smaller (and Send original is off). */
    var unprepared by mutableStateOf(0)
        private set

    /** The files now belong to the upload queue. Empty the second time. */
    fun handOff(): List<StagedFile> {
        if (sent) return emptyList()
        sent = true
        handedOff = true
        return staged.orEmpty()
    }

    override fun onCleared() {
        // A copy still being made removes itself when its coroutine is cancelled.
        staging?.cancel()
        if (!handedOff) staged?.forEach { Staging.delete(getApplication(), it.path) }
    }

    /** Whatever another app put into the intent: wrong types and null entries are dropped. */
    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent?): List<Uri> = try {
        when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_SEND_MULTIPLE ->
                (intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM) as List<*>?).orEmpty()
                    .filterIsInstance<Uri>().take(MAX_ITEMS)
            else -> emptyList()
        }
    } catch (e: RuntimeException) {
        emptyList()
    }

    private companion object {
        const val MAX_ITEMS = 50
        const val MAX_TEXT = 32_000
    }
}

@Composable
private fun ShareScreen(vm: ShareViewModel, onOpenApp: () -> Unit, onSent: (token: String, threadId: Long) -> Unit, onClose: () -> Unit) {
    val container = androidx.compose.ui.platform.LocalContext.current.appContainer
    val state by container.sessionState.collectAsStateWithLifecycle()
    when (val current = state) {
        SessionState.Loading -> LoadingScreen()
        SessionState.SignedOut -> MessagePane(
            title = "Sign in first",
            detail = "Sign in to ${appName()}, then share again.",
            action = "Open ${appName()}",
            onAction = onOpenApp,
        )
        is SessionState.Unreachable -> MessagePane(
            title = "Can't reach the server",
            detail = current.error.userMessage(),
            action = "Try again",
            onAction = container::restore,
        )
        is SessionState.SignedIn -> SharePicker(current.session, vm, onSent, onClose)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharePicker(session: TalkSession, vm: ShareViewModel, onSent: (String, Long) -> Unit, onClose: () -> Unit) {
    val colors = TalqTheme.colors
    val uploads = androidx.compose.ui.platform.LocalContext.current.appContainer.uploads
    val rooms by session.rooms.rooms.collectAsStateWithLifecycle()
    val roomsLoad by session.rooms.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var pickedToken by rememberSaveable { mutableStateOf<String?>(null) }
    var threadId by rememberSaveable { mutableLongStateOf(0L) }
    val picked = pickedToken?.let { t -> rooms.firstOrNull { it.token == t } }

    LaunchedEffect(session) { session.rooms.refresh() }
    BackHandler(enabled = pickedToken != null) { pickedToken = null }

    val staged = vm.staged
    val nothing = vm.offered == 0 && vm.text.isBlank() && staged != null
    val noUploads = vm.offered > 0 && !session.capabilities.attachments.allowed
    val maxLength = session.capabilities.maxMessageLength.takeIf { it > 0 } ?: 32_000

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(picked?.displayName ?: "Send to…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    if (pickedToken != null) {
                        IconButton(onClick = { pickedToken = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the chats")
                        }
                    } else {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when {
                nothing -> MessagePane(title = "Nothing to send", detail = "${appName()} can't read what the other app shared.")
                noUploads -> MessagePane(title = "Files can't be sent", detail = "This server doesn't allow you to upload files into chats.")
                picked == null -> {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search chats") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    val shown = remember(rooms, query) {
                        val q = query.trim()
                        rooms.filter { !it.isReadOnly && (q.isEmpty() || it.displayName.contains(q, ignoreCase = true)) }
                    }
                    when {
                        shown.isEmpty() && !roomsLoad.loaded && roomsLoad.error == null ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
                            }
                        shown.isEmpty() -> MessagePane(
                            title = if (query.isBlank()) "No chats" else "No chat matches",
                            detail = roomsLoad.error?.userMessage().orEmpty(),
                        )
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(shown, key = { it.token }) { room ->
                                ShareRoomRow(session, room) {
                                    threadId = 0L
                                    pickedToken = room.token
                                }
                            }
                        }
                    }
                }
                else -> {
                    ShareTarget(
                        session = session,
                        room = picked,
                        threadId = threadId,
                        onThread = { threadId = it },
                        modifier = Modifier.weight(1f),
                    )
                    HorizontalDivider(color = colors.divider)
                    val files = staged?.size ?: vm.offered
                    val failed = if (staged != null) vm.offered - staged.size else 0
                    Column(Modifier.fillMaxWidth().background(colors.bgSidebar).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        if (vm.offered > 0) {
                            Text(
                                when {
                                    staged == null -> "Preparing ${vm.offered} ${if (vm.offered == 1) "file" else "files"}…"
                                    failed > 0 && failed == vm.unprepared ->
                                        "$files ${if (files == 1) "file" else "files"} ($failed couldn't be made smaller; turn on Send original in a chat's paperclip to send as it is)"
                                    failed > 0 && files == 0 -> "The shared ${if (vm.offered == 1) "file" else "files"} couldn't be read."
                                    failed > 0 -> "$files ${if (files == 1) "file" else "files"} ($failed couldn't be read)"
                                    else -> "$files ${if (files == 1) "file" else "files"}"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (failed > 0) colors.danger else colors.textSecondary,
                            )
                        }
                        OutlinedTextField(
                            value = vm.text,
                            onValueChange = { vm.text = it.take(maxLength) },
                            placeholder = { Text(if (vm.offered > 0) "Add a message" else "Message") },
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                        val ready = staged != null && (staged.isNotEmpty() || vm.text.isNotBlank())
                        Button(
                            enabled = ready && !vm.sent,
                            onClick = {
                                if (vm.sent) return@Button
                                val token = picked.token
                                val files = vm.handOff()
                                val message = vm.text.trim()
                                if (files.isNotEmpty()) {
                                    uploads.enqueue(session, token, threadId, files, message)
                                } else {
                                    session.chat(token, if (threadId > 0) ChatScope.Topic(threadId) else ChatScope.All).send(message)
                                }
                                onSent(token, threadId)
                            },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Send") }
                    }
                }
            }
        }
    }
}

/** Where in the chosen chat: General or one of its topics. A chat without topics has no choice. */
@Composable
private fun ShareTarget(session: TalkSession, room: Room, threadId: Long, onThread: (Long) -> Unit, modifier: Modifier) {
    val colors = TalqTheme.colors
    val repo = remember(room.token) { session.topics(room.token) }
    val topics by repo.topics.collectAsStateWithLifecycle()
    val load by repo.state.collectAsStateWithLifecycle()
    LaunchedEffect(repo) { if (session.supportsTopics) repo.refresh() }

    Box(modifier.fillMaxWidth()) {
        when {
            !session.supportsTopics || (topics.isEmpty() && (load.loaded || load.error != null)) -> MessagePane(
                title = room.displayName,
                detail = "It will be sent into this chat.",
            )
            topics.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item(key = 0L) { TargetRow("General", colors.textMuted, threadId == 0L) { onThread(0L) } }
                items(topics, key = { it.id }) { topic ->
                    TargetRow(topic.title.ifBlank { "Untitled topic" }, topicColor(topic.id), threadId == topic.id) { onThread(topic.id) }
                }
            }
        }
    }
}

@Composable
private fun TargetRow(title: String, dot: androidx.compose.ui.graphics.Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ShareRoomRow(session: TalkSession, room: Room, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            url = session.client.roomAvatarUrl(room.token, room.avatarVersion, isSystemInDarkTheme()),
            authorization = session.client.authorizationHeader,
            name = room.displayName,
            size = 44.dp,
        )
        Spacer(Modifier.width(12.dp))
        Text(room.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
