package talq.core.model

/*
 * "@" suggestions in the message box. Ported from TalQ for desktop's composer
 * (`src/ui/ComposerWidget.cpp`: `maybeShowMentionCompletion` finds the "@word", a 150 ms debounce,
 * `fetchMentionsDebounced` shows at most 6 rows, `applyMentionCompletion` puts the mention in) and
 * `ApiClient::fetchMentions` (`GET chat/{token}/mentions`, limit 20).
 *
 * What goes into the text is the server's own form of a mention: `@"<mentionId>"` (the id where a
 * server sends no `mentionId`), which Nextcloud's comment parser reads for every kind of id (plain,
 * `group/...`, `guest/...`, `federated_user/...`, `all`). Decided difference: desktop leaves the
 * quotes off a plain id; here they are always there, so the box can tell an inserted mention from
 * typed text and draw it as a name (the same form the app's edit and forward already write).
 */

/** TalQ for desktop's pause after the last key before it asks (`m_mentionDebounce`). */
const val MENTION_DEBOUNCE_MILLIS = 150L

/** How many suggestions are asked for (desktop's `limit=20`). */
const val MENTION_FETCH_LIMIT = 20

/** How many are shown (desktop: `qMin(6, candidates.size())`). */
const val MENTION_ROWS = 6

/**
 * One suggestion of `GET chat/{token}/mentions`: [id] loads the avatar and status, [mentionId] (only
 * with `federation-v1`) is what goes into the text, [label] the name, [source] `users`, `groups`,
 * `guests`, `emails`, `federated_users`, `teams` or `calls` (everyone in the conversation, id `all`).
 * [status] etc. only for a user with a status, when asked with `includeStatus`; [details] only on
 * some rows (the server's subline).
 */
data class MentionCandidate(
    val id: String,
    val mentionId: String,
    val label: String,
    val source: String,
    val status: String = "",
    val statusIcon: String = "",
    val statusMessage: String = "",
    val details: String = "",
) {
    /** What the text carries: the server's mention id, else the id. */
    val wireId: String get() = mentionId.ifEmpty { id }

    val isEveryone: Boolean get() = source == "calls" || id == "all"

    val isUser: Boolean get() = source == "users"

    val name: String get() = label.ifEmpty { id }

    /** Unique in one answer: a group and a user may share an id. */
    val key: String get() = "$source:$id"
}

/** A suggestion that can go into the text: an id that the quoted form can carry. */
fun isMentionable(c: MentionCandidate): Boolean {
    val wire = c.wireId
    return c.id.isNotEmpty() && wire.isNotEmpty() && '"' !in wire && '\n' !in wire && '\r' !in wire
}

/**
 * The "@word" the caret stands in: [start] is the '@', [caret] the caret, [end] where the word ends
 * (it may go on past the caret), [query] what is typed between the '@' and the caret.
 */
data class MentionQuery(val start: Int, val caret: Int, val end: Int, val query: String)

/** Desktop's characters of an "@word": letters, digits and `. _ + -`. */
private fun isMentionWordChar(c: Char): Boolean =
    c.isLetterOrDigit() || c == '.' || c == '_' || c == '+' || c == '-'

/**
 * Desktop's `maybeShowMentionCompletion`: walk back from the caret over word characters to an '@'
 * that starts the text or follows white space (else it is part of an address). Null when the caret
 * is in no such word: a space, a quote or any other character on the way ends it.
 */
fun mentionQueryAt(text: String, caret: Int): MentionQuery? {
    if (caret < 1 || caret > text.length) return null
    var i = caret - 1
    while (text[i] != '@') {
        if (!isMentionWordChar(text[i])) return null
        if (--i < 0) return null
    }
    if (i > 0 && !text[i - 1].isWhitespace()) return null
    var end = caret
    while (end < text.length && isMentionWordChar(text[end])) end++
    return MentionQuery(i, caret, end, text.substring(i + 1, caret))
}

/** The text of [c]'s mention: `@"<mention id>"`. */
fun mentionToken(c: MentionCandidate): String = "@\"${c.wireId}\""

/**
 * [text] with the "@word" of [q] (through its end, not only up to the caret) replaced by [c]'s
 * mention and one space; the second value is the caret, after that space. A space already there is
 * used, not doubled.
 */
fun insertMention(text: String, q: MentionQuery, c: MentionCandidate): Pair<String, Int> {
    val token = mentionToken(c)
    val after = text.substring(q.end)
    val out = text.substring(0, q.start) + token + (if (after.startsWith(" ")) "" else " ") + after
    return out to q.start + token.length + 1
}

/** One `@"id"` mention in a text: [start] the '@', [end] after the closing quote. */
data class MentionSpan(val start: Int, val end: Int, val id: String)

/**
 * Every `@"id"` in [text] that starts the text or follows white space (as the server reads them),
 * in order, never overlapping. The id holds no quote and no line break, and is not empty.
 */
fun mentionSpans(text: String): List<MentionSpan> {
    var out: MutableList<MentionSpan>? = null
    var i = 0
    while (i < text.length - 2) {
        if (text[i] == '@' && text[i + 1] == '"' && (i == 0 || text[i - 1].isWhitespace())) {
            var j = i + 2
            while (j < text.length && text[j] != '"' && text[j] != '\n' && text[j] != '\r') j++
            if (j < text.length && text[j] == '"' && j > i + 2) {
                (out ?: mutableListOf<MentionSpan>().also { out = it }).add(MentionSpan(i, j + 1, text.substring(i + 2, j)))
                i = j + 1
                continue
            }
        }
        i++
    }
    return out ?: emptyList()
}

/** [text] with every `@"id"` written as `@<name>` ([name] of the id): for a line that only shows it. */
fun readableMentions(text: String, name: (String) -> String): String {
    val spans = mentionSpans(text)
    if (spans.isEmpty()) return text
    val out = StringBuilder(text.length)
    var at = 0
    for (s in spans) {
        out.append(text, at, s.start).append('@').append(name(s.id))
        at = s.end
    }
    return out.append(text, at, text.length).toString()
}

/**
 * The names of [m]'s mentions under the ids an edit writes for them ([editableText]: the
 * `mention-id`, else the id), so an edit box shows names, not ids. The call mention is left out
 * (it shows as "@all").
 */
fun mentionNamesOf(m: ChatMessage): Map<String, String> {
    val out = HashMap<String, String>()
    for (p in m.parameters.values) {
        if (!p.key.startsWith("mention") || isCallMention(p.key)) continue
        val id = p.value("mention-id").ifEmpty { p.id }
        if (id.isNotEmpty() && p.name.isNotBlank()) out[id] = p.name
    }
    return out
}
