package talq.core.model

// Whether a text is too long for the server, known before it is sent. Ported from TalQ for desktop's
// src/core/MessageLengthPolicy.h (`check`, `checkCaption`); the number here is what must go, for the
// composer's one line. A limit of 0 or less is an unknown one and never blocks.

/**
 * How many characters [text] is over the server's [limit], 0 when it fits. Counted as the server does
 * (`mb_strlen`): code points, so an emoji is one.
 */
fun messageOver(text: String, limit: Int): Int {
    // Never more code points than UTF-16 units: an ordinary text is not scanned.
    if (limit <= 0 || text.length <= limit) return 0
    var count = 0
    var at = 0
    while (at < text.length) {
        at += unitsAt(text, at)
        count++
    }
    return maxOf(0, count - limit)
}

/**
 * The same for the caption of a file: how many characters must go from its end. Talk keeps a caption
 * inside the JSON of the file's message (PHP's `json_encode`) and holds THAT to the limit: six characters
 * for each letter outside ASCII, twelve for an emoji, two for a quote, a slash or a line break.
 */
fun captionOver(text: String, limit: Int): Int {
    val budget = limit - FILE_MESSAGE_RESERVE
    if (budget <= 0 || text.length * 6L <= budget) return 0
    var spent = 0L
    var over = 0
    var at = 0
    while (at < text.length) {
        val units = unitsAt(text, at)
        spent += if (units == 2) 12 else jsonLength(text[at])
        if (spent > budget) over++
        at += units
    }
    return over
}

/** UTF-16 units of the code point at [at]. */
private fun unitsAt(text: String, at: Int): Int =
    if (text[at].isHighSurrogate() && at + 1 < text.length && text[at + 1].isLowSurrogate()) 2 else 1

/** Characters PHP's `json_encode` spends on one character inside a string. */
private fun jsonLength(c: Char): Int = when {
    c == '"' || c == '\\' || c == '/' || c == '\b' || c == '\u000C' || c == '\n' || c == '\r' || c == '\t' -> 2
    c.code < 0x20 || c.code >= 0x80 -> 6
    else -> 1
}

/** What the rest of a file message's JSON costs (keys, ids, the mime type), as desktop reserves it. */
private const val FILE_MESSAGE_RESERVE = 250
