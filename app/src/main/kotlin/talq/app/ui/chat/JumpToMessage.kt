package talq.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import talq.app.theme.TalqTheme
import talq.core.repo.ChatWindow

/*
 * The parts of ChatPane that go to a message further back than what is loaded (what is looked for, and the window
 * it is shown in, is ChatViewModel's): the way back to the newest messages, the ends of the list, and reading on.
 *
 * - The window takes the list's place and has a list state of its own; the newest view keeps its place under it,
 *   with its rows on their way out. The pill goes to the newest messages; Back goes to where the quote was tapped
 *   (beside the list, and for Esc, always).
 * - Nothing seen in the window counts as read, and it shows no "New messages" line: what is above it may never
 *   have been on screen. Scrolled down to the tab's own messages it becomes part of them, in place, and they are
 *   read as ever.
 * - A page that failed is said at that end, with "Try again", and is not asked for before that (or the network is
 *   back). Both lists, all ends.
 */

/** Under the last message. In a window the pill has its place there too: it never lies over the end row's button. */
internal val OwnListBottom = 6.dp
private val WindowBottom = 68.dp

/** The messages, newest at the bottom: the tab's own, or those [of] a window, a list of its own with a place of its own. */
@Composable
internal fun MessageList(of: ChatWindow?, state: LazyListState, visible: Boolean, content: LazyListScope.() -> Unit) = key(of) {
    LazyColumn(
        state = state,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize().alpha(if (visible) 1f else 0f),
        contentPadding = PaddingValues(top = 8.dp, bottom = if (of == null) OwnListBottom else WindowBottom),
        content = content,
    )
}

/** The list comes near its older (upper) end, which is last in the reversed list. */
internal fun LazyListState.nearOlderEnd(): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return false
    return last >= info.totalItemsCount - NEAR_ROWS
}

/** The same at its newer (lower) end. */
internal fun LazyListState.nearNewerEnd(): Boolean {
    val first = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: return false
    return first < NEAR_ROWS
}

/** How many rows before an end the next page is asked for. */
private const val NEAR_ROWS = 8

/** A page that failed, at that end of the list: a button. */
internal const val COULD_NOT_LOAD_MORE = "Couldn't load more. Try again"

/**
 * Asks for more ([load], which returns when it is done) as long as it is [wanted]: an end is near, there may be
 * more, the last page did not fail. Never in between: what came is laid out before the end is looked at again.
 */
internal suspend fun readOn(wanted: () -> Boolean, load: suspend () -> Unit) {
    snapshotFlow { wanted() }.collect {
        while (wanted()) {
            load()
            withFrameNanos { }
            withFrameNanos { }
        }
    }
}

/**
 * An end of the loaded messages that is not the end of the chat: the next page is on its way, or ([line], a button)
 * why it is not.
 */
@Composable
internal fun HistoryEnd(line: String?, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        if (line != null) {
            TextButton(onClick = onClick) {
                Text(line, color = TalqTheme.colors.accentText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/** Back to the newest messages, with how many of them have not been seen ([unseen], 0 = no count). */
@Composable
internal fun NewestPill(unseen: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TalqTheme.colors
    Surface(shape = CircleShape, color = colors.accentSoft, shadowElevation = 3.dp, modifier = modifier) {
        Row(
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.accentSoftInk, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Jump to newest", style = MaterialTheme.typography.labelLarge, color = colors.accentSoftInk, maxLines = 1)
            if (unseen > 0) {
                Spacer(Modifier.width(8.dp))
                UnseenCount(unseen)
            }
        }
    }
}

/** How many messages below have not been seen. */
@Composable
internal fun UnseenCount(count: Int, modifier: Modifier = Modifier) {
    val colors = TalqTheme.colors
    Text(
        text = if (count > 99) "99+" else count.toString(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = colors.inkOn(colors.countFill),
        modifier = modifier
            .defaultMinSize(minWidth = 18.dp)
            .clip(CircleShape)
            .background(colors.countFill)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** A message asked for from outside the chat is being loaded (a quote shows that itself). */
@Composable
internal fun SeekingDisc(modifier: Modifier = Modifier) {
    val colors = TalqTheme.colors
    Surface(shape = CircleShape, color = colors.accentSoft, shadowElevation = 3.dp, modifier = modifier.semantics { contentDescription = "Loading" }) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), color = colors.accentSoftInk, strokeWidth = 2.dp)
        }
    }
}
