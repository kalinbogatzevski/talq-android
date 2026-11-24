package talq.core.folders

import kotlin.math.floor

// The folders file, ported from TalQ for desktop (src/core/FolderLogic.h, "JSON codec"). The same
// bytes are the local file and the shared /.talq-folders.json in the user's own Files:
//
//   { "v": 1, "otherCollapsed": false,
//     "folders": [ { "id": "f_0a1b2c3d4e5f", "name": "HQ", "collapsed": false } ],
//     "assign":  { "chat0001": "f_0a1b2c3d4e5f" } }
//
// ENCODE writes what desktop's writer writes, byte for byte: keys sorted in every object, four
// spaces per level, `"key": value`, LF line ends and a final LF, folders in display order, assign
// sorted by token. (Desktop decides whether to upload by comparing STATES, so equal bytes are not
// required, but they cost nothing.)
//
// DECODE is at least as strict as desktop's, on purpose. It does NOT use talq.core.net's TalkJson
// or its accessors: those are lenient for the Talk server's PHP answers (numbers as strings, 0/1 as
// booleans), and a phone that read a file desktop refuses, then wrote it back, would undo desktop's
// refusal. So it has its own small JSON reader:
//   - RFC 8259 syntax only: no comments, no trailing commas, no unquoted keys, only the eight
//     standard escapes plus \uXXXX, no raw control characters inside strings, no leading zeros;
//     the text must be valid UTF-8; at most 1024 nested containers (desktop's limit);
//   - a number that overflows to infinity or underflows to zero is refused (desktop's reader
//     refuses both);
//   - a key repeated in the root, in a folder entry or in assign is refused (which of the two
//     desktop keeps is not pinned down; the two writers never repeat one);
//   - only the root members it needs are kept, and at most 1000 folder entries and 20001 filings:
//     a hostile 4 MiB file cannot make it allocate much more than its own size.
// Then desktop's checks, in desktop's order:
//   over 4 MiB -> Corrupt; one UTF-8 BOM is skipped; nothing but ASCII whitespace -> Ok, EMPTY
//   state (a zero-byte file is not damage); not JSON or a root that is not an object -> Corrupt;
//   "v" not a number, not finite, not whole or below 1 -> Corrupt; "v" above maxVersion ->
//   FutureVersion (EMPTY state; the caller must not write it back); "otherCollapsed" a boolean
//   else false; "folders" present but not an array, or more than 1000 entries -> Corrupt; an entry
//   that is not an object or has a non-string id or name -> dropped, repaired; "collapsed" a boolean
//   else false; "assign" present but not an object, or more than 20000 filings -> Corrupt; a
//   non-string value -> dropped, repaired; unknown keys are ignored (and not written back); then
//   sanitiseFolderState, which sets repaired when it changed anything.
// Every outcome but Ok carries an EMPTY state.

enum class DecodeStatus {
    /** The state is valid (possibly empty); [DecodeResult.repaired] says whether entries were dropped or cleaned. */
    Ok,
    /** Not a file this version can read; the state is EMPTY; the store sets the bytes aside. */
    Corrupt,
    /** "v" is newer than this build; the state is EMPTY; the caller must NOT write it back. */
    FutureVersion,
}

/** How the store found the file on disk (an input of the sync's base-trust rule). */
enum class FolderLoadOutcome {
    /** No account attached. */
    Detached,
    /** The file was read, valid, nothing repaired. */
    Ok,
    /** No file. */
    Missing,
    /** Decoded, but entries were dropped or cleaned. */
    Repaired,
    /** Corrupt, and renamed away. */
    SetAside,
    /** A newer format, or a corrupt file that could not be set aside: the store is read-only. */
    ReadOnlyLoad,
}

data class DecodeResult(val status: DecodeStatus, val state: FolderState, val repaired: Boolean)

private val CORRUPT = DecodeResult(DecodeStatus.Corrupt, FolderState(), false)

private const val HEX_DIGITS = "0123456789abcdef"

/**
 * Deterministic: folders in display order, assign sorted by token. [version] is the "v" written:
 * the local file takes the default; the sync passes its own constant for the shared file.
 */
fun encodeFolders(s: FolderState, version: Int = FOLDER_FORMAT_VERSION): ByteArray {
    val out = StringBuilder(256 + s.folders.size * 112 + s.assign.size * 48)
    out.append("{\n")
    out.append("    \"assign\": ")
    val tokens = s.assign.keys.sorted()
    if (tokens.isEmpty()) {
        out.append("{\n    }")
    } else {
        out.append("{\n")
        for (i in tokens.indices) {
            val t = tokens[i]
            out.append("        ")
            appendJsonString(out, t)
            out.append(": ")
            appendJsonString(out, s.assign.getValue(t))
            out.append(if (i < tokens.size - 1) ",\n" else "\n")
        }
        out.append("    }")
    }
    out.append(",\n")
    out.append("    \"folders\": ")
    if (s.folders.isEmpty()) {
        out.append("[\n    ]")
    } else {
        out.append("[\n")
        for (i in s.folders.indices) {
            val f = s.folders[i]
            out.append("        {\n")
            out.append("            \"collapsed\": ").append(if (f.collapsed) "true" else "false").append(",\n")
            out.append("            \"id\": ")
            appendJsonString(out, f.id)
            out.append(",\n")
            out.append("            \"name\": ")
            appendJsonString(out, f.name)
            out.append("\n")
            out.append("        }")
            out.append(if (i < s.folders.size - 1) ",\n" else "\n")
        }
        out.append("    ]")
    }
    out.append(",\n")
    out.append("    \"otherCollapsed\": ").append(if (s.otherCollapsed) "true" else "false").append(",\n")
    out.append("    \"v\": ").append(version).append("\n")
    out.append("}\n")
    return out.toString().encodeToByteArray()
}

// As desktop's writer: '"', '\' and the controls below U+0020 are escaped (five by name, the rest
// as \u00xx), a lone surrogate as \uxxxx (lowercase hex), everything else raw (UTF-8, '/' included).
// Also the sync sidecar's writer (FolderSyncLogic.kt).
internal fun appendJsonString(out: StringBuilder, v: String) {
    out.append('"')
    var i = 0
    while (i < v.length) {
        val c = v[i]
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\b' -> out.append("\\b")
            c == '\u000C' -> out.append("\\f")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c.code < 0x20 -> {
                out.append("\\u00")
                out.append(HEX_DIGITS[c.code shr 4]).append(HEX_DIGITS[c.code and 0xF])
            }
            c.isHighSurrogate() && i + 1 < v.length && v[i + 1].isLowSurrogate() -> {
                out.append(c).append(v[i + 1])
                i++
            }
            c.isSurrogate() -> {
                val u = c.code
                out.append("\\u")
                out.append(HEX_DIGITS[(u shr 12) and 0xF]).append(HEX_DIGITS[(u shr 8) and 0xF])
                out.append(HEX_DIGITS[(u shr 4) and 0xF]).append(HEX_DIGITS[u and 0xF])
            }
            else -> out.append(c)
        }
        i++
    }
    out.append('"')
}

/**
 * Never throws; its work is bounded by MAX_FOLDER_FILE_BYTES and the entry caps. [maxVersion] is the
 * newest "v" the caller can read (the local file: the default; the sync: its remote version). The
 * "v below 1" rule does not depend on it.
 */
fun decodeFolders(bytes: ByteArray, maxVersion: Int = FOLDER_FORMAT_VERSION): DecodeResult {
    if (bytes.size > MAX_FOLDER_FILE_BYTES) return CORRUPT
    // A hand edit in an old editor prepends a UTF-8 byte-order mark; that is not damage.
    val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
    if (isAsciiBlank(bytes, start)) return DecodeResult(DecodeStatus.Ok, FolderState(), false)

    val text = try {
        bytes.decodeToString(start, bytes.size, throwOnInvalidSequence = true)
    } catch (e: Exception) {
        return CORRUPT
    }
    val root = try {
        StrictReader(text).readRoot()
    } catch (e: JsonRefused) {
        return CORRUPT
    }

    val v = root.v as? JNum ?: return CORRUPT
    val version = v.value
    if (!version.isFinite() || version != floor(version) || version < 1.0) return CORRUPT
    if (version > maxVersion.toDouble()) return DecodeResult(DecodeStatus.FutureVersion, FolderState(), false)

    val otherCollapsed = (root.otherCollapsed as? JBool)?.value ?: false
    var repaired = false

    val folders = ArrayList<Folder>()
    when (val f = root.folders) {
        null -> {}
        FoldersSlot.NotArray -> return CORRUPT
        is FoldersSlot.Entries -> {
            if (f.count > MAX_FOLDER_ENTRIES_EXAMINED) return CORRUPT
            for (e in f.entries) {
                val id = e?.id as? JStr
                val name = e?.name as? JStr
                if (e == null || id == null || name == null) {
                    repaired = true
                    continue
                }
                folders.add(Folder(id.value, name.value, (e.collapsed as? JBool)?.value ?: false))
            }
        }
    }

    val assign = LinkedHashMap<String, String>()
    when (val a = root.assign) {
        null -> {}
        AssignSlot.NotObject -> return CORRUPT
        is AssignSlot.Entries -> {
            if (a.overCap) return CORRUPT
            for ((token, value) in a.map) {
                if (value is JStr) assign[token] = value.value
                else repaired = true
            }
        }
    }

    val sanitised = sanitiseFolderState(FolderState(folders, otherCollapsed, assign))
    return DecodeResult(DecodeStatus.Ok, sanitised.state, repaired || sanitised.changed)
}

// Space, \t, \n, \v, \f, \r: desktop's "is this file blank" test (wider than JSON's whitespace).
// Also used by decodeRemote and decodeSidecar (FolderSyncLogic.kt).
internal fun isAsciiBlank(bytes: ByteArray, from: Int): Boolean {
    for (k in from until bytes.size) {
        val b = bytes[k].toInt()
        if (b != 0x20 && (b < 0x09 || b > 0x0D)) return false
    }
    return true
}

// ---------------------------------------------------------------------------------------------
// The reader. It keeps only what decode reads; everything else is checked for syntax and dropped.

private class JsonRefused : Exception("not a folders file")

// One JSON value as the reader keeps it. Internal (not private) for readFlatJsonObject's callers.
internal sealed interface JVal
internal object JNull : JVal
/** An object or an array: checked for syntax, its content dropped. */
internal object JContainer : JVal
internal class JBool(val value: Boolean) : JVal
internal class JNum(val value: Double) : JVal
internal class JStr(val value: String) : JVal

/**
 * The members of [text] when it is exactly one JSON object (RFC 8259, the same strict reader as
 * decodeFolders, nesting capped at 1024), each value kept as a scalar ([JContainer] for an object
 * or array). Null when it is anything else, or when a key repeats. For the sync sidecar, whose
 * reading must be as strict as the folders file's.
 */
internal fun readFlatJsonObject(text: String): Map<String, JVal>? = try {
    StrictReader(text).readFlatObject()
} catch (e: JsonRefused) {
    null
}

/** One element of "folders" that was an object; null fields were absent. */
private class FolderEntry(val id: JVal?, val name: JVal?, val collapsed: JVal?)

private sealed interface FoldersSlot {
    object NotArray : FoldersSlot
    /** The first MAX_FOLDER_ENTRIES_EXAMINED elements (null = not an object); [count] counts them all. */
    class Entries(val entries: List<FolderEntry?>, val count: Int) : FoldersSlot
}

private sealed interface AssignSlot {
    object NotObject : AssignSlot
    /** [overCap]: more than MAX_ASSIGNMENTS keys (then [map] is not kept). */
    class Entries(val map: Map<String, JVal>, val overCap: Boolean) : AssignSlot
}

private class RootParse {
    var v: JVal? = null
    var otherCollapsed: JVal? = null
    var folders: FoldersSlot? = null
    var assign: AssignSlot? = null
}

private const val MAX_NESTING = 1024

private class StrictReader(private val s: String) {
    private var pos = 0

    private fun fail(): Nothing = throw JsonRefused()

    private fun ws() {
        while (pos < s.length) {
            val c = s[pos]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else return
        }
    }

    private fun peek(): Char {
        if (pos >= s.length) fail()
        return s[pos]
    }

    private fun expect(c: Char) {
        if (pos >= s.length || s[pos] != c) fail()
        pos++
    }

    /** After a member or an element: true at the container's end, false after a comma. */
    private fun endOrComma(end: Char): Boolean {
        ws()
        val c = peek()
        pos++
        if (c == end) return true
        if (c != ',') fail()
        return false
    }

    /** Reads `"key":` and leaves the position at the value. */
    private fun readKey(): String {
        ws()
        val key = readString()
        ws()
        expect(':')
        ws()
        return key
    }

    fun readRoot(): RootParse {
        ws()
        expect('{')
        val root = RootParse()
        ws()
        if (peek() == '}') {
            pos++
        } else {
            while (true) {
                when (readKey()) {
                    "v" -> {
                        if (root.v != null) fail()
                        root.v = readScalar(2)
                    }
                    "otherCollapsed" -> {
                        if (root.otherCollapsed != null) fail()
                        root.otherCollapsed = readScalar(2)
                    }
                    "folders" -> {
                        if (root.folders != null) fail()
                        root.folders = if (peek() == '[') readFolders(2) else {
                            skipValue(2)
                            FoldersSlot.NotArray
                        }
                    }
                    "assign" -> {
                        if (root.assign != null) fail()
                        root.assign = if (peek() == '{') readAssign(2) else {
                            skipValue(2)
                            AssignSlot.NotObject
                        }
                    }
                    else -> skipValue(2)
                }
                if (endOrComma('}')) break
            }
        }
        ws()
        if (pos != s.length) fail()
        return root
    }

    /** One object of scalar members (containers kept as JContainer); a repeated key is refused. */
    fun readFlatObject(): Map<String, JVal> {
        ws()
        expect('{')
        val members = LinkedHashMap<String, JVal>()
        ws()
        if (peek() == '}') {
            pos++
        } else {
            while (true) {
                val key = readKey()
                if (key in members) fail()
                members[key] = readScalar(2)
                if (endOrComma('}')) break
            }
        }
        ws()
        if (pos != s.length) fail()
        return members
    }

    private fun readFolders(depth: Int): FoldersSlot.Entries {
        if (depth > MAX_NESTING) fail()
        expect('[')
        val entries = ArrayList<FolderEntry?>()
        var count = 0
        ws()
        if (peek() == ']') {
            pos++
            return FoldersSlot.Entries(entries, 0)
        }
        while (true) {
            ws()
            if (count < MAX_FOLDER_ENTRIES_EXAMINED) {
                if (peek() == '{') {
                    entries.add(readFolderEntry(depth + 1))
                } else {
                    skipValue(depth + 1)
                    entries.add(null)
                }
            } else {
                skipValue(depth + 1)
            }
            count++
            if (endOrComma(']')) break
        }
        return FoldersSlot.Entries(entries, count)
    }

    private fun readFolderEntry(depth: Int): FolderEntry {
        if (depth > MAX_NESTING) fail()
        expect('{')
        var id: JVal? = null
        var name: JVal? = null
        var collapsed: JVal? = null
        ws()
        if (peek() == '}') {
            pos++
            return FolderEntry(null, null, null)
        }
        while (true) {
            when (readKey()) {
                "id" -> {
                    if (id != null) fail()
                    id = readScalar(depth + 1)
                }
                "name" -> {
                    if (name != null) fail()
                    name = readScalar(depth + 1)
                }
                "collapsed" -> {
                    if (collapsed != null) fail()
                    collapsed = readScalar(depth + 1)
                }
                else -> skipValue(depth + 1)
            }
            if (endOrComma('}')) break
        }
        return FolderEntry(id, name, collapsed)
    }

    private fun readAssign(depth: Int): AssignSlot.Entries {
        if (depth > MAX_NESTING) fail()
        expect('{')
        val map = LinkedHashMap<String, JVal>()
        var overCap = false
        ws()
        if (peek() == '}') {
            pos++
            return AssignSlot.Entries(map, false)
        }
        while (true) {
            val key = readKey()
            if (overCap) {
                skipValue(depth + 1)
            } else {
                if (key in map) fail()
                map[key] = readScalar(depth + 1)
                if (map.size > MAX_ASSIGNMENTS) {
                    overCap = true
                    map.clear()
                }
            }
            if (endOrComma('}')) break
        }
        return AssignSlot.Entries(map, overCap)
    }

    /** Any value; a container is checked and dropped. */
    private fun readScalar(depth: Int): JVal = when (peek()) {
        '{', '[' -> {
            skipValue(depth)
            JContainer
        }
        '"' -> JStr(readString())
        't' -> {
            literal("true")
            JBool(true)
        }
        'f' -> {
            literal("false")
            JBool(false)
        }
        'n' -> {
            literal("null")
            JNull
        }
        else -> JNum(readNumber())
    }

    /** Checks one value of any kind and keeps nothing. [depth] is the depth it has if it is a container. */
    private fun skipValue(depth: Int) {
        when (peek()) {
            '{' -> {
                if (depth > MAX_NESTING) fail()
                pos++
                ws()
                if (peek() == '}') {
                    pos++
                    return
                }
                while (true) {
                    readKey()
                    skipValue(depth + 1)
                    if (endOrComma('}')) return
                }
            }
            '[' -> {
                if (depth > MAX_NESTING) fail()
                pos++
                ws()
                if (peek() == ']') {
                    pos++
                    return
                }
                while (true) {
                    ws()
                    skipValue(depth + 1)
                    if (endOrComma(']')) return
                }
            }
            '"' -> readString()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> readNumber()
        }
    }

    private fun literal(word: String) {
        if (!s.startsWith(word, pos)) fail()
        pos += word.length
    }

    private fun readString(): String {
        expect('"')
        val start = pos
        while (true) {   // the common case: no escapes, one substring
            if (pos >= s.length) fail()
            val c = s[pos]
            if (c == '"') {
                val out = s.substring(start, pos)
                pos++
                return out
            }
            if (c == '\\') break
            if (c < ' ') fail()
            pos++
        }
        val out = StringBuilder(pos - start + 16)
        out.appendRange(s, start, pos)
        while (true) {
            if (pos >= s.length) fail()
            val c = s[pos]
            when {
                c == '"' -> {
                    pos++
                    return out.toString()
                }
                c == '\\' -> {
                    pos++
                    out.append(readEscape())
                }
                c < ' ' -> fail()
                else -> {
                    out.append(c)
                    pos++
                }
            }
        }
    }

    private fun readEscape(): Char {
        if (pos >= s.length) fail()
        val e = s[pos++]
        return when (e) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (pos + 4 > s.length) fail()
                var v = 0
                for (k in 0 until 4) v = v * 16 + hexValue(s[pos + k])
                pos += 4
                Char(v)
            }
            else -> fail()
        }
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> fail()
    }

    /** RFC 8259: -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?; overflow and underflow refused. */
    private fun readNumber(): Double {
        val start = pos
        if (pos < s.length && s[pos] == '-') pos++
        if (pos >= s.length) fail()
        val lead = s[pos]
        if (lead == '0') {
            pos++
        } else if (lead in '1'..'9') {
            while (pos < s.length && s[pos] in '0'..'9') pos++
        } else {
            fail()
        }
        if (pos < s.length && s[pos] == '.') {
            pos++
            val digits = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos == digits) fail()
        }
        if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
            pos++
            if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
            val digits = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos == digits) fail()
        }
        val text = s.substring(start, pos)
        val d = text.toDoubleOrNull() ?: fail()
        if (!d.isFinite()) fail()
        if (d == 0.0) {
            for (c in text) {
                if (c == 'e' || c == 'E') break
                if (c in '1'..'9') fail()   // a non-zero number that underflowed
            }
        }
        return d
    }
}
