package talq.app.ui.room

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import talq.app.theme.TalqTheme
import talq.app.ui.common.onSecondaryClick
import talq.app.ui.message.topicColor
import talq.app.ui.topics.TabUnread

/** One chip of the strip. */
data class StripChip(
    val tab: RoomTab,
    val title: String,
    val unread: TabUnread = TabUnread(),
    /** Topic notifications set to Never: dimmed, a slashed bell after the title, a quiet count. */
    val muted: Boolean = false,
    /** Topic notifications set to mentions only: a small "@" after the title. */
    val mentionsOnly: Boolean = false,
)

/**
 * The topic strip under the top bar: `[All] [General] [topic...] [+]`, scrolling sideways.
 * The order is the caller's; this only draws it and keeps the selected chip in view.
 *
 * @param onNew the "+" chip; null hides it (read-only conversation)
 * @param onLongPress a chip was long-pressed; null = chips have no menu
 * @param draftTitle a topic being started (title given, first message not sent yet): shown as
 *   one more chip, selected in place of [selected], with a close mark ([onDiscardDraft];
 *   null = no close mark, its first message is being sent)
 */
@Composable
fun TopicStrip(
    chips: List<StripChip>,
    selected: RoomTab,
    onSelect: (RoomTab) -> Unit,
    onNew: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongPress: ((RoomTab) -> Unit)? = null,
    draftTitle: String? = null,
    onDiscardDraft: (() -> Unit)? = null,
) {
    val colors = TalqTheme.colors
    val listState = rememberLazyListState()
    val selectedIndex = if (draftTitle != null) chips.size else chips.indexOfFirst { it.tab == selected }

    // The selected chip is always in view: after a switch from anywhere (a chip, a message's
    // topic label, a notification), and when chips join in front of it.
    LaunchedEffect(selected, selectedIndex, draftTitle) {
        if (selectedIndex < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        val fullyVisible = item != null && item.offset >= 0 &&
            item.offset + item.size <= info.viewportEndOffset - info.afterContentPadding
        if (!fullyVisible) {
            // Leave a little of the previous chip showing, so it reads as a row that continues.
            if (info.visibleItemsInfo.isEmpty()) listState.scrollToItem(selectedIndex, -SCROLL_LEAD_PX)
            else listState.animateScrollToItem(selectedIndex, -SCROLL_LEAD_PX)
        }
    }

    Column(modifier.fillMaxWidth().background(colors.bgSidebar)) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(chips, key = { it.tab.key }) { chip ->
                Chip(
                    chip = chip,
                    selected = draftTitle == null && chip.tab == selected,
                    onClick = { onSelect(chip.tab) },
                    onLongPress = onLongPress?.let { { it(chip.tab) } },
                    modifier = Modifier.animateItem(),
                )
            }
            if (draftTitle != null) {
                item(key = "draft") { DraftChip(draftTitle, onDiscardDraft, Modifier.animateItem()) }
            }
            if (onNew != null && draftTitle == null) {
                item(key = "new") {
                    Box(
                        modifier = Modifier
                            .size(CHIP_HEIGHT)
                            .clip(CircleShape)
                            .border(1.dp, colors.divider, CircleShape)
                            .clickable(role = Role.Button, onClick = onNew),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "New topic", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = colors.divider)
    }
}

private val CHIP_HEIGHT = 34.dp
private const val SCROLL_LEAD_PX = 48

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chip(
    chip: StripChip,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = TalqTheme.colors
    // A silenced (Never) topic never takes the unread style: it does not call for you (desktop's
    // TopicTabBar::makeChip). Selected: the accent tint (where you are). Others neutral: unread in
    // full ink, read or silenced dimmed.
    val unread = chip.unread.any && !chip.muted
    val fill = if (selected) colors.accentSoft else colors.bgSurface
    val ink = when {
        selected -> colors.accentSoftInk
        unread -> colors.textPrimary
        else -> colors.textSecondary
    }
    val shape = RoundedCornerShape(CHIP_HEIGHT / 2)
    Row(
        modifier = modifier
            .height(CHIP_HEIGHT)
            .clip(shape)
            .background(fill)
            .border(1.dp, if (selected) colors.accent else colors.divider, shape)
            .semantics { this.selected = selected }
            .onSecondaryClick(onLongPress)
            .combinedClickable(role = Role.Tab, onClick = onClick, onLongClick = onLongPress)
            .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mark(chip, ink = ink, dimmed = !selected && !unread)
        Spacer(Modifier.width(7.dp))
        Text(
            text = chip.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected || unread) FontWeight.SemiBold else FontWeight.Normal,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        // The topic's notification setting, as desktop marks it, in the chip's own ink: a slashed
        // bell for Never, "@" for mentions only. Nothing for the usual setting.
        if (chip.muted) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.NotificationsOff, contentDescription = "Muted", tint = ink, modifier = Modifier.size(13.dp))
        } else if (chip.mentionsOnly) {
            Spacer(Modifier.width(4.dp))
            Text(
                "@",
                fontSize = 12.sp,
                lineHeight = 14.sp,
                color = ink,
                modifier = Modifier.semantics { contentDescription = "Mentions only" },
            )
        }
        // One badge: "@" when mentioned, else the count, else a dot when the count is unknown. A
        // silenced topic's count is an outline in the quiet ink, not the filled pill (desktop).
        val badge = chip.unread.badge
        if (badge != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .clip(CircleShape)
                    .then(
                        if (chip.muted) Modifier.border(1.dp, colors.textSecondary, CircleShape)
                        else Modifier.background(colors.countFill),
                    )
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badge,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (chip.muted) colors.textSecondary else colors.inkOn(colors.countFill),
                    maxLines = 1,
                )
            }
        } else if (chip.unread.dot) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (chip.muted) colors.textMuted else colors.accent))
        }
    }
}

/** The topic being started: looks selected, is not a tab yet, and can be given up. */
@Composable
private fun DraftChip(title: String, onDiscard: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = TalqTheme.colors
    val shape = RoundedCornerShape(CHIP_HEIGHT / 2)
    Row(
        modifier = modifier
            .height(CHIP_HEIGHT)
            .clip(shape)
            .background(colors.accentSoft)
            .border(1.dp, colors.accent, shape)
            .semantics { this.selected = true }
            .padding(start = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Edit, contentDescription = null, tint = colors.controlInk, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.accentSoftInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        if (onDiscard == null) {
            Spacer(Modifier.width(10.dp))
        } else {
            Box(
                Modifier.size(CHIP_HEIGHT - 4.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDiscard),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Discard new topic", tint = colors.accentSoftInk, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * The small leading mark, in the chip's [ink]: a stack glyph for All, "#" for General, and for a
 * topic its identity colour as a small dot (the same dot as the conversation list's topic pill).
 * Desktop's chips carry only a glyph; the letter disc on every chip was noise.
 */
@Composable
private fun Mark(chip: StripChip, ink: Color, dimmed: Boolean) {
    when (val tab = chip.tab) {
        RoomTab.All -> Icon(Icons.Outlined.Layers, contentDescription = null, tint = ink, modifier = Modifier.size(15.dp))
        RoomTab.General -> Text("#", color = ink, fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
        is RoomTab.Topic -> Box(
            Modifier.size(10.dp).clip(CircleShape).background(topicColor(tab.id).copy(alpha = if (dimmed) 0.6f else 1f)),
        )
    }
}
