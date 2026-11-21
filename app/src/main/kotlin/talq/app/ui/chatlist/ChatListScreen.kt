package talq.app.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import talq.app.appContainer
import talq.app.appName
import talq.app.theme.TalqColors
import talq.app.theme.TalqTheme
import talq.app.ui.common.Avatar
import talq.app.ui.common.MessagePane
import talq.app.ui.common.StatusAvatar
import talq.app.ui.common.firstName
import talq.app.ui.common.relativeTime
import talq.app.ui.common.userMessage
import talq.app.ui.message.topicColor
import talq.core.TalkError
import talq.core.model.NotificationLevel
import talq.core.model.Room
import talq.core.model.RoomType
import talq.core.repo.TalkSession
import talq.core.shift.ShiftState

/** How often the list is reloaded while it is on screen. */
private const val REFRESH_MILLIS = 12_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    session: TalkSession,
    onOpenRoom: (token: String) -> Unit,
    onOpenTopic: (token: String, topicId: Long) -> Unit,
    onOpenSettings: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    val rooms by session.rooms.rooms.collectAsStateWithLifecycle()
    val load by session.rooms.state.collectAsStateWithLifecycle()
    val withTopics by session.roomsWithTopics.collectAsStateWithLifecycle()
    // Shift state of colleagues, as the app-level service knows it. Reading only: the
    // service decides by itself when to ask the ERP.
    val shifts by LocalContext.current.appContainer.shift.statuses.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pulling by remember { mutableStateOf(false) }

    // Reload while visible and at once on every return to the screen.
    LifecycleResumeEffect(session) {
        session.rooms.startAutoRefresh(REFRESH_MILLIS)
        onPauseOrDispose { session.rooms.stopAutoRefresh() }
    }
    LaunchedEffect(load.error) {
        if (load.error is TalkError.Unauthorized) onUnauthorized()
    }

    val sorted = remember(rooms) {
        rooms.sortedWith(
            compareByDescending<Room> { it.isFavorite }
                .thenBy { it.isArchived }
                .thenByDescending { it.lastActivity },
        )
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
            TopAppBar(
                title = { Text(appName(), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
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
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val error = load.error
            when {
                rooms.isEmpty() && !load.loaded && error == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }

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

                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (error != null) {
                        item(key = "error") { StaleBanner(error.userMessage()) }
                    }
                    items(sorted, key = { it.token }) { room ->
                        RoomRow(
                            session, room, now,
                            hasTopics = room.token in withTopics,
                            shift = shifts[room.partnerUserId]?.state ?: ShiftState.Unknown,
                            onClick = { onOpenRoom(room.token) },
                            onOpenTopic = { id -> onOpenTopic(room.token, id) },
                        )
                    }
                }
            }
        }
    }
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

@Composable
private fun RoomRow(
    session: TalkSession,
    room: Room,
    nowMillis: Long,
    hasTopics: Boolean,
    shift: ShiftState,
    onClick: () -> Unit,
    onOpenTopic: (topicId: Long) -> Unit,
) {
    val colors = TalqTheme.colors
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
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
    val preview = remember(room.lastMessage, colors, unreadTopic != null) {
        previewLine(session, room, colors, withTopic = unreadTopic == null)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (room.isOneToOne) {
            // Presence disc bottom right, shift square bottom left.
            StatusAvatar(
                url = session.client.roomAvatarUrl(room.token, room.avatarVersion, dark),
                authorization = session.client.authorizationHeader,
                name = room.displayName,
                ring = MaterialTheme.colorScheme.background,
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
                if (unreadTopic != null) {
                    TopicPill(unreadTopic.topicId, unreadTopic.threadTitle.trim()) { onOpenTopic(unreadTopic.topicId) }
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
                if (room.unreadMention) {
                    Spacer(Modifier.width(6.dp))
                    Badge("@", muted = muted && !room.unreadMentionDirect)
                }
                if (unread) {
                    Spacer(Modifier.width(6.dp))
                    Badge(if (room.unreadMessages > 999) "999+" else room.unreadMessages.toString(), muted)
                }
            }
        }
    }
}

/** The topic of the newest unread message: tap it to open the conversation on that topic. */
@Composable
private fun TopicPill(topicId: Long, title: String, onClick: () -> Unit) {
    val colors = TalqTheme.colors
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.bgSelected)
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
            modifier = Modifier.widthIn(max = 130.dp),
        )
    }
}

/** Unread pill. Accent when it wants attention, a quiet warm fill for muted conversations. */
@Composable
private fun Badge(text: String, muted: Boolean) {
    val colors = TalqTheme.colors
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .clip(CircleShape)
            .background(if (muted) colors.bgSelected else colors.accent)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (muted) colors.textSecondary else colors.controlInk,
            maxLines = 1,
        )
    }
}

/**
 * "Sender: text", or "Topic · Sender: text" when the last message sits in a topic (the server
 * repeats the topic title on its messages; [withTopic] false when the row shows the topic as a pill). One-to-one chats skip the other person's name.
 */
private fun previewLine(session: TalkSession, room: Room, colors: TalqColors, withTopic: Boolean = true): AnnotatedString {
    val last = room.lastMessage ?: return AnnotatedString("")
    val text = last.plainText.replace('\n', ' ').trim()
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
