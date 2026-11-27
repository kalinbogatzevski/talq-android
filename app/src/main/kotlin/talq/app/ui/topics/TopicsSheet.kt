package talq.app.ui.topics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import talq.app.theme.TalqTheme
import talq.app.ui.common.relativeTime
import talq.app.ui.message.topicColor
import talq.app.ui.room.RoomTab
import talq.core.model.ChatMessage
import talq.core.model.NotificationLevel
import talq.core.model.TalkThreadInfo
import talq.core.repo.TalkSession
import talq.core.repo.UnreadState

/**
 * Every tab of the room in one list, for rooms with more topics than the strip shows at a
 * glance: a search field on the titles, "New topic" first (while searching too), then General, then the
 * topics with unread ones first (then newest activity). Opens at half height; tapping a row switches
 * to it and closes.
 *
 * @param onNew "New topic", the same as the strip's "+"; null hides it (the user may not start one here)
 * @param generalLast the newest message outside the topics, for General's preview line
 * @param selected the open tab, tinted in the list
 * @param onMenu touch and hold or a right-click on a row: the menu of that tab's chip
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicsSheet(
    session: TalkSession,
    topics: List<TalkThreadInfo>,
    unread: UnreadState,
    lastRead: Long,
    generalLast: ChatMessage?,
    selected: RoomTab?,
    onPick: (RoomTab) -> Unit,
    onMenu: (RoomTab) -> Unit,
    onNew: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = TalqTheme.colors
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim()
    // The order is taken when the sheet opens: rows must not jump while the list is being read.
    val ordered = remember(topics.map { it.id }) {
        topics.sortedWith(
            compareByDescending<TalkThreadInfo> { topicUnread(session, unread, lastRead, it).any }
                .thenByDescending { it.thread.lastActivity },
        ).map { it.id }
    }
    val byId = topics.associateBy { it.id }
    val shownTopics = ordered.mapNotNull { byId[it] }.filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) }
    val showGeneral = needle.isEmpty() || "General".contains(needle, ignoreCase = true)
    val now = System.currentTimeMillis()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        // Full height inside the sheet, so it opens half way and can be pulled up.
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.replace('\n', ' ') },
                placeholder = { Text("Search topics") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (onNew != null) {
                    item(key = "new") { NewTopicRow(onNew) }
                }
                if (showGeneral) {
                    item(key = "general") {
                        TopicRow(
                            letter = "#",
                            fill = colors.bgSelected,
                            letterInk = colors.textSecondary,
                            title = "General",
                            preview = generalLast?.let { previewLine(session, it) } ?: "Messages outside the topics",
                            time = generalLast?.let { relativeTime(context, it.timestamp, now) } ?: "",
                            unread = generalUnread(unread),
                            muted = false,
                            onClick = { onPick(RoomTab.General) },
                            onMenu = { onMenu(RoomTab.General) },
                            modifier = if (selected == RoomTab.General) Modifier.background(colors.accentSoft) else Modifier,
                        )
                    }
                }
                items(shownTopics, key = { it.id }) { info ->
                    val title = info.title.ifBlank { "Untitled topic" }
                    TopicRow(
                        letter = title.trim().take(1).uppercase(),
                        fill = topicColor(info.id),
                        letterInk = Color.White,
                        title = title,
                        preview = info.preview?.let { previewLine(session, it) } ?: "",
                        time = relativeTime(context, info.thread.lastActivity, now),
                        unread = topicUnread(session, unread, lastRead, info),
                        muted = info.attendee.notificationLevel == NotificationLevel.NEVER,
                        onClick = { onPick(RoomTab.Topic(info.id)) },
                        onMenu = { onMenu(RoomTab.Topic(info.id)) },
                        modifier = if (selected == RoomTab.Topic(info.id)) Modifier.background(colors.accentSoft) else Modifier,
                    )
                }
                if (!showGeneral && shownTopics.isEmpty()) {
                    item(key = "none") {
                        Text(
                            "No topic matches \"$needle\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Laid out as a [TopicRow]: the strip's "+" as a row. */
@Composable
private fun NewTopicRow(onClick: () -> Unit) {
    val colors = TalqTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accentSoftInk)
        }
        Spacer(Modifier.width(12.dp))
        Text("New topic", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1)
    }
}
