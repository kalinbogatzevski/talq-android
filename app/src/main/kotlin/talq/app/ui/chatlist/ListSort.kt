package talq.app.ui.chatlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import talq.app.theme.TalqTheme
import talq.core.folders.SectionRows
import talq.core.folders.roomFolderMember
import talq.core.model.Room
import talq.core.model.RoomFilter
import talq.core.model.RoomSort
import talq.core.model.nameRanks
import talq.core.model.showsRoom
import talq.core.model.sortRooms

// "Sort by" of the conversation list, TalQ for desktop's funnel button beside the search field
// (MainWindow.cpp): the three orders and their words, and under them "Show" (ListFilter.kt); the button in the
// accent while either is not the usual one. The orders are in core (RoomOrder.kt); folders, "Archived" and a
// search take the chats in that order. The choices are kept on the device (ListSortStore).
//  - "Unread first" counts as the row's coloured badge does (nothing that was muted), as on desktop;
//  - decided difference: the conversation that is open keeps its place until it is closed or another one is
//    opened, so reading it does not move it ("Unread first": it counts with the number it had when it was
//    opened) and does not take it away ("Unread": it stays once it was in there). Desktop sorts and filters it
//    anew. On a phone that is while the list makes way for it.

private fun sortLabel(sort: RoomSort): String = when (sort) {
    RoomSort.Recent -> "Recent activity"
    RoomSort.Unread -> "Unread first"
    RoomSort.Name -> "Name (A–Z)"
}

/**
 * The button beside the search field and its menu; neither touches the search. [filters]: the choices of
 * "Show" ([offeredFilters]).
 */
@Composable
internal fun SortButton(
    sort: RoomSort,
    filter: RoomFilter,
    filters: List<RoomFilter>,
    onSort: (RoomSort) -> Unit,
    onFilter: (RoomFilter) -> Unit,
) {
    val colors = TalqTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        // The colour is not the only sign: a screen reader hears both choices too.
        IconButton(
            onClick = { open = true },
            modifier = Modifier.semantics { stateDescription = "${sortLabel(sort)}, ${filterLabel(filter)}" },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = "Sort and filter conversations",
                tint = if (sort == RoomSort.Recent && filter == RoomFilter.All) colors.textSecondary else colors.accentText,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeading("Sort by")
            for (choice in RoomSort.entries) {
                MenuChoice(sortLabel(choice), picked = choice == sort) {
                    open = false
                    if (choice != sort) onSort(choice)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.divider)
            MenuHeading("Show")
            for (choice in filters) {
                MenuChoice(filterLabel(choice), picked = choice == filter) {
                    open = false
                    if (choice != filter) onFilter(choice)
                }
            }
        }
    }
}

@Composable
private fun MenuHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = TalqTheme.colors.textSecondary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** One of several, a check on the one [picked]. */
@Composable
private fun MenuChoice(label: String, picked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            Box(Modifier.size(24.dp)) {
                if (picked) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = TalqTheme.colors.accentText)
            }
        },
        onClick = onClick,
    )
}

/** Turning the device leaves none open for less than this. */
private const val CLOSED_MILLIS = 1_000L

/**
 * The conversation that is open ([token]), the number it counted with when it was opened, and whether "Unread"
 * has had it since.
 */
private class KeptPlace(token: String? = null, unread: Int = 0, listed: Boolean = false) {
    var token by mutableStateOf(token)
    var unread by mutableIntStateOf(unread)
    var listed by mutableStateOf(listed)

    companion object {
        val Saver = listSaver<KeptPlace, Any?>(
            save = { listOf(it.token, it.unread, it.listed) },
            restore = { KeptPlace(it[0] as String?, it[1] as Int, it.getOrNull(2) == true) },
        )
    }
}

/** The conversations as the list draws them: [main] in its order, and those behind "Archived" at its end. */
internal class RoomListing(val main: List<Room>, val archived: List<Room>)

/**
 * What [filter] shows of [rooms], in the order [sort]; see the top of this file and of ListFilter.kt. [muted]:
 * how many of a room's unread messages are grey (`listedRoom`), by token. [openToken]: the conversation that is
 * open; [beside]: it is open beside the list (two panes).
 * One that is opened has its number noted in that same composition, so before any of it was read.
 */
@Composable
internal fun rememberRoomListing(
    rooms: List<Room>,
    sort: RoomSort,
    filter: RoomFilter,
    muted: Map<String, Int>,
    openToken: String?,
    beside: Boolean,
): RoomListing {
    val place = rememberSaveable(saver = KeptPlace.Saver) { KeptPlace() }
    val counted: (Room) -> Int = { room ->
        roomFolderMember(room, countMuted = false, mutedPart = muted[room.token] ?: 0).unread
    }
    // Beside the list none is open for a moment after turning the device: the one that was noted still is.
    val open: String? = place.token?.takeIf { it == openToken || (openToken == null && beside) }
    val kept: Int? = if (sort == RoomSort.Unread && open != null) place.unread else null
    val held: String? = if (filter == RoomFilter.Unread && place.listed) open else null
    SideEffect {
        if (sort != RoomSort.Unread && filter != RoomFilter.Unread) {
            place.token = null
        } else if (openToken != null) {
            val room = rooms.firstOrNull { it.token == openToken }
            if (room != null) {
                if (place.token != openToken) {
                    place.token = openToken
                    place.unread = counted(room)
                    place.listed = false
                }
                // Not by being open alone: one that was read before is not in "Unread" when that is picked.
                place.listed = filter == RoomFilter.Unread &&
                    (place.listed || showsRoom(filter, room, muted[openToken] ?: 0))
            }
        }
    }
    LaunchedEffect(openToken) {
        if (openToken == null) {
            delay(CLOSED_MILLIS)
            // Closed: opened again, it is noted anew.
            place.token = null
        }
    }
    // The places of the names, worked out again only when a name changed.
    val names = remember(rooms) { rooms.mapTo(HashSet<String>()) { it.displayName } }
    val ranks = remember(names, sort == RoomSort.Name) {
        if (sort == RoomSort.Name) nameRanks(names) else emptyMap<String, Int>()
    }
    val sorted = remember(rooms, sort, muted, open, kept, ranks) {
        sortRooms(rooms, sort, unread = { if (kept != null && it.token == open) kept else counted(it) }, ranks = ranks)
    }
    return remember(sorted, filter, muted, held) {
        RoomListing(
            main = sorted.filter { showsRoom(filter, it, muted[it.token] ?: 0) || (it.token == held && !it.isArchived) },
            archived = if (filter == RoomFilter.All) sorted.filter { it.isArchived } else emptyList(),
        )
    }
}

/**
 * The rows of the flat list slide to their new places: with "Unread first" a row also moves because something
 * was read, and in "Unread" it goes, and one sees where to. A frame late, so picking another order or view and
 * ending a search slide nothing (the list would bring the rows it does not know yet in from its edge).
 */
@Composable
internal fun rememberRowsSlide(sort: RoomSort, filter: RoomFilter, searching: Boolean): Boolean {
    val wanted = (sort == RoomSort.Unread || filter == RoomFilter.Unread) && !searching
    val now = Triple(sort, filter, searching)
    // Compared, not just true: a producer keeps its old value until it has run for the new key.
    val settled by produceState<Any?>(null, now) { value = now }
    return wanted && settled == now
}

/**
 * The keys of the list's rows from the first conversation or folder header on, in the order ChatListScreen
 * draws them (keep the two together); "Archived" at the end is left out.
 */
internal fun rowKeys(shown: List<Room>, sections: List<SectionRows<Room>>?): List<String> = buildList {
    if (sections == null) {
        for (room in shown) add(room.token)
    } else {
        for (rows in sections) {
            add(sectionKey(rows.section))
            if (!rows.closed) for (room in rows.members) add(room.token)
        }
    }
}

/**
 * A keyed list goes along with the row at the top of the screen. When that row is a conversation that has left
 * the ones under it (it was read, or got a message), they stay where they are instead. [keys]: the rows as
 * they are drawn next ([rowKeys]), with [lead] other rows above them. Called before the new list is measured,
 * never against a finger or a fling.
 */
internal fun LazyListState.keepRowsInPlace(keys: List<Any>, lead: Int) {
    val seen = layoutInfo.visibleItemsInfo
    if (isScrollInProgress || seen.size < 3 || seen[0].index != firstVisibleItemIndex) return
    // Not for a banner, a folder header or "Archived": those do not move, what is under them opens and closes.
    val top = seen[0].key as? String ?: return
    if (seen[0].index < lead || ':' in top) return
    val next = keys.indexOf(seen[1].key)
    if (next < 0 || keys.getOrNull(next + 1) != seen[2].key) return
    // Right above them as before: the list keeps its place by itself. Near the top of the list what came
    // between is to be seen, like a new first row.
    val at = keys.indexOf(top)
    if (at == next - 1 || (at in 0 until next && firstVisibleItemIndex <= 2)) return
    requestScrollToItem(lead + next, -seen[1].offset)
}
