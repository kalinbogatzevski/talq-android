package talq.app.ui.chatlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import talq.app.theme.TalqTheme
import talq.app.ui.common.onSecondaryClick
import talq.core.folders.FolderRollup
import talq.core.folders.rollupShowsBadge

/** The header of the built-in section for chats in no folder (TalQ for desktop's name for it). */
internal const val OTHER_SECTION_NAME = "Other"

/**
 * One folder's header in the conversation list, after TalQ for desktop's sidebar: a disclosure
 * chevron (pointing to the text side when closed, down when open), the name in the eyebrow style
 * (shown in capitals; the stored name keeps its case) and, whenever something in it is unread, open
 * or closed, the sum of its unread counts; nothing when there is nothing to read. That badge takes
 * the rows' colours ([unreadFill]: danger for a direct mention, amber for another mention, the
 * accent otherwise), with an "@" beside it for a mention (the colour is not the only sign).
 * Decided difference (the owner's choice): desktop shows the number of chats in the folder, and
 * the unread sum only while it is closed; here there is no chat count (it read like unread).
 *
 * A tap opens or closes the folder (saved, and synced when sync is on); [onToggle] is null while a
 * search shows every folder open, when a tap does nothing (as on desktop). [onLongPress] opens the
 * folder's menu (a right-click too); null for "Other", which has none.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderHeader(
    name: String,
    rollup: FolderRollup,
    closed: Boolean,
    onToggle: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    val colors = TalqTheme.colors
    val showBadge = rollupShowsBadge(rollup)
    val fill: Color = colors.unreadFill(rollup.mention, rollup.directMention)
    // The closed chevron points along the reading direction.
    val closedTurn = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 90f else -90f
    // Under a mouse pointer the header takes the hover ground, as desktop's sidebar paints it.
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val active = onToggle != null || onLongPress != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (active && hovered) colors.bgHover else Color.Transparent)
            .semantics { stateDescription = if (closed) "Collapsed" else "Expanded" }
            .then(
                if (!active) {
                    Modifier
                } else {
                    // A right-click opens the folder menu, like touch and hold (desktop's
                    // right-click on a folder header).
                    Modifier.hoverable(hover).onSecondaryClick(onLongPress).combinedClickable(
                        role = Role.Button,
                        onClickLabel = when {
                            onToggle == null -> null
                            closed -> "Expand"
                            else -> "Collapse"
                        },
                        onLongClickLabel = if (onLongPress != null) "Folder menu" else null,
                        onLongClick = onLongPress,
                        onClick = onToggle ?: {},
                    )
                },
            )
            .padding(start = 14.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp).rotate(if (closed) closedTurn else 0f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = name.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Read as typed: a screen reader may spell out a word in capitals.
            modifier = Modifier.weight(1f).semantics { contentDescription = name },
        )
        if (showBadge) {
            val words = unreadWords(rollup.unread, rollup.mention, rollup.directMention)
            Spacer(Modifier.width(2.dp))
            Row(
                modifier = Modifier.clearAndSetSemantics { contentDescription = words },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (rollup.mention) {
                    Spacer(Modifier.width(6.dp))
                    Badge("@", muted = false, mutedFill = fill, fill = fill)
                }
                Spacer(Modifier.width(6.dp))
                Badge(if (rollup.unread > 999) "999+" else rollup.unread.toString(), muted = false, mutedFill = fill, fill = fill)
            }
        }
    }
}
