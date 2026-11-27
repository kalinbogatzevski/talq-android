package talq.app.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import talq.app.theme.TalqTheme
import talq.core.model.RoomFilter
import talq.core.model.TalkCapabilities
import talq.core.model.showsRoom
import talq.core.repo.TalkSession
import talq.core.repo.listedRoom

// "Show" of the conversation list, the second part of the menu beside the search field (ListSort.kt): TalQ for
// desktop's filters (MainWindow.cpp `filterDefs`; SidebarPainter.cpp `rebuildLayouts`, `buildFolderRows`,
// `paintEvent`). What each one shows is in core (RoomOrder.kt); the choice is kept on the device.
//  - a search looks inside what is shown, and the chip that says so stays up meanwhile;
//  - folders keep their open and closed states (only a search opens them all), and one without a conversation
//    to show is left out. "Archived" at the list's end is All's only;
//  - "Unread" keeps the conversation that is open (ListSort.kt, KeptPlace): reading it does not take it away;
//  - a notification, a link and "New conversation" open what they name, whatever is shown here. Its closed
//    folder is opened for it only where the list shows it ([listShows], desktop's `revealToken`);
//  - decided differences: the chip above the list (desktop has the button's accent only), "Show all" beside
//    the line of an empty view, a line of its own for an empty "Archived" (desktop's says there is none at all).

/** Desktop's words. */
internal fun filterLabel(filter: RoomFilter): String = when (filter) {
    RoomFilter.All -> "All conversations"
    RoomFilter.Unread -> "Unread"
    RoomFilter.Favorites -> "Favorites"
    RoomFilter.Direct -> "Direct messages"
    RoomFilter.Groups -> "Groups"
    RoomFilter.Archived -> "Archived"
}

/** The one line where [filter] shows nothing. */
internal fun emptyLine(filter: RoomFilter): String = when (filter) {
    RoomFilter.All -> "No conversations yet"
    RoomFilter.Unread -> "No unread conversations"
    RoomFilter.Favorites -> "No favorites yet"
    RoomFilter.Direct -> "No direct messages"
    RoomFilter.Groups -> "No groups"
    RoomFilter.Archived -> "No archived conversations"
}

/** The choices of the menu: what the server does not have is left out, unless it is the one picked ([current]). */
internal fun offeredFilters(capabilities: TalkCapabilities, current: RoomFilter): List<RoomFilter> = RoomFilter.entries.filter {
    it == current || when (it) {
        RoomFilter.Favorites -> capabilities.favorites
        RoomFilter.Archived -> capabilities.archivedConversations
        else -> true
    }
}

/** The list shows conversation [token] under [filter], by what is known now. */
internal fun listShows(session: TalkSession, filter: RoomFilter, token: String): Boolean {
    val room = session.rooms.room(token) ?: return false
    val listed = listedRoom(room, session.silencedUnread.figures.value[token], session.unreadHere.value[token])
    return showsRoom(filter, listed.room, listed.muted)
}

/** Above the list while [filter] holds back some of it: a tap shows all again. */
@Composable
internal fun FilterLine(filter: RoomFilter, onShowAll: () -> Unit) {
    val colors = TalqTheme.colors
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .minimumInteractiveComponentSize()
            .height(32.dp)
            .clip(CircleShape)
            .background(colors.accentSoft)
            .semantics { stateDescription = "Filter on" }
            .clickable(role = Role.Button, onClickLabel = "Show all conversations", onClick = onShowAll)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            filterLabel(filter),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.accentSoftInk,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.Close, contentDescription = null, tint = colors.accentSoftInk, modifier = Modifier.size(16.dp))
    }
}

/** In place of the rows when there are none: [text], and "Show all" while a filter holds some back. */
@Composable
internal fun NothingShown(text: String, onShowAll: (() -> Unit)?) {
    val colors = TalqTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (onShowAll == null) 20.dp else 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onShowAll != null) {
            TextButton(onClick = onShowAll) { Text("Show all", color = colors.accentText, maxLines = 1) }
        }
    }
}
