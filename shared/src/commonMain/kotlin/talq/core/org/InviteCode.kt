package talq.core.org

/**
 * Invite codes for organisation mode (spec: docs/superpowers/specs/2026-11-27-organisation-mode-design.md).
 *
 * A code is 16 symbols of Crockford's base32 (80 random bits, drawn by the owner's issuing tool) and
 * one check symbol, shown as `XXXX-XXXX-XXXX-XXXX-C`. Everything that protects an organisation rests
 * on those 80 bits: nothing about a code may be derived from a name, a date or a counter, and the
 * code itself is never stored, logged or copied by the app.
 *
 * The check symbol is `CROCKFORD[sha256(the 16 symbols)[0] mod 32]`. It only catches typing mistakes
 * before any request is made; it adds no secrecy and takes none away (it is not part of the key).
 */
const val CROCKFORD: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/** How many symbols a code has, without its check symbol. */
const val CODE_SYMBOLS: Int = 16

/** What a typed or pasted code turned out to be. */
sealed interface CodeInput {
    /** A whole code whose check symbol matches; [symbols] are the 16 canonical symbols. */
    data class Valid(val symbols: String) : CodeInput {
        /** Never the code: this may end up in a log. */
        override fun toString(): String = "Valid(…)"
    }

    /** Fewer symbols than a code has: still being typed. */
    data object Incomplete : CodeInput

    /** All symbols are there, but the check symbol does not match: a typing mistake. */
    data object BadCheck : CodeInput

    /** A character that is in no code (U, punctuation other than dashes, too many symbols). */
    data object BadSymbol : CodeInput
}

/** Separators a pasted code may carry: any white space (line breaks, no-break spaces) and the dashes. */
private fun isCodeSeparator(ch: Char): Boolean =
    ch.isWhitespace() || ch == '-' || ch in '\u2010'..'\u2015' || ch == '\u2212'

/**
 * Reads a code the way a person types or pastes it: case, white space and dashes (typographic ones
 * too) do not matter, `I` and `L` read as `1` and `O` as `0` (Crockford's rules). Nothing else is
 * accepted.
 */
fun parseInviteCode(input: String): CodeInput {
    val symbols = StringBuilder(CODE_SYMBOLS + 1)
    for (ch in input) {
        if (isCodeSeparator(ch)) continue
        val c = when (val up = ch.uppercaseChar()) {
            'I', 'L' -> '1'
            'O' -> '0'
            else -> up
        }
        if (c !in CROCKFORD) return CodeInput.BadSymbol
        symbols.append(c)
        if (symbols.length > CODE_SYMBOLS + 1) return CodeInput.BadSymbol
    }
    if (symbols.length < CODE_SYMBOLS + 1) return CodeInput.Incomplete
    val body = symbols.substring(0, CODE_SYMBOLS)
    return if (symbols[CODE_SYMBOLS] == checkSymbol(body)) CodeInput.Valid(body) else CodeInput.BadCheck
}

/** What the code field shows, and where its caret goes. */
class ShapedCode(val text: String, val caret: Int)

/**
 * Shapes what is being typed or pasted into the code field: capitals, and a dash after every fourth
 * symbol up to the check symbol (`XXXX-XXXX-XXXX-XXXX-C`), put in only once the next symbol is there.
 * Spaces, line breaks and every kind of dash a paste may carry go; any other character stays, in
 * capitals, so [parseInviteCode] can still say what is wrong. [caret] (an index into [text]) keeps its
 * place among the symbols. A dash deleted on its own (backspace right after it, [before] being the field's
 * text until then) takes the symbol before it: the dash alone would only come back.
 * Letters are not mapped (`I`, `L`, `O` stay as typed): [parseInviteCode] reads them.
 */
fun shapeInviteCode(text: String, caret: Int, before: String = ""): ShapedCode {
    val kept = StringBuilder(text.length)
    var symbolsBeforeCaret = 0
    text.forEachIndexed { i, ch ->
        if (isCodeSeparator(ch)) return@forEachIndexed
        if (i < caret) symbolsBeforeCaret++
        kept.append(ch.uppercaseChar())
    }
    var symbols = kept.toString()
    val onlyADashWent = text.length == before.length - 1 &&
        symbols == before.filterNot(::isCodeSeparator).map { it.uppercaseChar() }.joinToString("")
    if (onlyADashWent && symbolsBeforeCaret > 0) {
        symbols = symbols.removeRange(symbolsBeforeCaret - 1, symbolsBeforeCaret)
        symbolsBeforeCaret--
    }
    val out = StringBuilder(symbols.length + 4)
    var at = -1
    symbols.forEachIndexed { i, c ->
        if (i > 0 && i % 4 == 0 && i <= CODE_SYMBOLS) out.append('-')
        if (i == symbolsBeforeCaret) at = out.length
        out.append(c)
    }
    return ShapedCode(out.toString(), if (at < 0) out.length else at)
}

/** The check symbol of the 16 canonical [symbols]. */
fun checkSymbol(symbols: String): Char {
    require(symbols.length == CODE_SYMBOLS) { "a code has $CODE_SYMBOLS symbols" }
    val first = sha256(symbols.encodeToByteArray())[0].toInt() and 0xFF
    return CROCKFORD[first % 32]
}

/** The code as it is shown and handed out: `XXXX-XXXX-XXXX-XXXX-C`. */
fun formatInviteCode(symbols: String): String {
    require(symbols.length == CODE_SYMBOLS) { "a code has $CODE_SYMBOLS symbols" }
    return symbols.chunked(4).joinToString("-") + "-" + checkSymbol(symbols)
}

/**
 * A new code's 16 symbols from 10 random bytes (exactly 80 bits, five per symbol, most significant
 * first, so every symbol is equally likely). [randomBytes] must be a cryptographic generator: the
 * issuing tool passes `SecureRandom`.
 */
fun newInviteSymbols(randomBytes: (Int) -> ByteArray): String {
    val bytes = randomBytes(10)
    require(bytes.size == 10) { "10 random bytes are needed" }
    val out = StringBuilder(CODE_SYMBOLS)
    var acc = 0
    var bits = 0
    for (b in bytes) {
        acc = (acc shl 8) or (b.toInt() and 0xFF)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            out.append(CROCKFORD[(acc shr bits) and 31])
        }
        acc = acc and ((1 shl bits) - 1)
    }
    return out.toString()
}

/**
 * What could be a code in running text: 17 letters or digits, in groups of four or not, apart by
 * any run of the separators [parseInviteCode] takes (white space, no-break and other Unicode spaces,
 * dashes, typographic dashes). Only the 4-4-4-4-1 grouping is looked for: a sentence whose letters
 * happen to add up to 17 is not taken for a code.
 */
private val CODE_LIKE = Regex("""(?i)\b[0-9a-z]{4}(?:[\s\p{Z}\-\u2010-\u2015\u2212]*[0-9a-z]{4}){3}[\s\p{Z}\-\u2010-\u2015\u2212]*[0-9a-z]\b""")

/**
 * [line] with every invite code in it replaced by `<hidden>` (for the Diagnostics log): whatever
 * reads as 17 code symbols, also with a wrong check symbol (a mistyped code still carries the 16 real
 * ones). A word with a letter no code has (U, most punctuation) stays, so exception names survive.
 */
fun hideInviteCodes(line: String): String =
    CODE_LIKE.replace(line) {
        when (parseInviteCode(it.value)) {
            is CodeInput.Valid, CodeInput.BadCheck -> "<hidden>"
            else -> it.value
        }
    }
