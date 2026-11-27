package talq.core.folders

// Conversation folders: every data rule, with no I/O. Ported function by function from TalQ for
// desktop (src/core/FolderLogic.h and src/core/TalkToken.h), so a desktop test case maps onto one
// check in talq.core.smoke.FolderCheck.
//
// WHAT A FOLDER IS. A named, ordered, collapsible section of the conversation list that the user
// files conversations into. Per account; never sent to the Talk server. With the opt-in sync on, a
// copy lives in the user's OWN Nextcloud Files (/.talq-folders.json), shared with TalQ for desktop.
// A conversation is in EXACTLY ONE folder or in none ("Other"), by data shape: `assign` maps a
// token to one folder id.
//
// THE INTEROP RULE. Desktop reads a shared file that needs ANY repair as unreadable and stops
// syncing. So every state this code produces is already a fixed point of [sanitiseFolderState]:
// ids match f_ + 12 lowercase hex, names are fixed points of [normaliseFolderName], tokens match
// Talk's route pattern, and the limits below hold. The mutations keep that true; only decode
// repairs, and it says so.
//
// TRAPS ALREADY ACCOUNTED FOR
//  - A name is measured in CODE POINTS (an emoji outside the BMP is two UTF-16 units): never cap a
//    text field for it with a UTF-16 length; the normaliser does the cut.
//  - A name that is too long is TRUNCATED, not refused: the same function repairs a file read from
//    disk, where refusing would drop the folder and every chat filed in it.
//  - Which characters a name loses, and what counts as whitespace, are FIXED Unicode tables here
//    (desktop's Unicode version), not the phone's: an old phone's tables would keep a character
//    desktop strips, and desktop would then see the shared file as damaged.
//  - Marks, NFC and case folding are the platform's tables, which cannot be fixed that way: a NEW
//    name holding a character those tables do not know is refused instead (hasUnknownCodePoint).
//  - States are immutable. A mutation that is refused or changes nothing hands back the SAME state
//    instance, so "a refusal leaves the state as it was" holds by construction.
//  - Never prune filings against the room list: a chat that is not listed (left, not loaded yet, an
//    empty answer) keeps its folder and comes back to it.

const val MAX_FOLDERS = 50
/** CODE POINTS, not UTF-16 units. */
const val MAX_FOLDER_NAME_CHARS = 40
/** Filed chats per account; the mutations and decode agree. */
const val MAX_ASSIGNMENTS = 20000
/** Decode's work cap on the folders array. */
const val MAX_FOLDER_ENTRIES_EXAMINED = MAX_FOLDERS * 20
/** Decode refuses more (the largest state the app can write is about 1.2 MB). */
const val MAX_FOLDER_FILE_BYTES = 4 * 1024 * 1024
const val FOLDER_FORMAT_VERSION = 1

data class Folder(val id: String, val name: String, val collapsed: Boolean = false)

/**
 * One account's folders. [folders] in display order; [otherCollapsed] is the built-in "Other"
 * section; [assign] maps a conversation token to a folder id. Treat it as immutable: every function
 * here builds new collections. Equality is desktop's "same folder state" (order, ids, exact names,
 * collapsed flags, otherCollapsed and the filings).
 */
data class FolderState(
    val folders: List<Folder> = emptyList(),
    val otherCollapsed: Boolean = false,
    val assign: Map<String, String> = emptyMap(),
)

// ---------------------------------------------------------------------------------------------
// Roll-up: what a closed folder shows at its right edge. One input per member row.

data class FolderRollup(
    val count: Int = 0,
    val unread: Int = 0,
    val mention: Boolean = false,
    val directMention: Boolean = false,
    /** The unread messages that were muted (a number of its own beside [unread], never in it). */
    val muted: Int = 0,
)

/**
 * count + 1, unread + max(0, unreadCount) (both saturating, so an absurd count cannot wrap negative
 * and hide a badge), flags OR-ed. A direct mention IS a mention. [muted] adds up the same way, apart.
 */
fun addToRollup(r: FolderRollup, unreadCount: Int, mention: Boolean, directMention: Boolean, muted: Int = 0): FolderRollup {
    val u = maxOf(0, unreadCount)
    val m = maxOf(0, muted)
    return FolderRollup(
        count = if (r.count < Int.MAX_VALUE) r.count + 1 else r.count,
        unread = if (r.unread > Int.MAX_VALUE - u) Int.MAX_VALUE else r.unread + u,
        mention = r.mention || mention || directMention,
        directMention = r.directMention || directMention,
        muted = if (r.muted > Int.MAX_VALUE - m) Int.MAX_VALUE else r.muted + m,
    )
}

/**
 * The badge is drawn only when the sum is above zero (a mention with a zero sum has no number to
 * show). Desktop asks it for a CLOSED folder only; the Android header (FolderHeader.kt) shows it open
 * or closed, by the owner's choice. Its colour (any direct mention = danger, else any mention =
 * amber, else plain) is the row badge's rule.
 */
fun rollupShowsBadge(r: FolderRollup): Boolean = r.unread > 0

// ---------------------------------------------------------------------------------------------
// Mutation results. A mutation that returns anything but Ok leaves the state as it was.
// NotAttached and ReadOnly come from the app's store only; they are here so the UI switches over
// one enum.

enum class FolderResult {
    /** Changed. */
    Ok,
    /** Already so; nothing to do, nothing to write. */
    Unchanged,
    /** Nothing left after cleaning the name. */
    EmptyName,
    /** Another folder already has that name (ignoring case). */
    DuplicateName,
    /** MAX_FOLDERS folders, or MAX_ASSIGNMENTS filed chats, already. */
    TooMany,
    /** No folder with that id. */
    NotFound,
    /** Not a Talk conversation token. */
    BadToken,
    /**
     * (create and rename only; not desktop's) The cleaned name holds a character this device's own
     * Unicode tables do not know: see [hasUnknownCodePoint].
     */
    UnknownCharacters,
    /** (store) No account is attached. */
    NotAttached,
    /** (store) The file on disk is a newer format, or could not be set aside. */
    ReadOnly;

    val isRefusal: Boolean get() = this != Ok && this != Unchanged
}

/**
 * What a mutation did. [state] is the new state when [result] is Ok, else the very instance that
 * was passed in. [folderId] is the new folder's id after an Ok create, else empty.
 */
data class FolderChange(val result: FolderResult, val state: FolderState, val folderId: String = "")

// ---------------------------------------------------------------------------------------------
// Code points (common Kotlin has no code point API; surrogate arithmetic by hand).

internal fun toCodePoint(high: Char, low: Char): Int = ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000

/** The code point at [i]; a lone surrogate is returned as itself. */
internal fun codePointAt(s: String, i: Int): Int {
    val c = s[i]
    if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) return toCodePoint(c, s[i + 1])
    return c.code
}

internal fun charCount(cp: Int): Int = if (cp >= 0x10000) 2 else 1

internal fun appendCodePoint(out: StringBuilder, cp: Int) {
    if (cp >= 0x10000) {
        val v = cp - 0x10000
        out.append(Char(0xD800 + (v ushr 10)))
        out.append(Char(0xDC00 + (v and 0x3FF)))
    } else {
        out.append(Char(cp))
    }
}

/** The number of code points (a lone surrogate counts as one). */
internal fun codePointCount(s: String): Int {
    var n = 0
    var i = 0
    while (i < s.length) {
        i += charCount(codePointAt(s, i))
        n++
    }
    return n
}

// ---------------------------------------------------------------------------------------------
// Names

/**
 * Desktop's whitespace set: U+0009..000D, U+0020, U+0085, U+00A0 and every Zs, Zl and Zp code point
 * (Unicode 16). NOT U+001C..001F (controls, stripped). Not Kotlin's isWhitespace(), which differs.
 */
internal fun isFolderSpace(cp: Int): Boolean =
    cp == 0x20 || cp in 0x09..0x0D || cp == 0x85 || cp == 0xA0 || cp == 0x1680 || cp in 0x2000..0x200A ||
        cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000

// Every format character (general category Cf), as start..end pairs: 170 code points (bidi controls
// and isolates, zero-width space and joiners, word joiner and the invisible operators, BOM, soft
// hyphen, the Arabic and Syriac number signs, the tag block, ...). The set is the same in Unicode
// 15.1, 16.0 (desktop's) and 17.0; :shared:folderCheck compares it with the JDK's tables.
private val FORMAT_RANGES = intArrayOf(
    0x00AD, 0x00AD, 0x0600, 0x0605, 0x061C, 0x061C, 0x06DD, 0x06DD, 0x070F, 0x070F, 0x0890, 0x0891,
    0x08E2, 0x08E2, 0x180E, 0x180E, 0x200B, 0x200F, 0x202A, 0x202E, 0x2060, 0x2064, 0x2066, 0x206F,
    0xFEFF, 0xFEFF, 0xFFF9, 0xFFFB, 0x110BD, 0x110BD, 0x110CD, 0x110CD, 0x13430, 0x1343F,
    0x1BCA0, 0x1BCA3, 0x1D173, 0x1D17A, 0xE0001, 0xE0001, 0xE0020, 0xE007F,
)

internal fun isFormatCodePoint(cp: Int): Boolean {
    var k = 0
    while (k < FORMAT_RANGES.size) {
        if (cp in FORMAT_RANGES[k]..FORMAT_RANGES[k + 1]) return true
        k += 2
    }
    return false
}

/**
 * Never part of a painted name: controls (Cc), format characters (Cf), surrogates (Cs), private
 * use (Co), the noncharacters, and the fillers that paint as NOTHING but are letters or marks (the
 * combining grapheme joiner, the Hangul and Khmer fillers, the Mongolian free variation selectors).
 * Emoji variation selectors U+FE00..FE0F are kept: they belong to the emoji before them.
 * Whitespace-class controls are mapped to a space BEFORE this test, or "a\nb" would become "ab".
 */
internal fun isStrippedCodePoint(cp: Int): Boolean {
    if (cp <= 0x1F || cp in 0x7F..0x9F) return true
    if (cp in 0xD800..0xDFFF) return true
    if (cp in 0xE000..0xF8FF || cp in 0xF0000..0xFFFFD || cp in 0x100000..0x10FFFD) return true
    if (cp in 0xFDD0..0xFDEF || (cp and 0xFFFE) == 0xFFFE) return true
    if (isFormatCodePoint(cp)) return true
    return cp == 0x034F || cp == 0x115F || cp == 0x1160 || cp == 0x17B4 || cp == 0x17B5 ||
        cp in 0x180B..0x180F || cp == 0x3164 || cp == 0xFFA0
}

/** A combining mark (Mn, Mc, Me), by the platform's tables. */
internal fun isMarkCodePoint(cp: Int): Boolean {
    val t = codePointType(cp)
    return t == CP_NON_SPACING_MARK || t == CP_COMBINING_SPACING_MARK || t == CP_ENCLOSING_MARK
}

/**
 * True when [s] holds a code point this device's own tables call unassigned (general category Cn).
 * Asked of the CLEANED name by create and rename only, which refuse it (UnknownCharacters); never by
 * sanitise or decode, which keep a name another device wrote as it is.
 *
 * Why: what counts as a combining mark, how NFC composes and reorders, and how case folds come from
 * the PLATFORM's tables (on a phone the system's, which can be years older than TalQ for desktop's),
 * and a character newer than those tables is passed through untouched. Desktop, which knows it, can
 * then clean the same name differently: a name of only a new combining mark is EMPTY there (the
 * folder and its chats are dropped), and a new composition or mark order is rewritten. Either way
 * desktop finds the shared file needs repair, reads it as unreadable and stops syncing for good.
 * Every such difference found so far involves a code point the older tables do not have, so a name
 * made only of characters this device knows is safe. Noncharacters are also Cn, but the fixed
 * stripped table has removed them before this is asked.
 */
internal fun hasUnknownCodePoint(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val cp = codePointAt(s, i)
        i += charCount(cp)
        if (codePointType(cp) == CP_UNASSIGNED) return true
    }
    return false
}

/** True when at least one code point is neither a space nor a mark. */
internal fun hasPaintingBase(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val cp = codePointAt(s, i)
        i += charCount(cp)
        if (cp != 0x20 && !isMarkCodePoint(cp)) return true
    }
    return false
}

private fun trimFolderSpaces(s: String): String = s.trim { isFolderSpace(it.code) }

/** Leading and trailing spaces dropped, every inner run of spaces made one (only U+0020 is left). */
private fun simplifySpaces(s: CharSequence): String {
    val out = StringBuilder(s.length)
    var pendingSpace = false
    for (k in 0 until s.length) {
        val c = s[k]
        if (c == ' ') {
            if (out.isNotEmpty()) pendingSpace = true
        } else {
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            out.append(c)
        }
    }
    return out.toString()
}

/** A cleaned name. [text] is empty when nothing usable was left; [shortened] says the cut removed something. */
data class NormalisedName(val text: String, val shortened: Boolean)

private val EMPTY_NAME = NormalisedName("", false)

/**
 * Clean a name for storage and painting. An EMPTY text means nothing usable was left (the caller
 * refuses).
 *   1. whitespace-class characters (\n, \t, NBSP, U+2028, ...) become a space
 *   2. controls, format, bidi, zero-width, private-use and lone-surrogate characters and the blank
 *      fillers are dropped
 *   3. trim, and collapse runs of spaces to one
 *   4. NFC, so "e" + U+0301 and the precomposed e-acute are ONE name for the duplicate check (NFC
 *      can LENGTHEN a string: U+0344 becomes two code points, so it runs before the cut)
 *   5. cut to MAX_FOLDER_NAME_CHARS CODE POINTS (a surrogate pair is never split), trim again in
 *      case the cut landed after a space
 *   6. empty if what is left has no character that paints by itself (only marks), tested on the
 *      FINAL string so the function is idempotent: a stored name must come back unchanged on load
 * Removing the zero-width joiner splits an emoji ZWJ sequence into its parts; that is the price of
 * keeping invisible characters out of a painted label.
 * [NormalisedName.shortened] is true only when step 5 cut something, and never for an empty result:
 * the UI says so, since the list would otherwise show a shorter name than was typed.
 */
fun normaliseFolderName(raw: String): NormalisedName {
    val cleaned = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        val cp: Int
        if (c.isHighSurrogate()) {
            if (i + 1 < raw.length && raw[i + 1].isLowSurrogate()) {
                cp = toCodePoint(c, raw[i + 1])
                i += 2
            } else {
                i += 1   // lone high surrogate: not a character
                continue
            }
        } else if (c.isLowSurrogate()) {
            i += 1       // lone low surrogate
            continue
        } else {
            cp = c.code
            i += 1
        }
        if (isFolderSpace(cp)) cleaned.append(' ')
        else if (!isStrippedCodePoint(cp)) appendCodePoint(cleaned, cp)
    }
    val simplified = simplifySpaces(cleaned)
    if (simplified.isEmpty()) return EMPTY_NAME
    val composed = nfc(simplified)

    var codePoints = 0
    var cut = 0
    while (cut < composed.length && codePoints < MAX_FOLDER_NAME_CHARS) {
        cut += if (composed[cut].isHighSurrogate() && cut + 1 < composed.length) 2 else 1
        codePoints++
    }
    val wasCut = cut < composed.length
    val result = trimFolderSpaces(composed.substring(0, cut))
    // The base test runs on the FINAL string: testing before the cut let 40 combining marks + "a"
    // through, the cut kept only the marks, and the name normalised to EMPTY on the next load,
    // which drops the folder and every chat filed in it.
    if (!hasPaintingBase(result)) return EMPTY_NAME
    return NormalisedName(result, wasCut)
}

/**
 * Same name ignoring case: as many code points, each pair equal after simple case folding. Not
 * locale-based, and accents count ("Été" and "ETÉ" are the same name; "Ete" is not).
 */
internal fun sameNameIgnoringCase(a: String, b: String): Boolean {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = codePointAt(a, i)
        val cb = codePointAt(b, j)
        if (ca != cb && caseFold(ca) != caseFold(cb)) return false
        i += charCount(ca)
        j += charCount(cb)
    }
    return i == a.length && j == b.length
}

// ---------------------------------------------------------------------------------------------
// Ids. "f_" + 12 lowercase hex: never starts with '!' and is never numeric, so it cannot be taken
// for a Talk tag id or a client pseudo-id ("!favorites", "!other").

fun isFolderId(id: String): Boolean {
    if (id.length != 14 || id[0] != 'f' || id[1] != '_') return false
    for (k in 2 until 14) {
        val c = id[k]
        if (c !in '0'..'9' && c !in 'a'..'f') return false
    }
    return true
}

/** "f_" + the low 48 bits of [bits] as 12 lowercase hex characters. */
fun folderIdFromBits(bits: Long): String = "f_" + (bits and 0xFFFFFFFFFFFFL).toString(16).padStart(12, '0')

/** The deterministic "derive again" step used on a clash (the splitmix64 finalizer, 64-bit wrapping). */
fun deriveFolderEntropy(x0: Long): Long {
    var x = x0.toULong()
    x += 0x9E3779B97F4A7C15uL
    x = (x xor (x shr 30)) * 0xBF58476D1CE4E5B9uL
    x = (x xor (x shr 27)) * 0x94D049BB133111EBuL
    return (x xor (x shr 31)).toLong()
}

/**
 * A fresh id from caller-supplied entropy (the store feeds random bits; a check feeds a constant).
 * On a clash with [existing] it derives again: eight mixed re-derivations, then a linear probe over
 * the 48-bit space, which always ends because [existing] is finite.
 */
fun makeFolderId(entropy: Long, existing: List<Folder>): String {
    val taken = HashSet<String>(existing.size * 2)
    for (f in existing) taken.add(f.id)
    var bits = entropy
    var attempt = 0
    while (true) {
        val id = folderIdFromBits(bits)
        if (id !in taken) return id
        bits = if (attempt < 8) deriveFolderEntropy(bits) else bits + 1
        attempt++
    }
}

/**
 * A Talk conversation token as the server's routes accept it: [a-z0-9]{4,30}, the whole string (a
 * trailing newline is refused). Checked when a chat is filed and when a file is loaded.
 */
fun isTalkToken(token: String): Boolean {
    if (token.length < 4 || token.length > 30) return false
    for (c in token) {
        if (c !in 'a'..'z' && c !in '0'..'9') return false
    }
    return true
}

// ---------------------------------------------------------------------------------------------
// Lookups

fun folderIndexOf(s: FolderState, id: String): Int {
    if (id.isEmpty()) return -1
    for (i in s.folders.indices) {
        if (s.folders[i].id == id) return i
    }
    return -1
}

/** The folder id [token] is filed under, or EMPTY when it is unfiled, also when that folder is gone (an orphan is "Other"). */
fun folderOf(s: FolderState, token: String): String {
    val id = s.assign[token] ?: return ""
    return if (folderIndexOf(s, id) >= 0) id else ""
}

/** Is [name] (already normalised) used by a folder other than [exceptId]? Ignoring case. */
fun folderNameTaken(s: FolderState, name: String, exceptId: String = ""): Boolean =
    s.folders.any { it.id != exceptId && sameNameIgnoringCase(it.name, name) }

// ---------------------------------------------------------------------------------------------
// Mutations. Each returns Ok only when it changed the state; every other outcome hands back the
// same state. The order rules are in FolderOrder.kt.

private fun unchanged(s: FolderState, r: FolderResult) = FolderChange(r, s)

/**
 * A new folder takes its place in name order when the list is in name order, else it goes at the
 * END. Checked in this order: EmptyName, TooMany, UnknownCharacters, DuplicateName (at the limit no
 * name would work, so say that first; the duplicate test folds case by the same tables that do not
 * know the character, so it comes after).
 */
fun createFolder(s: FolderState, rawName: String, entropy: Long, collator: NameCollator = folderNameCollator()): FolderChange {
    val name = normaliseFolderName(rawName).text
    if (name.isEmpty()) return unchanged(s, FolderResult.EmptyName)
    if (s.folders.size >= MAX_FOLDERS) return unchanged(s, FolderResult.TooMany)
    if (hasUnknownCodePoint(name)) return unchanged(s, FolderResult.UnknownCharacters)
    if (folderNameTaken(s, name)) return unchanged(s, FolderResult.DuplicateName)
    val f = Folder(makeFolderId(entropy, s.folders), name, false)
    val folders = if (inNameOrder(s.folders, collator)) insertInNameOrder(s.folders, f, collator) else s.folders + f
    return FolderChange(FolderResult.Ok, s.copy(folders = folders), f.id)
}

/**
 * A name that differs only by case from the folder's OWN name is allowed (a case fix, not a
 * duplicate). "Rename to what it already is" is Unchanged, checked BEFORE the duplicate test (a
 * loaded file may hold twins) and before UnknownCharacters (a name another device wrote may hold a
 * character this one does not know; keeping it writes nothing). In a list that was in name order
 * the folder moves to its new place; in a hand-arranged one it keeps its position.
 */
fun renameFolder(s: FolderState, id: String, rawName: String, collator: NameCollator = folderNameCollator()): FolderChange {
    val idx = folderIndexOf(s, id)
    if (idx < 0) return unchanged(s, FolderResult.NotFound)
    val name = normaliseFolderName(rawName).text
    if (name.isEmpty()) return unchanged(s, FolderResult.EmptyName)
    if (s.folders[idx].name == name) return unchanged(s, FolderResult.Unchanged)
    if (hasUnknownCodePoint(name)) return unchanged(s, FolderResult.UnknownCharacters)
    if (folderNameTaken(s, name, id)) return unchanged(s, FolderResult.DuplicateName)
    val keepNameOrder = inNameOrder(s.folders, collator)
    val renamed = s.folders[idx].copy(name = name)
    val folders = if (keepNameOrder) {
        val without = s.folders.toMutableList().also { it.removeAt(idx) }
        insertInNameOrder(without, renamed, collator)
    } else {
        s.folders.toMutableList().also { it[idx] = renamed }
    }
    return FolderChange(FolderResult.Ok, s.copy(folders = folders))
}

/** "Sort folders A to Z": Unchanged when the list already is in name order. Only the order changes. */
fun sortFoldersByName(s: FolderState, collator: NameCollator = folderNameCollator()): FolderChange {
    if (inNameOrder(s.folders, collator)) return unchanged(s, FolderResult.Unchanged)
    return FolderChange(FolderResult.Ok, s.copy(folders = sortedByName(s.folders, collator)))
}

/** Deleting returns the folder's chats to Other: exactly its filings are dropped. No confirmation. */
fun deleteFolder(s: FolderState, id: String): FolderChange {
    val idx = folderIndexOf(s, id)
    if (idx < 0) return unchanged(s, FolderResult.NotFound)
    val folders = s.folders.toMutableList().also { it.removeAt(idx) }
    val assign = LinkedHashMap<String, String>()
    for ((token, folderId) in s.assign) {
        if (folderId != id) assign[token] = folderId
    }
    return FolderChange(FolderResult.Ok, FolderState(folders, s.otherCollapsed, assign))
}

/**
 * [delta] -1 = up, +1 = down; any other value is clamped into the list (no overflow for
 * Int.MIN_VALUE / Int.MAX_VALUE). At the ends, or with delta 0, it is Unchanged.
 */
fun moveFolder(s: FolderState, id: String, delta: Int): FolderChange {
    val idx = folderIndexOf(s, id)
    if (idx < 0) return unchanged(s, FolderResult.NotFound)
    val wanted = idx.toLong() + delta.toLong()
    val target = wanted.coerceIn(0L, (s.folders.size - 1).toLong()).toInt()
    if (target == idx) return unchanged(s, FolderResult.Unchanged)
    val folders = s.folders.toMutableList()
    val f = folders.removeAt(idx)
    folders.add(target, f)
    return FolderChange(FolderResult.Ok, s.copy(folders = folders))
}

/**
 * File [token] under [folderId]; an EMPTY folderId unfiles it. The token is checked first (also
 * when unfiling), then the folder. Moving an already filed chat works at the cap; a NEW one there is
 * TooMany.
 */
fun assignToken(s: FolderState, token: String, folderId: String): FolderChange {
    if (!isTalkToken(token)) return unchanged(s, FolderResult.BadToken)
    if (folderId.isEmpty()) {
        if (token !in s.assign) return unchanged(s, FolderResult.Unchanged)
        val assign = LinkedHashMap(s.assign)
        assign.remove(token)
        return FolderChange(FolderResult.Ok, s.copy(assign = assign))
    }
    if (folderIndexOf(s, folderId) < 0) return unchanged(s, FolderResult.NotFound)
    val current = s.assign[token]
    if (current == folderId) return unchanged(s, FolderResult.Unchanged)
    if (current == null && s.assign.size >= MAX_ASSIGNMENTS) return unchanged(s, FolderResult.TooMany)
    val assign = LinkedHashMap(s.assign)
    assign[token] = folderId
    return FolderChange(FolderResult.Ok, s.copy(assign = assign))
}

fun setFolderCollapsed(s: FolderState, id: String, collapsed: Boolean): FolderChange {
    val idx = folderIndexOf(s, id)
    if (idx < 0) return unchanged(s, FolderResult.NotFound)
    if (s.folders[idx].collapsed == collapsed) return unchanged(s, FolderResult.Unchanged)
    val folders = s.folders.toMutableList().also { it[idx] = it[idx].copy(collapsed = collapsed) }
    return FolderChange(FolderResult.Ok, s.copy(folders = folders))
}

fun setOtherCollapsed(s: FolderState, collapsed: Boolean): FolderChange {
    if (s.otherCollapsed == collapsed) return unchanged(s, FolderResult.Unchanged)
    return FolderChange(FolderResult.Ok, s.copy(otherCollapsed = collapsed))
}

// ---------------------------------------------------------------------------------------------
// Repair after decoding (or any state of unknown origin).

/** [state] is the repaired state ([changed] false: the same instance that was passed in). */
data class Sanitised(val state: FolderState, val changed: Boolean)

/**
 *  - a folder whose id is not f_ + 12 lowercase hex is DROPPED with its filings (an id is never
 *    re-keyed: two files could then disagree about which folder a chat is in)
 *  - duplicate ids: the first VALID one is kept
 *  - names are normalised; a folder with no name left is dropped
 *  - at most MAX_FOLDERS folders survive (the first ones)
 *  - a filing is dropped when its token is not a Talk token or its folder is gone
 * Duplicate NAMES are tolerated (ids differ, nothing is lost); only create and rename refuse them.
 * The filing COUNT is capped by decode, not here.
 */
fun sanitiseFolderState(s: FolderState): Sanitised {
    var changed = false
    val kept = ArrayList<Folder>(minOf(s.folders.size, MAX_FOLDERS))
    val ids = HashSet<String>()
    for (f in s.folders) {
        if (!isFolderId(f.id) || f.id in ids) {
            changed = true
            continue
        }
        val name = normaliseFolderName(f.name).text
        if (name.isEmpty() || kept.size >= MAX_FOLDERS) {
            changed = true
            continue
        }
        var g = f
        if (name != f.name) {
            changed = true
            g = f.copy(name = name)
        }
        ids.add(f.id)
        kept.add(g)
    }
    val assign = LinkedHashMap<String, String>()
    for ((token, id) in s.assign) {
        if (!isTalkToken(token) || id !in ids) changed = true
        else assign[token] = id
    }
    if (!changed) return Sanitised(s, false)
    return Sanitised(FolderState(kept, s.otherCollapsed, assign), true)
}

// ---------------------------------------------------------------------------------------------
// Account scope: folders are per Nextcloud account, so the local file name comes from the server
// and the CANONICAL user id (not the login name). Desktop derives it the same way; the key only
// names local files, but the same rule keeps its known answers.

private fun trimSpaces(s: String): String = s.trim { isFolderSpace(it.code) }

/**
 * Trim, drop trailing slashes, lowercase the scheme and the HOST only (userinfo and the path keep
 * their case: a path can be case-sensitive). Empty when there is no host. Not TalkClient's
 * normaliser, which keeps the host's case.
 */
fun normaliseServerUrl(raw: String): String {
    val trimmed = trimSpaces(raw)
    var scheme = ""
    var rest = trimmed
    val sep = trimmed.indexOf("://")
    if (sep >= 0) {
        scheme = trimmed.substring(0, sep).lowercase()
        rest = trimmed.substring(sep + 3)
    }
    rest = rest.trimEnd('/')
    var authorityEnd = rest.length
    for (i in rest.indices) {
        val c = rest[i]
        if (c == '/' || c == '?' || c == '#') {
            authorityEnd = i
            break
        }
    }
    val authority = rest.substring(0, authorityEnd)
    val tail = rest.substring(authorityEnd)
    val at = authority.lastIndexOf('@')
    val hostPart = authority.substring(at + 1)   // at == -1: the whole authority
    if (hostPart.isEmpty()) return ""
    val rebuilt = authority.substring(0, at + 1) + hostPart.lowercase()
    return (if (sep >= 0) "$scheme://" else "") + rebuilt + tail
}

/**
 * The first 16 lowercase hex characters of SHA-1 over normalisedUrl + "\n" + userId (UTF-8). EMPTY
 * when either is empty: the store refuses an empty key.
 */
fun accountScopeKey(serverUrl: String, userId: String): String {
    val url = normaliseServerUrl(serverUrl)
    val uid = trimSpaces(userId)
    if (url.isEmpty() || uid.isEmpty()) return ""
    return sha1Hex("$url\n$uid".encodeToByteArray()).substring(0, 16)
}

/**
 * Exactly what [accountScopeKey] produces: 16 lowercase hex characters. The key becomes a file name,
 * so this refuses a path separator, "..", a drive colon and an empty string.
 */
fun isScopeKey(key: String): Boolean {
    if (key.length != 16) return false
    for (c in key) {
        if (c !in '0'..'9' && c !in 'a'..'f') return false
    }
    return true
}
