package talq.core.model

import talq.core.folders.NameCollator
import talq.core.folders.folderNameCollator
import talq.core.folders.roomFolderMember

/*
 * The order of the conversation list and what of it is shown: TalQ for desktop's "Sort by" and "Show"
 * (MainWindow.cpp `sortDefs`, `filterDefs`; SidebarPainter.cpp `rebuildLayouts`). Favourites first, then the
 * chosen key; equal ones stay in the order they came in, which is the newest activity first. Folders are
 * slices of the sorted list.
 *
 * Decided difference: names are compared as everywhere in the app (folders, members): the device's language,
 * case ignored, numbers by value ("Team 2" before "Team 10").
 */

/** What the conversation list is sorted by. [id] is what is stored, desktop's `Sidebar/sortMode`. */
enum class RoomSort(val id: Int) {
    /** Newest activity first. */
    Recent(0),

    /** Most unread first, by the number that counts (nothing that was muted), then as [Recent]. */
    Unread(1),

    /** By display name. */
    Name(2),
    ;

    companion object {
        /** Anything that was never stored or is no order of ours is [Recent], as on desktop. */
        fun fromId(id: Int?): RoomSort = entries.firstOrNull { it.id == id } ?: Recent
    }
}

/**
 * Each of [names] and its place in name order, one place for names that differ in case only. One collator for
 * all of it; the caller keeps the answer while the names are the same.
 */
fun nameRanks(names: Collection<String>, collator: NameCollator = folderNameCollator()): Map<String, Int> {
    val sorted = names.distinct().sortedWith(Comparator<String> { a, b -> collator.compare(a, b) })
    val ranks = HashMap<String, Int>(sorted.size * 2)
    var rank = 0
    for (i in sorted.indices) {
        if (i > 0 && collator.compare(sorted[i - 1], sorted[i]) != 0) rank++
        ranks[sorted[i]] = rank
    }
    return ranks
}

/**
 * [rooms] (newest activity first) in the order [sort]. [unread] answers the number of a room that counts;
 * [ranks] holds the places of the display names ([nameRanks]) and is read for [RoomSort.Name] only.
 */
fun sortRooms(
    rooms: List<Room>,
    sort: RoomSort,
    unread: (Room) -> Int,
    ranks: Map<String, Int> = if (sort == RoomSort.Name) nameRanks(rooms.map { it.displayName }) else emptyMap(),
): List<Room> {
    val favouritesFirst = compareByDescending<Room> { it.isFavorite }
    return when (sort) {
        RoomSort.Recent -> rooms.sortedWith(favouritesFirst.thenByDescending { it.lastActivity })
        RoomSort.Unread -> {
            // Asked once per room, not per comparison.
            val counts = rooms.associate { it.token to maxOf(0, unread(it)) }
            rooms.sortedWith(favouritesFirst.thenByDescending { counts[it.token] ?: 0 }.thenByDescending { it.lastActivity })
        }
        RoomSort.Name -> rooms.sortedWith(favouritesFirst.thenBy { ranks[it.displayName] ?: Int.MAX_VALUE })
    }
}

/** What of the conversation list is shown. [id] is what is stored, desktop's `Sidebar/filterMode`. */
enum class RoomFilter(val id: Int) {
    All(0),
    Unread(1),
    Favorites(2),

    /** One-to-one conversations (type 1 only, as on desktop). */
    Direct(3),

    /** Group and public conversations. */
    Groups(4),

    /** The only one that shows archived conversations. */
    Archived(5),
    ;

    companion object {
        /** Anything that was never stored or is no filter of ours is [All], as on desktop. */
        fun fromId(id: Int?): RoomFilter = entries.firstOrNull { it.id == id } ?: All
    }
}

/**
 * [filter] shows [room], one as the list counts it; [muted] of its unread messages are grey (`listedRoom`).
 * An archived one is in no view but [RoomFilter.Archived], however unread or favourite it is: that is what
 * archiving is (desktop's rule; with [RoomFilter.All] the screen has a section of its own for them).
 */
fun showsRoom(filter: RoomFilter, room: Room, muted: Int): Boolean {
    if (room.isArchived != (filter == RoomFilter.Archived)) return false
    return when (filter) {
        RoomFilter.All, RoomFilter.Archived -> true
        RoomFilter.Unread -> hasColouredUnread(room, muted)
        RoomFilter.Favorites -> room.isFavorite
        RoomFilter.Direct -> room.type == RoomType.ONE_TO_ONE
        RoomFilter.Groups -> room.type == RoomType.GROUP || room.type == RoomType.PUBLIC
    }
}

/**
 * The row of [room] has a badge in colour: a number that counts (desktop's `countedUnreadOf`), or an "@" that
 * is not grey. Decided difference: desktop knows no "@" without such a number; here a muted conversation in
 * which the user was named has one in colour. An "@" of everyone where all that is unread was muted is grey,
 * and no unread here either.
 */
private fun hasColouredUnread(room: Room, muted: Int): Boolean {
    if (roomFolderMember(room, countMuted = false, mutedPart = muted).unread > 0) return true
    val quiet = room.notificationLevel == NotificationLevel.NEVER || muted > 0
    return room.unreadMention && (room.unreadMentionDirect || !quiet)
}
