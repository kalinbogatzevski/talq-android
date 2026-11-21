package talq.app.ui.files

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import talq.app.appContainer
import talq.app.theme.TalqTheme
import talq.app.ui.common.MessagePane
import talq.app.ui.common.authorizedImage
import talq.app.ui.common.relativeTime
import talq.app.ui.common.userMessage
import talq.app.ui.message.DownloadRing
import talq.app.ui.message.FileCard
import talq.app.ui.message.PlayMark
import talq.app.ui.message.VoiceCard
import talq.app.ui.message.downloadProgress
import talq.core.SharedItemType
import talq.core.TalkError
import talq.core.model.ChatMessage
import talq.core.model.RichObject
import talq.core.repo.TalkSession

/**
 * One tab of the screen: the items of its [types], newest first, read page by page. The types
 * are read one after the other (voice messages, then audio files).
 */
class SharedSection(val title: String, val types: List<SharedItemType>) {
    var items by mutableStateOf<List<ChatMessage>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<TalkError?>(null)
    var ended by mutableStateOf(false)

    /** Changes with every page read, also an empty one: what "read more" is keyed on. */
    var pagesRead by mutableIntStateOf(0)
    internal var typeAt = 0
    internal var cursor = 0L
}

class SharedFilesViewModel(private val session: TalkSession, private val token: String) : ViewModel() {
    val sections = listOf(
        SharedSection("Media", listOf(SharedItemType.MEDIA)),
        SharedSection("Files", listOf(SharedItemType.FILE)),
        SharedSection("Voice", listOf(SharedItemType.VOICE, SharedItemType.AUDIO)),
    )

    fun loadMore(section: SharedSection) {
        if (section.loading || section.ended) return
        section.loading = true
        section.error = null
        viewModelScope.launch {
            try {
                val page = session.files.sharedItems(token, section.types[section.typeAt], lastKnownMessageId = section.cursor)
                val known = section.items.mapTo(HashSet()) { it.id }
                section.items = section.items + page.messages.filter { it.file != null && it.id !in known }
                val next = page.nextCursor
                if (next != null) {
                    section.cursor = next
                } else if (section.typeAt < section.types.lastIndex) {
                    section.typeAt++
                    section.cursor = 0L
                } else {
                    section.ended = true
                }
                section.pagesRead++
            } catch (e: TalkError) {
                section.error = e
            } finally {
                section.loading = false
            }
        }
    }
}

/**
 * "Shared files" of one conversation: Media (a grid of pictures and videos), Files (a list) and
 * Voice (voice messages and audio, played in place). Items open as they do in the chat; a long
 * press offers Save, Share and "Show in chat".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedFilesScreen(
    session: TalkSession,
    token: String,
    onBack: () -> Unit,
    onShowInChat: (ChatMessage) -> Unit,
) {
    val context = LocalContext.current
    val vm: SharedFilesViewModel = viewModel(key = "shared-$token") { SharedFilesViewModel(session, token) }
    val snackbar = remember { SnackbarHostState() }
    val fileUi = rememberFileUi(snackbar)
    val rooms by session.rooms.rooms.collectAsStateWithLifecycle()
    val roomName = remember(rooms) { rooms.firstOrNull { it.token == token }?.displayName.orEmpty() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    val section = vm.sections[tab]

    // Nothing keeps playing once this screen is left or the app is in the background.
    val voice = context.appContainer.voice
    val activity = LocalActivity.current
    // Turning the phone is not leaving.
    DisposableEffect(voice) { onDispose { if (activity?.isChangingConfigurations != true) voice.stop() } }
    LifecycleStartEffect(voice) { onStopOrDispose { voice.pause() } }

    LaunchedEffect(section.error) {
        if (section.error is TalkError.Unauthorized) context.appContainer.onUnauthorized()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Shared files", style = MaterialTheme.typography.titleMedium)
                        if (roomName.isNotEmpty()) {
                            Text(
                                roomName,
                                style = MaterialTheme.typography.labelSmall,
                                color = TalqTheme.colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                vm.sections.forEachIndexed { index, s ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(s.title) })
                }
            }
            LaunchedEffect(section) { if (section.pagesRead == 0) vm.loadMore(section) }
            val error = section.error
            when {
                section.items.isEmpty() && error != null -> MessagePane(
                    title = "Couldn't load the shared files",
                    detail = error.userMessage(),
                    action = "Try again",
                    onAction = { vm.loadMore(section) },
                )
                section.items.isEmpty() && !section.ended -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
                    // An empty page that is not the last one: keep reading.
                    LaunchedEffect(section, section.pagesRead) { vm.loadMore(section) }
                }
                section.items.isEmpty() -> MessagePane(
                    title = when (tab) {
                        0 -> "No pictures or videos yet"
                        1 -> "No files yet"
                        else -> "No voice messages yet"
                    },
                    detail = "What is shared in this conversation shows up here.",
                )
                tab == 0 -> MediaGrid(section, session, fileUi, onMore = { vm.loadMore(section) }, onLongPress = { actionsFor = it })
                else -> FileList(section, voiceTab = tab == 2, fileUi, onMore = { vm.loadMore(section) }, onLongPress = { actionsFor = it })
            }
        }
    }

    actionsFor?.let { m ->
        m.file?.let { file ->
            FileActionsSheet(file, fileUi, onDismiss = { actionsFor = null }, onShowInChat = { onShowInChat(m) })
        }
    }
    FileOverlays(fileUi, session)
}

/** The row under the items: reads the next page when it comes on screen, or offers a retry. */
@Composable
private fun MoreRow(section: SharedSection, onMore: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
        if (section.error != null) {
            TextButton(onClick = onMore) { Text("Couldn't load more. Try again") }
        } else {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            LaunchedEffect(section, section.pagesRead) { onMore() }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    section: SharedSection,
    session: TalkSession,
    fileUi: FileUi,
    onMore: () -> Unit,
    onLongPress: (ChatMessage) -> Unit,
) {
    val context = LocalContext.current
    val images = remember(section.items) { section.items.mapNotNull { m -> m.file?.takeIf { it.isImage && it.previewAvailable } } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
    ) {
        items(section.items, key = { it.id }) { m ->
            val file = m.file ?: return@items
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .aspectRatio(1f)
                    .background(if (file.isVideo) Color.Black else TalqTheme.colors.bgSecondary)
                    .combinedClickable(
                        onClick = { if (file.isImage && file.previewAvailable) fileUi.openImage(file, images) else fileUi.open(file) },
                        onLongClick = { onLongPress(m) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (file.previewAvailable) {
                    val url = session.client.filePreviewUrl(file.id, 360, 360)
                    val request = remember(url) { authorizedImage(context, url, session.client.authorizationHeader) }
                    AsyncImage(model = request, contentDescription = file.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else if (!file.isVideo) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = TalqTheme.colors.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(6.dp),
                    )
                }
                val fetching = downloadProgress(file.id)
                if (fetching != null) DownloadRing(file.id, fetching, size = 34) else if (file.isVideo) PlayMark(size = 34)
            }
        }
        if (!section.ended) item(key = "more", span = { GridItemSpan(maxLineSpan) }) { MoreRow(section, onMore) }
    }
}

@Composable
private fun FileList(
    section: SharedSection,
    voiceTab: Boolean,
    fileUi: FileUi,
    onMore: () -> Unit,
    onLongPress: (ChatMessage) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        items(section.items, key = { it.id }) { m ->
            val file: RichObject = m.file ?: return@items
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                if (voiceTab || file.isAudio) {
                    VoiceCard(file, voiceMessage = m.isVoiceMessage, onLongPress = { onLongPress(m) })
                } else {
                    FileCard(file, onClick = { fileUi.open(file) }, onLongPress = { onLongPress(m) }, modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = listOf(m.actorDisplayName, relativeTime(context, m.timestamp)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = TalqTheme.colors.textMuted,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        if (!section.ended) item(key = "more") { MoreRow(section, onMore) }
    }
}
