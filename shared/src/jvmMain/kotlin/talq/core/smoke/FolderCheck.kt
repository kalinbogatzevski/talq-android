package talq.core.smoke

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import talq.core.folders.*
import kotlinx.coroutines.runBlocking
import talq.core.model.NotificationLevel
import talq.core.model.Room
import kotlin.math.nextDown
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Offline checks of the folder model and its file format (talq.core.folders). No network, no env
 * file, nothing written:
 *
 *   ./gradlew :shared:folderCheck
 *
 * Each group mirrors a test of TalQ for desktop (tests/folder_logic_test.cpp,
 * tests/folder_sync_logic_test.cpp, tests/folder_sync_policy_test.cpp, tests/sidebar_folders_test.cpp,
 * and the cycle and probe parts of tests/folder_sync_controller_test.cpp against an in-memory
 * server), case for case, so the phone keeps writing files desktop reads without repairing them and
 * merges them as desktop does. Tokens are invented. Prints one [ok]/[FAIL] line per check and exits
 * non-zero on any failure.
 */
object FolderCheck {
    private var failures = 0
    private var passes = 0

    private val OK = FolderResult.Ok
    private val UNCHANGED = FolderResult.Unchanged
    private val EMPTY_NAME = FolderResult.EmptyName
    private val DUPLICATE = FolderResult.DuplicateName
    private val TOO_MANY = FolderResult.TooMany
    private val NOT_FOUND = FolderResult.NotFound
    private val BAD_TOKEN = FolderResult.BadToken
    private val UNKNOWN = FolderResult.UnknownCharacters

    private val en: NameCollator = folderNameCollator("en-US")

    @JvmStatic
    fun main(args: Array<String>) {
        val code = try {
            unicodeTables()
            testRollup()
            testNames()
            testNameProperties()
            testCaseFold()
            testIds()
            testCreate()
            testRename()
            testOrder()
            testDelete()
            testMove()
            testAssign()
            testCollapse()
            testLookup()
            testMutationFuzz()
            testSanitise()
            testScopeKey()
            testCodec()
            testCodecStrictness()
            testGoldenBytes()
            testSections()
            // folder sync
            testSyncStateHelpers()
            testDiffStates()
            testRemovesFoldersOrFilings()
            testDecodeRemote()
            testMergeWorkedExamples()
            testRenameInNameOrderIsNotAReorder()
            testMergePairing()
            testMergeCapsAndSelfCheck()
            testE24DroppedCounting()
            testSidecar()
            testSidecarHostile()
            testChooseBase()
            testE15ToE17()
            testE20Idempotence()
            testSyncPolicy()
            testSelfInconsistent412Tracker()
            testCycle()
            testProbe()
            if (failures == 0) 0 else 1
        } catch (e: Throwable) {
            println("FOLDER CHECK ABORTED: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            2
        }
        println(if (code == 0) "\nFOLDER CHECK OK ($passes checks)" else "\nFOLDER CHECK FAILED ($failures of ${passes + failures} checks failed)")
        exitProcess(code)
    }

    // -----------------------------------------------------------------------------------------
    // helpers

    private fun section(title: String) = println(title)

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) passes++ else failures++
        println("  [${if (ok) "ok" else "FAIL"}] $name${if (detail.isNotEmpty()) "  -- $detail" else ""}")
    }

    private fun checkStr(name: String, got: String, want: String) =
        check(name, got == want, if (got == want) "" else "got [${show(got)}] want [${show(want)}]")

    /** ASCII as is, everything else as \u{hex}. */
    private fun show(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            if (cp in 0x20..0x7E) out.append(Char(cp)) else out.append("\\u{").append(Integer.toHexString(cp)).append('}')
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** A string from code points (a lone surrogate included). */
    private fun S(vararg cps: Int): String {
        val sb = StringBuilder()
        for (cp in cps) appendCodePoint(sb, cp)
        return sb.toString()
    }

    private fun rep(s: String, n: Int): String = s.repeat(n)

    private fun cps(s: String): Int = s.codePointCount(0, s.length)

    private fun norm(s: String): String = normaliseFolderName(s).text

    private fun cutOf(s: String): Boolean = normaliseFolderName(s).shortened

    private fun bytes(s: String): ByteArray = s.encodeToByteArray()

    private fun names(s: FolderState): String = s.folders.joinToString("|") { it.name }

    /** A mutable holder: every mutation hands back a state, refused ones the same instance. */
    private class Box(var s: FolderState = FolderState()) {
        fun run(c: FolderChange): FolderResult {
            s = c.state
            return c.result
        }
    }

    private fun addFolder(b: Box, name: String, entropy: Long, c: NameCollator = folderNameCollator()): String {
        val r = createFolder(b.s, name, entropy, c)
        b.s = r.state
        if (r.result != OK) println("  (setup: createFolder(${show(name)}) refused: ${r.result})")
        return r.folderId
    }

    /** Empty when the state satisfies every invariant a state reachable through the mutations must. */
    private fun stateProblem(s: FolderState): String {
        if (s.folders.size > MAX_FOLDERS) return "more than MAX_FOLDERS folders"
        val ids = HashSet<String>()
        for (f in s.folders) {
            if (!isFolderId(f.id)) return "bad folder id"
            if (!ids.add(f.id)) return "duplicate folder id"
            if (f.name.isEmpty() || norm(f.name) != f.name) return "name not normalised"
        }
        for ((token, id) in s.assign) {
            if (!isTalkToken(token)) return "bad token in assign"
            if (id !in ids) return "assignment points at a missing folder"
        }
        if (sanitiseFolderState(s).changed) return "sanitise would change it"
        return ""
    }

    private fun stateIsClean(s: FolderState): Boolean = stateProblem(s).isEmpty()

    private fun fid(n: Long): String = folderIdFromBits(n)

    private class Lcg(var x: Long) {
        fun next(): Int {
            x = x * 6364136223846793005L + 1442695040888963407L
            return (x ushr 33).toInt()
        }
    }

    private val namePool: List<String> by lazy {
        listOf(
            "a", "B", "z", "1", "HQ", " ", "  ", "\t", "\n", S(0xA0), S(0x2028), S(0x301), S(0x344), S(0x308, 0x301),
            S(0xFE0F), S(0x20DD), S(0x1F600), S(0x1F3FB), S(0x1F1E6), S(0x2764), S(0x202E), S(0x2066),
            S(0x200B), S(0x200D), S(0xFEFF), S(0x3164), S(0x034F), S(0xD83D), S(0xDE00), S(0xE9),
            S('e'.code, 0x301), S(0x41F, 0x440), S(0x1100, 0x1161), S(0xF71, 0xF72), S(0x323, 0x302), S(0xFFFF),
            S(0xE000), S(0), S(0x7F),
        )
    }

    private fun randomName(rng: Lcg, maxFragments: Int): String {
        val sb = StringBuilder()
        val n = rng.next() % (maxFragments + 1)
        for (i in 0 until n) sb.append(namePool[rng.next() % namePool.size])
        return sb.toString()
    }

    // -----------------------------------------------------------------------------------------

    /** The fixed tables against the JDK's (Unicode 15.0 in JDK 21): no format or control character, and no separator, may be missed. */
    private fun unicodeTables() {
        section("unicode tables (stripped and whitespace sets against the JDK)")
        var bad = 0
        val samples = ArrayList<String>()
        var format = 0
        for (cp in 0..0x10FFFF) {
            val t = Character.getType(cp)
            val jvmStrip = t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt() ||
                t == Character.SURROGATE.toInt() || t == Character.PRIVATE_USE.toInt()
            val jvmSeparator = t == Character.SPACE_SEPARATOR.toInt() || t == Character.LINE_SEPARATOR.toInt() ||
                t == Character.PARAGRAPH_SEPARATOR.toInt()
            val tableSpace = isFolderSpace(cp)
            val tableStripByCategory = cp <= 0x1F || cp in 0x7F..0x9F || cp in 0xD800..0xDFFF ||
                cp in 0xE000..0xF8FF || cp in 0xF0000..0xFFFFD || cp in 0x100000..0x10FFFD || isFormatCodePoint(cp)
            if (isFormatCodePoint(cp)) format++
            val wantSpace = jvmSeparator || cp in 0x09..0x0D || cp == 0x85
            var why = ""
            if (tableSpace != wantSpace) why = "whitespace set"
            else if (jvmStrip && !tableStripByCategory) why = "the JDK calls it Cc/Cf/Cs/Co, the table keeps it"
            else if (tableStripByCategory && !jvmStrip && t != Character.UNASSIGNED.toInt()) why = "the table strips an assigned non-format character"
            else if (!tableSpace && jvmStrip && !isStrippedCodePoint(cp)) why = "not stripped"
            if (why.isNotEmpty()) {
                bad++
                if (samples.size < 10) samples.add("U+${Integer.toHexString(cp).uppercase()} ($why, JDK type $t)")
            }
        }
        check("every code point agrees with the JDK's categories (unassigned ones excepted)", bad == 0,
            if (bad == 0) "" else "$bad disagree: ${samples.joinToString("; ")}")
        check("the format table holds the 170 Cf code points of Unicode 15.1 to 17.0", format == 170, "$format")
        check("the fillers and noncharacters are stripped too",
            listOf(0x034F, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B, 0x180F, 0x3164, 0xFFA0, 0xFDD0, 0xFDEF, 0xFFFE, 0xFFFF, 0x10FFFF)
                .all { isStrippedCodePoint(it) })
        check("emoji variation selectors, letters, digits and emoji are kept",
            listOf(0xFE0F, 0xFE00, 'a'.code, 0x416, 0x1F600, 0x30, 0x20DD).none { isStrippedCodePoint(it) })
        check("U+001C..001F are controls, not whitespace", (0x1C..0x1F).all { !isFolderSpace(it) && isStrippedCodePoint(it) })
    }

    private fun testRollup() {
        section("roll-up")
        var r = FolderRollup()
        check("empty roll-up: nothing", r.count == 0 && r.unread == 0 && !r.mention && !r.directMention)
        check("empty roll-up: no badge", !rollupShowsBadge(r))
        r = addToRollup(r, 3, false, false)
        r = addToRollup(r, 0, false, false)
        r = addToRollup(r, 4, false, false)
        check("count counts every member, unread is the sum", r.count == 3 && r.unread == 7)
        check("plain sum: badge shown, no mention flags", rollupShowsBadge(r) && !r.mention && !r.directMention)
        r = addToRollup(r, -5, false, false)
        check("a negative unread count adds nothing (never subtracts)", r.count == 4 && r.unread == 7)
        r = addToRollup(r, 1, true, false)
        check("a mention sets mention, directMention stays false", r.mention && !r.directMention)
        r = addToRollup(r, 1, false, true)
        check("a direct mention sets directMention (and mention)", r.directMention && r.mention)
        r = addToRollup(r, 1, false, false)
        check("flags are sticky: a later plain member does not lower them", r.mention && r.directMention)
        val d = addToRollup(FolderRollup(), 1, false, true)
        check("a direct mention IMPLIES mention even when the caller passed mention=false", d.mention && d.directMention)
        val m = addToRollup(FolderRollup(), 0, true, false)
        check("a mention with a zero sum has no badge to show", !rollupShowsBadge(m) && m.mention)
        var big = FolderRollup()
        big = addToRollup(big, Int.MAX_VALUE, false, false)
        big = addToRollup(big, Int.MAX_VALUE, false, false)
        big = addToRollup(big, 5, false, false)
        check("the sum saturates at Int.MAX_VALUE instead of wrapping negative", big.unread == Int.MAX_VALUE && big.count == 3)
    }

    private fun testNames() {
        section("name cleaning")
        checkStr("trim", norm("  HQ  "), "HQ")
        checkStr("inner whitespace collapses to one space", norm("  Sales \t  and\n\n marketing "), "Sales and marketing")
        checkStr("NBSP and U+2028 are spaces, not dropped", norm(S('a'.code, 0xA0, 'b'.code, 0x2028, 'c'.code)), "a b c")
        checkStr("a newline between words does not glue them", norm(S('a'.code, '\n'.code, 'b'.code)), "a b")
        checkStr("ideographic space is a space", norm(S('a'.code, 0x3000, 'b'.code)), "a b")
        checkStr("Cyrillic survives", norm(S(0x41E, 0x442, 0x434, 0x435, 0x43B)), S(0x41E, 0x442, 0x434, 0x435, 0x43B))
        checkStr("an emoji with a variation selector survives whole", norm(S(0x2764, 0xFE0F, ' '.code, 'x'.code)), S(0x2764, 0xFE0F, ' '.code, 'x'.code))

        for (c in intArrayOf(0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x200E, 0x200F, 0x061C)) {
            val hex = Integer.toHexString(c).uppercase()
            checkStr("bidi control U+$hex is stripped from the middle of a name", norm(S('a'.code, c, 'b'.code)), "ab")
            check("a name of only U+$hex is refused (empty)", norm(S(c, c)).isEmpty())
        }
        for (c in intArrayOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x00AD, 0xE0041)) {
            val hex = Integer.toHexString(c).uppercase()
            checkStr("invisible U+$hex is stripped", norm(S('a'.code, c, 'b'.code)), "ab")
            check("a name of only U+$hex is refused (empty)", norm(S(c)).isEmpty())
        }
        for (c in intArrayOf(0x034F, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B, 0x180F, 0x3164, 0xFFA0)) {
            val hex = Integer.toHexString(c).uppercase()
            checkStr("blank filler U+$hex is stripped", norm(S('a'.code, c, 'b'.code)), "ab")
            check("a name of only U+$hex is refused (it would paint blank)", norm(S(c, ' '.code, c)).isEmpty())
        }
        check("a name of only a combining accent is empty", norm(S(0x301)).isEmpty())
        check("a name of only a variation selector is empty", norm(S(0xFE0F)).isEmpty())
        check("a name of only marks and spaces is empty", norm(S(' '.code, 0x301, ' '.code, 0x20DD, 0xFE0F)).isEmpty())
        checkStr("a mark after a real character stays", norm(S('1'.code, 0x301)), S('1'.code, 0x301))
        checkStr("NUL, ESC, DEL and a C1 control are dropped", norm(S('a'.code, 0, 'b'.code, 0x1B, 'c'.code, 0x7F, 'd'.code, 0x9F, 'e'.code)), "abcde")
        check("a name of only controls and bidi characters is empty", norm(S(0x202E, 0, 0x200B, 0x1B, 0x2066, 0xFEFF, 0x7F)).isEmpty())
        check("a name of only whitespace is empty", norm(S(' '.code, '\t'.code, '\n'.code, 0xA0, 0x2028)).isEmpty())
        check("an empty string is empty", norm("").isEmpty())
        checkStr("a lone high surrogate is dropped", norm(S('a'.code, 0xD83D, 'b'.code)), "ab")
        checkStr("a lone low surrogate is dropped", norm(S('a'.code, 0xDE00, 'b'.code)), "ab")
        checkStr("noncharacter U+FFFF is dropped", norm(S('a'.code, 0xFFFF, 'b'.code)), "ab")
        checkStr("private-use U+E000 is dropped", norm(S('a'.code, 0xE000, 'b'.code)), "ab")
        checkStr("decomposed input is stored precomposed (NFC)", norm(S('e'.code, 0x301)), S(0xE9))
        run {
            val input = rep("1", 39) + S(0x344)
            check("setup: 39 digits + U+0344 is 40 code points before NFC and 41 after", cps(input) == 40 && cps(nfc(input)) == 41)
            val got = norm(input)
            checkStr("NFC runs BEFORE the cut: the lengthened string is cut at 40 code points", got, rep("1", 39) + S(0x308))
            check("... never longer than the limit", cps(got) == MAX_FOLDER_NAME_CHARS)
        }

        section("name length (code points, truncate rule)")
        val a40 = rep("a", 40)
        checkStr("40 ASCII characters are kept whole", norm(a40), a40)
        checkStr("41 ASCII characters are TRUNCATED to 40 (not refused)", norm(a40 + "b"), a40)
        run {
            val input = rep("a", 39) + S(0x1F600)
            check("setup: 39 ASCII + 1 astral emoji is 40 code points but 41 UTF-16 units", cps(input) == 40 && input.length == 41)
            checkStr("39 ASCII + 1 astral emoji is exactly 40 code points: kept WHOLE", norm(input), input)
        }
        run {
            val got = norm(a40 + S(0x1F600))
            checkStr("40 ASCII + 1 emoji: the emoji is dropped whole", got, a40)
            check("... and no lone surrogate remains", got.length == 40)
        }
        run {
            val got = norm(rep(S(0x1F600), 41))
            check("41 emoji are cut to 40 emoji (80 UTF-16 units)", cps(got) == 40 && got.length == 80)
            var pairsOk = true
            var i = 0
            while (i + 1 < got.length) {
                pairsOk = pairsOk && got[i].isHighSurrogate() && got[i + 1].isLowSurrogate()
                i += 2
            }
            check("... every unit is in a proper high/low pair", pairsOk)
        }
        checkStr("a cut that lands right after a space is trimmed again", norm(rep("a", 39) + " bcd"), rep("a", 39))
        check("a very long name (100000 characters) is cut and returns", cps(norm(rep("x", 100000))) == 40)

        section("name length: the \"shortened\" flag")
        check("40 ASCII: not shortened", !cutOf(a40))
        check("41 ASCII: shortened", cutOf(a40 + "b"))
        check("39 ASCII + 1 astral emoji: not shortened", !cutOf(rep("a", 39) + S(0x1F600)))
        check("a cut that lands after a space: shortened", cutOf(rep("a", 39) + " bcd"))
        check("30 letters + 20 zero-width spaces: not shortened", !cutOf(rep("a", 30) + rep(S(0x200B), 20)))
        check("a run of 30 spaces collapsed to one: not shortened", !cutOf(rep("a", 20) + rep(" ", 30) + "bc"))
        check("cut down to marks only: empty, never \"shortened\"",
            norm(rep(S(0x301), 40) + "a").isEmpty() && !cutOf(rep(S(0x301), 40) + "a"))
        check("only whitespace: not shortened", !cutOf(rep(" ", 50)))
        checkStr("the flag changes nothing in the result", normaliseFolderName(a40 + "b").text, a40)
    }

    private fun testNameProperties() {
        section("name cleaning is idempotent and its output is clean (hand-picked + 40000 random)")
        val corpus = arrayListOf(
            "", " ", "HQ", "  a  b  ", S(0x301), rep(S(0x301), 40) + "a", rep(S(0x301), 39) + "a",
            rep(S(0x301), 41) + "a", rep("1", 39) + S(0x344), rep("1", 38) + S(0x344, 'x'.code), rep("a", 39) + S(0x344),
            rep(S(0x1F600), 41), rep("a", 39) + S(0x1F600), rep("a", 40) + S(0x1F600), rep("a", 39) + " bcd",
            rep("a", 38) + "  b", S('a'.code, 0x202E, 'b'.code), S(0x3164, 'x'.code), S(0xD83D, 'a'.code, 0xDE00), S('e'.code, 0x301),
            S(0x1100, 0x1161), rep(S(0x1100, 0x1161), 30), S(' '.code, 0x301, ' '.code, 'a'.code), S(0x301, 0x301, 'a'.code),
            rep(S(0xFE0F), 45) + "x", rep(" ", 80) + "x", "x" + rep(S(0x301), 60),
        )
        val rng = Lcg(0xC0FFEE1234L)
        for (i in 0 until 40000) corpus.add(randomName(rng, 70))

        var notIdempotent = 0
        var notNfc = 0
        var tooLong = 0
        var dirty = 0
        var baseless = 0
        var firstBad: String? = null
        for (x in corpus) {
            val y = norm(x)
            var bad = false
            if (norm(y) != y) { notIdempotent++; bad = true }
            if (y != nfc(y)) { notNfc++; bad = true }
            if (cps(y) > MAX_FOLDER_NAME_CHARS) { tooLong++; bad = true }
            if (y.isNotEmpty() && !hasPaintingBase(y)) { baseless++; bad = true }
            var isDirty = y.startsWith(" ") || y.endsWith(" ") || y.contains("  ")
            var i = 0
            while (i < y.length) {
                val c = y[i]
                if (c.isLowSurrogate() && (i == 0 || !y[i - 1].isHighSurrogate())) isDirty = true
                if (c.isHighSurrogate() && (i + 1 >= y.length || !y[i + 1].isLowSurrogate())) isDirty = true
                val cp = Character.codePointAt(y, i)
                if (cp != 0x20 && (isFolderSpace(cp) || isStrippedCodePoint(cp))) isDirty = true
                i += Character.charCount(cp)
            }
            if (isDirty) { dirty++; bad = true }
            if (bad && firstBad == null) firstBad = x
        }
        val where = firstBad?.let { "first offending input: [${show(it)}]" } ?: ""
        check("n(n(x)) == n(x) for every input", notIdempotent == 0, if (notIdempotent == 0) "" else where)
        check("n(x) is already in NFC for every input", notNfc == 0, if (notNfc == 0) "" else where)
        check("n(x) is never longer than 40 code points", tooLong == 0)
        check("n(x) is empty or has a character that paints by itself", baseless == 0)
        check("n(x) holds no control, bidi, zero-width, filler, lone surrogate, double or edge space", dirty == 0, if (dirty == 0) "" else where)
        check("40 combining marks + 'a' (a base the cut removes) is refused, not stored as marks", norm(rep(S(0x301), 40) + "a").isEmpty())
        checkStr("39 marks + 'a' keeps the 'a' (the base is inside the cut)", norm(rep(S(0x301), 39) + "a"), rep(S(0x301), 39) + "a")
    }

    private fun testCaseFold() {
        section("same name ignoring case (simple case folding, per code point)")
        check("Été and ETÉ are the same name", sameNameIgnoringCase(S(0xC9, 0x74, 0xE9), S(0xC9, 0x54, 0xC9)))
        check("Ete and Été are not (accents count)", !sameNameIgnoringCase("Ete", S(0xC9, 0x74, 0xE9)))
        check("the dotted capital I is not a plain i (no Turkic rule)", !sameNameIgnoringCase(S(0x130), "i"))
        check("the dotless i is not a plain i", !sameNameIgnoringCase(S(0x131), "i"))
        check("sharp s is not \"ss\" (no multi-character folds)", !sameNameIgnoringCase(S(0xDF), "ss"))
        check("capital sharp s folds to sharp s", sameNameIgnoringCase(S(0x1E9E), S(0xDF)))
        check("the Kelvin sign is a k", sameNameIgnoringCase(S(0x212A), "k"))
        check("final sigma is sigma", sameNameIgnoringCase(S(0x3C2), S(0x3A3)))
        check("astral letters fold too (Deseret)", sameNameIgnoringCase(S(0x10400), S(0x10428)))
        check("different lengths are different names", !sameNameIgnoringCase("ab", "abc") && !sameNameIgnoringCase("", "a"))
    }

    private fun testIds() {
        section("folder ids")
        check("isFolderId accepts f_ + 12 lowercase hex", isFolderId("f_0a1b2c3d4e5f"))
        check("isFolderId refuses uppercase hex", !isFolderId("f_0A1B2C3D4E5F"))
        check("isFolderId refuses 11 and 13 hex characters", !isFolderId("f_0a1b2c3d4e5") && !isFolderId("f_0a1b2c3d4e5f0"))
        check("isFolderId refuses a missing prefix and a pseudo-id", !isFolderId("0a1b2c3d4e5f") && !isFolderId("!favorites") && !isFolderId("!other"))
        check("isFolderId refuses a numeric tag id", !isFolderId("123") && !isFolderId("123456789012"))
        check("isFolderId refuses a trailing newline", !isFolderId("f_0a1b2c3d4e5f\n"))
        check("isFolderId refuses non-hex letters", !isFolderId("f_0a1b2c3d4e5g"))

        checkStr("folderIdFromBits is the low 48 bits, zero-padded, lowercase", folderIdFromBits(0xABC), "f_000000000abc")
        checkStr("folderIdFromBits ignores the high 16 bits", folderIdFromBits(0xFFFF000000000ABCuL.toLong()), "f_000000000abc")
        checkStr("folderIdFromBits(0) is all zeros", folderIdFromBits(0), "f_000000000000")

        val samples = longArrayOf(0, 1, 0xABC, -1L, 0x123456789ABCDEF0L, 9, 0xFFFF, Long.MAX_VALUE, Long.MIN_VALUE)
        var allValid = true
        var noBang = true
        var noneNumeric = true
        for (e in samples) {
            val id = makeFolderId(e, emptyList())
            allValid = allValid && isFolderId(id)
            noBang = noBang && !id.startsWith("!")
            noneNumeric = noneNumeric && !id.all { it.isDigit() }
        }
        check("ids from 9 awkward entropy values are all valid", allValid)
        check("no id starts with '!'", noBang)
        check("no id is all digits (so never a numeric Talk tag id)", noneNumeric)

        val seen = HashSet<String>()
        var ok = true
        for (e in 0L until 2000L) {
            val id = makeFolderId(e, emptyList())
            ok = ok && isFolderId(id)
            seen.add(id)
        }
        check("2000 distinct entropies give 2000 distinct valid ids", ok && seen.size == 2000)
        check("the same entropy gives the same id (deterministic)", makeFolderId(0xDEADBEEF, emptyList()) == makeFolderId(0xDEADBEEF, emptyList()))
        run {
            val existing = listOf(Folder(folderIdFromBits(0x1234), "x"))
            val id = makeFolderId(0x1234, existing)
            check("a clash derives a different id, deterministically",
                id != existing[0].id && isFolderId(id) && id == makeFolderId(0x1234, existing))
        }
        run {
            val existing = ArrayList<Folder>()
            var bits = 0x5555L
            for (attempt in 0 until 8) {
                existing.add(Folder(folderIdFromBits(bits), "x"))
                bits = deriveFolderEntropy(bits)
            }
            for (i in 0 until 30) {
                existing.add(Folder(folderIdFromBits(bits), "x"))
                bits += 1
            }
            val id = makeFolderId(0x5555, existing)
            check("38 consecutive clashes (re-derivation, then linear probe) still end in a free valid id",
                existing.none { it.id == id } && isFolderId(id))
        }
        run {
            val b = Box()
            val ids = HashSet<String>()
            for (i in 0 until MAX_FOLDERS) {
                val r = createFolder(b.s, "folder $i", 0x42)
                b.s = r.state
                ids.add(r.folderId)
                if (r.result != OK) break
            }
            check("50 folders created from one constant entropy still get 50 distinct ids", ids.size == MAX_FOLDERS && stateIsClean(b.s))
        }
    }

    private fun testCreate() {
        section("create")
        val b = Box()
        val first = createFolder(b.s, "HQ", 1)
        check("create 'HQ' is Ok", b.run(first) == OK)
        val id = first.folderId
        check("the new folder has a valid id, the cleaned name and is open",
            b.s.folders.size == 1 && b.s.folders[0].id == id && isFolderId(id) && b.s.folders[0].name == "HQ" && !b.s.folders[0].collapsed)
        check("create 'Ops' is Ok", b.run(createFolder(b.s, "Ops", 2)) == OK)
        check("'Ops' takes its place in name order, after 'HQ'", b.s.folders.size == 2 && b.s.folders[1].name == "Ops")

        val before = b.s
        check("empty name is refused: EmptyName", b.run(createFolder(b.s, "", 3)) == EMPTY_NAME)
        check("whitespace-only name is refused: EmptyName", b.run(createFolder(b.s, "  \t ", 3)) == EMPTY_NAME)
        check("a name made only of bidi/zero-width/control characters is refused: EmptyName",
            b.run(createFolder(b.s, S(0x202E, 0x200B, 0x2066, 0, 0xFEFF), 3)) == EMPTY_NAME)
        check("duplicate name is refused: DuplicateName", b.run(createFolder(b.s, "HQ", 3)) == DUPLICATE)
        check("duplicate ignoring case: 'hq'", b.run(createFolder(b.s, "hq", 3)) == DUPLICATE)
        check("duplicate after cleaning: '  HQ '", b.run(createFolder(b.s, "  HQ ", 3)) == DUPLICATE)
        check("refused creates left the state unchanged (the same instance)", b.s === before)
        run {
            val t = createFolder(b.s, " H Q ", 3)
            check("' H Q ' is NOT 'HQ' (inner spaces are kept as one space)",
                t.result == OK && t.state.folders.size == 3 && t.state.folders[folderIndexOf(t.state, t.folderId)].name == "H Q")
        }
        run {
            val u = Box()
            addFolder(u, S(0xC9, 0x74, 0xE9), 1)
            check("duplicate ignoring case for non-ASCII ('ETÉ' vs 'Été')", u.run(createFolder(u.s, S(0xC9, 0x54, 0xC9), 2)) == DUPLICATE)
            check("duplicate across NFC forms (e + combining acute vs precomposed)",
                u.run(createFolder(u.s, S(0xC9, 0x74, 'e'.code, 0x301), 3)) == DUPLICATE)
        }
        run {
            val l = Box()
            check("a 41-character name is accepted but TRUNCATED to 40",
                l.run(createFolder(l.s, rep("a", 40) + "b", 1)) == OK && l.s.folders[0].name == rep("a", 40))
            check("two over-long names that share their first 40 code points are duplicates",
                l.run(createFolder(l.s, rep("a", 40) + "zzz", 2)) == DUPLICATE)
        }
        run {
            val full = Box()
            for (i in 0 until MAX_FOLDERS) addFolder(full, "f$i", (1000 + i).toLong())
            check("50 folders fit", full.s.folders.size == MAX_FOLDERS)
            val atLimit = full.s
            check("the 51st folder is refused: TooMany", full.run(createFolder(full.s, "one more", 77)) == TOO_MANY)
            check("at the limit TooMany wins over DuplicateName", full.run(createFolder(full.s, "f0", 78)) == TOO_MANY)
            check("at the limit EmptyName still wins", full.run(createFolder(full.s, "", 79)) == EMPTY_NAME)
            check("at the limit TooMany wins over UnknownCharacters", full.run(createFolder(full.s, S(0x378), 80)) == TOO_MANY)
            check("a refused create left the state unchanged", full.s === atLimit)
            full.run(deleteFolder(full.s, full.s.folders[10].id))
            check("after a delete a new folder fits again",
                full.run(createFolder(full.s, "one more", 77)) == OK && full.s.folders.size == MAX_FOLDERS)
        }
        testCreateUnknownCharacters()
    }

    private fun jdkKnows(cp: Int): Boolean = Character.getType(cp) != Character.UNASSIGNED.toInt()

    /**
     * Not a desktop case: the phone's own refusal of a name with a character its tables do not know
     * (desktop's newer tables would clean such a name differently and then stop syncing). The cases
     * a cross-check with desktop's Unicode 16 tables found are refused on a JDK that predates them,
     * and are what a JDK that knows them makes of them otherwise.
     */
    private fun testCreateUnknownCharacters() {
        section("create: characters this device does not know")
        check("U+0378 and U+50000 are unassigned in this JDK (the premise of the next checks)", !jdkKnows(0x378) && !jdkKnows(0x50000))
        val b = Box()
        addFolder(b, "HQ", 1)
        val before = b.s
        check("a name with an unassigned code point is refused: UnknownCharacters", b.run(createFolder(b.s, "Ops" + S(0x378), 2)) == UNKNOWN)
        check("... also one from a plane with nothing in it (U+50000)", b.run(createFolder(b.s, S(0x50000) + " ops", 2)) == UNKNOWN)
        check("... also when the rest of it would be a duplicate", b.run(createFolder(b.s, "HQ" + S(0x378), 2)) == UNKNOWN)
        val loneMark = createFolder(b.s, S(0x897), 3).result
        check("a lone U+0897 (a Unicode 16 mark: desktop's normaliser drops the name) is never created",
            loneMark == if (jdkKnows(0x897)) EMPTY_NAME else UNKNOWN, "$loneMark")
        val seam = createFolder(b.s, "Seam " + S(0x11384, 0x113BB), 4).result
        check("a decomposed pair Unicode 16 composes (U+11384 U+113BB) is refused where the JDK does not know it",
            seam == if (jdkKnows(0x113BB)) OK else UNKNOWN, "$seam")
        val casePair = createFolder(b.s, S(0xA7CB) + " rams horn", 5).result
        check("a Unicode 16 capital (U+A7CB) is refused where the JDK does not know it",
            casePair == if (jdkKnows(0xA7CB)) OK else UNKNOWN, "$casePair")
        check("the refusals left the state unchanged (the same instance)", b.s === before)
        check("a noncharacter (also unassigned) is still only stripped, never a refusal",
            b.run(createFolder(b.s, "Ops" + S(0xFFFF, 0xFDD0), 6)) == OK && names(b.s) == "HQ|Ops")
        run {
            // A name another device wrote is kept: sanitise (and so decode) never refuses it.
            val t = FolderState(listOf(Folder("f_000000000001", "Old" + S(0x378))))
            check("sanitise keeps a stored name with an unassigned code point", !sanitiseFolderState(t).changed)
        }
    }

    private fun testRename() {
        section("rename")
        val b = Box()
        val hq = addFolder(b, "HQ", 1)
        val ops = addFolder(b, "Ops", 2)
        check("rename to a new name is Ok", b.run(renameFolder(b.s, hq, "Head office")) == OK && b.s.folders[0].name == "Head office")
        check("rename keeps the id ('Head office' still sorts before 'Ops')", b.s.folders[0].id == hq && b.s.folders[1].id == ops)
        check("renaming to the same name is Unchanged", b.run(renameFolder(b.s, hq, "Head office")) == UNCHANGED)
        check("renaming to the same name after cleaning is Unchanged", b.run(renameFolder(b.s, hq, "  Head   office ")) == UNCHANGED)
        check("a case-only change of the folder's OWN name is allowed (Ok)",
            b.run(renameFolder(b.s, hq, "HEAD OFFICE")) == OK && b.s.folders[0].name == "HEAD OFFICE")
        val before = b.s
        check("rename onto another folder's name (any case) is DuplicateName", b.run(renameFolder(b.s, hq, "ops")) == DUPLICATE)
        check("rename to an empty name is EmptyName", b.run(renameFolder(b.s, hq, "   ")) == EMPTY_NAME)
        check("rename to invisible characters only is EmptyName", b.run(renameFolder(b.s, hq, S(0x200B, 0x202E))) == EMPTY_NAME)
        check("rename of an unknown id is NotFound", b.run(renameFolder(b.s, "f_ffffffffffff", "x")) == NOT_FOUND)
        check("rename of an empty id is NotFound", b.run(renameFolder(b.s, "", "x")) == NOT_FOUND)
        check("refused renames left the state unchanged", b.s === before)
        run {
            val t = FolderState(listOf(Folder("f_000000000001", "Twin"), Folder("f_000000000002", "twin")))
            check("with a loaded twin, renaming a folder to its OWN exact name is Unchanged",
                renameFolder(t, "f_000000000001", "Twin").result == UNCHANGED && renameFolder(t, "f_000000000002", "twin").result == UNCHANGED)
            check("... while taking the twin's name is still DuplicateName", renameFolder(t, "f_000000000002", "Twin").result == DUPLICATE)
        }
        run {
            val before = b.s
            check("rename to a name with an unassigned code point is UnknownCharacters",
                b.run(renameFolder(b.s, hq, "Head" + S(0x378))) == UNKNOWN && b.s === before)
            val loneMark = renameFolder(b.s, hq, "  " + S(0x897) + "  ").result
            check("rename to a lone U+0897 is never Ok", loneMark == if (jdkKnows(0x897)) EMPTY_NAME else UNKNOWN, "$loneMark")
            val t = FolderState(listOf(Folder("f_000000000001", "Old" + S(0x378))))
            check("a stored name with an unassigned code point can be 'renamed' to itself (Unchanged) ...",
                renameFolder(t, "f_000000000001", "Old" + S(0x378)).result == UNCHANGED)
            check("... and to a name without it (Ok)", renameFolder(t, "f_000000000001", "Old").result == OK)
        }
        check("rename truncates a 41-character name", b.run(renameFolder(b.s, ops, rep("z", 41))) == OK && b.s.folders[1].name == rep("z", 40))
    }

    private fun testOrder() {
        section("order (alphabetical by default, a hand-arranged list kept)")
        run {
            val b = Box()
            check("an empty list is in name order", foldersInNameOrder(b.s, en))
            for (n in listOf("beta", "Alpha", "gamma", "Delta")) b.run(createFolder(b.s, n, 1, en))
            checkStr("new folders take their place: case does not matter", names(b.s), "Alpha|beta|Delta|gamma")
            check("... and the list reports itself in name order", foldersInNameOrder(b.s, en))
        }
        run {
            val b = Box()
            for (n in listOf("Folder 10", "Folder 2", "Folder 1")) b.run(createFolder(b.s, n, 1, en))
            checkStr("digits compare by value: Folder 1, 2, 10", names(b.s), "Folder 1|Folder 2|Folder 10")
        }
        run {
            val b = Box()
            val eclair = S(0xC9, 'c'.code, 'l'.code, 'a'.code, 'i'.code, 'r'.code)
            b.run(createFolder(b.s, "Zulu", 1, en))
            b.run(createFolder(b.s, eclair, 2, en))
            b.run(createFolder(b.s, "Delta", 3, en))
            checkStr("an accented letter sorts with its letter, not after Z", names(b.s), "Delta|$eclair|Zulu")
        }
        run {
            val ol = S(0xD6, 'l'.code)
            val sv = folderNameCollator("sv-SE")
            val inSv = Box()
            val inEn = Box()
            inSv.run(createFolder(inSv.s, "Zebra", 1, sv))
            inSv.run(createFolder(inSv.s, ol, 2, sv))
            inEn.run(createFolder(inEn.s, "Zebra", 1, en))
            inEn.run(createFolder(inEn.s, ol, 2, en))
            check("the order follows the language (Swedish: Zebra first; English: the O-word first)",
                names(inSv.s) == "Zebra|$ol" && names(inEn.s) == "$ol|Zebra", "sv ${show(names(inSv.s))}, en ${show(names(inEn.s))}")
        }
        run {
            val b = Box()
            b.run(createFolder(b.s, "Beta", 1))
            b.run(createFolder(b.s, "alpha", 2))
            checkStr("with the default language too, 'alpha' goes before 'Beta'", names(b.s), "alpha|Beta")
        }
        run {
            val b = Box()
            val a = addFolder(b, "Alpha", 1)
            val bb = addFolder(b, "Beta", 2)
            val g = addFolder(b, "Gamma", 3)
            b.run(assignToken(b.s, "aaaa1111", a))
            b.run(setFolderCollapsed(b.s, bb, true))
            checkStr("setup: Alpha|Beta|Gamma", names(b.s), "Alpha|Beta|Gamma")
            b.run(moveFolder(b.s, g, -2))
            check("after Move up the list is no longer in name order", names(b.s) == "Gamma|Alpha|Beta" && !foldersInNameOrder(b.s, en))
            b.run(createFolder(b.s, "Delta", 4, en))
            checkStr("a new folder in a hand-arranged list goes at the END", names(b.s), "Gamma|Alpha|Beta|Delta")
            check("a rename in a hand-arranged list keeps the position",
                b.run(renameFolder(b.s, a, "Zulu", en)) == OK && names(b.s) == "Gamma|Zulu|Beta|Delta")
            val beforeSort = b.s
            check("Sort A to Z is Ok", b.run(sortFoldersByName(b.s, en)) == OK && names(b.s) == "Beta|Delta|Gamma|Zulu")
            check("... only the order changed: ids, collapsed flags and filings are kept",
                b.s.assign == beforeSort.assign && b.s.otherCollapsed == beforeSort.otherCollapsed &&
                    b.s.folders[folderIndexOf(b.s, a)].name == "Zulu" && b.s.folders[folderIndexOf(b.s, bb)].collapsed &&
                    folderOf(b.s, "aaaa1111") == a)
            val sorted = b.s
            check("sorting a list already in name order is Unchanged and touches nothing",
                b.run(sortFoldersByName(b.s, en)) == UNCHANGED && b.s === sorted)
            check("a rename in a list in name order moves the folder to its new place",
                b.run(renameFolder(b.s, g, "Aardvark", en)) == OK && names(b.s) == "Aardvark|Beta|Delta|Zulu" && b.s.folders[0].id == g)
            check("... so the list stays in name order", foldersInNameOrder(b.s, en))
        }
        run {
            val one = FolderState(listOf(Folder("f_000000000003", "hq"), Folder("f_000000000002", "X"),
                Folder("f_000000000001", "HQ"), Folder("f_000000000004", "X")))
            val two = FolderState(listOf(Folder("f_000000000004", "X"), Folder("f_000000000001", "HQ"),
                Folder("f_000000000002", "X"), Folder("f_000000000003", "hq")))
            val s1 = sortFoldersByName(one, en).state
            val s2 = sortFoldersByName(two, en).state
            check("twins sort by exact name then id, the same from any starting order",
                s1.folders.map { it.id } == listOf("f_000000000001", "f_000000000003", "f_000000000002", "f_000000000004") && s1 == s2)
        }
        run {
            val b = Box()
            addFolder(b, "Beta", 1)
            addFolder(b, "Delta", 2)
            val before = b.s
            check("a refused create in a list in name order changes nothing",
                b.run(createFolder(b.s, "beta", 3, en)) == DUPLICATE && b.s === before)
            check("a refused rename changes nothing",
                b.run(renameFolder(b.s, b.s.folders[0].id, "DELTA", en)) == DUPLICATE && b.s === before)
        }
    }

    private fun testDelete() {
        section("delete")
        val b = Box()
        val a = addFolder(b, "A", 1)
        val bb = addFolder(b, "B", 2)
        val c = addFolder(b, "C", 3)
        b.run(assignToken(b.s, "aaaa1111", a))
        b.run(assignToken(b.s, "aaaa2222", a))
        b.run(assignToken(b.s, "bbbb1111", bb))
        b.run(assignToken(b.s, "cccc1111", c))
        check("setup: four chats filed", b.s.assign.size == 4)
        check("deleting B is Ok", b.run(deleteFolder(b.s, bb)) == OK)
        check("order of the others is kept", b.s.folders.size == 2 && b.s.folders[0].id == a && b.s.folders[1].id == c)
        check("exactly B's filings are dropped, nobody else's",
            b.s.assign.size == 3 && "bbbb1111" !in b.s.assign && b.s.assign["aaaa1111"] == a &&
                b.s.assign["aaaa2222"] == a && b.s.assign["cccc1111"] == c)
        check("the dropped chat is unfiled now", folderOf(b.s, "bbbb1111").isEmpty())
        val before = b.s
        check("deleting it again is NotFound", b.run(deleteFolder(b.s, bb)) == NOT_FOUND)
        check("deleting an empty id is NotFound", b.run(deleteFolder(b.s, "")) == NOT_FOUND)
        check("a refused delete left the state unchanged", b.s === before)
        check("deleting the last folders empties the list",
            b.run(deleteFolder(b.s, a)) == OK && b.run(deleteFolder(b.s, c)) == OK && b.s.folders.isEmpty() && b.s.assign.isEmpty())
    }

    private fun testMove() {
        section("move")
        val b = Box()
        val a = addFolder(b, "A", 1)
        val bb = addFolder(b, "B", 2)
        val c = addFolder(b, "C", 3)
        fun order() = b.s.folders.joinToString("") { it.name }
        check("move B up is Ok: BAC", b.run(moveFolder(b.s, bb, -1)) == OK && order() == "BAC")
        var before = b.s
        check("moving the first folder up is Unchanged", b.run(moveFolder(b.s, bb, -1)) == UNCHANGED && b.s === before)
        check("move C up: BCA", b.run(moveFolder(b.s, c, -1)) == OK && order() == "BCA")
        check("move B down: CBA", b.run(moveFolder(b.s, bb, 1)) == OK && order() == "CBA")
        before = b.s
        check("moving the last folder down is Unchanged", b.run(moveFolder(b.s, a, 1)) == UNCHANGED && b.s === before)
        check("delta 0 is Unchanged", b.run(moveFolder(b.s, bb, 0)) == UNCHANGED && b.s === before)
        check("a big delta clamps to the end (Int.MAX_VALUE): BAC", b.run(moveFolder(b.s, c, Int.MAX_VALUE)) == OK && order() == "BAC")
        check("a big negative delta clamps to the start (Int.MIN_VALUE) without overflow",
            b.run(moveFolder(b.s, c, Int.MIN_VALUE)) == OK && order() == "CBA")
        check("Int.MIN_VALUE on the first folder is Unchanged", b.run(moveFolder(b.s, c, Int.MIN_VALUE)) == UNCHANGED)
        check("Int.MAX_VALUE on the last folder is Unchanged", b.run(moveFolder(b.s, a, Int.MAX_VALUE)) == UNCHANGED)
        before = b.s
        check("moving an unknown id is NotFound", b.run(moveFolder(b.s, "f_ffffffffffff", 1)) == NOT_FOUND && b.s === before)
        val one = Box()
        val only = addFolder(one, "Only", 1)
        check("a single folder cannot move: Unchanged",
            one.run(moveFolder(one.s, only, 1)) == UNCHANGED && one.run(moveFolder(one.s, only, -1)) == UNCHANGED)
        check("moving in an empty list is NotFound", moveFolder(FolderState(), "f_000000000001", 1).result == NOT_FOUND)
    }

    private fun testAssign() {
        section("assign / unfile / exactly one folder")
        val b = Box()
        val a = addFolder(b, "A", 1)
        val bb = addFolder(b, "B", 2)
        check("assign a token to A is Ok", b.run(assignToken(b.s, "chat0001", a)) == OK)
        check("folderOf finds it", folderOf(b.s, "chat0001") == a)
        check("assigning it to A again is Unchanged", b.run(assignToken(b.s, "chat0001", a)) == UNCHANGED)
        check("assigning it to B MOVES it: Ok", b.run(assignToken(b.s, "chat0001", bb)) == OK)
        check("exactly one folder: the token is in B and only B, one entry", folderOf(b.s, "chat0001") == bb && b.s.assign.size == 1)
        check("... counted over every folder it is a member of exactly 1", b.s.folders.count { folderOf(b.s, "chat0001") == it.id } == 1)
        check("unfile (empty folder id) is Ok", b.run(assignToken(b.s, "chat0001", "")) == OK && b.s.assign.isEmpty())
        check("folderOf an unfiled token is empty", folderOf(b.s, "chat0001").isEmpty())
        check("unfiling an already-unfiled token is Unchanged", b.run(assignToken(b.s, "chat0001", "")) == UNCHANGED)
        check("unfiling a token never seen is Unchanged", b.run(assignToken(b.s, "neverseen", "")) == UNCHANGED)
        val before = b.s
        check("assigning to an unknown folder is NotFound", b.run(assignToken(b.s, "chat0001", "f_ffffffffffff")) == NOT_FOUND)
        check("assigning to a pseudo-id is NotFound", b.run(assignToken(b.s, "chat0001", "!favorites")) == NOT_FOUND)
        check("assigning to a numeric tag id is NotFound", b.run(assignToken(b.s, "chat0001", "12")) == NOT_FOUND)

        val bad = listOf(
            "uppercase" to "ABCD1234", "mixed case" to "Abcd1234", "3 characters" to "abc", "empty" to "",
            "31 characters" to rep("a", 31), "trailing newline" to "abcd1234\n", "leading newline" to "\nabcd1234",
            "forward slash" to "ab/cd1234", "backslash" to "ab\\cd1234", "dot segments" to "../abcd", "dot" to "ab.cd1234",
            "underscore" to "ab_cd1234", "space" to "ab cd1234", "NUL inside" to S('a'.code, 'b'.code, 'c'.code, 'd'.code, 0, 'e'.code),
            "non-ASCII letter" to S('a'.code, 'b'.code, 'c'.code, 0x430, 'd'.code), "percent escape" to "abcd%2f12",
        )
        for ((label, token) in bad) {
            check("bad token ($label) is refused: BadToken, state untouched", b.run(assignToken(b.s, token, a)) == BAD_TOKEN && b.s === before)
            check("bad token ($label) cannot be 'unfiled' either", b.run(assignToken(b.s, token, "")) == BAD_TOKEN)
        }
        check("a 4-character token is valid", b.run(assignToken(b.s, "ab12", a)) == OK)
        check("a 30-character token is valid", b.run(assignToken(b.s, rep("z", 30), a)) == OK)
        check("a digits-only token is valid", b.run(assignToken(b.s, "12345678", a)) == OK)
        run {
            val c = Box()
            val f = addFolder(c, "F", 1)
            val g = addFolder(c, "G", 2)
            val assign = LinkedHashMap<String, String>()
            for (i in 0 until MAX_ASSIGNMENTS) assign["t" + i.toString().padStart(7, '0')] = f
            c.s = c.s.copy(assign = assign)
            check("setup: the filing map is exactly at the cap", c.s.assign.size == MAX_ASSIGNMENTS)
            val atCap = c.s
            check("a NEW token at the cap is TooMany and changes nothing", c.run(assignToken(c.s, "newtoken1", f)) == TOO_MANY && c.s === atCap)
            check("moving an already-filed token at the cap still works",
                c.run(assignToken(c.s, "t0000000", g)) == OK && c.s.assign.size == MAX_ASSIGNMENTS)
            check("unfiling at the cap works and frees a slot",
                c.run(assignToken(c.s, "t0000001", "")) == OK && c.run(assignToken(c.s, "newtoken1", f)) == OK)
        }
    }

    private fun testCollapse() {
        section("collapse")
        val b = Box()
        val a = addFolder(b, "A", 1)
        check("a new folder is open", !b.s.folders[0].collapsed)
        check("collapse is Ok", b.run(setFolderCollapsed(b.s, a, true)) == OK && b.s.folders[0].collapsed)
        check("collapse again is Unchanged", b.run(setFolderCollapsed(b.s, a, true)) == UNCHANGED)
        check("open is Ok", b.run(setFolderCollapsed(b.s, a, false)) == OK && !b.s.folders[0].collapsed)
        check("open again is Unchanged", b.run(setFolderCollapsed(b.s, a, false)) == UNCHANGED)
        val before = b.s
        check("collapse of an unknown id is NotFound", b.run(setFolderCollapsed(b.s, "f_ffffffffffff", true)) == NOT_FOUND && b.s === before)
        check("Other starts open", !b.s.otherCollapsed)
        check("collapse Other is Ok", b.run(setOtherCollapsed(b.s, true)) == OK && b.s.otherCollapsed)
        check("collapse Other again is Unchanged", b.run(setOtherCollapsed(b.s, true)) == UNCHANGED)
        check("open Other is Ok", b.run(setOtherCollapsed(b.s, false)) == OK && !b.s.otherCollapsed)
        check("open Other again is Unchanged", b.run(setOtherCollapsed(b.s, false)) == UNCHANGED)
        b.run(setFolderCollapsed(b.s, a, true))
        check("collapsing a folder does not touch Other", !b.s.otherCollapsed)
    }

    private fun testLookup() {
        section("lookups / orphans")
        val b = Box()
        val a = addFolder(b, "A", 1)
        val bb = addFolder(b, "B", 2)
        check("folderIndexOf: first and second", folderIndexOf(b.s, a) == 0 && folderIndexOf(b.s, bb) == 1)
        check("folderIndexOf: unknown, empty and pseudo ids are -1",
            folderIndexOf(b.s, "f_ffffffffffff") == -1 && folderIndexOf(b.s, "") == -1 && folderIndexOf(b.s, "!other") == -1)
        check("folderOf an unknown token is empty", folderOf(b.s, "nosuchtk").isEmpty())
        b.s = b.s.copy(assign = b.s.assign + ("orphan01" to "f_aaaaaaaaaaaa"))
        check("folderOf a token mapped to a MISSING folder is empty (it reads as Other)", folderOf(b.s, "orphan01").isEmpty())
        b.s = b.s.copy(assign = b.s.assign + ("orphan02" to ""))
        check("folderOf a token mapped to an empty id is empty", folderOf(b.s, "orphan02").isEmpty())
        b.s = b.s.copy(assign = b.s.assign + ("orphan03" to "!favorites"))
        check("folderOf a token mapped to a pseudo-id is empty", folderOf(b.s, "orphan03").isEmpty())
        b.run(assignToken(b.s, "member01", bb))
        b.run(deleteFolder(b.s, bb))
        check("folderOf after the folder was deleted is empty", folderOf(b.s, "member01").isEmpty())
    }

    // A long pseudo-random run of every mutation, bad inputs included. After EVERY step a refused or
    // no-op call changed nothing and the state still satisfies every invariant; every 10th step it
    // also survives an encode/decode round trip.
    private fun testMutationFuzz() {
        section("mutation fuzz (invariants after every step)")
        val rng = Lcg(0x9E3779B97F4A7C15uL.toLong())
        val nameRng = Lcg(0xFEEDFACEL)
        var s = FolderState()
        var invariantsOk = true
        var refusalsPure = true
        var roundTripOk = true
        var refusals = 0
        var oks = 0
        var why = ""
        var step = 0
        while (step < 4000 && invariantsOk) {
            val before = s
            val op = rng.next() % 8
            val token = if (rng.next() % 12 == 0) "BAD tok" else "tok" + (rng.next() % 60).toString().padStart(4, '0')
            val folderId = if (s.folders.isEmpty() || rng.next() % 9 == 0) "f_ffffffffffff" else s.folders[rng.next() % s.folders.size].id
            val c: FolderChange = when (op) {
                0, 1 -> {
                    val name = if (rng.next() % 3 == 0) randomName(nameRng, 60) else "n${rng.next() % 70}"
                    val entropy = if (rng.next() % 5 == 0) 7L else (rng.next().toLong() shl 20) or rng.next().toLong()
                    createFolder(s, name, entropy)
                }
                2 -> renameFolder(s, folderId, if (rng.next() % 3 == 0) randomName(nameRng, 60) else "r${rng.next() % 70}")
                3 -> deleteFolder(s, folderId)
                4 -> moveFolder(s, folderId, rng.next() % 5 - 2)
                5 -> assignToken(s, token, folderId)
                6 -> assignToken(s, token, "")
                else -> if (rng.next() % 2 == 1) setFolderCollapsed(s, folderId, rng.next() % 2 == 1) else setOtherCollapsed(s, rng.next() % 2 == 1)
            }
            if (c.result == OK) {
                oks++
            } else {
                refusals++
                if (c.state !== before) refusalsPure = false
            }
            s = c.state
            why = stateProblem(s)
            if (why.isNotEmpty()) invariantsOk = false
            if (step % 10 == 0) {
                val d = decodeFolders(encodeFolders(s))
                if (d.status != DecodeStatus.Ok || d.repaired || d.state != s) roundTripOk = false
            }
            step++
        }
        check("invariants hold after every one of 4000 random mutations", invariantsOk, why)
        check("every refused or unchanged call handed back the same state", refusalsPure)
        check("the state survives encode/decode at every 10th step with nothing repaired", roundTripOk)
        check("the fuzz exercised both outcomes", oks > 200 && refusals > 200, "$oks ok, $refusals refused")
    }

    private fun testSanitise() {
        section("sanitise")
        run {
            val b = Box()
            val a = addFolder(b, "A", 1)
            b.run(assignToken(b.s, "aaaa1111", a))
            b.s = b.s.copy(otherCollapsed = true)
            val r = sanitiseFolderState(b.s)
            check("a clean state is left alone and reports no change", !r.changed && r.state === b.s)
        }
        run {
            val s = FolderState(
                listOf(Folder("f_0a1b2c3d4e5f", "A")),
                false,
                linkedMapOf("aaaa1111" to "f_0a1b2c3d4e5f", "BAD-TOKEN" to "f_0a1b2c3d4e5f", "bbbb2222" to "f_000000000000", "cccc3333" to ""),
            )
            val r = sanitiseFolderState(s)
            check("sanitise reports a change", r.changed)
            check("a filing with a bad token is dropped", "BAD-TOKEN" !in r.state.assign)
            check("a filing to a missing folder is dropped", "bbbb2222" !in r.state.assign && "cccc3333" !in r.state.assign)
            check("the good filing survives", r.state.assign.size == 1 && r.state.assign["aaaa1111"] == "f_0a1b2c3d4e5f")
        }
        run {
            val s = FolderState(listOf(Folder("f_0a1b2c3d4e5f", "First", true), Folder("f_0a1b2c3d4e5f", "Second")), false,
                mapOf("aaaa1111" to "f_0a1b2c3d4e5f"))
            val r = sanitiseFolderState(s).state
            check("duplicate ids: the FIRST is kept", r.folders.size == 1 && r.folders[0].name == "First" && r.folders[0].collapsed)
            check("... and the filing to that id is kept", r.assign.size == 1)
        }
        run {
            val badIds = listOf("F_0A1B2C3D4E5F", "f_123", "123", "!favorites", "!other", "f_0a1b2c3d4e5fg",
                "f_0a1b2c3d4e5f0", "f_0a1b2c3d4e5f\n", "", "g_0a1b2c3d4e5f", " f_0a1b2c3d4e5f", "12")
            var allDropped = true
            var filingsDropped = true
            for (bid in badIds) {
                val r = sanitiseFolderState(FolderState(listOf(Folder(bid, "Bad")), false, mapOf("aaaa1111" to bid))).state
                allDropped = allDropped && r.folders.isEmpty()
                filingsDropped = filingsDropped && r.assign.isEmpty()
            }
            check("a folder with a malformed id is dropped (12 shapes, none re-keyed)", allDropped)
            check("... and so are the filings that named it", filingsDropped)
        }
        run {
            val s = FolderState(
                listOf(Folder("f_000000000001", "  Spaced \t out  "), Folder("f_000000000002", S(0x202E, 0x200B)), Folder("f_000000000003", rep("q", 90))),
                false,
                mapOf("aaaa1111" to "f_000000000002", "bbbb2222" to "f_000000000001"),
            )
            val r = sanitiseFolderState(s).state
            check("names are normalised on load", r.folders.size == 2 && r.folders[0].name == "Spaced out")
            check("a folder whose name is empty after cleaning is dropped, with its filings",
                r.folders.size == 2 && "aaaa1111" !in r.assign && "bbbb2222" in r.assign)
            check("an over-long stored name is truncated, not dropped", r.folders[1].id == "f_000000000003" && r.folders[1].name == rep("q", 40))
        }
        run {
            val folders = ArrayList<Folder>()
            val assign = LinkedHashMap<String, String>()
            for (i in 0 until 60) {
                folders.add(Folder(fid((i + 1).toLong()), "n$i"))
                assign["tok" + i.toString().padStart(4, '0')] = folders.last().id
            }
            val r = sanitiseFolderState(FolderState(folders, false, assign))
            check("sanitise reports the clamp", r.changed)
            check("60 folders are clamped to 50, the FIRST 50 kept in order",
                r.state.folders.size == MAX_FOLDERS && r.state.folders[0].name == "n0" && r.state.folders[49].name == "n49")
            check("the filings of the 10 dropped folders go with them", r.state.assign.size == MAX_FOLDERS)
            check("the result is clean", stateIsClean(r.state))
        }
    }

    private fun testScopeKey() {
        section("account scope key")
        checkStr("known answer: cloud / alice", accountScopeKey("https://cloud.example.org", "alice"), "457a37cfeb503f81")
        checkStr("known answer: a path is part of the identity (case kept)", accountScopeKey("https://cloud.example.com/nextcloud", "Alice"), "b3ebec75ad339fcb")
        checkStr("known answer: non-ASCII user id (UTF-8)",
            accountScopeKey("https://cloud.example.org", S(0x419, 0x43E, 0x440, 0x434, 0x430, 0x43D, '-'.code, 0xD1)), "111736da42d38a1b")
        val base = accountScopeKey("https://cloud.example.org", "alice")
        check("a trailing slash does not change the key", accountScopeKey("https://cloud.example.org/", "alice") == base)
        check("several trailing slashes do not change the key", accountScopeKey("https://cloud.example.org///", "alice") == base)
        check("surrounding whitespace does not change the key", accountScopeKey("  https://cloud.example.org/ \n", "alice") == base)
        check("scheme and host case do not change the key", accountScopeKey("HTTPS://Cloud.EXAMPLE.Org", "alice") == base)
        check("the user id keeps its case: 'Alice' is a different account", accountScopeKey("https://cloud.example.org", "Alice") != base)
        check("a different user gives a different key", accountScopeKey("https://cloud.example.org", "bob") != base)
        check("a different server gives a different key", accountScopeKey("https://other.example.org", "alice") != base)
        check("http and https are different servers", accountScopeKey("http://cloud.example.org", "alice") != base)
        check("the path keeps its case", accountScopeKey("https://h.example/Cloud", "u") != accountScopeKey("https://h.example/cloud", "u"))
        check("a path is part of the identity", accountScopeKey("https://h.example/cloud", "u") != accountScopeKey("https://h.example", "u"))
        check("a trailing slash on a path is dropped too", accountScopeKey("https://h.example/cloud/", "u") == accountScopeKey("https://h.example/cloud", "u"))
        check("a port is part of the identity", accountScopeKey("https://h.example:8443", "u") != accountScopeKey("https://h.example", "u"))
        check("host case is folded even with a port and a path",
            accountScopeKey("https://H.Example:8443/Cloud", "u") == accountScopeKey("https://h.example:8443/Cloud", "u"))
        check("url + user are not interchangeable (the newline separates them)", accountScopeKey("https://a", "bc") != accountScopeKey("https://ab", "c"))
        check("the key is 16 lowercase hex characters", isScopeKey(base))
        check("an empty user id gives an EMPTY key", accountScopeKey("https://cloud.example.org", "").isEmpty())
        check("a whitespace-only user id gives an EMPTY key", accountScopeKey("https://cloud.example.org", "  \t").isEmpty())
        check("an empty url gives an EMPTY key", accountScopeKey("", "alice").isEmpty())
        check("a whitespace/slash-only url gives an EMPTY key", accountScopeKey(" / ", "alice").isEmpty())
        check("a scheme with no host gives an EMPTY key", accountScopeKey("https://", "alice").isEmpty() && accountScopeKey("https:///", "alice").isEmpty())
        check("both empty gives an EMPTY key", accountScopeKey("", "").isEmpty())
        checkStr("normaliseServerUrl: scheme+host lowercased, path kept, slashes dropped",
            normaliseServerUrl(" HTTPS://Cloud.Example.COM/NextCloud// "), "https://cloud.example.com/NextCloud")
        checkStr("normaliseServerUrl: userinfo keeps its case", normaliseServerUrl("https://User:Pw@Host.Example/"), "https://User:Pw@host.example")
        check("isScopeKey: 16 hex characters", isScopeKey("0123456789abcdef"))
        check("isScopeKey refuses an empty string", !isScopeKey(""))
        check("isScopeKey refuses uppercase hex", !isScopeKey("0123456789ABCDEF"))
        check("isScopeKey refuses 15 and 17 characters", !isScopeKey("0123456789abcde") && !isScopeKey("0123456789abcdef0"))
        check("isScopeKey refuses path separators and dot segments",
            !isScopeKey("../0123456789abc") && !isScopeKey("0123/56789abcdef") && !isScopeKey("0123\\56789abcdef") && !isScopeKey(".."))
        check("isScopeKey refuses a drive colon and a trailing newline", !isScopeKey("c:0123456789abcd") && !isScopeKey("0123456789abcdef\n"))
        check("isScopeKey refuses a reserved device name",!isScopeKey("con") && !isScopeKey("nul") && !isScopeKey("aux"))
    }

    private fun checkDecode(what: String, input: ByteArray, want: DecodeStatus) {
        val r = decodeFolders(input)
        val emptyOk = r.status == DecodeStatus.Ok || (r.state.folders.isEmpty() && r.state.assign.isEmpty() && !r.state.otherCollapsed)
        check(what, r.status == want && emptyOk, "got ${r.status}, want $want")
    }

    private fun testCodec() {
        section("JSON codec")
        val b = Box()
        val hq = addFolder(b, "HQ", 0x0a1b2c3d4e5fL)
        val emoji = addFolder(b, rep(S(0x1F600), 3) + " " + S(0x41E, 0x442, 0x434, 0x435, 0x43B), 2)
        val closed = addFolder(b, "Closed", 3)
        b.run(setFolderCollapsed(b.s, closed, true))
        b.run(setOtherCollapsed(b.s, true))
        b.run(assignToken(b.s, "chat0003", hq))
        b.run(assignToken(b.s, "chat0002", emoji))
        b.run(assignToken(b.s, "chat0004", hq))
        b.run(assignToken(b.s, "chat0001", closed))
        val s = b.s

        val encoded = encodeFolders(s)
        val d = decodeFolders(encoded)
        check("round trip: status Ok", d.status == DecodeStatus.Ok)
        check("round trip: nothing repaired", !d.repaired)
        check("round trip: the state is equal (folders, order, names, collapsed, Other, filings)", d.state == s)
        val empty = decodeFolders(encodeFolders(FolderState()))
        check("round trip of an empty state", empty.state == FolderState() && empty.status == DecodeStatus.Ok)
        check("encode is deterministic (twice, same bytes)", encodeFolders(s).contentEquals(encoded))
        run {
            val assign = LinkedHashMap<String, String>()
            for (tok in listOf("chat0001", "chat0004", "chat0002", "chat0003")) assign[tok] = s.assign.getValue(tok)
            val t = s.copy(assign = assign)
            check("encode does not depend on insertion order", encodeFolders(t).contentEquals(encoded))
            val text = encoded.decodeToString()
            val p1 = text.indexOf("chat0001")
            val p2 = text.indexOf("chat0002")
            val p3 = text.indexOf("chat0003")
            val p4 = text.indexOf("chat0004")
            check("assign is written sorted by token", p1 > 0 && p1 < p2 && p2 < p3 && p3 < p4)
        }
        run {
            // Read back with an independent parser (kotlinx.serialization).
            val o = Json.parseToJsonElement(encoded.decodeToString()).jsonObject
            check("the file has the documented keys: v, otherCollapsed, folders, assign",
                o["v"]?.jsonPrimitive?.int == 1 && o["otherCollapsed"]?.jsonPrimitive?.boolean == true &&
                    o["folders"] is JsonArray && o["assign"] is JsonObject)
            val f0 = (o["folders"] as JsonArray).map { it.jsonObject }.firstOrNull { it["id"]?.jsonPrimitive?.content == hq }
            check("a folder entry is {id, name, collapsed}",
                f0 != null && f0["name"]?.jsonPrimitive?.content == "HQ" && f0.containsKey("collapsed") && f0.size == 3)
            check("assign maps token -> folder id", (o["assign"] as JsonObject)["chat0003"]?.jsonPrimitive?.content == hq)
        }

        section("decode: shapes that are fine")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        checkDecode("empty bytes are an EMPTY STATE, not damage", ByteArray(0), DecodeStatus.Ok)
        checkDecode("whitespace-only bytes are an empty state", bytes(" \r\n\t "), DecodeStatus.Ok)
        checkDecode("a UTF-8 BOM and nothing else is an empty state", bom, DecodeStatus.Ok)
        checkDecode("a UTF-8 BOM before a valid file is tolerated", bom + encoded, DecodeStatus.Ok)
        checkDecode("{\"v\":1} alone is an empty state (missing containers)", bytes("{\"v\":1}"), DecodeStatus.Ok)
        checkDecode("\"v\" written as 1.0 is version 1", bytes("{\"v\":1.0,\"folders\":[],\"assign\":{}}"), DecodeStatus.Ok)
        run {
            val r = decodeFolders(bytes("{\"v\":1,\"extra\":[1,2,3],\"otherCollapsed\":true,\"folders\":[{\"id\":\"f_0a1b2c3d4e5f\",\"name\":\"HQ\",\"collapsed\":true,\"color\":\"red\",\"x\":{}}],\"assign\":{\"chat0001\":\"f_0a1b2c3d4e5f\"},\"future\":null}"))
            check("unknown fields (root and folder) are ignored; known ones are read",
                r.status == DecodeStatus.Ok && !r.repaired && r.state.otherCollapsed && r.state.folders.size == 1 &&
                    r.state.folders[0].collapsed && r.state.assign["chat0001"] == "f_0a1b2c3d4e5f")
            val again = encodeFolders(r.state).decodeToString()
            check("... and are not written back (re-encoding drops them)", !again.contains("color") && !again.contains("future"))
        }

        section("decode: not our file / hostile / damaged")
        checkDecode("\"null\" is Corrupt", bytes("null"), DecodeStatus.Corrupt)
        checkDecode("\"[]\" is Corrupt", bytes("[]"), DecodeStatus.Corrupt)
        checkDecode("a bare number is Corrupt", bytes("42"), DecodeStatus.Corrupt)
        checkDecode("a bare string is Corrupt", bytes("\"folders\""), DecodeStatus.Corrupt)
        checkDecode("\"{}\" is Corrupt (no version)", bytes("{}"), DecodeStatus.Corrupt)
        for (v in listOf("\"1\"", "1.5", "0", "-1", "null", "true", "[1]", "1e400", "-1e400")) {
            checkDecode("{\"v\":$v} is Corrupt", bytes("{\"v\":$v}"), DecodeStatus.Corrupt)
        }
        for (tail in listOf("\"folders\":\"x\"", "\"folders\":null", "\"folders\":{}", "\"assign\":[]", "\"assign\":\"x\"")) {
            checkDecode("{\"v\":1,$tail} (a container of the wrong type) is Corrupt", bytes("{\"v\":1,$tail}"), DecodeStatus.Corrupt)
        }
        checkDecode("truncated JSON (half of a valid file) is Corrupt", encoded.copyOfRange(0, encoded.size / 2), DecodeStatus.Corrupt)
        checkDecode("truncated JSON (all but the last two bytes) is Corrupt", encoded.copyOfRange(0, encoded.size - 2), DecodeStatus.Corrupt)
        checkDecode("trailing garbage after a valid document is Corrupt", encoded + bytes("}}garbage"), DecodeStatus.Corrupt)
        checkDecode("invalid UTF-8 bytes are Corrupt", byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte(), 0, 0x80.toByte()), DecodeStatus.Corrupt)
        checkDecode("an unquoted key is Corrupt", bytes("{v:1}"), DecodeStatus.Corrupt)
        run {
            val t0 = System.nanoTime()
            checkDecode("1 MB of 'x' is Corrupt", ByteArray(1 shl 20) { 'x'.code.toByte() }, DecodeStatus.Corrupt)
            checkDecode("1 MB of 0xFF is Corrupt", ByteArray(1 shl 20) { 0xFF.toByte() }, DecodeStatus.Corrupt)
            checkDecode("1 MB of NUL bytes is Corrupt", ByteArray(1 shl 20), DecodeStatus.Corrupt)
            checkDecode("1 MB of '[' (nesting bomb) is Corrupt", ByteArray(1 shl 20) { '['.code.toByte() }, DecodeStatus.Corrupt)
            val bomb = StringBuilder()
            while (bomb.length < (1 shl 20)) bomb.append("{\"a\":")
            checkDecode("1 MB of '{\"a\":' (object nesting bomb) is Corrupt", bytes(bomb.toString()), DecodeStatus.Corrupt)
            checkDecode("more than MAX_FOLDER_FILE_BYTES is Corrupt without parsing it",
                ByteArray(MAX_FOLDER_FILE_BYTES + 1) { ' '.code.toByte() }, DecodeStatus.Corrupt)
            val ms = (System.nanoTime() - t0) / 1_000_000
            check("the garbage cases together take under 3 s", ms < 3000, "$ms ms")
        }

        section("decode: future version")
        checkDecode("{\"v\":2} is FutureVersion", bytes("{\"v\":2}"), DecodeStatus.FutureVersion)
        checkDecode("{\"v\":99,...} is FutureVersion whatever follows", bytes("{\"v\":99,\"folders\":\"anything\",\"assign\":7}"), DecodeStatus.FutureVersion)
        checkDecode("{\"v\":2.0} is FutureVersion", bytes("{\"v\":2.0}"), DecodeStatus.FutureVersion)
        checkDecode("{\"v\":1e300} (huge but whole) is FutureVersion", bytes("{\"v\":1e300}"), DecodeStatus.FutureVersion)
        checkDecode("{\"v\":2.5} (not whole) is Corrupt", bytes("{\"v\":2.5}"), DecodeStatus.Corrupt)
        run {
            val r = decodeFolders(bytes("{\"v\":2,\"folders\":[{\"id\":\"f_0a1b2c3d4e5f\",\"name\":\"HQ\"}],\"assign\":{\"chat0001\":\"f_0a1b2c3d4e5f\"}}"))
            check("FutureVersion hands back an EMPTY state", r.status == DecodeStatus.FutureVersion && r.state.folders.isEmpty() && r.state.assign.isEmpty())
        }

        section("decode: per-entry damage is dropped, not fatal")
        run {
            val r = decodeFolders(bytes("{\"v\":1,\"folders\":[1,null,\"x\",[],{\"id\":5,\"name\":\"a\"},{\"id\":\"f_000000000001\"},{\"id\":\"f_000000000002\",\"name\":\"ok\",\"collapsed\":\"yes\"}],\"assign\":{\"aaaa1111\":7,\"bbbb2222\":\"f_000000000002\"}}"))
            check("non-object entries and entries with a non-string id/name are dropped; repaired is set",
                r.status == DecodeStatus.Ok && r.repaired && r.state.folders.size == 1 && r.state.folders[0].id == "f_000000000002")
            check("a wrong-typed 'collapsed' defaults to open", r.state.folders.isNotEmpty() && !r.state.folders[0].collapsed)
            check("a non-string filing value is dropped, a good one kept", r.state.assign.size == 1 && "bbbb2222" in r.state.assign)
        }
        run {
            val r = decodeFolders(bytes("{\"v\":1,\"otherCollapsed\":\"maybe\",\"folders\":[]}"))
            check("a wrong-typed otherCollapsed defaults to open (and is not fatal)", r.status == DecodeStatus.Ok && !r.state.otherCollapsed)
        }
        run {
            val r = decodeFolders(bytes("""{"v":1,"folders":[{"id":"f_000000000001","name":"a‮b\u0000c​d\n"}]}"""))
            check("control, bidi and zero-width characters in a stored name are stripped on load",
                r.status == DecodeStatus.Ok && r.repaired && r.state.folders.size == 1 && r.state.folders[0].name == "abcd")
        }
        run {
            val r = decodeFolders(bytes("""{"v":1,"folders":[{"id":"f_000000000001","name":"‮​"}],"assign":{"aaaa1111":"f_000000000001"}}"""))
            check("a folder named only with control characters is dropped with its filings",
                r.status == DecodeStatus.Ok && r.repaired && r.state.folders.isEmpty() && r.state.assign.isEmpty())
        }
        run {
            val r = decodeFolders(bytes("""{"v":1,"folders":[{"id":"f_000000000001","name":"First"},{"id":"f_000000000001","name":"Second"}],"assign":{"aaaa1111":"f_000000000001"}}"""))
            check("duplicate folder ids: the first is kept, repaired is set",
                r.status == DecodeStatus.Ok && r.repaired && r.state.folders.size == 1 && r.state.folders[0].name == "First" && r.state.assign.size == 1)
        }
        run {
            val r = decodeFolders(bytes("""{"v":1,"folders":[{"id":"f_000000000001","name":"A"}],"assign":{"aaaa1111":"f_000000000001","bbbb2222":"f_999999999999","BAD":"f_000000000001"}}"""))
            check("a filing pointing at a missing folder is dropped, as is one with a bad token",
                r.status == DecodeStatus.Ok && r.repaired && r.state.assign.size == 1 && "aaaa1111" in r.state.assign)
        }
        run {
            val r = decodeFolders(bytes("""{"v":1,"folders":[{"id":"!favorites","name":"A"},{"id":"123","name":"B"}],"assign":{"aaaa1111":"!favorites"}}"""))
            check("a pseudo-id or numeric folder id in a file is dropped (never re-keyed)", r.state.folders.isEmpty() && r.state.assign.isEmpty() && r.repaired)
        }
        run {
            val sb = StringBuilder("{\"v\":1,\"folders\":[")
            for (i in 0 until 60) sb.append(if (i > 0) "," else "").append("{\"id\":\"").append(fid((i + 1).toLong())).append("\",\"name\":\"n").append(i).append("\"}")
            sb.append("]}")
            val r = decodeFolders(bytes(sb.toString()))
            check("60 folders in a file are clamped to 50 and flagged repaired", r.status == DecodeStatus.Ok && r.repaired && r.state.folders.size == MAX_FOLDERS)
        }
        run {
            val sb = StringBuilder("{\"v\":1,\"folders\":[")
            for (i in 0 until MAX_FOLDER_ENTRIES_EXAMINED + 1) sb.append(if (i > 0) "," else "").append("{}")
            sb.append("]}")
            checkDecode("a folders array over the work cap is Corrupt", bytes(sb.toString()), DecodeStatus.Corrupt)
        }

        section("decode: sizes and time")
        run {
            val sb = StringBuilder("{\"v\":1,\"folders\":[")
            while (sb.length < MAX_FOLDER_FILE_BYTES - 8) sb.append("{},")
            sb.append("{}]}")
            val input = bytes(sb.toString())
            val t0 = System.nanoTime()
            val r = decodeFolders(input)
            val ms = (System.nanoTime() - t0) / 1_000_000
            check("a byte-cap-sized array of empty entries is Corrupt, with an empty state",
                input.size <= MAX_FOLDER_FILE_BYTES && r.status == DecodeStatus.Corrupt && r.state.folders.isEmpty(), "${input.size} bytes")
            check("... and returns in well under 5 s", ms < 5000, "$ms ms")
        }
        run {
            val sb = StringBuilder("{\"v\":1,\"folders\":[{\"id\":\"f_000000000001\",\"name\":\"A\"}],\"assign\":{")
            for (i in 0 until 100000) {
                if (i > 0) sb.append(',')
                sb.append("\"ab").append(i.toString().padStart(6, '0')).append("\":\"f_000000000001\"")
            }
            sb.append("}}")
            val input = bytes(sb.toString())
            val t0 = System.nanoTime()
            val r = decodeFolders(input)
            val ms = (System.nanoTime() - t0) / 1_000_000
            check("a 100000-entry assign map is Corrupt (over MAX_ASSIGNMENTS) with an empty state",
                input.size < MAX_FOLDER_FILE_BYTES && r.status == DecodeStatus.Corrupt && r.state.assign.isEmpty(), "${input.size} bytes")
            check("... and returns in well under 5 s", ms < 5000, "$ms ms")
        }
        run {
            val big = Box()
            val f = addFolder(big, "A", 1)
            val assign = LinkedHashMap<String, String>()
            for (i in 0 until MAX_ASSIGNMENTS) assign["ab" + i.toString().padStart(6, '0')] = f
            big.s = big.s.copy(assign = assign)
            val t0 = System.nanoTime()
            val enc = encodeFolders(big.s)
            val r = decodeFolders(enc)
            val ms = (System.nanoTime() - t0) / 1_000_000
            check("exactly MAX_ASSIGNMENTS filings round-trip", r.status == DecodeStatus.Ok && !r.repaired && r.state == big.s)
            check("... in well under 5 s", ms < 5000, "$ms ms")
        }

        section("caps are consistent: the biggest state the app can write reads back")
        run {
            val folders = ArrayList<Folder>()
            for (i in 0 until MAX_FOLDERS) {
                val name = StringBuilder()
                for (j in 0 until MAX_FOLDER_NAME_CHARS) appendCodePoint(name, 0x1F300 + i * MAX_FOLDER_NAME_CHARS + j)
                folders.add(Folder(fid((i + 1).toLong()), name.toString(), i % 2 == 0))
            }
            val assign = LinkedHashMap<String, String>()
            for (i in 0 until MAX_ASSIGNMENTS) assign["a" + i.toString().padStart(29, '0')] = folders[i % MAX_FOLDERS].id
            val big = FolderState(folders, true, assign)
            val enc = encodeFolders(big)
            check("setup: the maximal state satisfies the invariants", stateIsClean(big), stateProblem(big))
            check("the largest legal state encodes to LESS than MAX_FOLDER_FILE_BYTES", enc.size < MAX_FOLDER_FILE_BYTES, "${enc.size} bytes")
            val r = decodeFolders(enc)
            check("... and decodes back Ok, nothing repaired, equal", r.status == DecodeStatus.Ok && !r.repaired && r.state == big)
        }
    }

    /** The rules this reader adds on purpose (stricter than desktop where desktop is not pinned down). */
    private fun testCodecStrictness() {
        section("decode: strict on purpose")
        checkDecode("a repeated root key is Corrupt", bytes("{\"v\":1,\"v\":1}"), DecodeStatus.Corrupt)
        checkDecode("a repeated key in a folder entry is Corrupt",
            bytes("{\"v\":1,\"folders\":[{\"id\":\"f_000000000001\",\"id\":\"f_000000000002\",\"name\":\"A\"}]}"), DecodeStatus.Corrupt)
        checkDecode("a repeated filing is Corrupt",
            bytes("{\"v\":1,\"folders\":[{\"id\":\"f_000000000001\",\"name\":\"A\"}],\"assign\":{\"aaaa1111\":\"f_000000000001\",\"aaaa1111\":\"f_000000000001\"}}"),
            DecodeStatus.Corrupt)
        checkDecode("a repeated key inside an unknown member is fine", bytes("{\"v\":1,\"x\":{\"a\":1,\"a\":2}}"), DecodeStatus.Ok)
        checkDecode("a raw control character inside a string is Corrupt", bytes("{\"v\":1,\"x\":\"a\tb\"}"), DecodeStatus.Corrupt)
        checkDecode("an unknown escape is Corrupt", bytes("""{"v":1,"x":"\q"}"""), DecodeStatus.Corrupt)
        checkDecode("a short \\u escape is Corrupt", bytes("""{"v":1,"x":"\u12"}"""), DecodeStatus.Corrupt)
        checkDecode("a number that underflows to zero is Corrupt", bytes("{\"v\":1,\"x\":1e-400}"), DecodeStatus.Corrupt)
        checkDecode("a number that overflows is Corrupt", bytes("{\"v\":1,\"x\":1e400}"), DecodeStatus.Corrupt)
        checkDecode("zero written as 0e400 is fine", bytes("{\"v\":1,\"x\":0e400}"), DecodeStatus.Ok)
        checkDecode("a leading zero is Corrupt", bytes("{\"v\":01}"), DecodeStatus.Corrupt)
        checkDecode("a bare fraction is Corrupt", bytes("{\"v\":1,\"x\":.5}"), DecodeStatus.Corrupt)
        checkDecode("a trailing point is Corrupt", bytes("{\"v\":1.}"), DecodeStatus.Corrupt)
        checkDecode("a plus sign is Corrupt", bytes("{\"v\":+1}"), DecodeStatus.Corrupt)
        checkDecode("a trailing comma is Corrupt", bytes("{\"v\":1,}"), DecodeStatus.Corrupt)
        checkDecode("a comment is Corrupt", bytes("{\"v\":1 /* c */}"), DecodeStatus.Corrupt)
        checkDecode("a bare word is Corrupt", bytes("{\"v\":1,\"x\":tru}"), DecodeStatus.Corrupt)
        checkDecode("two BOMs are Corrupt", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes("{\"v\":1}"), DecodeStatus.Corrupt)
        checkDecode("\\v before the document is Corrupt (not JSON whitespace)", bytes("\u000B{\"v\":1}"), DecodeStatus.Corrupt)
        checkDecode("a vertical tab alone is a blank file", bytes("\u000B\u000C"), DecodeStatus.Ok)
        val deep = StringBuilder("{\"v\":1,\"x\":")
        for (i in 0 until 1023) deep.append('[')
        for (i in 0 until 1023) deep.append(']')
        deep.append('}')
        checkDecode("1024 nested containers are fine", bytes(deep.toString()), DecodeStatus.Ok)
        val deeper = StringBuilder("{\"v\":1,\"x\":")
        for (i in 0 until 1024) deeper.append('[')
        for (i in 0 until 1024) deeper.append(']')
        deeper.append('}')
        checkDecode("1025 nested containers are Corrupt", bytes(deeper.toString()), DecodeStatus.Corrupt)
        run {
            val r = decodeFolders(bytes("""{"v":1,"folders":[{"id":"f_000000000001","name":"a\ud800b \/ \"q\" \\"}]}"""))
            check("a lone-surrogate escape and the standard escapes are read (the name is then cleaned)",
                r.status == DecodeStatus.Ok && r.repaired && r.state.folders.size == 1 && r.state.folders[0].name == "ab / \"q\" \\")
        }
        run {
            val s = FolderState(listOf(Folder("f_000000000001", "say \"hi\" \\ / ok")), false, emptyMap())
            val enc = encodeFolders(s)
            val text = enc.decodeToString()
            check("quote and backslash are escaped, the slash is not", text.contains("\"name\": \"say \\\"hi\\\" \\\\ / ok\""))
            val r = decodeFolders(enc)
            check("... and read back unchanged", r.status == DecodeStatus.Ok && !r.repaired && r.state == s)
        }
        checkStr("empty containers are written as desktop's writer does",
            encodeFolders(FolderState()).decodeToString(),
            "{\n    \"assign\": {\n    },\n    \"folders\": [\n    ],\n    \"otherCollapsed\": false,\n    \"v\": 1\n}\n")
    }

    private const val GOLDEN_V1 =
        "{\n" +
            "    \"assign\": {\n" +
            "        \"chat0001\": \"f_000000000001\",\n" +
            "        \"chat0002\": \"f_000000000002\"\n" +
            "    },\n" +
            "    \"folders\": [\n" +
            "        {\n" +
            "            \"collapsed\": false,\n" +
            "            \"id\": \"f_000000000001\",\n" +
            "            \"name\": \"HQ\"\n" +
            "        },\n" +
            "        {\n" +
            "            \"collapsed\": true,\n" +
            "            \"id\": \"f_000000000002\",\n" +
            "            \"name\": \"Sales\"\n" +
            "        }\n" +
            "    ],\n" +
            "    \"otherCollapsed\": true,\n" +
            "    \"v\": 1\n" +
            "}\n"

    private fun goldenState(): FolderState {
        val assign = LinkedHashMap<String, String>()
        assign["chat0002"] = fid(2)
        assign["chat0001"] = fid(1)
        return FolderState(listOf(Folder(fid(1), "HQ", false), Folder(fid(2), "Sales", true)), true, assign)
    }

    private fun testGoldenBytes() {
        section("the v1 file is byte for byte what desktop writes")
        val got = encodeFolders(goldenState()).decodeToString()
        checkStr("encodeFolders of the fixed two-folder state equals desktop's bytes", got, GOLDEN_V1)
        val d = decodeFolders(bytes(GOLDEN_V1))
        check("the golden bytes decode Ok, nothing repaired", d.status == DecodeStatus.Ok && !d.repaired)
        check("and decode back to the fixed state", d.state == goldenState())

        section("the codec's version parameters")
        val s = goldenState()
        check("encodeFolders(s) is encodeFolders(s, 1)", encodeFolders(s).contentEquals(encodeFolders(s, 1)) && FOLDER_FORMAT_VERSION == 1)
        val v2 = encodeFolders(s, 2)
        val v2text = v2.decodeToString()
        check("encodeFolders(s, 2) writes \"v\": 2 and differs in that one number only",
            v2text.contains("\"v\": 2\n") && v2text.replace("\"v\": 2", "\"v\": 1") == encodeFolders(s).decodeToString())
        val dflt = decodeFolders(v2)
        check("decoding it with the default maxVersion is FutureVersion, with an empty state",
            dflt.status == DecodeStatus.FutureVersion && dflt.state == FolderState())
        check("decodeFolders(v2, 1) is FutureVersion too", decodeFolders(v2, 1).status == DecodeStatus.FutureVersion)
        val two = decodeFolders(v2, 2)
        check("with maxVersion 2 it is Ok, nothing repaired, and round-trips", two.status == DecodeStatus.Ok && !two.repaired && two.state == s)
        check("a v1 body still decodes with maxVersion 2", decodeFolders(encodeFolders(s), 2).status == DecodeStatus.Ok)
        check("\"v\":3 with maxVersion 2 is FutureVersion", decodeFolders(bytes("{\"v\":3}"), 2).status == DecodeStatus.FutureVersion)
        check("\"v\":0 is Corrupt whatever the maxVersion", decodeFolders(bytes("{\"v\":0}"), 2).status == DecodeStatus.Corrupt)
        check("\"v\":1.5 is Corrupt whatever the maxVersion", decodeFolders(bytes("{\"v\":1.5}"), 2).status == DecodeStatus.Corrupt)
    }

    // -----------------------------------------------------------------------------------------
    // The grouped list (desktop's sidebar folder test: the same rooms, folders and expectations).

    private data class Row(val token: String, val name: String, val lastActivity: Long, val unread: Int,
                           val mention: Boolean, val direct: Boolean, val archived: Boolean)

    private fun describe(sections: List<SectionRows<Row>>): String = buildString {
        for (sec in sections) {
            if (isNotEmpty()) append(' ')
            when (val x = sec.section) {
                is FolderSection.InFolder -> append('[').append(x.folder.name).append(']')
                FolderSection.Other -> append("[Other]")
            }
            if (!sec.closed) for (m in sec.members) append(' ').append(m.token)
        }
    }

    private fun rollupOf(sections: List<SectionRows<Row>>, name: String): FolderRollup? =
        sections.firstOrNull {
            val x = it.section
            if (x is FolderSection.InFolder) x.folder.name == name else name == "Other"
        }?.rollup

    private fun testSections() {
        section("the list grouped by folder")
        val rooms = listOf(
            Row("tokbravo", "Bravo", 400, 0, false, false, false),
            Row("tokecho1", "Echo", 100, 0, false, false, false),
            Row("tokalpha", "Alpha", 500, 2, false, false, false),
            Row("tokcharl", "Charlie", 300, 5, true, false, false),
            Row("tokfoxtr", "Foxtrot", 600, 9, true, true, true),
            Row("tokdelta", "Delta", 200, 1, true, true, false),
        )
        val work = "f_aaaaaaaaaaaa"
        val home = "f_bbbbbbbbbbbb"
        var state = FolderState(
            listOf(Folder(work, "Work"), Folder(home, "Home")),
            false,
            mapOf("tokbravo" to work, "tokalpha" to work, "tokfoxtr" to work, "tokcharl" to home, "tokecho1" to "f_999999999999"),
        )
        val member = { r: Row -> FolderMember(r.token, r.unread, r.mention, r.direct) }
        // The list the flat view shows: archived left out (as desktop does), newest activity first.
        val listed = rooms.filter { !it.archived }.sortedByDescending { it.lastActivity }

        val open = buildFolderSections(listed, state, member)
        checkStr("folders in order, chats newest first in each, a gone folder's chat and an unfiled one in Other, the archived chat nowhere",
            describe(open), "[Work] tokalpha tokbravo [Home] tokcharl [Other] tokdelta tokecho1")
        val w = rollupOf(open, "Work")
        val h = rollupOf(open, "Home")
        val o = rollupOf(open, "Other")
        check("Work: 2 chats, 2 unread, no mention", w == FolderRollup(2, 2, false, false), "$w")
        check("Home: 1 chat, 5 unread, a mention", h == FolderRollup(1, 5, true, false), "$h")
        check("Other: 2 chats, 1 unread, a direct mention", o == FolderRollup(2, 1, true, true), "$o")
        check("sections carry the folder's index", (open[0].section as? FolderSection.InFolder)?.index == 0 &&
            (open[1].section as? FolderSection.InFolder)?.index == 1)

        state = setFolderCollapsed(state, work, true).state
        val closed = buildFolderSections(listed, state, member)
        checkStr("a closed folder keeps its header, its chats go", describe(closed), "[Work] [Home] tokcharl [Other] tokdelta tokecho1")
        check("a closed folder still counts what is hidden in it", rollupOf(closed, "Work") == FolderRollup(2, 2, false, false))

        val hits = listed.filter { it.name.contains("alp", ignoreCase = true) }
        val searched = buildFolderSections(hits, state, member, searching = true)
        checkStr("a search shows the closed folder open with the hit; empty sections are left out", describe(searched), "[Work] tokalpha")
        check("... without touching the stored flag", state.folders[0].collapsed)
        checkStr("search cleared: the folder is closed again", describe(buildFolderSections(listed, state, member)),
            "[Work] [Home] tokcharl [Other] tokdelta tokecho1")

        check("nothing listed: no sections at all", buildFolderSections(emptyList(), state, member).isEmpty())
        val withEmpty = addFolderTo(state, "Empty")
        check("an empty folder is shown when nothing filters", describe(buildFolderSections(listed, withEmpty, member)).contains("[Empty]"))
        check("... and left out while a filter is active",
            !describe(buildFolderSections(listed, withEmpty, member, filterActive = true)).contains("[Empty]"))
        val allFiled = FolderState(listOf(Folder(work, "Work")), false, listed.associate { it.token to work })
        check("Other appears only with a member", buildFolderSections(listed, allFiled, member).none { it.section == FolderSection.Other })
        state = setOtherCollapsed(state, true).state
        check("Other closes like a folder", buildFolderSections(listed, state, member).last().let { it.section == FolderSection.Other && it.closed })

        // The room rule: a room on Never adds nothing unless muted rooms are counted.
        val muted = room("chat0001", unread = 5, mention = true, level = NotificationLevel.NEVER)
        val loud = room("chat0002", unread = 3, mention = true, level = NotificationLevel.DEFAULT)
        check("a muted room adds 0 and no mention when muted rooms are not counted",
            roomFolderMember(muted, countMuted = false) == FolderMember("chat0001", 0, false, false))
        check("a muted room adds its number when they are counted", roomFolderMember(muted, countMuted = true).unread == 5)
        check("a room that is not muted adds its number and its mention", roomFolderMember(loud, countMuted = false) == FolderMember("chat0002", 3, true, false))
    }

    private fun addFolderTo(s: FolderState, name: String): FolderState = createFolder(s, name, 0x77, en).state

    private fun room(token: String, unread: Int, mention: Boolean, level: Int): Room = Room(
        id = 1, token = token, type = 2, name = token, displayName = token, description = "",
        unreadMessages = unread, unreadMention = mention, unreadMentionDirect = false, lastActivity = 0,
        lastMessage = null, lastReadMessage = 0, lastCommonReadMessage = 0, readOnly = 0, notificationLevel = level,
        avatarVersion = "", isCustomAvatar = false, isFavorite = false, isArchived = false, isImportant = false,
        hasCall = false, participantType = 0, permissions = 0, actorType = "", actorId = "", objectType = "",
        objectId = "", status = "", statusIcon = "", statusMessage = "", statusClearAt = 0, raw = JsonObject(emptyMap()),
    )

    // =========================================================================================
    // Folder sync: FolderSyncLogic, FolderSyncPolicy and FolderSyncCycle, case for case from
    // tests/folder_sync_logic_test.cpp, tests/folder_sync_policy_test.cpp and the cycle and probe
    // parts of tests/folder_sync_controller_test.cpp. Fixtures as desktop's: Fn is the folder with
    // id folderIdFromBits(n), chats are invented tokens chat0001, chat0002, ...

    /** A folder as a desktop test writes it: its number, its name, collapsed or not. */
    private fun F(n: Int, name: String, collapsed: Boolean = false): Folder = Folder(fid(n.toLong()), name, collapsed)

    /** desktop's makeState: folders in order, filings as token to folder number. */
    private fun st(vararg folders: Folder, filed: List<Pair<String, Int>> = emptyList(), other: Boolean = false): FolderState {
        val assign = LinkedHashMap<String, String>()
        for ((token, n) in filed) assign[token] = fid(n.toLong())
        return FolderState(folders.toList(), other, assign)
    }

    /** F1..Fcount named "Folder n" (not capped: a state the app itself would refuse to hold). */
    private fun manyFolders(count: Int): FolderState = FolderState(List(count) { F(it + 1, "Folder ${it + 1}") })

    /** F<from>..F<to>, each named prefix + n, so two sides with different prefixes never pair by name. */
    private fun foldersNamed(from: Int, to: Int, prefix: String): FolderState = FolderState((from..to).map { F(it, prefix + it) })

    /** chat00001 .. chat20001: lowercase letters and digits, a legal Talk token. */
    private fun bulkToken(i: Int): String = "chat" + i.toString().padStart(5, '0')

    private fun shortId(id: String): String = id.removePrefix("f_").toLongOrNull(16)?.let { "F$it" } ?: id

    /** [F12 "HQ"+c, F7 "Sales"] {chat0003->F12} other+c */
    private fun describe(s: FolderState): String = buildString {
        append('[')
        append(s.folders.joinToString(", ") { shortId(it.id) + " \"" + show(it.name) + "\"" + (if (it.collapsed) "+c" else "") })
        append(']')
        val tokens = s.assign.keys.sorted()
        if (tokens.isNotEmpty()) append(" {").append(tokens.joinToString(", ") { it + "->" + shortId(s.assign.getValue(it)) }).append('}')
        if (s.otherCollapsed) append(" other+c")
    }

    private fun fixtureSanitised(s: FolderState): Boolean = !sanitiseFolderState(s).changed

    /** Merges after asserting the inputs are sanitised (a fixture that is not would read as a merge bug). */
    private fun mergeChecked(what: String, b: FolderState, l: FolderState, r: FolderState, prefer: MergePrefer): MergeResult {
        if (!fixtureSanitised(b) || !fixtureSanitised(l) || !fixtureSanitised(r)) check("$what: a fixture is not sanitised (fix the check, not the merge)", false)
        return mergeFolderStates(b, l, r, prefer, en)
    }

    private fun checkMerge(what: String, got: MergeResult, want: FolderState) {
        val ok = got.status == MergeStatus.Ok && got.merged == want
        check(what, ok, if (ok) "" else "got ${got.status} ${describe(got.merged)}, want ${describe(want)}")
    }

    private fun checkMerge(what: String, b: FolderState, l: FolderState, r: FolderState, prefer: MergePrefer, want: FolderState) =
        checkMerge(what, mergeChecked(what, b, l, r, prefer), want)

    private fun checkCount(what: String, got: Int, want: Int) = check(what, got == want, if (got == want) "" else "got $got, want $want")

    /** A refused merge returns its status and no state to apply. */
    private fun checkRefused(what: String, got: MergeResult, want: MergeStatus) {
        val ok = got.status == want && isEmptyFolderState(got.merged) && !got.merged.otherCollapsed &&
            got.foldersPaired == 0 && got.foldersDropped == 0
        check(what, ok, if (ok) "" else "got ${got.status} with ${got.merged.folders.size} folders")
    }

    private fun testSyncStateHelpers() {
        section("isEmptyFolderState, sameFolderState, the shared file's constants")
        check("a default state is empty", isEmptyFolderState(FolderState()))
        check("otherCollapsed alone does not make it non-empty", isEmptyFolderState(st(other = true)))
        check("a folder makes it non-empty", !isEmptyFolderState(st(F(1, "HQ"))))
        check("a filing alone makes it non-empty", !isEmptyFolderState(FolderState(assign = mapOf("chat0001" to fid(1)))))

        val filed = listOf("chat0001" to 1, "chat0002" to 2)
        val base = st(F(1, "HQ"), F(2, "Sales", true), filed = filed, other = true)
        check("a state equals itself", sameFolderState(base, base))
        check("two empty states are the same", sameFolderState(FolderState(), FolderState()))
        check("the same content built in another insertion order is the same",
            sameFolderState(base, st(F(1, "HQ"), F(2, "Sales", true), filed = listOf("chat0002" to 2, "chat0001" to 1), other = true)))
        check("a different folder id differs",
            !sameFolderState(base, st(F(1, "HQ"), F(3, "Sales", true), filed = listOf("chat0001" to 1, "chat0002" to 3), other = true)))
        check("a different name differs", !sameFolderState(base, st(F(1, "HQ"), F(2, "Sales EU", true), filed = filed, other = true)))
        check("a case-only rename differs", !sameFolderState(base, st(F(1, "hq"), F(2, "Sales", true), filed = filed, other = true)))
        check("a different collapsed flag differs", !sameFolderState(base, st(F(1, "HQ", true), F(2, "Sales", true), filed = filed, other = true)))
        check("a different otherCollapsed differs", !sameFolderState(base, st(F(1, "HQ"), F(2, "Sales", true), filed = filed, other = false)))
        check("a different folder order differs", !sameFolderState(base, st(F(2, "Sales", true), F(1, "HQ"), filed = filed, other = true)))
        check("a missing folder differs", !sameFolderState(base, st(F(1, "HQ"), filed = listOf("chat0001" to 1), other = true)))
        check("a chat filed in another folder differs",
            !sameFolderState(base, st(F(1, "HQ"), F(2, "Sales", true), filed = listOf("chat0001" to 2, "chat0002" to 2), other = true)))
        check("an extra filing differs",
            !sameFolderState(base, st(F(1, "HQ"), F(2, "Sales", true), filed = filed + ("chat0003" to 1), other = true)))
        check("a missing filing differs", !sameFolderState(base, st(F(1, "HQ"), F(2, "Sales", true), filed = listOf("chat0001" to 1), other = true)))
        check("the shared file is /.talq-folders.json, written as \"v\": 1",
            REMOTE_FOLDERS_FILE == ".talq-folders.json" && REMOTE_VERSION == 1 && encodeFolders(base, REMOTE_VERSION).contentEquals(encodeFolders(base)))
    }

    private fun checkDiff(what: String, got: StateDiff, added: Int, removed: Int, renamed: Int, chats: Int, order: Boolean, collapsed: Boolean, visible: Boolean) {
        val ok = got.foldersAdded == added && got.foldersRemoved == removed && got.foldersRenamed == renamed &&
            got.chatsChanged == chats && got.orderChanged == order && got.collapsedChanged == collapsed && got.visible == visible
        check(what, ok, if (ok) "" else "got $got visible=${got.visible}")
    }

    private fun testDiffStates() {
        section("diffStates")
        val hq = st(F(1, "HQ"))
        checkDiff("identical states: nothing changed, not visible", diffStates(hq, hq), 0, 0, 0, 0, false, false, false)
        checkDiff("two empty states: nothing changed", diffStates(FolderState(), FolderState()), 0, 0, 0, 0, false, false, false)
        checkDiff("a folder added at the end: added 1, not a reorder", diffStates(hq, st(F(1, "HQ"), F(2, "Sales"))), 1, 0, 0, 0, false, false, true)
        checkDiff("a folder added in the middle: added 1",
            diffStates(st(F(1, "HQ"), F(3, "Support")), st(F(1, "HQ"), F(2, "Sales"), F(3, "Support"))), 1, 0, 0, 0, false, false, true)
        checkDiff("a folder removed: removed 1",
            diffStates(st(F(1, "HQ"), F(2, "Sales"), F(3, "Support")), st(F(1, "HQ"), F(3, "Support"))), 0, 1, 0, 0, false, false, true)
        checkDiff("a folder renamed: renamed 1", diffStates(hq, st(F(1, "Head Office"))), 0, 0, 1, 0, false, false, true)
        checkDiff("a case-only rename counts as a rename", diffStates(hq, st(F(1, "hq"))), 0, 0, 1, 0, false, false, true)
        checkDiff("a chat filed for the first time: chats 1", diffStates(hq, st(F(1, "HQ"), filed = listOf("chat0001" to 1))), 0, 0, 0, 1, false, false, true)
        checkDiff("a chat unfiled: chats 1", diffStates(st(F(1, "HQ"), filed = listOf("chat0001" to 1)), hq), 0, 0, 0, 1, false, false, true)
        checkDiff("a chat moved to another folder: chats 1",
            diffStates(st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 1)), st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 2))),
            0, 0, 0, 1, false, false, true)
        checkDiff("two chats moved: chats 2",
            diffStates(st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 1, "chat0002" to 1)),
                st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 2, "chat0002" to 2))),
            0, 0, 0, 2, false, false, true)
        checkDiff("a folder deleted with a chat in it: removed 1, chats 1",
            diffStates(st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 2)), st(F(1, "HQ"))), 0, 1, 0, 1, false, false, true)
        checkDiff("the first folder moved to the end: order changed",
            diffStates(st(F(1, "HQ"), F(2, "Sales"), F(3, "Support")), st(F(2, "Sales"), F(3, "Support"), F(1, "HQ"))), 0, 0, 0, 0, true, false, true)
        checkDiff("the last folder moved to the top: order changed",
            diffStates(st(F(1, "HQ"), F(2, "Sales"), F(3, "Support")), st(F(3, "Support"), F(1, "HQ"), F(2, "Sales"))), 0, 0, 0, 0, true, false, true)
        checkDiff("a swap that also adds a folder: order changed and added 1",
            diffStates(st(F(1, "HQ"), F(2, "Sales")), st(F(2, "Sales"), F(1, "HQ"), F(3, "Support"))), 1, 0, 0, 0, true, false, true)
        checkDiff("a folder collapsed: collapsedChanged, NOT visible", diffStates(hq, st(F(1, "HQ", true))), 0, 0, 0, 0, false, true, false)
        checkDiff("a folder expanded: collapsedChanged, NOT visible", diffStates(st(F(1, "HQ", true)), hq), 0, 0, 0, 0, false, true, false)
        checkDiff("the Other section collapsed: collapsedChanged, NOT visible",
            diffStates(st(F(1, "HQ")), st(F(1, "HQ"), other = true)), 0, 0, 0, 0, false, true, false)
        checkDiff("a collapse with a rename is visible because of the rename", diffStates(hq, st(F(1, "Head Office", true))), 0, 0, 1, 0, false, true, true)
        checkDiff("a folder added collapsed is an add, not a collapse change", diffStates(hq, st(F(1, "HQ"), F(2, "Sales", true))), 1, 0, 0, 0, false, false, true)
        checkDiff("from nothing to two folders and a filing",
            diffStates(FolderState(), st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 1))), 2, 0, 0, 1, false, false, true)
        checkDiff("from two folders and a filing to nothing",
            diffStates(st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 1)), FolderState()), 0, 2, 0, 1, false, false, true)
    }

    private fun testRemovesFoldersOrFilings() {
        section("removesFoldersOrFilings")
        val filed = st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 1, "chat0002" to 2))
        check("the same state removes nothing", !removesFoldersOrFilings(filed, filed))
        check("two empty states remove nothing", !removesFoldersOrFilings(FolderState(), FolderState()))
        check("a folder missing from the target is a removal", removesFoldersOrFilings(filed, st(F(1, "HQ"), filed = listOf("chat0001" to 1))))
        check("a folder missing with no chat in it is still a removal", removesFoldersOrFilings(st(F(1, "HQ"), F(2, "Sales")), st(F(1, "HQ"))))
        check("a chat unfiled in the target is a removal", removesFoldersOrFilings(filed, st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 1))))
        check("every filing gone while every folder stays is a removal", removesFoldersOrFilings(filed, st(F(1, "HQ"), F(2, "Sales"))))
        check("a chat that points at a folder the target lacks counts as unfiled: a removal",
            removesFoldersOrFilings(st(F(1, "HQ"), filed = listOf("chat0001" to 1)), st(F(2, "Sales"), filed = listOf("chat0001" to 1))))
        check("everything gone is a removal", removesFoldersOrFilings(filed, FolderState()))
        check("a folder added is not a removal", !removesFoldersOrFilings(st(F(1, "HQ")), st(F(1, "HQ"), F(2, "Sales"))))
        check("a chat filed for the first time is not a removal", !removesFoldersOrFilings(st(F(1, "HQ")), st(F(1, "HQ"), filed = listOf("chat0001" to 1))))
        check("a chat moved to another folder is not a removal",
            !removesFoldersOrFilings(st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 1)), st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 2))))
        check("a rename is not a removal",
            !removesFoldersOrFilings(filed, st(F(1, "Head Office"), F(2, "Sales"), filed = listOf("chat0001" to 1, "chat0002" to 2))))
        check("a collapse is not a removal",
            !removesFoldersOrFilings(filed, st(F(1, "HQ", true), F(2, "Sales"), filed = listOf("chat0001" to 1, "chat0002" to 2), other = true)))
        check("a reorder is not a removal",
            !removesFoldersOrFilings(filed, st(F(2, "Sales"), F(1, "HQ"), filed = listOf("chat0001" to 1, "chat0002" to 2))))
        check("from nothing to a full state is not a removal", !removesFoldersOrFilings(FolderState(), filed))
    }

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** {"v":1} followed by spaces up to exactly [total] bytes: valid JSON of any size. */
    private fun paddedBody(total: Int): ByteArray = bytes("{\"v\":1}" + " ".repeat(total - 7))

    private fun checkKind(what: String, input: ByteArray, want: RemoteKind) {
        val got = decodeRemote(input).kind
        check(what, got == want, if (got == want) "" else "got $got, want $want")
    }

    /** A body decodeFolders accepts as Ok but REPAIRED, which decodeRemote must call Unreadable. */
    private fun checkRepairedIsUnreadable(what: String, damaged: FolderState) {
        val body = encodeFolders(damaged, REMOTE_VERSION)
        val d = decodeFolders(body, REMOTE_VERSION)
        check("$what: decodeFolders itself answers Ok with repaired set", d.status == DecodeStatus.Ok && d.repaired)
        checkKind("$what: Unreadable", body, RemoteKind.Unreadable)
    }

    private fun testDecodeRemote() {
        section("decodeRemote")
        val s = goldenState()
        checkKind("zero bytes: Absent", ByteArray(0), RemoteKind.Absent)
        checkKind("whitespace only: Absent", bytes("  \n"), RemoteKind.Absent)
        checkKind("tabs and CRLF only: Absent", bytes("\r\n\t "), RemoteKind.Absent)
        checkKind("a byte-order mark alone: Absent (not a readable empty file)", BOM, RemoteKind.Absent)
        checkKind("a byte-order mark and whitespace: Absent", BOM + bytes(" \r\n"), RemoteKind.Absent)
        check("an Absent result carries an empty state", isEmptyFolderState(decodeRemote(bytes("  \n")).state))

        val p = decodeRemote(encodeFolders(s, REMOTE_VERSION))
        check("a valid body: Present, with the state it holds", p.kind == RemoteKind.Present && p.state == s)
        checkKind("a valid body behind a byte-order mark: Present", BOM + encodeFolders(s, REMOTE_VERSION), RemoteKind.Present)
        val empty = decodeRemote(bytes("{\"v\":1}"))
        check("{\"v\":1} is Present, not Absent: a readable file that holds nothing", empty.kind == RemoteKind.Present && isEmptyFolderState(empty.state))
        checkKind("50 folders, the cap: Present", encodeFolders(manyFolders(MAX_FOLDERS), REMOTE_VERSION), RemoteKind.Present)

        checkKind("\"v\":2: Newer", bytes("{\"v\":2}"), RemoteKind.Newer)
        checkKind("\"v\":99: Newer", bytes("{\"v\":99}"), RemoteKind.Newer)
        checkKind("a whole v2 body: Newer", encodeFolders(s, 2), RemoteKind.Newer)
        check("a Newer result carries no state to merge", isEmptyFolderState(decodeRemote(encodeFolders(s, 2)).state))

        checkKind("not JSON: Unreadable", bytes("this is not json"), RemoteKind.Unreadable)
        checkKind("truncated JSON: Unreadable", bytes("{\"v\":1,\"folders\":["), RemoteKind.Unreadable)
        checkKind("a root that is not an object: Unreadable", bytes("[1,2]"), RemoteKind.Unreadable)
        checkKind("no \"v\": Unreadable", bytes("{}"), RemoteKind.Unreadable)
        checkKind("\"v\" a string: Unreadable", bytes("{\"v\":\"1\"}"), RemoteKind.Unreadable)
        checkKind("\"v\":0: Unreadable", bytes("{\"v\":0}"), RemoteKind.Unreadable)
        checkKind("\"v\":-1: Unreadable", bytes("{\"v\":-1}"), RemoteKind.Unreadable)
        checkKind("\"v\":1.5: Unreadable", bytes("{\"v\":1.5}"), RemoteKind.Unreadable)
        checkKind("\"v\":1e400 (not finite): Unreadable", bytes("{\"v\":1e400}"), RemoteKind.Unreadable)
        checkKind("\"folders\" of the wrong type: Unreadable", bytes("{\"v\":1,\"folders\":{}}"), RemoteKind.Unreadable)
        checkKind("\"assign\" of the wrong type: Unreadable", bytes("{\"v\":1,\"assign\":[]}"), RemoteKind.Unreadable)

        checkKind("a valid body of exactly 4 MiB: Present", paddedBody(MAX_FOLDER_FILE_BYTES), RemoteKind.Present)
        checkKind("a valid body one byte over 4 MiB: Unreadable", paddedBody(MAX_FOLDER_FILE_BYTES + 1), RemoteKind.Unreadable)
        checkKind("a whitespace-only body of exactly 4 MiB: Absent", ByteArray(MAX_FOLDER_FILE_BYTES) { 0x20 }, RemoteKind.Absent)
        checkKind("a whitespace-only body over 4 MiB: Unreadable, not Absent", ByteArray(MAX_FOLDER_FILE_BYTES + 1) { 0x20 }, RemoteKind.Unreadable)

        val tooMany = encodeFolders(manyFolders(MAX_FOLDER_ENTRIES_EXAMINED + 1), REMOTE_VERSION)
        check("1001 folder entries: decodeFolders itself answers Corrupt", decodeFolders(tooMany, REMOTE_VERSION).status == DecodeStatus.Corrupt)
        checkKind("1001 folder entries: Unreadable", tooMany, RemoteKind.Unreadable)

        checkRepairedIsUnreadable("51 folders", manyFolders(MAX_FOLDERS + 1))
        checkRepairedIsUnreadable("a folder id that is not f_ and 12 hex digits", FolderState(listOf(F(1, "HQ"), Folder("not-an-id", "Sales", false))))
        checkRepairedIsUnreadable("a duplicate folder id", FolderState(listOf(F(1, "HQ"), F(1, "Sales"))))
        checkRepairedIsUnreadable("a chat token that is not a Talk token", FolderState(listOf(F(1, "HQ")), false, mapOf("NOT A TOKEN" to fid(1))))
        checkRepairedIsUnreadable("an assignment to a folder that is not in the file",
            FolderState(listOf(F(1, "HQ")), false, mapOf("chat0001" to fid(1), "chat0002" to fid(9))))
        checkRepairedIsUnreadable("a name that normalisation changes", FolderState(listOf(F(1, " HQ "))))
    }

    private fun testMergeWorkedExamples() {
        val L = MergePrefer.Local
        val R = MergePrefer.Remote
        section("merge E1 both rename")
        val b1 = st(F(1, "HQ"))
        checkMerge("both renamed, trusted: the merging device's name wins", b1, st(F(1, "Head Office")), st(F(1, "HQ2")), L, st(F(1, "Head Office")))
        checkMerge("only the local renamed (R unchanged): the local name", b1, st(F(1, "Head Office")), st(F(1, "HQ")), L, st(F(1, "Head Office")))
        checkMerge("only the remote renamed (L unchanged): the remote name", b1, st(F(1, "HQ")), st(F(1, "HQ2")), L, st(F(1, "HQ2")))
        checkMerge("both renamed, untrusted (B empty): conflict, the remote name", FolderState(), st(F(1, "Head Office")), st(F(1, "HQ2")), R, st(F(1, "HQ2")))

        section("merge E2 remote adds")
        val e2 = mergeChecked("E2", b1, b1, st(F(1, "HQ"), F(2, "Sales")), L)
        checkMerge("M is F1, F2", e2, st(F(1, "HQ"), F(2, "Sales")))
        checkCount("diff from L: foldersAdded 1", diffStates(b1, e2.merged).foldersAdded, 1)

        section("merge E3 delete beats rename")
        checkMerge("L deleted F2 (X unfiled), R renamed it: M F1, X unfiled",
            st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 2)), st(F(1, "HQ")),
            st(F(1, "HQ"), F(2, "Sales EU"), filed = listOf("chat0001" to 2)), L, st(F(1, "HQ")))

        section("merge E4 remote deletes, local filed a new chat there")
        val b4 = st(F(1, "HQ"), F(2, "Sales"))
        val l4 = st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0002" to 2))
        val e4 = mergeChecked("E4", b4, l4, st(F(1, "HQ")), L)
        checkMerge("M F1, Y unfiled", e4, st(F(1, "HQ")))
        checkCount("diff from L: foldersRemoved 1", diffStates(l4, e4.merged).foldersRemoved, 1)
        checkCount("foldersDropped 1 (in B and L, the remote lacks it)", e4.foldersDropped, 1)
        checkMerge("L moved X into F2, R deleted F2 and left X in F1: X unfiled, not back in F1",
            st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 1)), st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 2)),
            st(F(1, "HQ"), filed = listOf("chat0001" to 1)), L, st(F(1, "HQ")))

        section("merge E5 same chat, two folders")
        val l5 = st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 1))
        val r5 = st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 2))
        checkMerge("trusted: X->F1 (the merging device)", st(F(1, "HQ"), F(2, "Sales")), l5, r5, L, l5)
        checkMerge("untrusted (B empty): X->F2 (the remote)", FolderState(), l5, r5, R, r5)

        section("merge E6 winner's folder is gone")
        checkMerge("prefer Local gives F1, which R deleted, so R's F3 is used: M F2, F3; X->F3",
            st(F(1, "HQ"), F(2, "Sales"), F(3, "Support"), filed = listOf("chat0001" to 2)),
            st(F(1, "HQ"), F(2, "Sales"), F(3, "Support"), filed = listOf("chat0001" to 1)),
            st(F(2, "Sales"), F(3, "Support"), filed = listOf("chat0001" to 3)), L,
            st(F(2, "Sales"), F(3, "Support"), filed = listOf("chat0001" to 3)))

        section("merge E7 implicit un-file is not a change")
        val b7 = st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 1))
        checkMerge("L moved X to F2, R deleted F1 (X unfiled by it): M F2; X->F2", b7,
            st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 2)), st(F(2, "Sales")), L, st(F(2, "Sales"), filed = listOf("chat0001" to 2)))
        checkMerge("mirrored: L deleted F1, R moved X to F2: M F2; X->F2", b7,
            st(F(2, "Sales")), st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 2)), L, st(F(2, "Sales"), filed = listOf("chat0001" to 2)))

        section("merge E8 un-file")
        val b8 = st(F(1, "HQ"), filed = listOf("chat0001" to 1))
        checkMerge("L unfiled X, R unchanged: X unfiled", b8, st(F(1, "HQ")), b8, L, st(F(1, "HQ")))
        checkMerge("L unfiled X, R moved X to F2: a real conflict, Local's unfiled wins when trusted", b8,
            st(F(1, "HQ")), st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 2)), L, st(F(1, "HQ"), F(2, "Sales")))

        section("merge E9 collapsed")
        val open = st(F(1, "HQ"))
        val closed = st(F(1, "HQ", true))
        checkMerge("L collapsed F1, R unchanged: collapsed", open, closed, open, L, closed)
        checkMerge("R collapsed F1, L unchanged: collapsed", open, open, closed, L, closed)
        checkMerge("untrusted, L collapsed and R open: the remote wins, open", FolderState(), closed, open, R, open)
        val otherOpen = st(F(1, "HQ"))
        val otherClosed = st(F(1, "HQ"), other = true)
        checkMerge("Other: L collapsed it, trusted: collapsed", otherOpen, otherClosed, otherOpen, L, otherClosed)
        checkMerge("Other: R collapsed it, trusted: collapsed", otherOpen, otherOpen, otherClosed, L, otherClosed)
        checkMerge("Other: untrusted, L collapsed and R open: the remote wins, open", FolderState(), otherClosed, otherOpen, R, otherOpen)

        section("merge E10 order")
        val b10 = st(F(1, "HQ"), F(2, "Sales"), F(3, "Support"))
        checkMerge("L moved F3 to the top, R added F4: [F3,F1,F2,F4]", b10, st(F(3, "Support"), F(1, "HQ"), F(2, "Sales")),
            st(F(1, "HQ"), F(2, "Sales"), F(3, "Support"), F(4, "Finance")), L, st(F(3, "Support"), F(1, "HQ"), F(2, "Sales"), F(4, "Finance")))
        val l10 = st(F(3, "Support"), F(1, "HQ"), F(2, "Sales"))
        val r10 = st(F(1, "HQ"), F(3, "Support"), F(2, "Sales"))
        checkMerge("both reordered differently, trusted: the local order", b10, l10, r10, L, l10)
        checkMerge("both reordered differently, the rule with prefer Remote: the remote order", b10, l10, r10, R, r10)
        checkMerge("both reordered differently, untrusted (B empty): the remote order", FolderState(), l10, r10, R, r10)
        val rTop = st(F(3, "Support"), F(1, "HQ"), F(2, "Sales"))
        checkMerge("a folder created at the end stays last, even when R moved F3 to the top", b10,
            st(F(1, "HQ"), F(2, "Sales"), F(3, "Support"), F(4, "Finance")), rTop, L, st(F(3, "Support"), F(1, "HQ"), F(2, "Sales"), F(4, "Finance")))
        checkMerge("a new folder moved up before the sync lands before its old next neighbour", b10,
            st(F(1, "HQ"), F(4, "Finance"), F(2, "Sales"), F(3, "Support")), rTop, L, st(F(3, "Support"), F(1, "HQ"), F(4, "Finance"), F(2, "Sales")))
        checkMerge("a remote-new folder lands before its following folder in the REMOTE list", st(F(1, "HQ"), F(2, "Sales")),
            st(F(2, "Sales"), F(1, "HQ")), st(F(1, "HQ"), F(5, "Legal"), F(2, "Sales")), L, st(F(5, "Legal"), F(2, "Sales"), F(1, "HQ")))
        checkMerge("an id new on both sides is placed once", st(F(1, "HQ")), st(F(1, "HQ"), F(5, "Legal")), st(F(1, "HQ"), F(5, "Legal")), L,
            st(F(1, "HQ"), F(5, "Legal")))

        section("merge E11 first enable, pairing")
        val e11 = mergeChecked("E11", FolderState(), st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 1, "chat0003" to 2)),
            st(F(3, "hq", true), F(4, "Support"), filed = listOf("chat0002" to 3)), R)
        checkMerge("F1 pairs F3: M [F3 \"hq\"+c, F4, F2]; X->F3, Y->F3, Z->F2", e11,
            st(F(3, "hq", true), F(4, "Support"), F(2, "Sales"), filed = listOf("chat0001" to 3, "chat0002" to 3, "chat0003" to 2)))
        checkCount("foldersPaired 1", e11.foldersPaired, 1)
        checkCount("foldersDropped 0 (no base)", e11.foldersDropped, 0)
        checkMerge("a local-only folder lands before the paired folder that follows it: [F2, F3]", FolderState(),
            st(F(2, "Sales"), F(1, "HQ")), st(F(3, "hq")), R, st(F(2, "Sales"), F(3, "hq")))

        section("merge E12 both create \"HQ\" after a sync")
        val e12 = mergeChecked("E12", st(F(1, "Sales")), st(F(1, "Sales"), F(5, "HQ"), filed = listOf("chat0001" to 5)), st(F(1, "Sales"), F(6, "hq")), L)
        checkMerge("both new, they pair: M F1, F6 \"hq\"; X->F6", e12, st(F(1, "Sales"), F(6, "hq"), filed = listOf("chat0001" to 6)))
        checkCount("foldersPaired 1", e12.foldersPaired, 1)

        section("merge E13 twin names")
        val e13 = mergeChecked("E13", FolderState(), st(F(1, "HQ"), F(2, "Hq")), st(F(3, "HQ"), F(4, "hq")), R)
        checkMerge("F1 pairs F3, F2 pairs F4 (smallest id first): M [F3 \"HQ\", F4 \"hq\"]", e13, st(F(3, "HQ"), F(4, "hq")))
        checkCount("foldersPaired 2", e13.foldersPaired, 2)
        val e13b = mergeChecked("E13 by position", FolderState(), st(F(1, "HQ"), F(2, "Hq"), filed = listOf("chat0001" to 1, "chat0002" to 2)),
            st(F(3, "hq"), F(4, "HQ")), R)
        checkMerge("L [HQ, Hq] against R [hq, HQ]: F1 pairs F3, F2 pairs F4, chats re-pointed", e13b,
            st(F(3, "hq"), F(4, "HQ"), filed = listOf("chat0001" to 3, "chat0002" to 4)))
        checkCount("foldersPaired 2", e13b.foldersPaired, 2)

        section("merge R6 twin pairing is independent of remote order")
        val b6 = st(F(1, "C1"), F(2, "C2"))
        val l6 = st(F(2, "C2"), F(1, "C1"), F(10, "HQ"), filed = listOf("chat0001" to 10))
        val r6 = mergeChecked("R6", b6, l6, st(F(3, "hq"), F(1, "C1"), F(4, "HQ"), F(2, "C2")), L)
        checkMerge("F10 pairs the smaller-id twin F3: M [F4, C2, F3, C1]; chat0001->F3", r6,
            st(F(4, "HQ"), F(2, "C2"), F(3, "hq"), F(1, "C1"), filed = listOf("chat0001" to 3)))
        checkCount("foldersPaired 1", r6.foldersPaired, 1)
        checkMerge("merge(B, L, M) == M (E20)", b6, l6, r6.merged, L, r6.merged)
        val swapped = mergeChecked("R6 swapped", b6, l6, st(F(4, "HQ"), F(1, "C1"), F(3, "hq"), F(2, "C2")), L)
        check("twins swapped in R: still Ok, chat0001 still in F3", swapped.status == MergeStatus.Ok && folderOf(swapped.merged, "chat0001") == fid(3))
        check("twins swapped in R: the same folders and filings as before (order aside)",
            swapped.merged.folders.sortedBy { it.id } == r6.merged.folders.sortedBy { it.id } && swapped.merged.assign == r6.merged.assign)
        checkMerge("twins swapped in R: merge(B, L, M) == M (E20)", b6, l6, swapped.merged, L, swapped.merged)

        section("merge Step 1: a folder in the base is never new, so it never pairs")
        val i1 = mergeChecked("(i)", st(F(1, "HQ")), st(F(9, "hq")), st(F(1, "HQ")), L)
        checkMerge("(i) B [F1 HQ], L [F9 hq], R [F1 HQ], trusted: M exactly [F9 hq]", i1, st(F(9, "hq")))
        checkCount("(i) foldersPaired 0", i1.foldersPaired, 0)
        checkCount("(i) foldersDropped 0 (this device deleted F1 itself)", i1.foldersDropped, 0)
        checkMerge("(i) with chats: the chat of the deleted F1 is unfiled, F9's chat stays in F9",
            st(F(1, "HQ"), filed = listOf("chat0001" to 1)), st(F(9, "hq"), filed = listOf("chat0002" to 9)),
            st(F(1, "HQ"), filed = listOf("chat0001" to 1)), L, st(F(9, "hq"), filed = listOf("chat0002" to 9)))
        val i2 = mergeChecked("(ii)", st(F(1, "HQ"), filed = listOf("chat0001" to 1)), st(F(1, "HQ"), filed = listOf("chat0001" to 1, "chat0002" to 1)),
            st(F(2, "hq")), L)
        checkMerge("(ii) B [F1 HQ], L [F1 HQ], R [F2 hq], trusted: M exactly [F2 hq], every chat of F1 unfiled", i2, st(F(2, "hq")))
        checkCount("(ii) foldersPaired 0", i2.foldersPaired, 0)
        checkCount("(ii) foldersDropped 1", i2.foldersDropped, 1)

        section("merge E20 contract boundary and E22 fixed cases")
        val bb = st(F(1, "HQ"), filed = listOf("chat0001" to 1))
        val lb = st(F(2, "Clients"), filed = listOf("chat0001" to 2))
        val rb = st(F(1, "HQ"))
        val outside = mergeChecked("outside the contract", bb, lb, rb, R)
        checkMerge("outside the contract (non-empty base, prefer Remote): M = [F2 Clients], chat0001 unfiled", outside, st(F(2, "Clients")))
        checkMerge("outside the contract: merge(B, L, M) files chat0001 in F2 (NOT idempotent, expected)", bb, lb, outside.merged, R,
            st(F(2, "Clients"), filed = listOf("chat0001" to 2)))
        check("inside the contract (the same L and R, the empty base, prefer Remote): idempotent",
            idempotenceFailure(FolderState(), lb, rb, R).isEmpty())
        val b22 = st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 1))
        checkMerge("B [F1, F2] {c->F1}, L [F1], R [F1, F2] {c->F2}, trusted: M [F1], c unfiled", b22, st(F(1, "HQ")),
            st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 2)), L, st(F(1, "HQ")))
        checkMerge("B [F1, F2] {c->F1}, L [F1, F2] {c->F2}, R [F1] {c->F1}, trusted: M [F1], c unfiled", b22,
            st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 2)), st(F(1, "HQ"), filed = listOf("chat0001" to 1)), L, st(F(1, "HQ")))
    }

    /** The fix of TalQ for desktop commit 1b5043fc: in a list in name order a rename's move is not a reorder by hand. */
    private fun testRenameInNameOrderIsNotAReorder() {
        section("merge Step 5: a rename in a list in name order is not a reorder")
        val L = MergePrefer.Local
        val b = st(F(1, "Alpha"), F(2, "Beta"), F(3, "Gamma"))
        val renamedOnly = st(F(2, "Able"), F(1, "Alpha"), F(3, "Gamma"))
        val rename = renameFolder(b, fid(2), "Able", en)
        check("the local rename of Beta to \"Able\" moves it to the top: [Able, Alpha, Gamma]",
            rename.result == OK && rename.state == renamedOnly)
        val l = rename.state
        val kept = st(F(3, "Gamma"), F(1, "Alpha"), F(2, "Able"))
        val m = mergeChecked("rename vs remote reorder", b, l, st(F(3, "Gamma"), F(1, "Alpha"), F(2, "Beta")), L)
        checkMerge("R moved Gamma to the top, L renamed Beta to \"Able\": Gamma stays first, [Gamma, Alpha, Able]", m, kept)
        checkMerge("... and merged again with that result as the remote, nothing changes", b, l, m.merged, L, m.merged)
        checkMerge("R unchanged, L renamed Beta to \"Able\": [Able, Alpha, Gamma]", b, l, b, L, renamedOnly)

        val r = renameFolder(b, fid(2), "Able", en).state
        val lGammaTop = moveFolder(moveFolder(b, fid(3), -1).state, fid(3), -1).state
        check("(fixture) Gamma moved up twice is [Gamma, Alpha, Beta]", lGammaTop == st(F(3, "Gamma"), F(1, "Alpha"), F(2, "Beta")))
        checkMerge("R renamed Beta to \"Able\", L moved Gamma to the top: [Gamma, Alpha, Able]", b, lGammaTop, r, L, kept)

        val bHand = st(F(3, "Gamma"), F(1, "Alpha"), F(2, "Beta"))
        val lSorted = sortFoldersByName(bHand, en).state
        checkMerge("B arranged by hand, L sorted it A to Z, R moved Beta to the top: the local sort", bHand, lSorted,
            st(F(2, "Beta"), F(3, "Gamma"), F(1, "Alpha")), L, st(F(1, "Alpha"), F(2, "Beta"), F(3, "Gamma")))
    }

    private fun testMergePairing() {
        section("merge pairing by case folding, beyond ASCII (simple folding: sharp s stays, capital sharp s folds to it, no Turkic rules)")
        val officeLower = "Офис"
        val officeUpper = "ОФИС"
        val streetSharp = "Straße"
        val streetCapSharp = "STRAẞE"
        val streetSS = "STRASSE"
        val dotlessLower = "Kılıç"
        val dotlessUpper = "KILIÇ"
        val dottedCap = "İstanbul"
        val plainLower = "istanbul"
        check("the Cyrillic pair folds to one string", caseFoldedName(officeLower) == caseFoldedName(officeUpper))
        check("\"Stra\\u00DFe\" and \"STRA\\u1E9EE\" (capital sharp s) fold to one string", caseFoldedName(streetSharp) == caseFoldedName(streetCapSharp))
        check("\"Stra\\u00DFe\" and \"STRASSE\" do NOT fold to one string", caseFoldedName(streetSharp) != caseFoldedName(streetSS))
        check("the dotless-i name and its upper case do NOT fold to one string", caseFoldedName(dotlessLower) != caseFoldedName(dotlessUpper))
        check("the dotted capital I name and \"istanbul\" do NOT fold to one string", caseFoldedName(dottedCap) != caseFoldedName(plainLower))

        val l = FolderState(listOf(F(1, officeLower), F(2, streetSharp), F(3, dotlessLower), F(4, dottedCap)), false,
            mapOf("chat0001" to fid(1), "chat0002" to fid(2), "chat0003" to fid(3), "chat0004" to fid(4)))
        val r = FolderState(listOf(F(5, officeUpper), F(6, streetCapSharp, true), F(7, streetSS), F(8, dotlessUpper), F(9, plainLower)), false,
            mapOf("chat0005" to fid(5), "chat0006" to fid(7), "chat0007" to fid(8), "chat0008" to fid(9)))
        val m = mergeChecked("non-ASCII", FolderState(), l, r, MergePrefer.Remote)
        val want = FolderState(
            listOf(F(5, officeUpper), F(6, streetCapSharp, true), F(7, streetSS), F(8, dotlessUpper), F(9, plainLower), F(3, dotlessLower), F(4, dottedCap)),
            false,
            mapOf("chat0001" to fid(5), "chat0002" to fid(6), "chat0003" to fid(3), "chat0004" to fid(4),
                "chat0005" to fid(5), "chat0006" to fid(7), "chat0007" to fid(8), "chat0008" to fid(9)),
        )
        checkMerge("Cyrillic and sharp-s pairs merge into the remote folder; the rest stay two folders", m, want)
        checkCount("foldersPaired 2", m.foldersPaired, 2)
        checkCount("every one of the 8 chats is still filed", (1..8).count { folderOf(m.merged, "chat000$it").isNotEmpty() }, 8)
    }

    private fun testMergeCapsAndSelfCheck() {
        section("merge E14 caps")
        val l14 = foldersNamed(1, 30, "Local ")
        val r14 = foldersNamed(31, 55, "Remote ")
        checkRefused("30 + 25 distinct new folders, trusted: TooManyFolders, no state",
            mergeChecked("E14", FolderState(), l14, r14, MergePrefer.Local), MergeStatus.TooManyFolders)
        checkRefused("30 + 25 distinct new folders, untrusted: TooManyFolders, no state",
            mergeChecked("E14", FolderState(), l14, r14, MergePrefer.Remote), MergeStatus.TooManyFolders)
        val la = LinkedHashMap<String, String>()
        for (i in 1..10001) la[bulkToken(i)] = fid(1)
        val ra = LinkedHashMap<String, String>()
        for (i in 10002..20001) ra[bulkToken(i)] = fid(1)
        val lA = FolderState(listOf(F(1, "HQ")), false, la)
        val rA = FolderState(listOf(F(1, "HQ")), false, ra)
        checkRefused("10001 + 10000 distinct filings: TooManyAssignments, no state",
            mergeChecked("E14", st(F(1, "HQ")), lA, rA, MergePrefer.Local), MergeStatus.TooManyAssignments)
        checkRefused("the same, untrusted: TooManyAssignments, no state",
            mergeChecked("E14", FolderState(), lA, rA, MergePrefer.Remote), MergeStatus.TooManyAssignments)

        section("merge at the caps")
        val bAssign = LinkedHashMap<String, String>()
        for (i in 1..MAX_ASSIGNMENTS) bAssign[bulkToken(i)] = fid(((i - 1) % MAX_FOLDERS + 1).toLong())
        val b = FolderState(foldersNamed(1, MAX_FOLDERS, "Folder ").folders, false, bAssign)
        val lFolders = b.folders.toMutableList().also { it[0] = it[0].copy(name = "Head Office") }
        val l = FolderState(lFolders, false, LinkedHashMap(bAssign).also { it[bulkToken(1)] = fid(3) })
        val rFolders = b.folders.toMutableList().also { it[1] = it[1].copy(collapsed = true) }
        val r = FolderState(rFolders, false, LinkedHashMap(bAssign).also { it[bulkToken(2)] = fid(4) })
        check("the at-caps fixtures are sanitised", fixtureSanitised(b) && fixtureSanitised(l) && fixtureSanitised(r))
        val wantFolders = b.folders.toMutableList().also {
            it[0] = it[0].copy(name = "Head Office")
            it[1] = it[1].copy(collapsed = true)
        }
        val want = FolderState(wantFolders, false, LinkedHashMap(bAssign).also {
            it[bulkToken(1)] = fid(3)
            it[bulkToken(2)] = fid(4)
        })
        val atCaps = mergeFolderStates(b, l, r, MergePrefer.Local, en)
        checkMerge("50 folders and 20000 filings, a change on each side: Ok, both changes kept", atCaps, want)

        val lu = LinkedHashMap<String, String>()
        val ru = LinkedHashMap<String, String>()
        for (i in 1..MAX_ASSIGNMENTS) {
            val n = (i - 1) % MAX_FOLDERS + 1
            lu[bulkToken(i)] = fid(n.toLong())
            ru[bulkToken(i)] = fid((100 + n).toLong())
        }
        val luState = FolderState(foldersNamed(1, MAX_FOLDERS, "Folder ").folders, false, lu)
        val ruState = FolderState((1..MAX_FOLDERS).map { F(100 + it, "FOLDER $it") }, false, ru)
        val paired = mergeFolderStates(FolderState(), luState, ruState, MergePrefer.Remote, en)
        checkMerge("no base, 50 folders pair by name, 20000 filings re-pointed: Ok, the remote state", paired, ruState)
        checkCount("foldersPaired 50", paired.foldersPaired, MAX_FOLDERS)

        val b49 = foldersNamed(1, MAX_FOLDERS - 1, "Folder ")
        checkRefused("49 shared + 1 new each side = 51 folders: TooManyFolders",
            mergeChecked("one folder over", b49, FolderState(b49.folders + F(50, "Local new")), FolderState(b49.folders + F(51, "Remote new")), MergePrefer.Local),
            MergeStatus.TooManyFolders)
        val bA = LinkedHashMap<String, String>()
        for (i in 1 until MAX_ASSIGNMENTS) bA[bulkToken(i)] = fid(1)
        val lAA = FolderState(listOf(F(1, "HQ")), false, LinkedHashMap(bA).also { it[bulkToken(MAX_ASSIGNMENTS)] = fid(1) })
        val rAA = FolderState(listOf(F(1, "HQ")), false, LinkedHashMap(bA).also { it[bulkToken(MAX_ASSIGNMENTS + 1)] = fid(1) })
        checkRefused("19999 shared + 1 new filing each side = 20001: TooManyAssignments",
            mergeChecked("one filing over", FolderState(listOf(F(1, "HQ")), false, bA), lAA, rAA, MergePrefer.Local), MergeStatus.TooManyAssignments)

        section("merge self-check")
        checkRefused("a filing under a token that is not a Talk token: Invalid, no state",
            mergeFolderStates(st(F(1, "HQ")), st(F(1, "HQ")), FolderState(listOf(F(1, "HQ")), false, mapOf("NOT A TOKEN" to fid(1))), MergePrefer.Local, en),
            MergeStatus.Invalid)
        checkRefused("a name that normalisation would change: Invalid, no state",
            mergeFolderStates(FolderState(), st(F(1, " HQ ")), FolderState(), MergePrefer.Remote, en), MergeStatus.Invalid)
    }

    private fun testE24DroppedCounting() {
        section("merge E24 foldersDropped counting (the shrink brake's input)")
        val four = foldersNamed(1, 4, "Folder ")
        val all = mergeChecked("E24", four, four, FolderState(), MergePrefer.Local)
        checkMerge("B = L = F1..F4, R empty, trusted: M empty", all, FolderState())
        checkCount("foldersDropped 4 of 4", all.foldersDropped, 4)
        val six = foldersNamed(1, 6, "Folder ")
        val three = mergeChecked("E24", six, six, foldersNamed(1, 3, "Folder "), MergePrefer.Local)
        checkMerge("L F1..F6, R F1..F3: M F1..F3", three, foldersNamed(1, 3, "Folder "))
        checkCount("foldersDropped 3", three.foldersDropped, 3)
        checkCount("L F1..F6, R F1, F2: foldersDropped 4", mergeChecked("E24", six, six, foldersNamed(1, 2, "Folder "), MergePrefer.Local).foldersDropped, 4)
        val f3 = foldersNamed(1, 3, "Folder ")
        checkCount("L F1..F3, R F1: foldersDropped 2", mergeChecked("E24", f3, f3, foldersNamed(1, 1, "Folder "), MergePrefer.Local).foldersDropped, 2)
        val own = mergeChecked("E24", four, foldersNamed(1, 1, "Folder "), four, MergePrefer.Local)
        checkMerge("this device deleted F2..F4, R still has them: M F1", own, foldersNamed(1, 1, "Folder "))
        checkCount("a device's own deletion: foldersDropped 0", own.foldersDropped, 0)
        checkCount("a folder both sides deleted: foldersDropped 0",
            mergeChecked("E24", f3, foldersNamed(1, 1, "Folder "), foldersNamed(1, 1, "Folder "), MergePrefer.Local).foldersDropped, 0)
        val untrusted = mergeChecked("E24", FolderState(), four, FolderState(), MergePrefer.Remote)
        checkMerge("untrusted base, R empty: M is L, nothing removed", untrusted, four)
        checkCount("untrusted base: foldersDropped 0", untrusted.foldersDropped, 0)
    }

    // ---- the sidecar

    private val shaA = "ab".repeat(20)
    private val shaB = "cd".repeat(20)

    /** Every field a base can hold: three folders (one collapsed, one with a non-ASCII name), two filings, Other collapsed. */
    private fun richState(): FolderState =
        st(F(1, "HQ"), F(2, "Sales", true), F(3, "Café"), filed = listOf("chat0001" to 1, "chat0002" to 3), other = true)

    private fun baseText(s: FolderState): String = encodeFolders(s).decodeToString()

    /** [s] as a JSON string literal. */
    private fun jstr(s: String): String = StringBuilder().also { appendJsonString(it, s) }.toString()

    /** A compact sidecar built part by part; each argument is the raw JSON of the value, or null to leave the key out. */
    private fun sidecarWith(v: String?, sha: String?, base: String?): ByteArray {
        val parts = ArrayList<String>()
        if (v != null) parts.add("\"v\":$v")
        if (sha != null) parts.add("\"localSha\":$sha")
        if (base != null) parts.add("\"base\":$base")
        return bytes(parts.joinToString(",", "{", "}"))
    }

    /** What a non-Ok sidecar hands back must be NOTHING. */
    private fun checkSidecar(what: String, input: ByteArray, want: SidecarStatus) {
        val d = decodeSidecar(input)
        val carriesNothing = want == SidecarStatus.Ok || (d.sidecar.localSha.isEmpty() && d.sidecar.base == FolderState())
        val ok = d.status == want && carriesNothing
        check(what, ok, if (ok) "" else "got ${d.status}${if (carriesNothing) "" else " carrying a sha or a base"}, want $want")
    }

    private fun testSidecar() {
        section("sidecar round trip")
        val s = richState()
        check("the fixture is sanitised", fixtureSanitised(s))
        val encoded = encodeSidecar(s, shaA)
        val d = decodeSidecar(encoded)
        check("a sidecar read back: Ok, with its localSha and its base", d.status == SidecarStatus.Ok && d.sidecar.localSha == shaA && d.sidecar.base == s)
        val root = readFlatJsonObject(encoded.decodeToString())
        check("the file is { v, localSha, base } and nothing else", root != null && root.keys == setOf("v", "localSha", "base"))
        check("\"v\" is the number 1", (root?.get("v") as? JNum)?.value == 1.0)
        check("\"localSha\" is a JSON string holding the sha", (root?.get("localSha") as? JStr)?.value == shaA)
        check("\"base\" is a JSON STRING holding exactly the text encodeFolders writes", (root?.get("base") as? JStr)?.value == baseText(s))
        check("encoding is deterministic", encodeSidecar(s, shaA).contentEquals(encoded))
        check("re-encoding what was read gives the same bytes", encodeSidecar(d.sidecar.base, d.sidecar.localSha).contentEquals(encoded))
        val other = decodeSidecar(encodeSidecar(s, shaB))
        check("another sha reads back as that sha, with the same base", other.status == SidecarStatus.Ok && other.sidecar.localSha == shaB && other.sidecar.base == s)
        val empty = decodeSidecar(encodeSidecar(FolderState(), shaA))
        check("an empty base round trips as Ok", empty.status == SidecarStatus.Ok && empty.sidecar.base == FolderState())
        val collapsedOnly = decodeSidecar(encodeSidecar(st(other = true), shaA))
        check("a base that is only the Other collapsed flag keeps the flag", collapsedOnly.status == SidecarStatus.Ok && collapsedOnly.sidecar.base.otherCollapsed)
        val bigAssign = LinkedHashMap<String, String>()
        for (i in 1..MAX_ASSIGNMENTS) bigAssign[bulkToken(i)] = fid((1 + i % MAX_FOLDERS).toLong())
        val big = FolderState(manyFolders(MAX_FOLDERS).folders, false, bigAssign)
        val bigBytes = encodeSidecar(big, shaA)
        check("a sidecar for 50 folders and 20000 filings is under MAX_SIDECAR_BYTES, and reads back whole",
            bigBytes.size < MAX_SIDECAR_BYTES && decodeSidecar(bigBytes).let { it.status == SidecarStatus.Ok && it.sidecar.base == big })

        section("sidecar with an empty localSha")
        val noSha = decodeSidecar(encodeSidecar(s, ""))
        check("written with no sha: Ok, an empty localSha, the base intact", noSha.status == SidecarStatus.Ok && noSha.sidecar.localSha.isEmpty() && noSha.sidecar.base == s)
        check("the key is written as an empty string, not left out",
            (readFlatJsonObject(encodeSidecar(s, "").decodeToString())?.get("localSha") as? JStr)?.value == "")
        checkSidecar("hand-written \"localSha\": \"\": Ok", sidecarWith("1", jstr(""), jstr(baseText(s))), SidecarStatus.Ok)
        checkSidecar("a MISSING localSha is not the empty string: Unreadable", sidecarWith("1", null, jstr(baseText(s))), SidecarStatus.Unreadable)
    }

    private fun testSidecarHostile() {
        section("sidecar hostile input")
        val s = richState()
        val okV = "1"
        val okSha = jstr(shaA)
        val okBase = jstr(baseText(s))
        val valid = sidecarWith(okV, okSha, okBase)
        checkSidecar("control: every part valid reads Ok (each case below breaks ONE part)", valid, SidecarStatus.Ok)
        checkSidecar("a valid sidecar of exactly MAX_SIDECAR_BYTES: Ok", valid + ByteArray(MAX_SIDECAR_BYTES - valid.size) { 0x20 }, SidecarStatus.Ok)
        checkSidecar("a valid sidecar one byte over MAX_SIDECAR_BYTES: Unreadable", valid + ByteArray(MAX_SIDECAR_BYTES + 1 - valid.size) { 0x20 }, SidecarStatus.Unreadable)
        checkSidecar("zero bytes: Unreadable (never Absent: that is the file layer's)", ByteArray(0), SidecarStatus.Unreadable)
        checkSidecar("whitespace only: Unreadable", bytes(" \r\n\t"), SidecarStatus.Unreadable)
        checkSidecar("not JSON: Unreadable", bytes("this is not json"), SidecarStatus.Unreadable)
        checkSidecar("a root that is an array: Unreadable", bytes("[]"), SidecarStatus.Unreadable)
        checkSidecar("a root that is a number: Unreadable", bytes("42"), SidecarStatus.Unreadable)
        checkSidecar("a root that is a string: Unreadable", bytes("\"text\""), SidecarStatus.Unreadable)
        checkSidecar("a root that is null: Unreadable", bytes("null"), SidecarStatus.Unreadable)
        checkSidecar("an empty object: Unreadable", bytes("{}"), SidecarStatus.Unreadable)
        checkSidecar("a key twice: Unreadable", bytes("{\"v\":1,\"v\":1,\"localSha\":$okSha,\"base\":$okBase}"), SidecarStatus.Unreadable)
        var anyTruncationAccepted = false
        for (len in 0 until valid.size) {
            if (decodeSidecar(valid.copyOf(len)).status != SidecarStatus.Unreadable) anyTruncationAccepted = true
        }
        check("every proper prefix of a valid sidecar is Unreadable", !anyTruncationAccepted)

        for ((what, v) in listOf("no \"v\"" to null, "\"v\" a string" to "\"1\"", "\"v\" null" to "null", "\"v\" a boolean" to "true",
            "\"v\":0" to "0", "\"v\":-1" to "-1", "\"v\":1.5" to "1.5", "\"v\":2.5 (not a version at all, so not Newer)" to "2.5",
            "\"v\":1e400 (not finite)" to "1e400")) {
            checkSidecar("$what: Unreadable", sidecarWith(v, okSha, okBase), SidecarStatus.Unreadable)
        }
        checkSidecar("\"v\":2 with valid parts: Newer", sidecarWith("2", okSha, okBase), SidecarStatus.Newer)
        checkSidecar("\"v\":99: Newer", sidecarWith("99", okSha, okBase), SidecarStatus.Newer)
        checkSidecar("\"v\":2 and nothing else: Newer", bytes("{\"v\":2}"), SidecarStatus.Newer)
        checkSidecar("\"v\":2 with a localSha that is a number and no base: Newer (the version decides)", sidecarWith("2", "5", null), SidecarStatus.Newer)

        for ((what, sha) in listOf("no localSha" to null, "localSha null" to "null", "localSha a number" to "5", "localSha a boolean" to "false",
            "localSha an array" to "[]", "localSha an object" to "{}", "localSha a short string" to jstr("abc"),
            "localSha of 39 hex digits" to jstr(shaA.take(39)), "localSha of 41 hex digits" to jstr(shaA + "a"),
            "localSha of 40 hex digits and a newline" to jstr(shaA + "\n"), "localSha behind a space" to jstr(" " + shaA.take(39)),
            "localSha in upper case" to jstr(shaA.uppercase()), "localSha with a letter that is not hex" to jstr("g" + shaA.drop(1)),
            "localSha of 40 non-ASCII characters" to jstr("é".repeat(40)), "localSha of 40 spaces" to jstr(" ".repeat(40)))) {
            checkSidecar("$what: Unreadable", sidecarWith(okV, sha, okBase), SidecarStatus.Unreadable)
        }

        for ((what, base) in listOf("no base" to null, "base null" to "null", "base a number" to "5", "base a boolean" to "true",
            "base an array" to "[]", "base the state as nested JSON instead of a string" to baseText(s),
            "base \"\"" to jstr(""), "base of whitespace only" to jstr(" \r\n\t"), "base of a byte-order mark alone" to jstr("﻿"),
            "base not JSON" to jstr("this is not json"), "base cut short" to jstr(baseText(s).take(30)), "base whose root is an array" to jstr("[]"),
            "base with no \"v\"" to jstr("{}"), "base with \"folders\" of the wrong type" to jstr("{\"v\":1,\"folders\":{}}"),
            "base written as local format v2 (Unreadable, not Newer)" to jstr(encodeFolders(s, 2).decodeToString()),
            "base of 1001 folder entries" to jstr(baseText(manyFolders(MAX_FOLDER_ENTRIES_EXAMINED + 1))),
            "base of 51 folders (repaired)" to jstr(baseText(manyFolders(MAX_FOLDERS + 1))),
            "base with a folder id that is not an id (repaired)" to jstr(baseText(FolderState(listOf(F(1, "HQ"), Folder("not-an-id", "Sales", false))))),
            "base with a duplicate id (repaired)" to jstr(baseText(FolderState(listOf(F(1, "HQ"), F(1, "Sales"))))),
            "base with a token that is not a Talk token (repaired)" to jstr(baseText(FolderState(listOf(F(1, "HQ")), false, mapOf("NOT A TOKEN" to fid(1))))),
            "base with an orphan filing (repaired)" to jstr(baseText(FolderState(listOf(F(1, "HQ")), false, mapOf("chat0001" to fid(1), "chat0002" to fid(9))))),
            "base with a name normalisation changes (repaired)" to jstr(baseText(FolderState(listOf(F(1, " HQ "))))))) {
            checkSidecar("$what: Unreadable", sidecarWith(okV, okSha, base), SidecarStatus.Unreadable)
        }
    }

    // ---- chooseBase

    private fun trustedBase(): FolderState = st(F(1, "HQ"), F(2, "Sales", true), filed = listOf("chat0001" to 1, "chat0002" to 2), other = true)

    /** Inputs on which NO rule fires. */
    private fun passing(): BaseInputs = BaseInputs(
        sidecarStatus = SidecarStatus.Ok, sidecar = Sidecar(shaA, trustedBase()), outcome = FolderLoadOutcome.Ok,
        loadedSha = shaA, localEmpty = false, localEditCount = 0, stateUnchangedSinceAttach = true,
    )

    /** Inputs as an attach builds them: the sidecar READ BACK from its bytes; the local file loaded Ok. */
    private fun attachedWith(base: FolderState, sidecarSha: String, loadedSha: String): BaseInputs {
        val d = decodeSidecar(encodeSidecar(base, sidecarSha))
        return BaseInputs(sidecarStatus = d.status, sidecar = d.sidecar, outcome = FolderLoadOutcome.Ok, loadedSha = loadedSha)
    }

    private fun checkChoice(what: String, input: BaseInputs, want: BaseWhy) {
        val c = chooseBase(input)
        val wantTrusted = want == BaseWhy.Trusted
        val baseOk = if (wantTrusted) c.base == input.sidecar.base else c.base == FolderState()
        val ok = c.why == want && c.trusted == wantTrusted && baseOk
        check(what, ok, if (ok) "" else "got ${c.why}, trusted ${c.trusted}, base ${if (baseOk) "as wanted" else "WRONG"}")
    }

    private fun preferFor(c: BaseChoice): MergePrefer = if (c.trusted) MergePrefer.Local else MergePrefer.Remote

    private fun testChooseBase() {
        section("chooseBase")
        check("fixture: the passing base is non-empty and carries otherCollapsed", !isEmptyFolderState(passing().sidecar.base) && passing().sidecar.base.otherCollapsed)
        checkChoice("no rule fires: Trusted, and the base is the sidecar's own", passing(), BaseWhy.Trusted)
        checkChoice("local edits this session do not by themselves distrust the base", passing().copy(localEditCount = 7), BaseWhy.Trusted)

        checkChoice("rule 1: no sidecar is NoSidecar", passing().copy(sidecarStatus = SidecarStatus.Absent), BaseWhy.NoSidecar)
        checkChoice("rule 1: an unreadable sidecar is SidecarUnreadable", passing().copy(sidecarStatus = SidecarStatus.Unreadable), BaseWhy.SidecarUnreadable)
        checkChoice("rule 1: a newer sidecar is SidecarNewer", passing().copy(sidecarStatus = SidecarStatus.Newer), BaseWhy.SidecarNewer)

        for (o in listOf(FolderLoadOutcome.Detached, FolderLoadOutcome.Missing, FolderLoadOutcome.Repaired, FolderLoadOutcome.SetAside, FolderLoadOutcome.ReadOnlyLoad)) {
            checkChoice("rule 2: outcome $o is LocalLoadNotClean", passing().copy(outcome = o), BaseWhy.LocalLoadNotClean)
        }
        val bothEmpty = passing().copy(sidecar = Sidecar("", trustedBase()), outcome = FolderLoadOutcome.Missing, loadedSha = "", localEmpty = true)
        checkChoice("rule 2: an empty localSha and a Missing file (both shas empty) is untrusted", bothEmpty, BaseWhy.LocalLoadNotClean)
        checkChoice("rule 2: an empty localSha and a SetAside file is untrusted", bothEmpty.copy(outcome = FolderLoadOutcome.SetAside), BaseWhy.LocalLoadNotClean)

        checkChoice("rule 3: a different sha is LocalChangedByOther", passing().copy(loadedSha = shaB), BaseWhy.LocalChangedByOther)
        checkChoice("rule 3: a sidecar written when no file existed, now a file exists", passing().copy(sidecar = Sidecar("", trustedBase())), BaseWhy.LocalChangedByOther)
        checkChoice("rule 3: a clean load with no sha at all never matches a real sha", passing().copy(loadedSha = ""), BaseWhy.LocalChangedByOther)
        checkChoice("rule 3: a clean load and a sidecar that BOTH have no sha is still not a match",
            passing().copy(loadedSha = "", sidecar = Sidecar("", trustedBase())), BaseWhy.LocalChangedByOther)

        val r4 = passing().copy(localEmpty = true, localEditCount = 0, stateUnchangedSinceAttach = false)
        checkChoice("rule 4: local empty, base not, no edit, state moved: EmptyLocalWithoutEdit", r4, BaseWhy.EmptyLocalWithoutEdit)
        checkChoice("rule 4 needs the local state to be empty", r4.copy(localEmpty = false), BaseWhy.Trusted)
        checkChoice("rule 4 needs a base with something in it", r4.copy(sidecar = Sidecar(shaA, FolderState())), BaseWhy.Trusted)
        checkChoice("rule 4: a base that is only otherCollapsed counts as empty", r4.copy(sidecar = Sidecar(shaA, st(other = true))), BaseWhy.Trusted)
        checkChoice("rule 4: a base of one folder and no filings is not empty", r4.copy(sidecar = Sidecar(shaA, st(F(1, "HQ")))), BaseWhy.EmptyLocalWithoutEdit)
        checkChoice("rule 4 needs no edit this session", r4.copy(localEditCount = 1), BaseWhy.Trusted)
        checkChoice("rule 4 needs the state to have moved since attach", r4.copy(stateUnchangedSinceAttach = true), BaseWhy.Trusted)

        checkChoice("order: rule 1 before rule 2", passing().copy(sidecarStatus = SidecarStatus.Absent, outcome = FolderLoadOutcome.Missing), BaseWhy.NoSidecar)
        checkChoice("order: rule 1 before rule 3", passing().copy(sidecarStatus = SidecarStatus.Unreadable, loadedSha = shaB), BaseWhy.SidecarUnreadable)
        checkChoice("order: rule 1 before rule 4",
            passing().copy(sidecarStatus = SidecarStatus.Newer, localEmpty = true, stateUnchangedSinceAttach = false), BaseWhy.SidecarNewer)
        checkChoice("order: rule 2 before rule 3", passing().copy(outcome = FolderLoadOutcome.Repaired, loadedSha = shaB), BaseWhy.LocalLoadNotClean)
        checkChoice("order: rule 2 before rule 4",
            passing().copy(outcome = FolderLoadOutcome.SetAside, loadedSha = "", localEmpty = true, stateUnchangedSinceAttach = false), BaseWhy.LocalLoadNotClean)
        checkChoice("order: rule 3 before rule 4",
            passing().copy(loadedSha = shaB, localEmpty = true, stateUnchangedSinceAttach = false), BaseWhy.LocalChangedByOther)
        checkChoice("order: all four fire, rule 1 decides",
            passing().copy(sidecarStatus = SidecarStatus.Absent, outcome = FolderLoadOutcome.Missing, loadedSha = shaB, localEmpty = true, stateUnchangedSinceAttach = false),
            BaseWhy.NoSidecar)

        val fromBytes = attachedWith(trustedBase(), shaA, shaA)
        checkChoice("from bytes: a sidecar read back and a matching file is Trusted", fromBytes, BaseWhy.Trusted)
        val bad = decodeSidecar(bytes("not json"))
        checkChoice("from bytes: an unreadable sidecar is SidecarUnreadable", fromBytes.copy(sidecarStatus = bad.status, sidecar = bad.sidecar), BaseWhy.SidecarUnreadable)
        val newer = decodeSidecar(bytes("{\"v\":2}"))
        checkChoice("from bytes: a newer sidecar is SidecarNewer", fromBytes.copy(sidecarStatus = newer.status, sidecar = newer.sidecar), BaseWhy.SidecarNewer)
        checkChoice("from bytes: a sidecar with an empty localSha beside a Missing file is untrusted",
            attachedWith(trustedBase(), "", "").copy(outcome = FolderLoadOutcome.Missing, localEmpty = true), BaseWhy.LocalLoadNotClean)
    }

    private fun testE15ToE17() {
        section("E15 deleted everything, on purpose")
        val b = st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 1, "chat0002" to 2))
        val l = FolderState()
        val sameSession = attachedWith(b, shaA, shaA).copy(localEmpty = true, localEditCount = 2, stateUnchangedSinceAttach = false)
        checkChoice("(i) same session, after edits: Trusted", sameSession, BaseWhy.Trusted)
        val c1 = chooseBase(sameSession)
        checkMerge("(i) the merge deletes: M is empty and is what gets uploaded", c1.base, l, b, preferFor(c1), FolderState())
        val restart = attachedWith(b, shaA, shaA).copy(localEmpty = true, localEditCount = 0, stateUnchangedSinceAttach = true)
        checkChoice("(ii) after a restart, matching localSha, state unchanged: Trusted", restart, BaseWhy.Trusted)
        val c2 = chooseBase(restart)
        checkMerge("(ii) the merge deletes: M is empty", c2.base, l, b, preferFor(c2), FolderState())
        val hand = restart.copy(loadedSha = shaB)
        checkChoice("(ii) but an empty file that is NOT the one this app wrote: LocalChangedByOther", hand, BaseWhy.LocalChangedByOther)
        val c3 = chooseBase(hand)
        checkMerge("(ii) so the deletion does not propagate: M is the union, both folders stay", c3.base, l, b, preferFor(c3), b)
        checkChoice("(ii) and a state that moved with no edit and no merge: EmptyLocalWithoutEdit (E16)",
            restart.copy(stateUnchangedSinceAttach = false), BaseWhy.EmptyLocalWithoutEdit)

        section("E16 empty with no explanation")
        val b16 = st(F(1, "HQ"), F(2, "Sales"), filed = listOf("chat0001" to 1))
        val in16 = attachedWith(b16, shaA, shaA).copy(localEmpty = true, localEditCount = 0, stateUnchangedSinceAttach = false)
        checkChoice("empty with no edit and a moved state: EmptyLocalWithoutEdit", in16, BaseWhy.EmptyLocalWithoutEdit)
        val c16 = chooseBase(in16)
        checkMerge("untrusted, so the merge is a union: M is F1, F2 and the filing", c16.base, FolderState(), b16, preferFor(c16), b16)
        checkMerge("control: had the base been believed, M would be empty (a mass deletion)", b16, FolderState(), b16, MergePrefer.Local, FolderState())
        checkChoice("an empty file that does not match localSha fails rule 3 instead",
            attachedWith(b16, shaA, shaB).copy(localEmpty = true, stateUnchangedSinceAttach = true), BaseWhy.LocalChangedByOther)
        checkChoice("a Missing file fails rule 2 instead",
            attachedWith(b16, shaA, "").copy(outcome = FolderLoadOutcome.Missing, localEmpty = true, stateUnchangedSinceAttach = true), BaseWhy.LocalLoadNotClean)

        section("E17 a file written by someone else in between")
        val b17 = manyFolders(10)
        val l17 = st(F(1, "Folder 1"))
        val in17 = attachedWith(b17, shaA, shaB)
        checkChoice("the sidecar's localSha differs from the loaded bytes: LocalChangedByOther", in17, BaseWhy.LocalChangedByOther)
        val c17 = chooseBase(in17)
        val m17 = mergeChecked("E17 untrusted", c17.base, l17, b17, preferFor(c17))
        check("untrusted: the merge is a union and keeps all ten folders", m17.status == MergeStatus.Ok && m17.merged.folders.size == 10)
        val believed = mergeChecked("E17 control", b17, l17, b17, MergePrefer.Local)
        check("control: had the base been believed, nine folders would have been deleted", believed.status == MergeStatus.Ok && believed.merged.folders.size == 1)
    }

    // ---- E20 idempotence on random inputs (the property, with this file's own random stream)

    private val SYNC_NAME_POOL = listOf("HQ", "hq", "Hq", "Sales", "SALES", "Support", "Clients", "Archive")

    private fun pick(rng: Lcg, n: Int): Int = rng.next() % n

    private fun poolToken(i: Int): String = "chat" + (i + 1).toString().padStart(4, '0')

    /** Folder numbers unique within one triple, drawn at random so id order is not creation order. */
    private class FolderNumbers {
        val used = HashSet<Long>()

        fun fresh(rng: Lcg): Long {
            while (true) {
                val n = 1L + rng.next() % 4095
                if (used.add(n)) return n
            }
        }
    }

    private class SyncTriple(val b: FolderState, val l: FolderState, val r: FolderState, val shape: String)

    /** A random sanitised state over [pool]: a random subset in random order, pool names (twins included), some collapsed, about a third of 30 chats filed. */
    private fun randomState(rng: Lcg, pool: List<Long>): FolderState {
        val order = pool.toMutableList()
        for (i in order.size - 1 downTo 1) {
            val j = pick(rng, i + 1)
            val t = order[i]
            order[i] = order[j]
            order[j] = t
        }
        val count = pick(rng, order.size + 1)
        val folders = ArrayList<Folder>()
        for (i in 0 until count) folders.add(Folder(fid(order[i]), SYNC_NAME_POOL[pick(rng, SYNC_NAME_POOL.size)], pick(rng, 4) == 0))
        val other = pick(rng, 2) == 0
        val assign = LinkedHashMap<String, String>()
        if (folders.isNotEmpty()) {
            for (t in 0 until 30) {
                if (pick(rng, 3) != 0) continue
                assign[poolToken(t)] = folders[pick(rng, folders.size)].id
            }
        }
        return FolderState(folders, other, assign)
    }

    /** One user operation, made with the model's own mutations, so every state stays one the app can hold. */
    private fun randomOp(s: FolderState, rng: Lcg, numbers: FolderNumbers): FolderState {
        val op = pick(rng, 8)
        if (op == 0) return createFolder(s, SYNC_NAME_POOL[pick(rng, SYNC_NAME_POOL.size)], numbers.fresh(rng), en).state
        if (op == 7) return setOtherCollapsed(s, !s.otherCollapsed).state
        if (op == 4) {
            val token = poolToken(pick(rng, 30))
            if (s.folders.isEmpty()) return s
            return assignToken(s, token, s.folders[pick(rng, s.folders.size)].id).state
        }
        if (op == 5) {
            val filed = s.assign.keys.sorted()
            if (filed.isEmpty()) return s
            return assignToken(s, filed[pick(rng, filed.size)], "").state
        }
        if (s.folders.isEmpty()) return s
        val f = s.folders[pick(rng, s.folders.size)]
        return when (op) {
            1 -> renameFolder(s, f.id, SYNC_NAME_POOL[pick(rng, SYNC_NAME_POOL.size)], en).state
            2 -> deleteFolder(s, f.id).state
            3 -> moveFolder(s, f.id, if (pick(rng, 2) == 0) -1 else 1).state
            else -> setFolderCollapsed(s, f.id, !f.collapsed).state
        }
    }

    /** Half DERIVED (a random B, and L and R are B after up to six operations each), half INDEPENDENT (three random states over one pool); a fifth with the empty base. */
    private fun randomTriple(rng: Lcg): SyncTriple {
        val numbers = FolderNumbers()
        var b: FolderState
        var l: FolderState
        var r: FolderState
        val shape: String
        if (pick(rng, 2) == 0) {
            shape = "derived"
            val pool = List(8) { numbers.fresh(rng) }
            b = randomState(rng, pool)
            l = b
            r = b
            repeat(pick(rng, 7)) { l = randomOp(l, rng, numbers) }
            repeat(pick(rng, 7)) { r = randomOp(r, rng, numbers) }
        } else {
            shape = "independent"
            val pool = List(10) { numbers.fresh(rng) }
            b = randomState(rng, pool)
            l = randomState(rng, pool)
            r = randomState(rng, pool)
        }
        if (pick(rng, 5) == 0) b = FolderState()
        return SyncTriple(b, l, r, shape)
    }

    /** E20 on one (B, L, R): with M = merge(B, L, R), merge(B, L, M) == M, merge(B, M, M) == M and sanitise(M) changes nothing. Empty when it holds. */
    private fun idempotenceFailure(b: FolderState, l: FolderState, r: FolderState, prefer: MergePrefer): String {
        val m = mergeFolderStates(b, l, r, prefer, en)
        if (m.status != MergeStatus.Ok) return "merge(B, L, R) refused: ${m.status}"
        if (sanitiseFolderState(m.merged).changed) return "sanitiseFolderState(M) changed M ${describe(m.merged)}"
        val again = mergeFolderStates(b, l, m.merged, prefer, en)
        if (again.status != MergeStatus.Ok || again.merged != m.merged) {
            return "merge(B, L, M) != M: M ${describe(m.merged)}, merge(B, L, M) ${describe(again.merged)} (${again.status})"
        }
        val self = mergeFolderStates(b, m.merged, m.merged, prefer, en)
        if (self.status != MergeStatus.Ok || self.merged != m.merged) {
            return "merge(B, M, M) != M: M ${describe(m.merged)}, merge(B, M, M) ${describe(self.merged)} (${self.status})"
        }
        return ""
    }

    private fun testE20Idempotence() {
        section("E20 idempotence (property: 20 seeds x 300 random triples, both preferences)")
        for (seed in 1..20) {
            val rng = Lcg(seed * 1_000_003L + 17L)
            var failure = ""
            var i = 0
            while (i < 300 && failure.isEmpty()) {
                val t = randomTriple(rng)
                for (prefer in listOf(MergePrefer.Local, MergePrefer.Remote)) {
                    // prefer Remote means an untrusted base, and an untrusted base IS the empty state.
                    val b = if (prefer == MergePrefer.Remote) FolderState() else t.b
                    val why = idempotenceFailure(b, t.l, t.r, prefer)
                    if (why.isNotEmpty()) {
                        failure = "case $i (${t.shape}, $prefer): $why; B ${describe(b)}; L ${describe(t.l)}; R ${describe(t.r)}"
                        break
                    }
                }
                i++
            }
            check("E20 seed $seed: re-merging the result changes nothing", failure.isEmpty(), failure)
        }
    }

    // ---- FolderSyncPolicy

    private fun testSyncPolicy() {
        section("classifyDav")
        val table = listOf(
            200 to DavOutcome.Ok, 204 to DavOutcome.Ok, 201 to DavOutcome.Created, 202 to DavOutcome.Ok, 206 to DavOutcome.Ok,
            207 to DavOutcome.Ok, 299 to DavOutcome.Ok, 404 to DavOutcome.NotFound, 412 to DavOutcome.PreconditionFailed,
            401 to DavOutcome.AuthRefused, 403 to DavOutcome.AuthRefused, 409 to DavOutcome.Conflict, 413 to DavOutcome.PayloadTooLarge,
            423 to DavOutcome.Locked, 507 to DavOutcome.QuotaFull, 0 to DavOutcome.Transient, 408 to DavOutcome.Transient,
            429 to DavOutcome.Transient, 500 to DavOutcome.Transient, 502 to DavOutcome.Transient, 503 to DavOutcome.Transient,
            504 to DavOutcome.Transient,
        ) + listOf(100, 199, 300, 301, 304, 307, 400, 405, 410, 415, 422, 501, 505, 511, 599, 600, -1).map { it to DavOutcome.Fatal }
        for ((status, want) in table) {
            val got = classifyDav(status, false)
            check("$status: $want", got == want, if (got == want) "" else "got $got")
        }
        check("aborted wins over every status (0, 2xx, 4xx, 5xx, 999)",
            listOf(0, 200, 201, 204, 401, 404, 412, 413, 423, 500, 503, 507, 999).all { classifyDav(it, true) == DavOutcome.Cancelled })

        section("nextBackoffMs")
        val ladder = longArrayOf(30_000, 60_000, 120_000, 300_000, 600_000, 900_000)
        fun expectMs(failures: Int, unit: Double, want: Long) {
            val got = nextBackoffMs(failures, unit)
            check("nextBackoffMs($failures, $unit) == $want", got == want, if (got == want) "" else "got $got")
        }
        for (i in 0 until 6) expectMs(i + 1, 0.5, ladder[i])
        expectMs(0, 0.5, 30_000)
        expectMs(-1, 0.5, 30_000)
        expectMs(7, 0.5, 900_000)
        expectMs(10, 0.5, 900_000)
        expectMs(1000, 0.5, 900_000)
        expectMs(Int.MAX_VALUE, 0.5, 900_000)
        for (i in 0 until 6) {
            expectMs(i + 1, 0.0, ladder[i] * 8 / 10)
            expectMs(i + 1, 0.25, ladder[i] * 9 / 10)
            expectMs(i + 1, 0.75, ladder[i] * 11 / 10)
        }
        val justBelowOne = 1.0.nextDown()
        check("just below unit 1: above 1.19x and at most 1.2x on every step",
            (0 until 6).all { val got = nextBackoffMs(it + 1, justBelowOne); got * 100 > ladder[it] * 119 && got * 100 <= ladder[it] * 120 })
        var inBounds = true
        for (f in -3..200) for (u in listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.9, justBelowOne)) {
            val ms = nextBackoffMs(f, u)
            if (ms < 24_000 || ms > 18 * 60_000) inBounds = false
        }
        check("never below 24 s and never above 18 min", inBounds)
        expectMs(1, 0.00005, 24_001)
        expectMs(1, 0.00001, 24_000)
        expectMs(1, -5.0, 24_000)
        expectMs(1, 7.0, 36_000)
        expectMs(6, 7.0, 18 * 60_000L)
        expectMs(1, Double.NaN, 24_000)

        section("remoteShrinkBrake (dropped of local)")
        val fires = listOf(1 to 1, 2 to 2, 3 to 4, 4 to 6, 4 to 4, 3 to 3, 5 to 5, 50 to 50, 3 to 5, 4 to 7, 5 to 8, 26 to 50)
        val holds = listOf(2 to 3, 3 to 6, 0 to 0, 0 to 3, 1 to 2, 1 to 3, 2 to 4, 2 to 5, 1 to 50, 3 to 7, 4 to 8, 25 to 50, 3 to 40)
        for ((dropped, local) in fires) check("dropped $dropped of local $local: brake fires", remoteShrinkBrake(local, dropped))
        for ((dropped, local) in holds) check("dropped $dropped of local $local: no brake", !remoteShrinkBrake(local, dropped))

        section("uploadAllowed, transientNoticeDue, the timing defaults")
        check("empty merge over an absent or zero-byte remote: not allowed", !uploadAllowed(true, true))
        check("empty merge over a readable remote: allowed (a deliberate delete-everything)", uploadAllowed(true, false))
        check("non-empty merge over an absent remote: allowed", uploadAllowed(false, true))
        check("non-empty merge over a readable remote: allowed", uploadAllowed(false, false))
        val tenMin = 10L * 60 * 1000
        check("3 failures over exactly 10 min, reachable: due", transientNoticeDue(3, tenMin, true))
        check("2 failures over 10 min: not due", !transientNoticeDue(2, tenMin, true))
        check("3 failures over 10 min minus 1 ms: not due", !transientNoticeDue(3, tenMin - 1, true))
        check("3 failures over 10 min, the server not reachable: not due", !transientNoticeDue(3, tenMin, false))
        check("no failures: not due", !transientNoticeDue(0, 0, true))
        val t = FolderSyncTiming()
        check("the defaults are desktop's numbers",
            t.loginDelayMs == 3_000L && t.activationGapMs == 120_000L && t.pushGapMs == 15_000L && t.backoffOverrideGapMs == 30_000L &&
                t.editDebounceMs == 10_000L && t.editMaxWaitMs == 60_000L && t.pollMs == 600_000L && t.getTimeoutMs == 20_000L &&
                t.putTimeoutMs == 30_000L && t.applyGatePollMs == 500L && t.applyGateGiveUpMs == 30_000L &&
                t.conflictRetryMinMs == 200L && t.conflictRetryMaxMs == 800L && t.replaceGapMs == 1_200L && t.backoffScale == 1.0)
        check("the replace gap is MORE than one second", t.replaceGapMs > 1000)
        check("MAX_PUT_ATTEMPTS 3, MAX_STALE_RESTARTS 5, no notice for a device's own upload",
            MAX_PUT_ATTEMPTS == 3 && MAX_STALE_RESTARTS == 5 && !NOTIFY_ON_UPLOAD)
        check("FolderSyncState and FolderSyncPause have the zero values Off and None",
            FolderSyncState.entries.first() == FolderSyncState.Off && FolderSyncPause.entries.first() == FolderSyncPause.None)
    }

    private fun testSelfInconsistent412Tracker() {
        section("SelfInconsistent412Tracker")
        val e1 = "\"etag-one\""
        val e2 = "\"etag-two\""
        val fresh = SelfInconsistent412Tracker()
        check("a new tracker has count 0 and does not pause", fresh.count == 0 && !fresh.shouldPause)
        val t = SelfInconsistent412Tracker()
        t.putFailed412(e1)
        t.nextGet(e1)
        check("one 412 whose re-GET returned the same ETag: count 1, no pause", t.count == 1 && !t.shouldPause)
        t.putFailed412(e1)
        check("a second 412 alone changes nothing until its re-GET returns", t.count == 1 && !t.shouldPause)
        t.nextGet(e1)
        check("a second one: count 2 and shouldPause", t.count == 2 && t.shouldPause)
        val race = SelfInconsistent412Tracker()
        race.putFailed412(e1)
        race.nextGet(e2)
        check("a re-GET with a DIFFERENT ETag is a race: count stays 0", race.count == 0)
        race.putFailed412(e1)
        race.nextGet(e1)
        race.putFailed412(e1)
        race.nextGet(e2)
        check("a race after a count resets it to 0", race.count == 0)
        val plain = SelfInconsistent412Tracker()
        plain.nextGet(e1)
        plain.nextGet(e1)
        check("an ordinary GET with no failed PUT never counts", plain.count == 0)
        plain.putFailed412(e1)
        plain.nextGet(e1)
        plain.nextGet(e1)
        plain.nextGet(e2)
        check("only the GET right after the failed PUT counts, and a later ordinary GET does not clear it", plain.count == 1)
        val across = SelfInconsistent412Tracker()
        across.putFailed412(e1)
        across.nextGet(e1)
        across.nextGet(e1)
        across.putFailed412(e1)
        across.nextGet(e1)
        check("two consecutive cycles, each with one same-ETag 412, pause", across.shouldPause)
        across.reset()
        check("reset() clears the count", across.count == 0 && !across.shouldPause)
        val armed = SelfInconsistent412Tracker()
        armed.putFailed412(e1)
        armed.reset()
        armed.nextGet(e1)
        check("reset() drops an armed failed PUT", armed.count == 0)
        val exact = SelfInconsistent412Tracker()
        exact.putFailed412("\"abc\"")
        exact.nextGet("W/\"abc\"")
        check("\"abc\" and W/\"abc\" are different ETags: a race", exact.count == 0)
        exact.putFailed412("\"abc\"")
        exact.nextGet("\"ABC\"")
        check("ETags that differ only in case are different ETags", exact.count == 0)
        exact.putFailed412("\"abc\" ")
        exact.nextGet("\"abc\"")
        check("a trailing space makes it a different ETag (no trimming)", exact.count == 0)
        exact.putFailed412("W/\"abc\"")
        exact.nextGet("W/\"abc\"")
        check("identical weak ETags count", exact.count == 1)
        val empties = SelfInconsistent412Tracker()
        empties.putFailed412(e1)
        empties.nextGet(e1)
        empties.putFailed412("")
        empties.nextGet("")
        check("an empty ETag on both sides never counts, and resets like a race", empties.count == 0)
    }

    // ---- the cycle and the probe against an in-memory server

    /** A monotonic clock that moves only when the code under check sleeps or the fake server answers. */
    private class FakeClock : FolderSyncClock {
        var now = 1_000L
        val sleeps = ArrayList<Long>()

        override fun nowMs(): Long = now

        override suspend fun sleep(ms: Long) {
            sleeps.add(ms)
            if (ms > 0) now += ms
        }
    }

    private class Req(val method: String, val name: String, val ifMatch: String?, val sentAt: Long, val status: Int)

    /**
     * A WebDAV home that honours If-Match and If-None-Match: * as the measured server does (412 on a
     * failed precondition, 201 on a create, 204 on a replace, a new ETag on every write). Every
     * request takes [latencyMs] on [clock].
     */
    private class FakeRemote(val clock: FakeClock) : FolderRemote {
        override var accountKnown = true
        val files = HashMap<String, ByteArray>()
        val etags = HashMap<String, String>()
        val log = ArrayList<Req>()
        val latencyMs = 40L
        private var nextTag = 1

        /** Runs once, right before the next PUT is judged: another device writing in between. */
        var beforePut: (() -> Unit)? = null

        /** Runs before EVERY PUT is judged: a file that keeps changing. */
        var racer: (() -> Unit)? = null

        /** Answer every If-Match PUT with 412 and keep the file: a server that refuses its own ETag. */
        var refuseIfMatch = false

        /** Store every PUT whatever its precondition: a server the probe must refuse. */
        var ignorePreconditions = false

        var sendEtags = true

        /** One-shot answers. */
        var forcedGet: DavGet? = null
        var forcedPut: Int? = null

        fun write(name: String, body: ByteArray) {
            files[name] = body
            etags[name] = "\"e" + nextTag++ + "\""
        }

        override suspend fun get(name: String): DavGet {
            val sent = clock.now
            clock.now += latencyMs
            val forced = forcedGet
            val answer = when {
                forced != null -> {
                    forcedGet = null
                    forced
                }
                name !in files -> DavGet(404)
                else -> DavGet(200, if (sendEtags) etags.getValue(name) else "", files.getValue(name).copyOf())
            }
            log.add(Req("GET", name, null, sent, answer.status))
            return answer
        }

        override suspend fun put(name: String, body: ByteArray, ifMatch: String?): Int {
            val sent = clock.now
            clock.now += latencyMs
            beforePut?.let {
                beforePut = null
                it()
            }
            racer?.invoke()
            val forced = forcedPut
            val exists = name in files
            val status = when {
                forced != null -> {
                    forcedPut = null
                    forced
                }
                ignorePreconditions -> {
                    write(name, body.copyOf())
                    if (exists) 204 else 201
                }
                ifMatch == null -> if (exists) 412 else {
                    write(name, body.copyOf())
                    201
                }
                refuseIfMatch -> 412
                !exists || etags[name] != ifMatch -> 412
                else -> {
                    write(name, body.copyOf())
                    204
                }
            }
            log.add(Req("PUT", name, ifMatch, sent, status))
            return status
        }

        override suspend fun delete(name: String): Int {
            val sent = clock.now
            clock.now += latencyMs
            val status = if (files.remove(name) != null) {
                etags.remove(name)
                204
            } else {
                404
            }
            log.add(Req("DELETE", name, null, sent, status))
            return status
        }
    }

    /** The store as the cycle sees it: a generation that moves on every change, a Stale apply when it moved. */
    private class FakeLocal(var state: FolderState) : FolderLocal {
        var generation = 1L
        var applies = 0

        /** Runs once inside the next applyMerged, before the generation is compared: the user edits meanwhile. */
        var editBeforeApply: (() -> Unit)? = null

        override fun isAttached(): Boolean = true

        override fun snapshot(): LocalSnapshot = LocalSnapshot(generation, state)

        override suspend fun applyMerged(expectedGeneration: Long, merged: FolderState): ApplyResult {
            editBeforeApply?.let {
                editBeforeApply = null
                it()
            }
            if (expectedGeneration != generation) return ApplyResult.Stale
            if (merged == state) return ApplyResult.Unchanged
            state = merged
            generation++
            applies++
            return ApplyResult.Applied
        }

        fun edit(s: FolderState) {
            state = s
            generation++
        }
    }

    private fun trusted(base: FolderState): BaseChoice = BaseChoice(true, base, BaseWhy.Trusted)

    private fun cycle(
        remote: FakeRemote,
        local: FakeLocal,
        choice: BaseChoice = BaseChoice(),
        tracker: SelfInconsistent412Tracker = SelfInconsistent412Tracker(),
        acceptShownShrink: Boolean = false,
    ): CycleResult = runBlocking {
        runFolderSyncCycle(remote, local, { choice }, tracker, acceptShownShrink, FolderSyncTiming(), remote.clock, Random(7), en)
    }

    private fun methods(remote: FakeRemote): String = remote.log.joinToString(" ") { "${it.method}:${it.status}" }

    private fun synced(r: CycleResult): CycleEnd.Synced? = r.end as? CycleEnd.Synced

    private fun pausedBy(r: CycleResult): FolderSyncPause? = (r.end as? CycleEnd.Paused)?.pause

    private fun testCycle() {
        section("cycle: the remote file is absent (404)")
        run {
            val remote = FakeRemote(FakeClock())
            val l = st(F(1, "HQ"), filed = listOf("chat0001" to 1))
            val r = cycle(remote, FakeLocal(l))
            val s = synced(r)
            check("Synced, uploaded, nothing to apply", s != null && s.uploaded && !s.applied, r.outcomeName)
            check("GET 404, then ONE PUT with If-None-Match: * (no If-Match), answered 201", methods(remote) == "GET:404 PUT:201" && remote.log[1].ifMatch == null)
            check("a create does not wait for the replace gap", remote.log[1].sentAt == remote.log[0].sentAt + remote.latencyMs)
            val body = remote.files[REMOTE_FOLDERS_FILE]
            check("the body is encodeFolders(L, 1), and desktop reads it as Present holding L",
                body != null && body.contentEquals(encodeFolders(l, REMOTE_VERSION)) && decodeRemote(body) == RemoteDecode(RemoteKind.Present, l))
            check("its log line: outcome=uploaded, 2 requests, http=2xx",
                folderSyncCycleLine(r.outcomeName, "sync-now", r.lastStatus, r.requests, r.elapsedMs).startsWith("FolderSync: cycle outcome=uploaded origin=sync-now http=2xx requests=2 ms="))
        }
        run {
            val remote = FakeRemote(FakeClock())
            val r = cycle(remote, FakeLocal(st(other = true)))
            val s = synced(r)
            check("an EMPTY local state over an absent remote: Synced with nothing sent (D26)",
                s != null && !s.uploaded && methods(remote) == "GET:404" && remote.files.isEmpty(), methods(remote))
        }
        run {
            val remote = FakeRemote(FakeClock())
            val r = cycle(remote, FakeLocal(FolderState(listOf(F(1, " HQ ")))))
            check("a local state desktop would have to repair is never uploaded, not even over an absent remote: Paused(Fatal)",
                pausedBy(r) == FolderSyncPause.Fatal && methods(remote) == "GET:404" && remote.files.isEmpty(), methods(remote))
        }

        section("cycle: a replace waits the replace gap and sends the GET's ETag")
        run {
            val clock = FakeClock()
            val remote = FakeRemote(clock)
            val b = st(F(1, "HQ"))
            remote.write(REMOTE_FOLDERS_FILE, encodeFolders(b, REMOTE_VERSION))
            val etag = remote.etags.getValue(REMOTE_FOLDERS_FILE)
            val local = FakeLocal(st(F(1, "HQ"), F(2, "Sales")))
            val r = cycle(remote, local, trusted(b))
            val s = synced(r)
            check("Synced, uploaded, nothing to apply", s != null && s.uploaded && !s.applied, r.outcomeName)
            check("GET 200 then PUT 204 with If-Match the GET's ETag, byte for byte", methods(remote) == "GET:200 PUT:204" && remote.log[1].ifMatch == etag)
            val gap = remote.log[1].sentAt - (remote.log[0].sentAt + remote.latencyMs)
            check("the PUT went out 1200 ms after the GET's answer", gap == 1_200L, "gap $gap ms")
            check("the remote now holds the local state", decodeRemote(remote.files.getValue(REMOTE_FOLDERS_FILE)).state == local.state)
        }
        run {
            val remote = FakeRemote(FakeClock())
            val b = st(F(1, "HQ"), filed = listOf("chat0001" to 1))
            remote.write(REMOTE_FOLDERS_FILE, bytes("{\n  \"v\": 1, \"note\": \"by hand\", \"assign\": {\"chat0001\": \"${fid(1)}\"}, \"folders\": [{\"name\": \"HQ\", \"id\": \"${fid(1)}\"}]}"))
            val before = remote.files.getValue(REMOTE_FOLDERS_FILE)
            val r = cycle(remote, FakeLocal(b), trusted(b))
            check("the same STATE in other bytes (other key order, an unknown key): no PUT, the bytes stay",
                r.outcomeName == "no-change" && methods(remote) == "GET:200" && remote.files.getValue(REMOTE_FOLDERS_FILE).contentEquals(before), methods(remote))
        }
        run {
            val remote = FakeRemote(FakeClock())
            remote.write(REMOTE_FOLDERS_FILE, ByteArray(0))
            val etag = remote.etags.getValue(REMOTE_FOLDERS_FILE)
            val r = cycle(remote, FakeLocal(st(F(1, "HQ"))))
            check("a zero-byte remote is 'nobody wrote yet': replaced with If-Match its ETag (not If-None-Match), after the gap",
                synced(r)?.uploaded == true && methods(remote) == "GET:200 PUT:204" && remote.log[1].ifMatch == etag &&
                    remote.log[1].sentAt - (remote.log[0].sentAt + remote.latencyMs) == 1_200L, methods(remote))
        }
        run {
            val remote = FakeRemote(FakeClock())
            val b = st(F(1, "HQ"))
            remote.write(REMOTE_FOLDERS_FILE, encodeFolders(b, REMOTE_VERSION))
            remote.sendEtags = false
            val r = cycle(remote, FakeLocal(st(F(1, "HQ"), F(2, "Sales"))), trusted(b))
            check("a readable remote without an ETag is never written unconditionally: Paused(Fatal), no PUT",
                pausedBy(r) == FolderSyncPause.Fatal && methods(remote) == "GET:200", methods(remote))
        }

        section("cycle: a 412 goes back to the GET and merges again")
        run {
            val clock = FakeClock()
            val remote = FakeRemote(clock)
            val b = st(F(1, "HQ"))
            remote.write(REMOTE_FOLDERS_FILE, encodeFolders(b, REMOTE_VERSION))
            remote.beforePut = { remote.write(REMOTE_FOLDERS_FILE, encodeFolders(st(F(1, "HQ"), F(3, "Support")), REMOTE_VERSION)) }
            val local = FakeLocal(st(F(1, "HQ"), F(2, "Sales")))
            val tracker = SelfInconsistent412Tracker()
            val r = cycle(remote, local, trusted(b), tracker)
            val want = st(F(1, "HQ"), F(3, "Support"), F(2, "Sales"))
            check("GET, PUT 412, GET, PUT 204", methods(remote) == "GET:200 PUT:412 GET:200 PUT:204", methods(remote))
            // e1 = the first file, e2 = the other device's write, e3 = this device's successful write.
            check("the second PUT is conditioned on the second GET's ETag", remote.log[1].ifMatch == "\"e1\"" && remote.log[3].ifMatch == "\"e2\"")
            check("the re-GET waited a random 200..800 ms", clock.sleeps.any { it in 200L..800L }, clock.sleeps.toString())
            check("both changes are kept, on the server and here", decodeRemote(remote.files.getValue(REMOTE_FOLDERS_FILE)).state == want && local.state == want)
            check("outcome uploaded+applied, 4 requests, the 412 count back to 0", r.outcomeName == "uploaded+applied" && r.requests == 4 && tracker.count == 0, r.outcomeName)
        }
        run {
            val remote = FakeRemote(FakeClock())
            val b = st(F(1, "HQ"))
            remote.write(REMOTE_FOLDERS_FILE, encodeFolders(b, REMOTE_VERSION))
            remote.refuseIfMatch = true
            val tracker = SelfInconsistent412Tracker()
            val r = cycle(remote, FakeLocal(st(F(1, "HQ"), F(2, "Sales"))), trusted(b), tracker)
            check("a server that refuses the ETag it just handed out, twice: Paused(ServerUnsupported)",
                pausedBy(r) == FolderSyncPause.ServerUnsupported && methods(remote) == "GET:200 PUT:412 GET:200 PUT:412 GET:200", methods(remote))
        }
        run {
            val remote = FakeRemote(FakeClock())
            val b = st(F(1, "HQ"))
            remote.write(REMOTE_FOLDERS_FILE, encodeFolders(b, REMOTE_VERSION))
            var flip = false
            remote.racer = {
                flip = !flip
                remote.write(REMOTE_FOLDERS_FILE, encodeFolders(st(F(1, "HQ", flip)), REMOTE_VERSION))
            }
            val r = cycle(remote, FakeLocal(st(F(1, "HQ"), F(2, "Sales"))), trusted(b))
            check("a file that keeps changing: three PUTs, then a transient failure (backoff)",
                r.end is CycleEnd.Failed && remote.log.count { it.method == "PUT" } == MAX_PUT_ATTEMPTS && r.requests == 6, methods(remote))
        }

        section("cycle: what is never merged or overwritten")
        run {
            val remote = FakeRemote(FakeClock())
            remote.write(REMOTE_FOLDERS_FILE, encodeFolders(FolderState(listOf(F(1, " HQ "))), REMOTE_VERSION))
            val before = remote.files.getValue(REMOTE_FOLDERS_FILE)
            val r = cycle(remote, FakeLocal(st(F(2, "Sales"))))
            check("a remote that needs repair: Paused(RemoteUnreadable), no PUT, left as it is",
                pausedBy(r) == FolderSyncPause.RemoteUnreadable && methods(remote) == "GET:200" && remote.files.getValue(REMOTE_FOLDERS_FILE).contentEquals(before))
        }
        run {
            val remote = FakeRemote(FakeClock())
            remote.write(REMOTE_FOLDERS_FILE, bytes("{\"v\":2}"))
            val r = cycle(remote, FakeLocal(st(F(2, "Sales"))))
            check("a remote of a newer version: Paused(RemoteNewer), no PUT", pausedBy(r) == FolderSyncPause.RemoteNewer && methods(remote) == "GET:200")
        }
        run {
            val remote = FakeRemote(FakeClock())
            remote.forcedGet = DavGet(200, "\"big\"", oversize = true)
            val r = cycle(remote, FakeLocal(st(F(2, "Sales"))))
            check("a download over 4 MiB: Paused(RemoteUnreadable)", pausedBy(r) == FolderSyncPause.RemoteUnreadable)
        }
        for (status in listOf(404, 401, 503)) {
            // Desktop reads its size abort before the status: an error answer over the cap is not a 404's create.
            val remote = FakeRemote(FakeClock())
            remote.forcedGet = DavGet(status, oversize = true)
            val r = cycle(remote, FakeLocal(st(F(2, "Sales"))))
            check("an error answer $status over 4 MiB: Paused(RemoteUnreadable), nothing more sent",
                pausedBy(r) == FolderSyncPause.RemoteUnreadable && remote.log.size == 1, r.outcomeName)
        }
        for ((status, want) in listOf(401 to "paused:AuthRefused", 403 to "paused:AuthRefused", 503 to "failed", 0 to "failed",
            423 to "failed", 507 to "paused:QuotaFull", 301 to "paused:Fatal", 409 to "paused:Fatal")) {
            val remote = FakeRemote(FakeClock())
            remote.forcedGet = DavGet(status)
            val r = cycle(remote, FakeLocal(st(F(2, "Sales"))))
            check("GET answered $status: $want, nothing more sent", r.outcomeName == want && remote.log.size == 1, r.outcomeName)
        }
        run {
            val remote = FakeRemote(FakeClock())
            remote.write(REMOTE_FOLDERS_FILE, encodeFolders(st(F(1, "HQ")), REMOTE_VERSION))
            remote.forcedPut = 413
            val r = cycle(remote, FakeLocal(st(F(1, "HQ"), F(2, "Sales"))), trusted(st(F(1, "HQ"))))
            check("PUT answered 413: Paused(ServerSizeLimit)", pausedBy(r) == FolderSyncPause.ServerSizeLimit)
        }
        run {
            val remote = FakeRemote(FakeClock())
            remote.accountKnown = false
            val r = cycle(remote, FakeLocal(st(F(1, "HQ"))))
            check("the account's user id is not known: AccountNotReady, no request", r.end == CycleEnd.AccountNotReady && remote.log.isEmpty() && r.requests == 0)
        }

        section("cycle: the shrink brake")
        run {
            val remote = FakeRemote(FakeClock())
            val four = foldersNamed(1, 4, "Folder ")
            remote.write(REMOTE_FOLDERS_FILE, bytes("{\"v\":1}"))
            val local = FakeLocal(four)
            val r = cycle(remote, local, trusted(four))
            val p = r.end as? CycleEnd.Paused
            check("the remote would remove all 4 folders: Paused(RemoteShrank), 4 of 4, nothing applied or sent",
                p != null && p.pause == FolderSyncPause.RemoteShrank && p.shrinkDropped == 4 && p.shrinkOf == 4 &&
                    methods(remote) == "GET:200" && local.state == four && local.applies == 0)
            val accepted = cycle(remote, local, trusted(four), acceptShownShrink = true)
            check("Sync now after that pause accepts it: applied, local empty, nothing uploaded (it equals the remote)",
                synced(accepted)?.applied == true && synced(accepted)?.uploaded == false && local.state == FolderState(), accepted.outcomeName)
        }

        section("cycle: a user edit during the cycle (Stale) takes a new snapshot")
        run {
            val remote = FakeRemote(FakeClock())
            val b = st(F(1, "HQ"))
            remote.write(REMOTE_FOLDERS_FILE, encodeFolders(st(F(1, "HQ"), F(2, "Sales")), REMOTE_VERSION))
            val local = FakeLocal(b)
            local.editBeforeApply = { local.edit(st(F(1, "HQ"), F(3, "Clients"))) }
            val r = cycle(remote, local, trusted(b))
            val want = st(F(1, "HQ"), F(2, "Sales"), F(3, "Clients"))
            check("the first apply is Stale; the second pass GETs again, merges the edit in, uploads and applies",
                methods(remote) == "GET:200 GET:200 PUT:204" && local.state == want && decodeRemote(remote.files.getValue(REMOTE_FOLDERS_FILE)).state == want &&
                    r.outcomeName == "uploaded+applied", methods(remote))
        }

        section("cycle: a remote apply in the trusted three-way merge (E2 through the cycle)")
        run {
            val remote = FakeRemote(FakeClock())
            val b = st(F(1, "HQ"))
            val rState = st(F(1, "HQ"), F(2, "Sales"))
            remote.write(REMOTE_FOLDERS_FILE, encodeFolders(rState, REMOTE_VERSION))
            val local = FakeLocal(b)
            val r = cycle(remote, local, trusted(b))
            val s = synced(r)
            check("one GET, no PUT, the local state is the remote's", methods(remote) == "GET:200" && local.state == rState && r.outcomeName == "applied")
            check("its diff is visible (a folder was added), so it is worth a notice", s != null && s.diff.foldersAdded == 1 && s.diff.visible)
        }
    }

    private fun testProbe() {
        section("the capability probe")
        run {
            val remote = FakeRemote(FakeClock())
            val p = runBlocking { runFolderProbe(remote, Random(3)) }
            check("a server that honours conditional writes: Pass", p.verdict == ProbeVerdict.Pass && p.stoppedAtStep == 0)
            check("PUT 201, GET 200, PUT 412, PUT 412, PUT 204, DELETE 204: 6 requests",
                methods(remote) == "PUT:201 GET:200 PUT:412 PUT:412 PUT:204 DELETE:204" && p.requests == 6, methods(remote))
            val name = remote.log[0].name
            check("on a scratch file .talq-folders.probe-<8 lowercase hex>, never the shared file",
                name.startsWith(PROBE_FILE_PREFIX) && name.length == PROBE_FILE_PREFIX.length + 8 &&
                    name.drop(PROBE_FILE_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' } && remote.log.all { it.name == name })
            check("steps 1 and 3 create (no If-Match), step 4 sends the bogus ETag, step 5 the GET's",
                remote.log[0].ifMatch == null && remote.log[2].ifMatch == null && remote.log[3].ifMatch == PROBE_BOGUS_ETAG && remote.log[4].ifMatch == "\"e1\"")
            check("the scratch file is gone afterwards", remote.files.isEmpty())
        }
        run {
            val remote = FakeRemote(FakeClock())
            remote.ignorePreconditions = true
            val p = runBlocking { runFolderProbe(remote, Random(3)) }
            check("a server that ignores If-None-Match: Refused at step 3, the scratch file deleted",
                p.verdict == ProbeVerdict.Refused && p.stoppedAtStep == 3 && methods(remote) == "PUT:201 GET:200 PUT:204 DELETE:204" && remote.files.isEmpty(), methods(remote))
        }
        run {
            val remote = FakeRemote(FakeClock())
            remote.sendEtags = false
            val p = runBlocking { runFolderProbe(remote, Random(3)) }
            check("a server that sends no ETag: Refused at step 2, the scratch file deleted",
                p.verdict == ProbeVerdict.Refused && p.stoppedAtStep == 2 && methods(remote) == "PUT:201 GET:200 DELETE:204", methods(remote))
        }
        run {
            val remote = FakeRemote(FakeClock())
            remote.forcedPut = 401
            val p = runBlocking { runFolderProbe(remote, Random(3)) }
            check("401 at step 1: AuthRefused, and no DELETE", p.verdict == ProbeVerdict.AuthRefused && methods(remote) == "PUT:401", methods(remote))
        }
        run {
            val remote = FakeRemote(FakeClock())
            remote.forcedPut = 503
            val p = runBlocking { runFolderProbe(remote, Random(3)) }
            check("503 at step 1: CouldNotRun, and the DELETE is still sent (the file may exist)",
                p.verdict == ProbeVerdict.CouldNotRun && methods(remote) == "PUT:503 DELETE:404", methods(remote))
        }
        run {
            val remote = FakeRemote(FakeClock())
            remote.write(PROBE_FILE_PREFIX + "x", ByteArray(0))
            remote.forcedPut = 412
            val p = runBlocking { runFolderProbe(remote, Random(3)) }
            check("412 at step 1 (the name is not ours): Refused, and no DELETE", p.verdict == ProbeVerdict.Refused && methods(remote) == "PUT:412", methods(remote))
        }
        run {
            val remote = FakeRemote(FakeClock())
            remote.accountKnown = false
            val p = runBlocking { runFolderProbe(remote, Random(3)) }
            check("the user id is not known: CouldNotRun, nothing sent", p.verdict == ProbeVerdict.CouldNotRun && remote.log.isEmpty())
        }
    }
}
