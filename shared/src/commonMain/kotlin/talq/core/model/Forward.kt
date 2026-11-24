package talq.core.model

/*
 * Several messages at once: what "Copy" puts on the clipboard and what "Forward" sends.
 * Ported from TalQ for desktop: `src/core/ForwardLogic.h` (the forwarded body),
 * `MainWindow::forwardOneMessage` (file or text), `MessageListModel::rawBodyFor` /
 * `forwardBodyFor`, the selection bar's Copy in `MainWindow.cpp` and `copiedMessageHeader` in
 * `src/painter/BodyHtml.h`.
 *
 * Talk has no forward endpoint (Talk 25.0.7's `sendMessage` takes message, actorDisplayName,
 * referenceId, replyTo, replyToToken, silent, threadTitle, threadId; nothing says "forwarded").
 * So a forwarded text is a NEW message whose body carries the attribution, and a forwarded file
 * is the same file shared again into the target conversation.
 *
 * Everything here starts from the server's own text ([ChatMessage.message] with its
 * `{placeholders}` and [ChatMessage.parameters]), never from anything rendered.
 */

/** Can this message be selected? System notices and deleted messages cannot (nor anything unsent). */
fun isSelectableMessage(m: ChatMessage): Boolean = m.id > 0 && !m.isSystem && !m.deleted && !m.isUpdateSignal

/** The call-wide mention (`@all`): no id the server can resolve, so it goes across as bold text. */
fun isCallMention(key: String): Boolean = "mention-call" in key

/**
 * Put `{mention-*}` placeholders back into what the server parses on the way in: `@"<mention-id>"`
 * (the server's own text of the mention, e.g. `group/<id>` or `guest/<id>`; the plain `id` where
 * a server does not send it), or `**<name>**` for the call mention. Other placeholders (a file, a
 * poll, any rich object) are left.
 */
fun restoreMentions(raw: String, mentions: List<RichObject>): String {
    var out = raw
    for (m in mentions) {
        val placeholder = "{${m.key}}"
        val replacement = if (isCallMention(m.key)) "**${m.name}**" else "@\"${m.value("mention-id").ifEmpty { m.id }}\""
        out = out.replace(placeholder, replacement)
    }
    return out
}

/** True when the raw text still holds a rich object that is not prose (a poll, a file, a card...). */
fun carriesRichObject(raw: String, nonMentionKeys: Collection<String>): Boolean =
    nonMentionKeys.any { "{$it}" in raw }

/**
 * The author's name as it may stand inside `*Forwarded from NAME*`: markup characters and line
 * breaks are dropped (an asterisk would close the emphasis early), spaces and tabs trimmed.
 */
fun sanitizeForwardAuthor(name: String): String {
    val kept = name.filterNot { it == '*' || it == '_' || it == '`' || it == '~' || it == '\\' || it == '\r' || it == '\n' }
    return kept.trim(' ', '\t')
}

/**
 * The exact body to post when forwarding [raw]: the attribution line, a blank line, then the
 * text verbatim (only the mentions are put back into their source form). Empty when there is
 * nothing to forward; no attribution when the author has no usable name.
 */
fun forwardBody(raw: String, mentions: List<RichObject>, author: String): String {
    val body = restoreMentions(raw, mentions)
    if (body.isEmpty()) return ""
    val who = sanitizeForwardAuthor(author)
    if (who.isEmpty()) return body
    return "*Forwarded from $who*\n\n$body"
}

/** What forwarding one message sends. */
sealed interface ForwardPlan {
    /** Post this text into the target. */
    data class Text(val body: String) : ForwardPlan

    /** Share this file (a path in the user's own files) into the target. */
    data class File(val path: String, val name: String) : ForwardPlan

    /** Nothing that could be sent. */
    data object Empty : ForwardPlan
}

/**
 * How [m] is forwarded, as on desktop:
 * - a shared file (only the server-built `file` parameter, never a posted rich object that claims
 *   to be one) with a known path is shared again; its caption does not go with it. Only when the
 *   reader may share it (the share bit, 16, of the reader's own `permissions` on it; a file
 *   somebody else sent usually lacks it, and the server may refuse): otherwise a text says a
 *   file was there, `[File: name]`, with its caption below when it has one;
 * - otherwise the raw text with the attribution line;
 * - a text that holds a rich object (a poll, a card, a location) cannot go as markup: its plain
 *   text goes instead, without attribution (desktop's fallback), or nothing when that is empty.
 */
fun forwardPlan(m: ChatMessage): ForwardPlan {
    val file = m.file
    if (file != null && file.path.isNotEmpty()) {
        val permissions = file.value("permissions").toIntOrNull()
        if (permissions == null || permissions and SHARE_PERMISSION != 0) return ForwardPlan.File(file.path, file.name)
        // The caption alone would hide that there was a file. Without one this is the label itself.
        val label = "[File: ${file.name}]"
        val caption = plainMessageText(m)
        return ForwardPlan.Text(if (caption.isEmpty() || caption == label) label else "$label\n$caption")
    }
    val raw = m.message
    if (raw.isNotEmpty()) {
        val mentions = m.parameters.values.filter { it.key.startsWith("mention") }
        val others = m.parameters.keys.filterNot { it.startsWith("mention") }
        if (!carriesRichObject(raw, others)) {
            val body = forwardBody(raw, mentions, m.actorDisplayName)
            if (body.isNotEmpty()) return ForwardPlan.Text(body)
        }
    }
    val plain = plainMessageText(m)
    return if (plain.isNotBlank()) ForwardPlan.Text(plain) else ForwardPlan.Empty
}

/** The share bit of a file's permissions (Nextcloud's `PERMISSION_SHARE`). */
private const val SHARE_PERMISSION = 16

private val PLACEHOLDER = Regex("""\{([^{}\s]+)\}""")

/**
 * The message as plain text, from the raw text and its parameters, in ONE pass (a name that looks
 * like a placeholder is never substituted again): a person, a guest or the call mention is
 * `@name`, a shared file is left out (see below), anything else (a poll, a card) is its name. A
 * placeholder without a known object stays as typed. Blank lines at the start and trailing space
 * are dropped; a file message with no other text is `[File: name]`.
 */
fun plainMessageText(m: ChatMessage): String {
    val text = PLACEHOLDER.replace(m.message) { match ->
        val obj = m.parameters[match.groupValues[1]] ?: return@replace match.value
        val name = obj.name.ifEmpty { obj.id }
        when {
            name.isEmpty() -> match.value
            obj.isFile -> ""
            obj.type == "user" || obj.type == "call" || obj.type == "guest" -> "@$name"
            else -> name
        }
    }
    val body = trimBlankEdges(text)
    val file = m.file
    return if (body.isEmpty() && file != null) "[File: ${file.name}]" else body
}

/** Drop whole blank lines at the start and whitespace at the end; the first line keeps its indent. */
fun trimBlankEdges(s: String): String {
    var start = 0
    while (true) {
        val nl = s.indexOf('\n', start)
        if (nl < 0) break
        if (s.substring(start, nl).isNotBlank()) break
        start = nl + 1
    }
    var end = s.length
    while (end > start && s[end - 1].isWhitespace()) end--
    return s.substring(start, end)
}

/**
 * The line Copy puts above each message: `[Alice, 2026-10-14 09:05]`, or `[2026-10-14 09:05]`
 * without an author. [stamp] is the time as `yyyy-MM-dd HH:mm` in local time (the caller
 * formats it), empty when the message has none.
 */
fun copiedMessageHeader(author: String, stamp: String): String = when {
    author.isEmpty() -> "[$stamp]"
    stamp.isEmpty() -> "[$author]"
    else -> "[$author, $stamp]"
}

/**
 * The clipboard text for several messages, in chat order: each one's header, a line break, its
 * text, a blank line; the whole trimmed. [stampOf] gives a message's `yyyy-MM-dd HH:mm`.
 */
fun copiedMessages(messages: List<ChatMessage>, stampOf: (ChatMessage) -> String): String =
    buildString {
        for (m in messages.sortedBy { it.id }) {
            append(copiedMessageHeader(m.actorDisplayName, stampOf(m)))
            append('\n')
            append(plainMessageText(m))
            append("\n\n")
        }
    }.trim()
