package talq.core.folders

import talq.core.model.NotificationLevel
import talq.core.model.Room

// The conversation list grouped by folder: the partition TalQ for desktop's sidebar makes in
// "Group by Folders" (its folder row build), as a pure function for the list screen.
//
//  - The input is the list ALREADY filtered and sorted the way the flat list shows it; each folder
//    gets its slice in that order (a stable partition), so favourites stay on top inside a folder.
//  - Folders come in their stored order, "Other" (the unfiled chats, and chats filed in a folder
//    that no longer exists) always last and only when it has a member.
//  - Nothing in the input -> no sections at all, not even empty folders (the screen shows its own
//    empty state instead; a header there would be an invisible target).
//  - An empty folder is shown (it is how you see a folder you just made), unless a filter is
//    active, when a folder with no matching member is left out.
//  - A search shows every folder open without touching the stored collapsed flags.
//  - Every member counts in its section's roll-up, also while the section is closed.
//  - A filing of a chat that is not in the input is simply not shown: never pruned here.

/** One section of the grouped list. */
sealed interface FolderSection {
    /** A real folder; [index] is its position in [FolderState.folders]. */
    data class InFolder(val folder: Folder, val index: Int) : FolderSection

    /** The built-in section for unfiled chats. It has no id and no menu. */
    data object Other : FolderSection
}

/**
 * A section, its members in display order, their roll-up, and whether it is drawn CLOSED (its
 * header without its rows).
 */
data class SectionRows<T>(
    val section: FolderSection,
    val members: List<T>,
    val rollup: FolderRollup,
    val closed: Boolean,
)

/** What one list row adds to its section: its token and the numbers its own badges show ([muted]: its grey one). */
data class FolderMember(
    val token: String,
    val unread: Int,
    val mention: Boolean,
    val directMention: Boolean,
    val muted: Int = 0,
)

/**
 * The partition of [sorted] by [state]. [member] reads a row's token and counted unread numbers.
 * [searching]: a text search is active (every folder is drawn open). [filterActive]: anything is
 * filtering the list (a search, a filter other than "all"); then empty folders are left out.
 */
fun <T> buildFolderSections(
    sorted: List<T>,
    state: FolderState,
    member: (T) -> FolderMember,
    searching: Boolean = false,
    filterActive: Boolean = searching,
): List<SectionRows<T>> {
    if (sorted.isEmpty()) return emptyList()

    // Folder ids to positions once, so the partition is one map lookup per row. A duplicate id
    // keeps its first position; a filing to an id not in the list leaves the chat in Other.
    val indexById = HashMap<String, Int>(state.folders.size * 2)
    for (i in state.folders.indices) {
        val id = state.folders[i].id
        if (id !in indexById) indexById[id] = i
    }

    val n = state.folders.size
    val members = List(n) { ArrayList<T>() }
    val rollups = Array(n) { FolderRollup() }
    val other = ArrayList<T>()
    var otherRollup = FolderRollup()
    for (row in sorted) {
        val m = member(row)
        val folderId = state.assign[m.token]
        val f = if (folderId == null) -1 else indexById[folderId] ?: -1
        if (f >= 0) {
            members[f].add(row)
            rollups[f] = addToRollup(rollups[f], m.unread, m.mention, m.directMention, m.muted)
        } else {
            other.add(row)
            otherRollup = addToRollup(otherRollup, m.unread, m.mention, m.directMention, m.muted)
        }
    }

    val out = ArrayList<SectionRows<T>>(n + 1)
    for (i in 0 until n) {
        if (members[i].isEmpty() && filterActive) continue
        val folder = state.folders[i]
        out.add(SectionRows(FolderSection.InFolder(folder, i), members[i], rollups[i], !searching && folder.collapsed))
    }
    if (other.isNotEmpty()) {
        out.add(SectionRows(FolderSection.Other, other, otherRollup, !searching && state.otherCollapsed))
    }
    return out
}

/**
 * A room as a folder member, by desktop's "counted unread" rule: a room set to Never adds nothing
 * unless [countMuted], and its mention flags count only when its counted number is above zero.
 * [mutedPart] is how many of its unread messages were muted (all of them in a room set to Never, else
 * those in silenced topics: `mutedUnread`): they are left out of the counted number and handed on
 * apart, for the grey badge. [countMuted] has no default on purpose: whether a muted
 * room's number belongs in its folder's total is the screen's decision.
 */
fun roomFolderMember(room: Room, countMuted: Boolean, mutedPart: Int = 0): FolderMember {
    val unread = maxOf(0, room.unreadMessages)
    val muted = if (countMuted) 0 else mutedPart.coerceIn(0, unread)
    val counted = if (!countMuted && room.notificationLevel == NotificationLevel.NEVER) 0 else unread - muted
    return FolderMember(
        token = room.token,
        unread = counted,
        mention = room.unreadMention && counted > 0,
        directMention = room.unreadMentionDirect && counted > 0,
        muted = muted,
    )
}

/** [buildFolderSections] for the conversation list's rooms; [mutedPart] as in [roomFolderMember]. */
fun buildRoomSections(
    sorted: List<Room>,
    state: FolderState,
    countMuted: Boolean,
    searching: Boolean = false,
    filterActive: Boolean = searching,
    mutedPart: (Room) -> Int = { 0 },
): List<SectionRows<Room>> =
    buildFolderSections(sorted, state, { roomFolderMember(it, countMuted, mutedPart(it)) }, searching, filterActive)
