package talq.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import talq.app.theme.TalqTheme
import talq.app.ui.common.StatusAvatar
import talq.core.TalkError
import talq.core.model.MENTION_DEBOUNCE_MILLIS
import talq.core.model.MENTION_ROWS
import talq.core.model.MentionCandidate
import talq.core.model.MentionQuery
import talq.core.model.MentionSpan
import talq.core.model.RoomBot
import talq.core.model.botMentions
import talq.core.model.mentionSpans
import talq.core.repo.TalkSession

/*
 * "@" suggestions in the message box, ported from TalQ for desktop's composer (ComposerWidget.cpp:
 * `maybeShowMentionCompletion`, `fetchMentionsDebounced`, `applyMentionCompletion`, the popup's keys in
 * `eventFilter`; the rules in talq.core.model Mentions.kt):
 *
 * - An '@' at the start of the text or after white space, then letters, digits or `. _ + -` up to the
 *   caret, asks the server (`GET chat/{token}/mentions`, 150 ms after the last change, limit 20) and
 *   shows at most 6 rows above the box: avatar (with the presence disc when the server sent a
 *   status), name, and `@id` ("Everyone" / "@all" for the whole conversation, as the server offers it).
 *   Like desktop the list shows right after the '@' too (the conversation's people).
 * - After them come the conversation's enabled bots whose name holds what is typed, as "name (bot)",
 *   inserted as desktop does (`fetchEnabledBots`; `botMentions`, `mentionToken`). Decided difference:
 *   asked once per box, not on every word (nor where the server has no `bots-v1`, nor in a federated
 *   conversation); a refusal means no bots and never empties the people.
 * - It hides on a space or anything else that ends the word, when the server finds nobody or the
 *   request fails (nothing is said), on Esc and on Back (until the word changes), while the box is
 *   read-only, and once a row is picked.
 * - A tap, or Enter / Tab on a hardware keyboard, replaces the whole "@word" with `@"<mention id>"` and
 *   a space. Up and Down move the highlighted row.
 * - The box keeps that text (it is what is sent, drafted and saved) and DRAWS it as a pill with the
 *   name ([MentionDisplay]); the caret never stops inside it and one backspace takes the whole
 *   mention ([keepMentionsWhole]).
 * - A name that is not known yet (a draft back after the app was ended, a message scheduled elsewhere)
 *   is asked of the conversation's participants ([resolveMentionNames]); unknown, it stays `@id`.
 */

/** What the list shows: the answer for the "@word" starting at [start] (typed as [query] then). */
data class MentionResult(val start: Int, val query: String, val candidates: List<MentionCandidate>)

/**
 * The suggestions of one box. [follow] is told the box's "@word" on every change; the answer is
 * [shown]. Runs on [scope] (the tab's). Main thread.
 */
class MentionSuggester(
    private val session: TalkSession,
    private val token: String,
    private val scope: CoroutineScope,
    private val drafts: ComposerDrafts,
) {
    /** The newest answer, kept while the next one is on its way (the rows narrow, they do not blink). */
    var shown by mutableStateOf<MentionResult?>(null)
        private set

    private var asked: Pair<Int, String>? = null
    private var job: Job? = null

    /** The conversation's enabled bots, asked for once (again only when the network failed). */
    private var bots: Deferred<List<RoomBot>>? = null

    /** Null where bots are not asked for: no `bots-v1`, a federated conversation, or one not known yet. */
    private fun roomBots(): Deferred<List<RoomBot>>? {
        if (!session.capabilities.bots || session.rooms.room(token)?.isFederated != false) return null
        bots?.let { return it }
        // Not part of the word's own job: the next key must not cut the request off.
        return scope.async {
            try {
                session.client.bots(token).filter { it.isEnabled }
            } catch (e: TalkError) {
                if (e is TalkError.Network) bots = null
                emptyList()
            }
        }.also { bots = it }
    }

    /** The box's "@word" is now [q] (null: the caret is in none). */
    fun follow(q: MentionQuery?) {
        if (q == null) {
            asked = null
            job?.cancel()
            shown = null
            return
        }
        val key = q.start to q.query
        if (key == asked) return
        asked = key
        job?.cancel()
        val roomBots = roomBots()
        job = scope.launch {
            delay(MENTION_DEBOUNCE_MILLIS)
            val found = try {
                session.client.mentions(token, q.query)
            } catch (_: TalkError) {
                emptyList()
            }
            // A slow first answer about bots does not hold the people back.
            val known = if (roomBots == null) emptyList() else withTimeoutOrNull(BOTS_WAIT_MILLIS) { roomBots.await() }.orEmpty()
            shown = MentionResult(q.start, q.query, (found + botMentions(known, q.query)).take(MENTION_ROWS))
        }
    }

    /** [c] went into the text: the box shows it by its name from now on. */
    fun picked(c: MentionCandidate) {
        // A bot's id is its name, and may be somebody's user id.
        if (!c.isBot) drafts.nameMentions(mapOf(c.wireId to c.name))
        job?.cancel()
        asked = null
        shown = null
    }

    /** The names of the mentions in [text], by id (read in composition: a new name redraws the box). */
    fun namesIn(text: String): Map<String, String> = drafts.shownNames(text)

    /** [text] with its mentions as names, for a line that only shows it (the bubble on its way). */
    fun readable(text: String): String = drafts.readable(text)

    val authorization: String get() = session.client.authorizationHeader

    /** A person's own picture; empty for everyone else (a group, a guest, a federated user). */
    fun avatarUrl(c: MentionCandidate, dark: Boolean): String =
        if (c.isUser) session.client.userAvatarUrl(c.id, 64, dark) else ""
}

/** How long the people wait for the first answer about bots. */
private const val BOTS_WAIT_MILLIS = 1_000L

/**
 * Finds the names of the mentions in [texts] that have none yet (`GET room/{token}/participants`; no
 * request when there are none). Display only: the texts stay as they are, an id nobody there has keeps
 * showing as `@id`, and a failure says nothing. Main thread.
 */
suspend fun resolveMentionNames(session: TalkSession, token: String, drafts: ComposerDrafts, texts: List<String>) {
    val wanted = drafts.unnamed(texts)
    if (wanted.isEmpty()) return
    val people = try {
        session.client.participants(token)
    } catch (_: TalkError) {
        return
    }
    drafts.nameMentions(people.filter { it.mentionId in wanted }.associate { it.mentionId to it.displayName })
}

/**
 * Draws every `@"id"` of the box as a pill holding `@<name>` ([names] of the ids, see
 * [MentionSuggester.namesIn]; the id itself when missing; [style] the message pill's colours). The
 * text itself is untouched. A caret offset inside a mention maps to the pill's end; a tap on the
 * pill lands before or after it, whichever is nearer. Equal while the names and colours are, so the
 * field filters again only when one of them or the text changes.
 */
data class MentionDisplay(private val names: Map<String, String>, private val style: SpanStyle) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val spans = mentionSpans(text.text)
        if (spans.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val starts = IntArray(spans.size)
        val ends = IntArray(spans.size)
        val out = AnnotatedString.Builder(text.length + spans.size * 8)
        var at = 0
        spans.forEachIndexed { i, s ->
            out.append(text.subSequence(at, s.start))
            starts[i] = out.length
            // Thin spaces inside the fill give the pill its shoulders, as in a message.
            out.withStyle(style) { append(THIN_SPACE + "@" + (names[s.id] ?: s.id) + THIN_SPACE) }
            ends[i] = out.length
            at = s.end
        }
        out.append(text.subSequence(at, text.length))
        return TransformedText(out.toAnnotatedString(), MentionOffsets(spans, starts, ends))
    }
}

private const val THIN_SPACE = "\u2009"

private class MentionOffsets(private val spans: List<MentionSpan>, private val starts: IntArray, private val ends: IntArray) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        var shift = 0
        for (i in spans.indices) {
            val s = spans[i]
            if (offset <= s.start) return offset + shift
            if (offset < s.end) return ends[i]
            shift = ends[i] - s.end
        }
        return offset + shift
    }

    override fun transformedToOriginal(offset: Int): Int {
        var shift = 0
        for (i in spans.indices) {
            val s = spans[i]
            if (offset <= starts[i]) return offset - shift
            if (offset < ends[i]) return if (offset - starts[i] < ends[i] - offset) s.start else s.end
            shift = ends[i] - s.end
        }
        return offset - shift
    }
}

/**
 * [after] (the field's next value, coming from [before]) with every mention kept whole: a deletion
 * that cuts into one takes all of it (one backspace after a pill removes the pill), and the caret or
 * a selection end inside one moves to its edge (left when the caret moved left, else right).
 */
fun keepMentionsWhole(before: TextFieldValue, after: TextFieldValue): TextFieldValue {
    var next = after
    val b = before.text
    val a = after.text
    if (a.length < b.length) {
        // What was taken out: the common start stops at the caret, so a run of equal characters is
        // cut where the caret was.
        var p = 0
        val pMax = minOf(a.length, after.selection.min)
        while (p < pMax && b[p] == a[p]) p++
        var s = 0
        while (s < a.length - p && b[b.length - 1 - s] == a[a.length - 1 - s]) s++
        val from = p
        val to = b.length - s
        if (a.length - s == p && to > from) {
            val hit = mentionSpans(b).filter { it.start < to && it.end > from }
            if (hit.isNotEmpty()) {
                val wideFrom = minOf(from, hit.first().start)
                val wideTo = maxOf(to, hit.last().end)
                if (wideFrom != from || wideTo != to) next = TextFieldValue(b.removeRange(wideFrom, wideTo), TextRange(wideFrom))
            }
        }
    }
    val spans = mentionSpans(next.text)
    if (spans.isEmpty()) return next
    fun inside(o: Int) = spans.firstOrNull { o > it.start && o < it.end }
    val sel = next.selection
    if (sel.collapsed) {
        val m = inside(sel.start) ?: return next
        val left = next.text == b && sel.start < before.selection.start
        return next.copy(selection = TextRange(if (left) m.start else m.end))
    }
    val forward = sel.start <= sel.end
    val start = inside(sel.start)?.let { if (forward) it.start else it.end } ?: sel.start
    val end = inside(sel.end)?.let { if (forward) it.end else it.start } ?: sel.end
    return if (start == sel.start && end == sel.end) next else next.copy(selection = TextRange(start, end))
}

/** The pill's colours: the message mention pill's (accentSoft fill, its ink). */
@Composable
fun mentionPillStyle(): SpanStyle {
    val colors = TalqTheme.colors
    return remember(colors) { SpanStyle(color = colors.accentSoftInk, background = colors.accentSoft, fontWeight = FontWeight.Medium) }
}

/**
 * The rows above the box: [highlighted] has the selected ground (Enter takes it), a mouse pointer the
 * hover ground. Scrolls to the highlighted row when the keys move it out of sight. The composer gives
 * it only the height its field and bars leave ([modifier] carries a non-filling weight), so on a short
 * window the box stays in view.
 */
@Composable
fun MentionList(
    candidates: List<MentionCandidate>,
    highlighted: Int,
    suggester: MentionSuggester,
    onPick: (MentionCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TalqTheme.colors
    val state = rememberLazyListState()
    LaunchedEffect(highlighted, candidates) {
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && (highlighted < visible.first().index || highlighted > visible.last().index)) {
            state.scrollToItem(highlighted)
        }
    }
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxWidth().heightIn(max = 236.dp).background(colors.bgSidebar).padding(vertical = 4.dp),
    ) {
        itemsIndexed(candidates, key = { _, c -> c.key }) { i, c ->
            MentionRow(c, i == highlighted, suggester, onPick)
        }
    }
}

@Composable
private fun MentionRow(c: MentionCandidate, highlighted: Boolean, suggester: MentionSuggester, onPick: (MentionCandidate) -> Unit) {
    val colors = TalqTheme.colors
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val ground = when {
        highlighted -> colors.bgSelected
        hovered -> colors.bgHover
        else -> colors.bgSidebar
    }
    val title = when {
        c.isEveryone -> "Everyone"
        c.isBot -> "${c.name} (bot)"
        else -> c.name
    }
    val subline = when {
        c.isEveryone -> "@all"
        c.source == "groups" -> "Group"
        c.source == "teams" -> "Team"
        c.source == "guests" -> "Guest"
        else -> "@" + c.id
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ground)
            .hoverable(hover)
            // Never takes the focus: the caret and the keyboard stay in the box.
            .focusProperties { canFocus = false }
            .clickable(role = Role.Button, onClickLabel = "Mention") { onPick(c) }
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (c.isUser) {
            StatusAvatar(
                url = suggester.avatarUrl(c, !colors.isLight),
                authorization = suggester.authorization,
                name = c.name,
                ring = ground,
                size = 32.dp,
                presence = c.status,
                everyState = true,
            )
        } else {
            Box(Modifier.size(32.dp).clip(CircleShape).background(colors.bgSecondary), contentAlignment = Alignment.Center) {
                Icon(
                    if (c.isBot) Icons.Outlined.SmartToy else Icons.Outlined.Groups,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.widthIn(max = 420.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subline, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
