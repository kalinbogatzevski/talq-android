package talq.core.folders

import android.icu.lang.UCharacter
import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

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

// ICU's default (non-Turkic) simple folding: exactly CaseFolding.txt's C and S lines.
internal actual fun caseFold(cp: Int): Int = UCharacter.foldCase(cp, true)

// The same collator settings as TalQ for desktop's folder order: the locale's rules, secondary
// strength (case ignored, accents kept), canonical normalisation on, numeric collation on,
// punctuation not ignored.
actual fun platformNameCollator(languageTag: String?): NameCollator {
    val locale = if (languageTag == null) Locale.getDefault() else Locale.forLanguageTag(languageTag)
    val shared = Collator.getInstance(locale)
    val c = if (shared.isFrozen) shared.cloneAsThawed() else shared
    c.setStrength(Collator.SECONDARY)
    c.setDecomposition(Collator.CANONICAL_DECOMPOSITION)
    if (c is RuleBasedCollator) {
        c.setNumericCollation(true)
        c.setAlternateHandlingShifted(false)
    }
    return NameCollator { a, b -> c.compare(a, b) }
}
