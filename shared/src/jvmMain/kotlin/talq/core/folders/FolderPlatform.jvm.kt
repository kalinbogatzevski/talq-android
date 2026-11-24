package talq.core.folders

import java.security.MessageDigest
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

// The JVM target only runs the offline checks (:shared:folderCheck) and the smoke run; the phone
// uses the androidMain actuals. Where the JDK lacks what the system's ICU has (simple case folding,
// numeric collation), this is the closest plain-JDK equivalent, good for the desktop test vectors.

internal actual fun sha1Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
    val out = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"

internal actual fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

internal actual fun codePointType(cp: Int): Int = Character.getType(cp)

// Upper then lower case matches simple case folding for every code point except the two Turkic i's,
// which fold to themselves (CaseFolding.txt gives them only T and F lines).
internal actual fun caseFold(cp: Int): Int =
    if (cp == 0x130 || cp == 0x131) cp else Character.toLowerCase(Character.toUpperCase(cp))

// java.text.Collator at secondary strength with canonical decomposition, plus what it lacks: runs
// of digits are compared by their value, the rest run by run with the collator.
actual fun platformNameCollator(languageTag: String?): NameCollator {
    val locale = if (languageTag == null) Locale.getDefault() else Locale.forLanguageTag(languageTag)
    val c = Collator.getInstance(locale)
    c.strength = Collator.SECONDARY
    c.decomposition = Collator.CANONICAL_DECOMPOSITION
    return NameCollator { a, b -> compareWithDigitRuns(c, a, b) }
}

private fun compareWithDigitRuns(c: Collator, a: String, b: String): Int {
    val ra = runsOf(a)
    val rb = runsOf(b)
    val n = minOf(ra.size, rb.size)
    for (i in 0 until n) {
        val x = ra[i]
        val y = rb[i]
        val byRun = if (isDigitRun(x) && isDigitRun(y)) compareNumbers(x, y) else c.compare(x, y)
        if (byRun != 0) return byRun
    }
    return ra.size.compareTo(rb.size)
}

private fun isDigitRun(s: String): Boolean = s.isNotEmpty() && Character.isDigit(s.codePointAt(0))

// Splits into maximal runs of decimal digits and of everything else.
private fun runsOf(s: String): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < s.length) {
        val digit = Character.isDigit(s.codePointAt(i))
        val start = i
        while (i < s.length && Character.isDigit(s.codePointAt(i)) == digit)
            i += Character.charCount(s.codePointAt(i))
        out.add(s.substring(start, i))
    }
    return out
}

// By value, leading zeros ignored (as ICU's numeric collation does).
private fun compareNumbers(x: String, y: String): Int {
    val dx = digitValues(x).dropWhile { it == 0 }
    val dy = digitValues(y).dropWhile { it == 0 }
    if (dx.size != dy.size) return dx.size.compareTo(dy.size)
    for (i in dx.indices) {
        if (dx[i] != dy[i]) return dx[i].compareTo(dy[i])
    }
    return 0
}

private fun digitValues(s: String): List<Int> {
    val out = ArrayList<Int>()
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        out.add(Character.digit(cp, 10))
        i += Character.charCount(cp)
    }
    return out
}
