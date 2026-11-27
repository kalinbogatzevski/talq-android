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
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import talq.app.theme.TalqTheme
import talq.app.ui.chatlist.unreadFill
import talq.app.ui.chatlist.unreadWords
import talq.app.ui.common.onSecondaryClick
import talq.app.ui.message.topicColor
import talq.app.ui.topics.TabUnread

/** One chip of the strip. */
data class StripChip(
    val tab: RoomTab,
    val title: String,
    val unread: TabUnread = TabUnread(),
    /** Topic notifications set to Never: dimmed, a grey count; a slashed bell after the title only while it is the selected chip. */
    val muted: Boolean = false,
    /** Topic notifications set to mentions only: a small "@" after the title. */
    val mentionsOnly: Boolean = false,
)

/**
 * A topic being started, as its chip shows it: [selected] = it is what the room shows; [sending] = its
 * first message is on its way; [failed] = that could not be sent.
 */
data class DraftChipState(val title: String, val selected: Boolean, val sending: Boolean, val failed: Boolean)

/**
 * The topic strip under the top bar: `[+] [General] [topic...]`. "+" stays at the left, the chips scroll
 * sideways beside it. No chip mixes the topics: General is what is in none of them (TalQ for desktop's
 * first chip, `TopicTabBar`). The order is the caller's; this only draws it and keeps the selected chip in view.
 *
 * @param onNew "+" (with a [draft] the caller goes back to that one: one at a time); null hides it (the
 *   user may not start a topic here)
 * @param newReady false: "+" keeps its place but cannot be used yet
 * @param onLongPress a chip was long-pressed; null = chips have no menu
 * @param draft a topic being started: shown as one more chip after the others. While it is what the
 *   room shows it is the selected chip, with a close mark ([onDiscardDraft]; null = no close mark, its
 *   first message is being sent); else a tap on it ([onSelectDraft]) goes back to it.
 */
@Composable
fun TopicStrip(
    chips: List<StripChip>,
    selected: RoomTab,
    onSelect: (RoomTab) -> Unit,
    onNew: (() -> Unit)?,
    modifier: Modifier = Modifier,
    newReady: Boolean = true,
    onLongPress: ((RoomTab) -> Unit)? = null,
    draft: DraftChipState? = null,
    onSelectDraft: () -> Unit = {},
    onDiscardDraft: (() -> Unit)? = null,
) {
    val colors = TalqTheme.colors
    val listState = rememberLazyListState()
    val onDraft = draft?.selected == true
    val selectedIndex = if (onDraft) chips.size else chips.indexOfFirst { it.tab == selected }

    // The selected chip is always in view: after a switch from anywhere (a chip, a message's
    // topic label, a notification), and when chips join in front of it.
    LaunchedEffect(selected, selectedIndex, draft?.title, onDraft) {
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Beside the scrolling row, not in it: always in view, and a chip's place in the row is its
            // place in [chips] (selectedIndex).
            if (onNew != null) {
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(CHIP_HEIGHT)
                        .clip(CircleShape)
                        .border(1.dp, colors.divider, CircleShape)
                        .clickable(enabled = newReady, role = Role.Button, onClick = onNew),
                    contentAlignment = Alignment.Center,
                ) {
                    val ink = if (newReady) colors.textSecondary else colors.textMuted
                    Icon(Icons.Filled.Add, contentDescription = "New topic", tint = ink, modifier = Modifier.size(18.dp))
                }
            }
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = if (onNew != null) 6.dp else 10.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(chips, key = { it.tab.key }) { chip ->
                    Chip(
                        chip = chip,
                        selected = !onDraft && chip.tab == selected,
                        onClick = { onSelect(chip.tab) },
                        onLongPress = onLongPress?.let { { it(chip.tab) } },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (draft != null) {
                    item(key = "draft") { DraftChip(draft, onSelectDraft, onDiscardDraft, Modifier.animateItem()) }
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
    // full ink, read dimmed, silenced dimmer still (it has no bell unless selected).
    val unread = chip.unread.any && !chip.muted
    val fill = if (selected) colors.accentSoft else colors.bgSurface
    val ink = when {
        selected -> colors.accentSoftInk
        unread -> colors.textPrimary
        chip.muted -> colors.textMuted
        else -> colors.textSecondary
    }
    val shape = RoundedCornerShape(CHIP_HEIGHT / 2)
    Row(
        modifier = modifier
            .height(CHIP_HEIGHT)
            .clip(shape)
            .background(fill)
            .border(1.dp, if (selected) colors.accent else colors.divider, shape)
            .semantics {
                this.selected = selected
                // Said of every silenced chip, with its bell or without.
                if (chip.muted) stateDescription = "Muted"
            }
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
        // The topic's notification setting, in the chip's own ink: a slashed bell for Never, on the
        // selected chip only (desktop marks every one; a strip full of bells is noise), "@" for
        // mentions only. Nothing for the usual setting.
        if (chip.muted && selected) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.NotificationsOff, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp))
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
        // One badge: "@" when mentioned, else the count, else a dot when the count is unknown, in the
        // conversation list's colours (danger for you by name, amber for everyone). A silenced
        // topic's is grey, like the list's count of what was muted, except a mention of you by name.
        val badge = chip.unread.badge
        if (badge != null) {
            val quiet = chip.muted && !chip.unread.direct
            val badgeFill = if (quiet) colors.mutedFill else colors.unreadFill(chip.unread.mentioned, chip.unread.direct)
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clearAndSetSemantics {
                        contentDescription = unreadWords(chip.unread.count, chip.unread.mentioned, chip.unread.direct)
                    }
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .clip(CircleShape)
                    .background(badgeFill)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badge,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.inkOn(badgeFill),
                    maxLines = 1,
                )
            }
        } else if (chip.unread.dot) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (chip.muted) colors.textMuted else colors.accent))
        }
    }
}

/**
 * The topic being started: not a tab yet. Selected (what the room shows) it can be given up with its X;
 * else it waits with what was typed, and a tap goes back to it. The disc says how far it is: a pencil
 * while it is being written, a ring while its first message is on its way, an exclamation mark on the
 * danger colour when that could not be sent. It has no menu: a right-click is a tap.
 */
@Composable
private fun DraftChip(draft: DraftChipState, onSelect: () -> Unit, onDiscard: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = TalqTheme.colors
    val shape = RoundedCornerShape(CHIP_HEIGHT / 2)
    val shown = draft.selected
    Row(
        modifier = modifier
            .height(CHIP_HEIGHT)
            .clip(shape)
            .background(if (shown) colors.accentSoft else colors.bgSurface)
            .border(1.dp, if (shown) colors.accent else colors.divider, shape)
            .semantics {
                selected = shown
                stateDescription = when {
                    draft.failed -> "New topic, not sent"
                    draft.sending -> "New topic, sending"
                    else -> "New topic"
                }
            }
            .onSecondaryClick(onSelect)
            .then(if (shown) Modifier else Modifier.clickable(role = Role.Tab, onClick = onSelect))
            .padding(start = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fill = if (draft.failed) colors.danger else colors.accent
        val ink = if (draft.failed) colors.inkOn(fill) else colors.controlInk
        Box(Modifier.size(22.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            when {
                draft.sending -> CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp, color = ink)
                draft.failed -> Icon(Icons.Filled.PriorityHigh, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp))
                else -> Icon(Icons.Filled.Edit, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp))
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = draft.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (shown) colors.accentSoftInk else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        if (onDiscard == null || !shown) {
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
 * The small leading mark, in the chip's [ink]: "#" for General (the room itself), and for a
 * topic its identity colour as a small dot (the same dot as the conversation list's topic pill).
 * Desktop's chips carry only a glyph; the letter disc on every chip was noise.
 */
@Composable
private fun Mark(chip: StripChip, ink: Color, dimmed: Boolean) {
    when (val tab = chip.tab) {
        RoomTab.All, RoomTab.General -> Text("#", color = ink, fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
        is RoomTab.Topic -> Box(
            Modifier.size(10.dp).clip(CircleShape).background(topicColor(tab.id).copy(alpha = if (dimmed) 0.6f else 1f)),
        )
    }
}
