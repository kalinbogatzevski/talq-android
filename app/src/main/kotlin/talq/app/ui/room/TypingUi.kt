package talq.app.ui.room

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import talq.app.appContainer
import talq.app.theme.TalqTheme
import talq.core.repo.TalkSession

/** A name longer than this is cut, so that "typing…" stays in view on a phone. */
private const val NAME_LENGTH = 20

/**
 * Who is typing in the conversation, and the conversation's presence for it: joined only while this is in
 * front (see `TypingPresence`).
 */
@Composable
fun rememberTypists(session: TalkSession, token: String): List<String> {
    val typing = LocalContext.current.appContainer.typing
    LifecycleResumeEffect(session, token) {
        typing.chatInFront(session, token)
        onPauseOrDispose { typing.chatLeft(session, token) }
    }
    val shown by typing.shown.collectAsStateWithLifecycle()
    return if (shown.token == token) shown.names else emptyList()
}

/** Line 2 of a one-to-one conversation's header while the other person types (in the presence line's place). */
@Composable
fun TypingHeaderLine() {
    Text("typing…", style = MaterialTheme.typography.labelSmall, color = TalqTheme.colors.accentText, maxLines = 1)
}

/** The line above the composer of a group: "Ana typing…", "Ana and Bo typing…". */
@Composable
fun TypingLine(names: List<String>) {
    // What it said last, while it goes away.
    val last = remember { arrayOf("") }
    if (names.isNotEmpty()) last[0] = typingWords(names)
    AnimatedVisibility(
        visible = names.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Text(
            last[0],
            style = MaterialTheme.typography.labelMedium,
            color = TalqTheme.colors.accentText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        )
    }
}

/** One short line for any number of people ([names] is not empty). */
internal fun typingWords(names: List<String>): String {
    fun name(i: Int): String = names[i].let { if (it.length <= NAME_LENGTH) it else it.take(NAME_LENGTH - 1).trimEnd() + "…" }
    return when (names.size) {
        1 -> "${name(0)} typing…"
        2 -> "${name(0)} and ${name(1)} typing…"
        3 -> "${name(0)}, ${name(1)} and 1 other typing…"
        else -> "${name(0)}, ${name(1)} and ${names.size - 2} others typing…"
    }
}
