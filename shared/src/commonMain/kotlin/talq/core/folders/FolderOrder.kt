package talq.core.folders

// Folder order, ported from TalQ for desktop (src/core/FolderLogic.h, "Folder order").
//
// The order IS the list position, stored and synced; there is no "sorted" flag in the file. The
// list's own order says which rule applies:
//   - a list in NAME ORDER (folderSortsBefore) stays in it: a new folder is inserted at its place
//     and a renamed one moves to its new place. An empty list or a single folder is in name order,
//     so folders are alphabetical from the first one on;
//   - a list the user arranged by hand (Move up / Move down took it out of name order) is left
//     alone: a new folder goes at the END and a renamed one keeps its position;
//   - "Sort folders A to Z" (sortFoldersByName) puts a hand-arranged list back into name order.
// Nothing reorders on load, on repair or on a merged remote state: only the user's own create,
// rename or sort does, so a list another device (or another language) left out of name order is
// never rewritten behind the user's back, and two devices cannot re-sort each other's uploads in a
// loop. The sync merge needs [inNameOrder] for its order step: a base and a local list that are
// both in name order mean a local rename moved a folder, not a reorder by hand.

/** Compares two folder names; see [platformNameCollator]. */
fun interface NameCollator {
    fun compare(a: String, b: String): Int
}

/**
 * The collator for folder names: the device's language unless [languageTag] names another. Built
 * per call (the language can change while the app runs, and an instance is not thread-safe).
 */
fun folderNameCollator(languageTag: String? = null): NameCollator = platformNameCollator(languageTag)

/**
 * The one ordering of folders: the collator's, then the exact name (UTF-16 code units), then the
 * id. The tie-breaks make it a strict TOTAL order, because twin names ("HQ" and "hq") are tolerated
 * on load: every device with the same language then puts the same folders in the same order.
 */
fun folderCompare(c: NameCollator, a: Folder, b: Folder): Int {
    val byName = c.compare(a.name, b.name)
    if (byName != 0) return byName
    val exact = a.name.compareTo(b.name)
    if (exact != 0) return exact
    return a.id.compareTo(b.id)
}

fun folderSortsBefore(c: NameCollator, a: Folder, b: Folder): Boolean = folderCompare(c, a, b) < 0

/** No folder sorts before the one above it. An empty list and a single folder are in name order. */
fun inNameOrder(folders: List<Folder>, c: NameCollator): Boolean {
    for (i in 1 until folders.size) {
        if (folderSortsBefore(c, folders[i], folders[i - 1])) return false
    }
    return true
}

fun foldersInNameOrder(s: FolderState, c: NameCollator = folderNameCollator()): Boolean = inNameOrder(s.folders, c)

/**
 * [folders] (in name order) with [f] inserted at its place: after every folder that does not sort
 * after it (there are no equals, the order is total).
 */
internal fun insertInNameOrder(folders: List<Folder>, f: Folder, c: NameCollator): List<Folder> {
    var at = 0
    while (at < folders.size && !folderSortsBefore(c, f, folders[at])) at++
    val out = ArrayList<Folder>(folders.size + 1)
    out.addAll(folders.subList(0, at))
    out.add(f)
    out.addAll(folders.subList(at, folders.size))
    return out
}

internal fun sortedByName(folders: List<Folder>, c: NameCollator): List<Folder> =
    folders.sortedWith(Comparator { a, b -> folderCompare(c, a, b) })
