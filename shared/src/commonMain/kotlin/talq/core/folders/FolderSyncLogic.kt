package talq.core.folders

import kotlin.math.floor

// The pure half of folder sync, ported function by function from TalQ for desktop
// (src/core/FolderSyncLogic.h): how the shared remote file is read, how two folder states are
// compared and diffed, the three-way merge of base, local and remote, and the sidecar that records
// the base together with the rule for when that base may be believed. No I/O, no clock.
//
// Every function takes SANITISED states (sanitiseFolderState changes nothing): a local state that
// needed repair was repaired at load, and a remote body that needed it never reaches a comparison,
// because decodeRemote calls it Unreadable.

/**
 * The "v" of the SHARED file, separate from FOLDER_FORMAT_VERSION (the local file). Any additive
 * change to the remote body bumps it: a writer rebuilds the file from FolderState, so a key it does
 * not know is dropped on its next write, and an older sync build must pause on a newer "v" instead.
 */
const val REMOTE_VERSION = 1

/** The sidecar is read with this limit; over it is Unreadable (an untrusted base). */
const val MAX_SIDECAR_BYTES = 8 * 1024 * 1024

/** The sidecar's own "v", separate from the local and the remote format. */
const val SIDECAR_VERSION = 1

/** "Empty" is no folders and no filings. otherCollapsed alone does not make a state non-empty. */
fun isEmptyFolderState(s: FolderState): Boolean = s.folders.isEmpty() && s.assign.isEmpty()

/**
 * Order, ids, names (exact: a case-only rename is a difference), collapsed flags, otherCollapsed
 * and the filing map. This is FolderState's own equality, named as desktop names it.
 */
fun sameFolderState(a: FolderState, b: FolderState): Boolean = a == b

/** What changed between two states, in the terms a notice and a backup decision need. A folder is its id. */
data class StateDiff(
    /** Ids in `to` that `from` lacks. */
    val foldersAdded: Int = 0,
    /** Ids in `from` that `to` lacks. */
    val foldersRemoved: Int = 0,
    /** Ids in both whose name differs (exact: a case-only rename counts). */
    val foldersRenamed: Int = 0,
    /** Tokens whose folder differs; unfiled (or filed in a folder that is gone) is the folder "none". */
    val chatsChanged: Int = 0,
    /** The folders present on BOTH sides are in another relative order; an add, a remove or an append alone does not set it. */
    val orderChanged: Boolean = false,
    /** A folder present on both sides changed its collapsed flag, or the built-in "Other" did. */
    val collapsedChanged: Boolean = false,
) {
    /** Worth telling the user about. A collapse is a view setting: collapsedChanged alone is NOT visible. */
    val visible: Boolean
        get() = foldersAdded > 0 || foldersRemoved > 0 || foldersRenamed > 0 || chatsChanged > 0 || orderChanged
}

fun diffStates(from: FolderState, to: FolderState): StateDiff {
    val fromById = HashMap<String, Folder>()
    for (f in from.folders) fromById[f.id] = f

    var added = 0
    var renamed = 0
    var collapsedChanged = false
    val toIds = HashSet<String>()
    val commonInToOrder = ArrayList<String>()
    for (f in to.folders) {
        toIds.add(f.id)
        val old = fromById[f.id]
        if (old == null) {
            added++
            continue
        }
        commonInToOrder.add(f.id)
        if (old.name != f.name) renamed++
        if (old.collapsed != f.collapsed) collapsedChanged = true
    }

    var removed = 0
    val commonInFromOrder = ArrayList<String>()
    for (f in from.folders) {
        if (f.id in toIds) commonInFromOrder.add(f.id) else removed++
    }
    if (from.otherCollapsed != to.otherCollapsed) collapsedChanged = true

    val tokens = HashSet<String>()
    tokens.addAll(from.assign.keys)
    tokens.addAll(to.assign.keys)
    var chats = 0
    for (t in tokens) {
        if (folderOf(from, t) != folderOf(to, t)) chats++
    }
    return StateDiff(added, removed, renamed, chats, commonInFromOrder != commonInToOrder, collapsedChanged)
}

/**
 * Would going from [from] to [to] take something away: a folder id of `from` that `to` lacks, or a
 * chat filed in `from` that is unfiled (or absent) in `to`. A rename, a collapse, a reorder, a move
 * to another folder and every addition are not removals. The store keeps a backup before applying a
 * state for which this is true.
 */
fun removesFoldersOrFilings(from: FolderState, to: FolderState): Boolean {
    val toIds = HashSet<String>()
    for (f in to.folders) toIds.add(f.id)
    for (f in from.folders) {
        if (f.id !in toIds) return true
    }
    for (token in from.assign.keys) {
        if (folderOf(to, token).isEmpty()) return true
    }
    return false
}

// ---------------------------------------------------------------------------------------------
// The remote file

enum class RemoteKind {
    /** Zero bytes, or only whitespace (after an optional BOM): nobody wrote anything yet. */
    Absent,
    /** A readable file of a version this build knows, nothing repaired; [RemoteDecode.state] is set. */
    Present,
    /** "v" above REMOTE_VERSION: a newer version wrote it; never write it back. */
    Newer,
    /** Not a file this build can trust: never merge it, never overwrite it. */
    Unreadable,
}

/** [state] is meaningful only for Present; empty otherwise. */
data class RemoteDecode(val kind: RemoteKind, val state: FolderState = FolderState())

/**
 * decodeFolders(bytes, REMOTE_VERSION) with the remote's own readings:
 *  - Absent is not "an empty state": for the REMOTE a blank body means nobody has written yet (like
 *    a 404); reading it as "everyone deleted everything" would wipe every device. Only a readable
 *    file WITH a "v" is evidence of that (`{"v":1}` is Present with an empty state).
 *  - A body decodeFolders had to REPAIR is Unreadable: merging it would silently cut the file down,
 *    and the cycle would upload the cut-down copy. This is what makes desktop refuse a file any
 *    writer got slightly wrong, and why everything this app writes is already sanitised.
 *  - The size is judged first: a huge all-whitespace body is Unreadable, not Absent.
 */
fun decodeRemote(bytes: ByteArray): RemoteDecode {
    if (bytes.size > MAX_FOLDER_FILE_BYTES) return RemoteDecode(RemoteKind.Unreadable)
    if (isAsciiBlank(bytes, bomLength(bytes))) return RemoteDecode(RemoteKind.Absent)
    val d = decodeFolders(bytes, REMOTE_VERSION)
    return when (d.status) {
        DecodeStatus.Ok -> if (d.repaired) RemoteDecode(RemoteKind.Unreadable) else RemoteDecode(RemoteKind.Present, d.state)
        DecodeStatus.FutureVersion -> RemoteDecode(RemoteKind.Newer)
        DecodeStatus.Corrupt -> RemoteDecode(RemoteKind.Unreadable)
    }
}

/** 3 when [bytes] start with a UTF-8 byte-order mark, else 0. */
private fun bomLength(bytes: ByteArray): Int =
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0

// ---------------------------------------------------------------------------------------------
// The three-way merge (desktop spec "Merge model", Steps 1 to 6)
//
//   base   = the state at this device's last successful sync, or the EMPTY state when the base is
//            not trusted;
//   local  = this device's state now;   remote = the shared file's state now.
//
// A folder is its id, a filed chat is its token. There is no timestamp, counter or tombstone: a
// deletion is known only because the base has a folder and one side does not, so an untrusted
// (empty) base makes the merge a union and a deleted folder may come back: the safe direction. No
// wall clock is read: "the merging device wins" means the one that syncs second, as the server's
// If-Match sequence ordered it.

enum class MergePrefer {
    /** The base is TRUSTED: on a real conflict the merging device wins. */
    Local,
    /** The base is NOT trusted (the caller passes the empty state): the remote wins. */
    Remote,
}

enum class MergeStatus {
    /** [MergeResult.merged] is the state to apply and, if it differs, upload. */
    Ok,
    /** The result would hold more than MAX_FOLDERS folders: refused. */
    TooManyFolders,
    /** The result would hold more than MAX_ASSIGNMENTS filed chats: refused. */
    TooManyAssignments,
    /** The result failed the sanitise self-check (an input that was not sanitised, or a bug here): refused. */
    Invalid,
}

/**
 * [merged] is meaningful for Ok only; a refused merge carries the empty state and zero counts.
 * [foldersPaired]: local folders matched to a remote folder by name (Step 1). [foldersDropped]:
 * folders in base AND local that the remote lacks (Step 2), i.e. what the REMOTE takes away from
 * this device; a folder this device deleted itself is never counted, so it cannot brake on its own
 * deletion.
 */
data class MergeResult(
    val status: MergeStatus = MergeStatus.Ok,
    val merged: FolderState = FolderState(),
    val foldersPaired: Int = 0,
    val foldersDropped: Int = 0,
)

/** A name with every code point simply case-folded (desktop's QString::toCaseFolded: ß stays, ẞ becomes ß, no Turkic rules). */
internal fun caseFoldedName(s: String): String {
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = codePointAt(s, i)
        i += charCount(cp)
        appendCodePoint(out, caseFold(cp))
    }
    return out.toString()
}

private fun indexById(s: FolderState): Map<String, Int> {
    val idx = HashMap<String, Int>(s.folders.size * 2)
    for (i in s.folders.indices) idx[s.folders[i].id] = i
    return idx
}

private fun idsInOrder(s: FolderState): List<String> = s.folders.map { it.id }

/** The folder [token] is filed under in [s], or EMPTY when unfiled (an orphan is unfiled); [ids] is s's own index. */
private fun filingIn(s: FolderState, ids: Map<String, Int>, token: String): String {
    val id = s.assign[token] ?: return ""
    return if (id in ids) id else ""
}

/**
 * Step 3's rule for one field. [hasBase]: the base has an opinion (for a folder's own fields, the
 * folder is in the base; for otherCollapsed, the base is trusted).
 */
private fun <T> pick3(hasBase: Boolean, b: T, l: T, r: T, prefer: MergePrefer): T {
    if (l == r) return l
    if (hasBase && l == b) return r   // only the remote changed it
    if (hasBase && r == b) return l   // only the local changed it
    return if (prefer == MergePrefer.Local) l else r   // both changed it, or no base to judge by
}

/**
 * Step 4's "effective" value of one side. A side that has the chat unfiled while the base's folder is
 * gone from that side did not unfile it: deleting the folder did. That counts as "unchanged".
 */
private fun effectiveFiling(b: String, side: String, sideIds: Map<String, Int>): String =
    if (b.isNotEmpty() && side.isEmpty() && b !in sideIds) b else side

/**
 * Step 5: place sideOrder[at] immediately before the nearest FOLLOWING folder of the same side's
 * list that is already placed; append it when none follows.
 */
private fun placeBeforeFollowing(out: MutableList<String>, placed: MutableSet<String>, sideOrder: List<String>, at: Int) {
    val id = sideOrder[at]
    for (j in at + 1 until sideOrder.size) {
        if (sideOrder[j] in placed) {
            out.add(out.indexOf(sideOrder[j]), id)
            placed.add(id)
            return
        }
    }
    out.add(id)
    placed.add(id)
}

/**
 * The three-way merge. Every input must be SANITISED. `prefer == Local` is how the caller says the
 * base is trusted.
 *
 * The base/prefer contract: prefer Remote means the base is untrusted and MUST be the empty state; a
 * non-empty base with prefer Remote is outside the contract and idempotence does not hold there.
 * chooseBase hands over the empty state whenever it distrusts the base, so the sync never makes that
 * call; a check does, on purpose.
 *
 * The result is never truncated to fit: over a cap it is REFUSED, because sanitiseFolderState would
 * silently drop the folders past 50 together with their chats.
 *
 * [collator] is only for Step 5's "is this list in name order" test and must order names as
 * createFolder and renameFolder do (null: the device's language, built only when that test runs).
 */
fun mergeFolderStates(
    base: FolderState,
    local: FolderState,
    remote: FolderState,
    prefer: MergePrefer,
    collator: NameCollator? = null,
): MergeResult {
    val preferLocal = prefer == MergePrefer.Local
    var paired = 0
    var dropped = 0

    val baseIds = indexById(base)
    val remoteIds = indexById(remote)

    // Step 1, pairing. A folder is NEW on a side when the base lacks its id. Walking the local new
    // folders that the remote lacks by id, in local order, each is paired with the unpaired remote
    // new folder (that the ORIGINAL local lacks by id) whose case-folded name is equal and whose id
    // is the SMALLEST (plain string comparison), not the first in remote order: Step 5 can put the
    // remote-new folders in another relative order in the result, and a pairing keyed on position
    // would pair twins differently when that result is merged again. The paired local folder is
    // replaced IN PLACE by a copy of the remote one (remote id, name and collapsed flag), and its
    // chats are re-pointed to the remote id. `loc` is that re-pointed local state (L').
    val loc: FolderState
    run {
        val localIdsBefore = indexById(local)
        val remoteNew = ArrayList<Int>()            // indices into remote.folders
        val remoteNewFolded = ArrayList<String>()
        for (i in remote.folders.indices) {
            val f = remote.folders[i]
            if (f.id !in baseIds && f.id !in localIdsBefore) {
                remoteNew.add(i)
                remoteNewFolded.add(caseFoldedName(f.name))
            }
        }
        val taken = BooleanArray(remoteNew.size)
        val repoint = HashMap<String, String>()     // old local id -> remote id
        val folders = ArrayList<Folder>(local.folders)
        for (idx in folders.indices) {
            val f = folders[idx]
            if (f.id in baseIds || f.id in remoteIds) continue
            val folded = caseFoldedName(f.name)
            var best = -1
            for (k in remoteNew.indices) {
                if (taken[k] || remoteNewFolded[k] != folded) continue
                if (best < 0 || remote.folders[remoteNew[k]].id < remote.folders[remoteNew[best]].id) best = k
            }
            if (best < 0) continue
            taken[best] = true
            val match = remote.folders[remoteNew[best]]
            repoint[f.id] = match.id
            folders[idx] = match
            paired++
        }
        val assign: Map<String, String> = if (repoint.isEmpty()) {
            local.assign
        } else {
            val a = LinkedHashMap<String, String>(local.assign.size * 2)
            for ((token, id) in local.assign) a[token] = repoint[id] ?: id
            a
        }
        loc = FolderState(folders, local.otherCollapsed, assign)
    }
    val localIds = indexById(loc)

    // Step 2, which folders survive. In base and on both sides: survives. In base and on ONE side:
    // the other side deleted it, and a deletion beats an edit, so it is dropped (counted only when
    // it is the REMOTE that lacks it). In base only: dropped. Not in base (new on either or both
    // sides): survives.
    val survivors = HashSet<String>()
    for (f in base.folders) {
        val inLocal = f.id in localIds
        val inRemote = f.id in remoteIds
        if (inLocal && inRemote) survivors.add(f.id)
        else if (inLocal) dropped++
    }
    for (f in loc.folders) if (f.id !in baseIds) survivors.add(f.id)
    for (f in remote.folders) if (f.id !in baseIds) survivors.add(f.id)

    // Step 3, fields. A survivor on both sides takes pick3 per field; a survivor on one side only is
    // taken as that side has it.
    fun mergedFolder(id: String): Folder {
        val li = localIds[id]
        val ri = remoteIds[id]
        if (ri == null) return loc.folders[li!!]
        if (li == null) return remote.folders[ri]
        val l = loc.folders[li]
        val r = remote.folders[ri]
        val bi = baseIds[id]
        val hasBase = bi != null
        val b = if (bi != null) base.folders[bi] else Folder("", "", false)
        return Folder(
            id,
            pick3(hasBase, b.name, l.name, r.name, prefer),
            pick3(hasBase, b.collapsed, l.collapsed, r.collapsed, prefer),
        )
    }

    val otherCollapsed = pick3(preferLocal, base.otherCollapsed, loc.otherCollapsed, remote.otherCollapsed, prefer)

    // Step 4, filing, per token in the union of the keys of base, local and remote. An untrusted
    // (empty) base has every chat unfiled, which makes these rules a union.
    val tokens = HashSet<String>(base.assign.size + loc.assign.size + remote.assign.size)
    tokens.addAll(base.assign.keys)
    tokens.addAll(loc.assign.keys)
    tokens.addAll(remote.assign.keys)
    val assign = LinkedHashMap<String, String>(tokens.size * 2)
    for (token in tokens) {
        val b = filingIn(base, baseIds, token)
        val l = filingIn(loc, localIds, token)
        val r = filingIn(remote, remoteIds, token)
        var chosen: String
        var conflict = false
        if (l == r) {
            chosen = l
        } else if (effectiveFiling(b, l, localIds) == b) {
            chosen = r   // the local did not change it
        } else if (effectiveFiling(b, r, remoteIds) == b) {
            chosen = l   // the remote did not change it
        } else {
            conflict = true
            chosen = if (preferLocal) l else r
        }
        // A chosen FOLDER that did not survive: in a conflict, the other side's folder if that
        // survived; otherwise the chat is unfiled, exactly as deleteFolder returns a deleted folder's
        // chats to Other. ("Unfiled" is not a folder: when it is chosen, it stands.)
        if (chosen.isNotEmpty() && chosen !in survivors) {
            val other = if (preferLocal) r else l
            chosen = if (conflict && other.isNotEmpty() && other in survivors) other else ""
        }
        if (chosen.isNotEmpty()) assign[token] = chosen
    }

    // Step 5, order. `common` = the survivors in base (by Step 2 they are on both sides). Their
    // order: the shared one if both sides agree; else the side that changed it; else prefer's,
    // except that a local order which only kept the list in name order does not count as a change by
    // hand (below). Then the new survivors: first every one the remote has (remote-only, pairs, and
    // ids new on both sides), in remote order, each placed by the REMOTE list; then the local-only
    // ones, in local order, each placed by the (paired) LOCAL list.
    val baseOrder = idsInOrder(base)
    val localOrder = idsInOrder(loc)
    val remoteOrder = idsInOrder(remote)
    fun commonIn(order: List<String>): List<String> = order.filter { it in baseIds && it in survivors }
    val relB = commonIn(baseOrder)
    val relL = commonIn(localOrder)
    val relR = commonIn(remoteOrder)
    val chosenOrder: List<String> = if (relL == relR) {
        relL
    } else if (relL == relB) {
        relR
    } else if (relR == relB) {
        relL
    } else if (!preferLocal) {
        relR
    } else {
        // Both sides changed it. In a list that is in name order a rename moves the folder to its new
        // place (FolderOrder.kt), so a base and a local list that are both in name order mean the
        // local order moved only to stay in name order: nothing was arranged here by hand, so the
        // remote's order is kept and the local renames are applied in it (Step 3). Judged on the
        // ORIGINAL base and local lists alone, so merging the result again decides the same way.
        // Any other local change wins.
        val c = collator ?: folderNameCollator()
        val localKeptNameOrder = inNameOrder(base.folders, c) && inNameOrder(local.folders, c)
        if (localKeptNameOrder) relR else relL
    }
    val order = ArrayList<String>(chosenOrder)
    val placed = HashSet<String>(order)
    for (i in remoteOrder.indices) {
        if (remoteOrder[i] !in baseIds) placeBeforeFollowing(order, placed, remoteOrder, i)
    }
    for (i in localOrder.indices) {
        val id = localOrder[i]
        if (id !in baseIds && id !in remoteIds) placeBeforeFollowing(order, placed, localOrder, i)
    }
    val folders = ArrayList<Folder>(order.size)
    for (id in order) folders.add(mergedFolder(id))
    val merged = FolderState(folders, otherCollapsed, assign)

    // Step 6, caps and self-check. Never truncate: over a cap, or a result sanitiseFolderState would
    // change, is refused with nothing to apply.
    if (merged.folders.size > MAX_FOLDERS) return MergeResult(MergeStatus.TooManyFolders)
    if (merged.assign.size > MAX_ASSIGNMENTS) return MergeResult(MergeStatus.TooManyAssignments)
    if (sanitiseFolderState(merged).changed) return MergeResult(MergeStatus.Invalid)
    return MergeResult(MergeStatus.Ok, merged, paired, dropped)
}

// ---------------------------------------------------------------------------------------------
// The sidecar (<scopeKey>.sync.json)
//
//   { "v": 1,
//     "localSha": "<40 lowercase hex, or the empty string when no local file existed>",
//     "base": "<the text encodeFolders() writes for the base state, as a JSON string>" }
//
// `base` holds the local file's own codec output, read back by decodeFolders: there is no second
// codec and the caps are the same. The sidecar is DERIVED data (the state at this device's last
// successful sync and a fingerprint of the local file as this app last wrote it), so every reading
// that is not a clean one only costs trust: the base is thrown away and the merge is a union. It is
// per device: desktop never reads this one.

enum class SidecarStatus {
    /** Readable, a version this build knows, nothing repaired; [SidecarDecode.sidecar] is set. */
    Ok,
    /** There is no sidecar file. Set by the file layer, NEVER by decodeSidecar. */
    Absent,
    /** There, but not something to believe: over the cap, not JSON, a wrong shape, a sha that is not a sha, no usable base, or a base that decodes only after repair. */
    Unreadable,
    /** "v" above SIDECAR_VERSION: a newer version wrote it. */
    Newer,
}

/** [localSha]: 40 lowercase hex digits, or empty. */
data class Sidecar(val localSha: String = "", val base: FolderState = FolderState())

/** [sidecar] is meaningful only when status is Ok; otherwise it is EMPTY, so a caller that forgot to look at the status still cannot believe a base. */
data class SidecarDecode(val status: SidecarStatus, val sidecar: Sidecar = Sidecar())

private val SIDECAR_UNREADABLE = SidecarDecode(SidecarStatus.Unreadable)

/** Exactly 40 lowercase hex digits, checked by hand (a "$" pattern would accept a trailing newline). */
internal fun isSha1Hex(s: String): Boolean {
    if (s.length != 40) return false
    for (c in s) {
        if (c !in '0'..'9' && c !in 'a'..'f') return false
    }
    return true
}

/**
 * Keys in sorted order, four spaces, a final newline. [localShaHex] is written as given:
 * decodeSidecar is the gate, and a value it would refuse reads back as Unreadable, the safe way.
 */
fun encodeSidecar(base: FolderState, localShaHex: String): ByteArray {
    val out = StringBuilder()
    out.append("{\n    \"base\": ")
    appendJsonString(out, encodeFolders(base).decodeToString())
    out.append(",\n    \"localSha\": ")
    appendJsonString(out, localShaHex)
    out.append(",\n    \"v\": ").append(SIDECAR_VERSION).append("\n}\n")
    return out.toString().encodeToByteArray()
}

/**
 * Never returns Absent. In order:
 *  - over MAX_SIDECAR_BYTES, not valid UTF-8, not one JSON object, or a repeated key: Unreadable
 *    (zero bytes too: a file that exists and holds nothing is not one this app wrote);
 *  - "v" missing, not a number, not whole, not finite, or below 1: Unreadable; above
 *    SIDECAR_VERSION: Newer. The version is judged BEFORE any other key, because a newer writer may
 *    have changed what the other keys are;
 *  - "localSha" not a string, or a string that is neither empty nor 40 lowercase hex: Unreadable;
 *  - "base" not a string; a BLANK string (decodeFolders reads blank bytes as an empty state, which
 *    would hand a damaged sidecar a believed, empty base); text decodeFolders calls Corrupt or
 *    FutureVersion (the inner "v" is the local format's, so that is "no usable base", not Newer);
 *    or text it had to REPAIR: Unreadable.
 */
fun decodeSidecar(bytes: ByteArray): SidecarDecode {
    if (bytes.size > MAX_SIDECAR_BYTES) return SIDECAR_UNREADABLE
    val text = try {
        bytes.decodeToString(0, bytes.size, throwOnInvalidSequence = true)
    } catch (e: Exception) {
        return SIDECAR_UNREADABLE
    }
    val root = readFlatJsonObject(text) ?: return SIDECAR_UNREADABLE

    val v = root["v"] as? JNum ?: return SIDECAR_UNREADABLE
    val version = v.value
    if (!version.isFinite() || version != floor(version) || version < 1.0) return SIDECAR_UNREADABLE
    if (version > SIDECAR_VERSION.toDouble()) return SidecarDecode(SidecarStatus.Newer)

    val sha = (root["localSha"] as? JStr)?.value ?: return SIDECAR_UNREADABLE
    if (sha.isNotEmpty() && !isSha1Hex(sha)) return SIDECAR_UNREADABLE

    val baseText = (root["base"] as? JStr)?.value ?: return SIDECAR_UNREADABLE
    val baseBytes = baseText.encodeToByteArray()
    if (isAsciiBlank(baseBytes, bomLength(baseBytes))) return SIDECAR_UNREADABLE
    val d = decodeFolders(baseBytes)
    if (d.status != DecodeStatus.Ok || d.repaired) return SIDECAR_UNREADABLE
    return SidecarDecode(SidecarStatus.Ok, Sidecar(sha, d.state))
}

// ---------------------------------------------------------------------------------------------
// Trusting the base (desktop spec D12, worked examples E15 to E17)
//
// The base is used for one thing: telling "the other side deleted it" from "this side never had
// it". A base believed wrongly turns an empty or damaged local state into a deletion on every
// device, so every rule here leans one way: when in doubt the base is thrown away (the EMPTY state),
// the merge becomes a union, and the worst outcome is a deleted folder that comes back.

enum class BaseWhy {
    /** No rule fired. */
    Trusted,
    /** Rule 1: there is no sidecar. */
    NoSidecar,
    /** Rule 1: it is there but unreadable, or its base needed repair. */
    SidecarUnreadable,
    /** Rule 1: a newer version wrote it. */
    SidecarNewer,
    /** Rule 2: the local file did not load cleanly. */
    LocalLoadNotClean,
    /** Rule 3: the loaded bytes are not the ones this app last wrote. */
    LocalChangedByOther,
    /** Rule 4: the within-session brake. */
    EmptyLocalWithoutEdit,
}

data class BaseInputs(
    val sidecarStatus: SidecarStatus = SidecarStatus.Absent,
    /** Read only when sidecarStatus is Ok. */
    val sidecar: Sidecar = Sidecar(),
    /** How the store's attach found the file. */
    val outcome: FolderLoadOutcome = FolderLoadOutcome.Detached,
    /** Hex SHA-1 of the bytes the store loaded at attach; empty when there were none (Missing, SetAside). */
    val loadedSha: String = "",
    /** The local state is empty NOW (isEmptyFolderState). */
    val localEmpty: Boolean = false,
    /** User mutations this session. */
    val localEditCount: Int = 0,
    /** The store's generation still equals its value at attach. */
    val stateUnchangedSinceAttach: Boolean = true,
)

/**
 * [base] is the sidecar's base when [trusted], and the EMPTY state otherwise (otherCollapsed
 * included), so a caller that merges with it can never act on a base it was told not to believe. A
 * trusted base merges with MergePrefer.Local, an untrusted one with MergePrefer.Remote.
 */
data class BaseChoice(
    val trusted: Boolean = false,
    val base: FolderState = FolderState(),
    val why: BaseWhy = BaseWhy.NoSidecar,
)

/**
 * The first rule that fires decides, in this order:
 *  1. no sidecar, an unreadable or newer one;
 *  2. the local file did not load cleanly: EVERY outcome but Ok, Detached and ReadOnlyLoad
 *     included (a Missing or SetAside file is an empty state that must not read as a deletion; an
 *     empty localSha, which records "no file existed", never overrides this);
 *  3. the loaded bytes' sha is not the sidecar's localSha: someone else wrote the file after this
 *     app did (a hand edit, a restore, a truncation). A load that is Ok yet has no sha at all is an
 *     inconsistent input and matches nothing, an empty localSha included;
 *  4. the within-session brake: the local state is empty, the base is not, there was no user edit
 *     this session, and the state moved since attach. A MATCHING localSha with an UNCHANGED attach
 *     state exempts it (E15): "delete my last folder, quit, restart" ends deleted.
 * Rules 1 to 3 are judged once, at attach; rule 4 every cycle.
 */
fun chooseBase(input: BaseInputs): BaseChoice {
    fun distrust(why: BaseWhy) = BaseChoice(trusted = false, base = FolderState(), why = why)

    // Rule 1. Anything but Ok is distrusted.
    if (input.sidecarStatus != SidecarStatus.Ok) {
        return when (input.sidecarStatus) {
            SidecarStatus.Unreadable -> distrust(BaseWhy.SidecarUnreadable)
            SidecarStatus.Newer -> distrust(BaseWhy.SidecarNewer)
            else -> distrust(BaseWhy.NoSidecar)
        }
    }
    // Rule 2.
    if (input.outcome != FolderLoadOutcome.Ok) return distrust(BaseWhy.LocalLoadNotClean)
    // Rule 3.
    if (input.loadedSha.isEmpty() || input.loadedSha != input.sidecar.localSha) return distrust(BaseWhy.LocalChangedByOther)
    // Rule 4.
    if (input.localEmpty && !isEmptyFolderState(input.sidecar.base) && input.localEditCount == 0 && !input.stateUnchangedSinceAttach) {
        return distrust(BaseWhy.EmptyLocalWithoutEdit)
    }
    return BaseChoice(trusted = true, base = input.sidecar.base, why = BaseWhy.Trusted)
}
