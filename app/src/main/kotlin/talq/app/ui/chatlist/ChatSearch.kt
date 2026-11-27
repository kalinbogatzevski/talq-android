package talq.app.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.Normalizer
import talq.app.theme.TalqTheme

// Searching the conversation list, as TalQ for desktop's search row does (MainWindow.cpp
// `m_searchField`; SidebarPainter.cpp `setFilterText`, `rebuildLayouts`, `buildFolderRows`):
//  - a filter over the conversations the app already holds, as you type; nothing is asked of the
//    server. It matches the conversation's name only (never the last message or a folder name), and
//    looks inside what "Show" shows (ListFilter.kt), whose chip stays up meanwhile;
//  - folders stay, every folder is shown open and a folder without a match is left out
//    (`buildRoomSections(searching = true)`); a folder header tap does nothing meanwhile, so no
//    hidden collapsed state changes;
//  - archived conversations stay behind the "Archived" row, which then counts and lists only the
//    archived ones that match, and is left out when none does;
//  - nothing found: one line, `No conversations match "…"`.
// Differences, decided: the text is trimmed (phone keyboards add a space after a word) and the
// match ignores accents as well as case (desktop ignores case only).

/** [text] lower-cased and without accents (combining marks after NFD), for [matchesSearch]. */
internal fun searchFold(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
    val out = StringBuilder(decomposed.length)
    for (c in decomposed) {
        if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) out.append(c)
    }
    return out.toString()
}

/**
 * The search pill under the profile bar: a search glyph, the text and, while there is text, a clear
 * button. The new-conversation sheet uses it for people, with its own [placeholder].
 */
@Composable
internal fun ChatSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search conversations…",
) {
    val colors = TalqTheme.colors
    val focusManager = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(20.dp)
    val textStyle = MaterialTheme.typography.bodyLarge
    // The String overload, as the composer's field (both are marked deprecated in this Compose).
    BasicTextField(
        value = query,
        onValueChange = { onQueryChange(it.replace('\n', ' ')) },
        singleLine = true,
        textStyle = textStyle.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // The list is already filtered: the key only puts the keyboard away.
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            // Low-key until focused, like desktop's pill.
            Row(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(shape)
                    .background(if (focused) colors.bgHover else colors.bgSecondary)
                    .border(1.dp, if (focused) colors.accent else colors.divider, shape)
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            placeholder,
                            style = textStyle,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Spacer(Modifier.width(10.dp))
                }
            }
        },
    )
}
