package talq.app.ui.message

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import talq.app.theme.TalqColors
import talq.core.model.ChatMessage
import talq.core.model.RichObject

/**
 * True only for a real web link. Links in messages come from other users (rich objects can be
 * posted with any link), so a prefix test like startsWith("http") is not enough: "httpx://..."
 * would hand an arbitrary scheme to another app.
 */
internal fun isWebUrl(link: String): Boolean {
    val scheme = link.substringBefore(':', missingDelimiterValue = "").lowercase()
    return (scheme == "http" || scheme == "https") && link.length > scheme.length + 3 &&
        link.regionMatches(scheme.length, "://", 0, 3)
}

/** A message body cut into what a bubble stacks: running text and fenced code blocks. */
sealed interface TextBlock {
    data class Rich(val text: AnnotatedString) : TextBlock
    data class Code(val code: String) : TextBlock
}

/** Colours the rich text needs; taken from the theme at the call site. */
data class RichTextColors(
    val link: Color,
    val mentionInk: Color,
    val mentionFill: Color,
    val selfMentionInk: Color,
    val selfMentionFill: Color,
    val codeBg: Color,
    val muted: Color,
) {
    companion object {
        fun from(c: TalqColors) = RichTextColors(
            link = c.link,
            mentionInk = c.accentSoftInk,
            mentionFill = c.accentSoft,
            selfMentionInk = c.controlInk,
            selfMentionFill = c.accent,
            codeBg = c.codeBg,
            muted = c.textMuted,
        )
    }
}

private val URL = Regex("""https?://[^\s<>"']+""")
private const val FENCE = "```"

/**
 * Message text for display: `{placeholders}` become mention pills or names, links become
 * tappable, and (for markdown messages) bold, italic, strikethrough, inline code and fenced
 * code blocks are styled. File placeholders are dropped: files are drawn as attachments.
 *
 * @param myUserId mentions of this user (and of everyone) get the stronger pill
 */
fun renderMessage(message: ChatMessage, colors: RichTextColors, myUserId: String): List<TextBlock> {
    val source = message.message
    if (source.isEmpty()) return emptyList()
    val blocks = ArrayList<TextBlock>()
    if (!message.markdown || !source.contains(FENCE)) {
        addRich(blocks, source, message, colors, myUserId)
        return blocks
    }
    var pos = 0
    while (pos < source.length) {
        val open = source.indexOf(FENCE, pos)
        val close = if (open >= 0) source.indexOf(FENCE, open + 3) else -1
        if (open < 0 || close < 0) {
            addRich(blocks, source.substring(pos), message, colors, myUserId)
            break
        }
        addRich(blocks, source.substring(pos, open), message, colors, myUserId)
        var code = source.substring(open + 3, close)
        // Drop the language tag of "```kotlin".
        val firstBreak = code.indexOf('\n')
        if (firstBreak >= 0 && code.substring(0, firstBreak).none { it.isWhitespace() }) code = code.substring(firstBreak + 1)
        blocks.add(TextBlock.Code(code.trim('\n')))
        pos = close + 3
    }
    return blocks
}

private fun addRich(out: MutableList<TextBlock>, raw: String, m: ChatMessage, colors: RichTextColors, myUserId: String) {
    val text = raw.trim('\n')
    if (text.isBlank()) return
    val rendered = buildAnnotatedString { Inline(text, m, colors, myUserId, this).run() }
    if (rendered.text.isNotBlank()) out.add(TextBlock.Rich(rendered))
}

/** One pass over a run of text. Markers nest by recursion on the text between them. */
private class Inline(
    private val src: String,
    private val message: ChatMessage,
    private val colors: RichTextColors,
    private val myUserId: String,
    private val out: AnnotatedString.Builder,
) {
    private val markdown = message.markdown
    private val linkStyles = TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))

    fun run() {
        val lines = src.split('\n')
        lines.forEachIndexed { index, line ->
            if (index > 0) out.append('\n')
            line(line)
        }
    }

    private fun line(line: String) {
        if (!markdown) {
            span(line, 0, line.length)
            return
        }
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("# ") || trimmed.startsWith("## ") || trimmed.startsWith("### ") -> {
                val body = trimmed.trimStart('#').trimStart()
                styled(SpanStyle(fontWeight = FontWeight.SemiBold)) { span(body, 0, body.length) }
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                out.append("  • ")
                span(trimmed, 2, trimmed.length)
            }
            trimmed.startsWith("> ") -> {
                out.append("│ ")
                styled(SpanStyle(fontStyle = FontStyle.Italic, color = colors.muted)) { span(trimmed, 2, trimmed.length) }
            }
            else -> span(line, 0, line.length)
        }
    }

    private inline fun styled(style: SpanStyle, body: () -> Unit) {
        val index = out.pushStyle(style)
        body()
        out.pop(index)
    }

    /** Emits `s[start, end)`. */
    private fun span(s: String, start: Int, end: Int) {
        var i = start
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out.append(plain.toString())
                plain.setLength(0)
            }
        }
        while (i < end) {
            val c = s[i]
            // {placeholder}
            if (c == '{') {
                val close = s.indexOf('}', i + 1)
                val obj = if (close in (i + 1) until end) message.parameters[s.substring(i + 1, close)] else null
                if (obj != null) {
                    flush()
                    placeholder(obj)
                    i = close + 1
                    continue
                }
            }
            // Bare link
            if (c == 'h' && (s.startsWith("http://", i) || s.startsWith("https://", i))) {
                val match = URL.matchAt(s.substring(0, end), i)
                if (match != null) {
                    val url = match.value.trimEnd('.', ',', ')', ';', ':', '!', '?')
                    flush()
                    link(url) { out.append(url) }
                    i += url.length
                    continue
                }
            }
            if (markdown) {
                // `code`
                if (c == '`') {
                    val close = s.indexOf('`', i + 1)
                    if (close in (i + 2)..(end - 1)) {
                        flush()
                        styled(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBg, fontSize = 13.sp)) {
                            out.append(s.substring(i + 1, close))
                        }
                        i = close + 1
                        continue
                    }
                }
                // [text](url)
                if (c == '[') {
                    val mid = s.indexOf("](", i + 1)
                    val close = if (mid in (i + 2) until end) s.indexOf(')', mid + 2) else -1
                    if (close in (mid + 3) until end) {
                        val url = s.substring(mid + 2, close)
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            flush()
                            link(url) { span(s, i + 1, mid) }
                            i = close + 1
                            continue
                        }
                    }
                }
                val marker = when {
                    s.startsWith("**", i) -> "**"
                    s.startsWith("__", i) -> "__"
                    s.startsWith("~~", i) -> "~~"
                    c == '*' -> "*"
                    c == '_' -> "_"
                    else -> null
                }
                if (marker != null) {
                    val close = closing(s, marker, i + marker.length, end)
                    // An underscore inside a word (snake_case) is not emphasis.
                    val wordInner = marker[0] == '_' && i > start && s[i - 1].isLetterOrDigit()
                    if (close > 0 && !wordInner) {
                        flush()
                        val style = when (marker) {
                            "**", "__" -> SpanStyle(fontWeight = FontWeight.SemiBold)
                            "~~" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                            else -> SpanStyle(fontStyle = FontStyle.Italic)
                        }
                        styled(style) { span(s, i + marker.length, close) }
                        i = close + marker.length
                        continue
                    }
                }
            }
            plain.append(c)
            i++
        }
        flush()
    }

    /** Index of the closing [marker], or -1. The text between must not start or end with a space. */
    private fun closing(s: String, marker: String, from: Int, end: Int): Int {
        if (from >= end || s[from].isWhitespace()) return -1
        var at = s.indexOf(marker, from)
        while (at in from until end) {
            if (at > from && !s[at - 1].isWhitespace()) {
                // "*" must not match the first star of "**".
                if (marker.length == 2 || !(at + 1 < end && s[at + 1] == marker[0])) return at
                at++
            }
            at = s.indexOf(marker, at + 1)
        }
        return -1
    }

    private fun link(url: String, body: () -> Unit) {
        val index = out.pushLink(LinkAnnotation.Url(url, linkStyles))
        body()
        out.pop(index)
    }

    private fun placeholder(obj: RichObject) {
        when {
            obj.isFile -> Unit
            obj.key.startsWith("mention-") || (obj.isMention && !message.isSystem) -> {
                val me = obj.type == "call" || (obj.type == "user" && obj.id == myUserId)
                val style = SpanStyle(
                    color = if (me) colors.selfMentionInk else colors.mentionInk,
                    background = if (me) colors.selfMentionFill else colors.mentionFill,
                    fontWeight = FontWeight.Medium,
                )
                // Thin spaces inside the fill give the pill some shoulder.
                styled(style) { out.append(" @${obj.name} ") }
            }
            isWebUrl(obj.link) && !message.isSystem -> link(obj.link) { out.append(obj.name) }
            else -> out.append(obj.name)
        }
    }
}

/** One to three emoji and nothing else: drawn large, without a bubble's worth of ceremony. */
fun isEmojiOnly(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.length > 24) return false
    var visible = 0
    var i = 0
    while (i < t.length) {
        val cp = t.codePointAt(i)
        i += Character.charCount(cp)
        when {
            cp == 0x200D || cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF || cp == ' '.code -> Unit
            cp < 0x2190 || Character.isLetterOrDigit(cp) -> return false
            else -> visible++
        }
    }
    return visible in 1..8
}
