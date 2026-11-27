package talq.app.ui.room

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import talq.app.SessionState
import talq.app.appContainer
import talq.app.nav.RoomRoute
import talq.app.net.NetLink
import talq.app.theme.TalqTheme
import talq.app.ui.chat.ComposerDrafts
import talq.app.ui.chat.MessageActionsSheet
import talq.app.ui.chat.MessageList
import talq.app.ui.chat.SPINNER_MILLIS
import talq.app.ui.chat.copyToClipboard
import talq.app.ui.chat.copyable
import talq.app.ui.chat.drawnWithoutNames
import talq.app.ui.chat.sayAnywhere
import talq.app.ui.common.Avatar
import talq.app.ui.common.MessagePane
import talq.app.ui.common.SAVED_LINE_MILLIS
import talq.app.ui.common.SavedLine
import talq.app.ui.common.WAITING_FOR_SERVER
import talq.app.ui.common.savedState
import talq.app.ui.common.userMessage
import talq.app.ui.message.ChatItem
import talq.app.ui.message.DaySeparator
import talq.app.ui.message.MessageRow
import talq.app.ui.message.ReadMark
import talq.app.ui.message.SystemMessageRow
import talq.app.ui.message.buildChatItems
import talq.core.TalkError
import talq.core.model.ChatMessage
import talq.core.model.Room
import talq.core.repo.ChatScope
import talq.core.repo.SavedChat
import talq.core.repo.TalkSession

/**
 * A conversation ([files]: its shared files) opened while the account is not checked yet, from the saved list or
 * restored after Android ended the app: its name, the messages the phone kept of it, and what it waits for. Nothing is
 * made of it, so nothing can be typed or sent here, and nothing counts as read; what was typed before waits untouched
 * in the saved state of this screen's place in the back stack. Its own screen takes over, on the tab picked here ([onTab]).
 *
 * @param tab the tab it opens on: a [RoomTab] key, or [RoomRoute.REMEMBERED]
 * @param request a tab asked for since (a topic picked in the list beside it, or here)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomWaitingScreen(
    session: TalkSession,
    token: String,
    showBack: Boolean,
    onBack: () -> Unit,
    files: Boolean = false,
    tab: Long = RoomRoute.REMEMBERED,
    request: RoomTab? = null,
    onTab: (RoomTab) -> Unit = {},
) {
    val container = LocalContext.current.appContainer
    val start by container.saved.collectAsStateWithLifecycle()
    val room = start?.rooms?.firstOrNull { it.token == token }
    val state by container.sessionState.collectAsStateWithLifecycle()
    val link by container.network.link.collectAsStateWithLifecycle()
    val error = (state as? SessionState.Unreachable)?.error
    val account = remember(session) { ComposerDrafts.accountOf(session) }
    // Read off the main thread; the tab last open there comes with it.
    val kept by produceState<Kept?>(null, account, token, files) {
        val chat = if (files) null else container.messages.peek(account, token)
        container.roomTabs.awaitLoaded()
        value = Kept(chat, container.roomTabs.peek(account, token))
    }
    val late by produceState(false, token) {
        delay(SPINNER_MILLIS)
        value = true
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (room != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(
                                url = session.client.roomAvatarUrl(token, room.avatarVersion, !TalqTheme.colors.isLight),
                                authorization = session.client.authorizationHeader,
                                name = room.displayName,
                                size = 36.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(room.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            val read = kept
            val chat = read?.chat
            when {
                chat != null -> {
                    val long by produceState(false, token) {
                        delay(SAVED_LINE_MILLIS)
                        value = true
                    }
                    KeptChat(
                        session, chat, room,
                        first = RoomTab.fromKey(tab) ?: RoomTab.fromKey(read.tab) ?: RoomTab.General,
                        request = request,
                        onTab = onTab,
                        line = { SavedLine(if (long) savedState(link, error) else null, messages = true) },
                    )
                }
                // For the moment it takes to read that.
                read == null -> Unit
                // It goes on by itself then: the chat's own words.
                link == NetLink.None || error is TalkError.Network ->
                    MessagePane(title = "No connection", detail = "${if (files) "Shared files" else "Messages"} load when you're back online.")
                error != null -> MessagePane(
                    title = if (files) "Couldn't load the shared files" else "Couldn't load the messages",
                    detail = error.userMessage(),
                    action = "Try again",
                    onAction = { container.restore() },
                )
                late -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
            }
        }
    }
}

/** How often messages that disappear while they are on screen are looked for. */
private const val EXPIRY_LOOK_MILLIS = 15_000L

/** What the phone has of a conversation: the messages (null = none), and the key of the tab last open there. */
private class Kept(val chat: SavedChat?, val tab: Long?)

/**
 * The messages the phone kept, as the chat shows them, without anything that needs the server: no composer, no
 * reactions, no files; pictures are the ones the phone has. Touch and hold copies.
 */
@Composable
private fun KeptChat(
    session: TalkSession,
    chat: SavedChat,
    room: Room?,
    first: RoomTab,
    request: RoomTab?,
    onTab: (RoomTab) -> Unit,
    line: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Messages that disappear do so here too.
    val now by produceState(System.currentTimeMillis() / 1000, chat) {
        while (true) {
            value = System.currentTimeMillis() / 1000
            delay(EXPIRY_LOOK_MILLIS)
        }
    }
    val topics = remember(chat) { if (session.supportsTopics) chat.topics else emptyList() }
    // Picked here; the room opens on it (also when the phone was turned meanwhile).
    var picked by rememberSaveable { mutableStateOf<Long?>(null) }
    val pick by rememberUpdatedState(onTab)
    LaunchedEffect(picked) { RoomTab.fromKey(picked)?.let { pick(it) } }
    LaunchedEffect(request) { request?.let { picked = it.key } }
    val shown: RoomTab = if (topics.isEmpty()) RoomTab.All else RoomTab.fromKey(picked) ?: first
    Column(Modifier.fillMaxSize()) {
        if (topics.isNotEmpty()) {
            val chips = remember(topics, shown) {
                val open = (shown as? RoomTab.Topic)?.takeIf { t -> topics.none { it.first == t.id } }
                buildList {
                    add(StripChip(RoomTab.General, "General"))
                    if (open != null) add(StripChip(open, "Topic"))
                    for ((id, title) in topics) add(StripChip(RoomTab.Topic(id), title.ifBlank { "Untitled topic" }))
                }
            }
            // "+" where the room's own line will have it, so that General does not move under the finger.
            val canStart = remember(room) { room?.takesPosts(session.capabilities, System.currentTimeMillis() / 1000) != false }
            TopicStrip(
                chips = chips, selected = shown, onSelect = { picked = it.key },
                onNew = if (canStart) ({}) else null, newReady = false,
            )
        }
        line()
        val holder = rememberSaveableStateHolder()
        holder.SaveableStateProvider(shown.key) {
            KeptMessages(session, chat, shown.scope, room, now, onOpenTopic = { picked = it }, say = { sayAnywhere(context, it) })
        }
    }
}

@Composable
private fun KeptMessages(
    session: TalkSession,
    chat: SavedChat,
    scope: ChatScope,
    room: Room?,
    now: Long,
    onOpenTopic: (Long) -> Unit,
    say: (String) -> Unit,
) {
    val context = LocalContext.current
    val messages = remember(chat, scope, now) { chat.messages(scope, now) }
    if (messages.isEmpty()) {
        MessagePane(title = "No saved messages", detail = "They load when the server answers.")
        return
    }
    fun mine(m: ChatMessage): Boolean = session.isMe(m.actorType, m.actorId)
    val items = remember(messages) {
        buildChatItems(messages, emptyList(), ::mine, dividerAfter = 0L, hideTopicNotices = scope is ChatScope.Topic).asReversed()
    }
    val list = rememberLazyListState()
    val launcher = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<ChatMessage?>(null) }
    val showRead = session.capabilities.readStatus && session.capabilities.sharesReadStatus
    val waiting = { say(WAITING_FOR_SERVER) }
    Box(Modifier.fillMaxWidth()) {
        MessageList(of = null, state = list, visible = true) {
            items(items, key = { it.key }, contentType = { it::class }) { item ->
                when (item) {
                    is ChatItem.Day -> DaySeparator(item.label)
                    is ChatItem.System -> SystemMessageRow(item.message)
                    is ChatItem.Message -> {
                        val m = item.message
                        MessageRow(
                            item = item,
                            session = session,
                            readMark = when {
                                !item.mine -> null
                                showRead && chat.commonRead > 0L && m.id <= chat.commonRead -> ReadMark.Read
                                else -> ReadMark.Delivered
                            },
                            showSender = !drawnWithoutNames(room),
                            topicChip = null,
                            highlighted = false,
                            canReply = false,
                            onReply = {},
                            onLongPress = { if (copyable(m)) menuFor = m else waiting() },
                            onQuoteClick = { id ->
                                val at = items.indexOfFirst { it is ChatItem.Message && it.message.id == id }
                                if (at >= 0) launcher.launch { list.animateScrollToItem(at) } else waiting()
                            },
                            onOpenTopic = onOpenTopic,
                            onOpenImage = { waiting() },
                            onOpenFile = { waiting() },
                            onReaction = { waiting() },
                        )
                    }
                    // Not here: nothing is on its way, and nothing is known to be new.
                    ChatItem.NewMessages, is ChatItem.Pending, is ChatItem.PendingFile -> Unit
                }
            }
        }
    }
    menuFor?.let { m ->
        MessageActionsSheet(
            message = m,
            canReply = false,
            canReact = false,
            showOpenTopic = false,
            onDismiss = { menuFor = null },
            onReply = {},
            onCopy = {
                copyToClipboard(context, m.plainText)
                menuFor = null
            },
            onReaction = {},
            onOpenTopic = {},
            onForward = null,
            onSelect = null,
            file = null,
            onSave = {},
            onShare = {},
            onEdit = null,
            onDelete = null,
        )
    }
}
