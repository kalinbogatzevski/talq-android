package talq.app.ui.message

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import talq.app.theme.AvatarFills
import talq.app.theme.TalqTheme
import talq.app.ui.adaptive.LocalTwoPane
import talq.app.ui.common.Avatar
import talq.app.ui.common.authorizedImage
import talq.app.ui.common.userMessage
import talq.core.model.ChatMessage
import talq.core.model.RichObject
import talq.core.repo.OutboxMessage
import talq.core.repo.OutboxStatus
import talq.core.repo.TalkSession

/** The topic a message belongs to, for the chip shown in the whole-chat view. */
data class TopicChip(val id: Long, val title: String, /** Set on the topic's first message. */ val replies: Int?)

/** Stable identity colour of a topic, shared by the topic list and the chips. */
fun topicColor(topicId: Long): Color = AvatarFills[((topicId % AvatarFills.size).toInt() + AvatarFills.size) % AvatarFills.size]

private val AvatarSize = 32.dp
/** How wide a bubble may get: wider in a tablet's conversation pane than on a phone. */
@Composable
@ReadOnlyComposable
internal fun bubbleMaxWidth(): Dp = if (LocalTwoPane.current) 480.dp else 310.dp

fun clockTime(context: android.content.Context, timestamp: Long): String =
    Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault()).format(
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", Locale.getDefault()),
    )

@Composable
fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TalqTheme.colors.textMuted,
            modifier = Modifier.clip(CircleShape).background(TalqTheme.colors.bgSecondary).padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun NewMessagesDivider() {
    val colors = TalqTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = colors.accentText.copy(alpha = 0.45f))
        Text(
            "New messages",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.accentText,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = colors.accentText.copy(alpha = 0.45f))
    }
}

@Composable
fun SystemMessageRow(message: ChatMessage) {
    Text(
        text = message.plainText,
        style = MaterialTheme.typography.labelSmall,
        color = TalqTheme.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 5.dp),
    )
}

/**
 * One chat message: avatar and name at the head of a group, the bubble (topic chip, quote,
 * images, files, text, time) and the reactions under it.
 */
@OptIn(ExperimentalFoundationApi::class)
/** The status of an own message, after its time, as on desktop: delivered ○, read by everyone ◉. */
enum class ReadMark { Delivered, Read }

@Composable
fun MessageRow(
    item: ChatItem.Message,
    session: TalkSession,
    /** Null for other people's messages. */
    readMark: ReadMark?,
    showSender: Boolean,
    topicChip: TopicChip?,
    highlighted: Boolean,
    canReply: Boolean,
    onReply: () -> Unit,
    onLongPress: () -> Unit,
    onQuoteClick: (Long) -> Unit,
    onOpenTopic: (Long) -> Unit,
    onOpenImage: (RichObject) -> Unit,
    onOpenFile: (RichObject) -> Unit,
    onReaction: (String) -> Unit,
) {
    val colors = TalqTheme.colors
    val m = item.message
    val mine = item.mine
    SwipeToReply(enabled = canReply && !m.deleted, onReply = onReply) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (highlighted) colors.accentSoft.copy(alpha = 0.5f) else Color.Transparent)
                .padding(
                    start = 10.dp, end = 10.dp,
                    top = if (item.startsGroup) 6.dp else 1.5.dp,
                    bottom = if (item.endsGroup) 2.dp else 0.dp,
                ),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        ) {
            if (!mine) {
                if (item.startsGroup) {
                    Avatar(
                        url = session.client.actorAvatarUrl(m.actorType, m.actorId),
                        authorization = session.client.authorizationHeader,
                        name = m.actorDisplayName,
                        size = AvatarSize,
                        modifier = Modifier.padding(top = if (showSender) 18.dp else 0.dp),
                    )
                } else {
                    Spacer(Modifier.width(AvatarSize))
                }
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.width(40.dp))
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).widthIn(max = bubbleMaxWidth()),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                if (!mine && showSender && item.startsGroup) {
                    Text(
                        text = m.actorDisplayName.ifBlank { "Guest" },
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.authorInk(m.actorType + m.actorId),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                val shape = RoundedCornerShape(
                    topStart = if (!mine && !item.startsGroup) 6.dp else 14.dp,
                    topEnd = if (mine && !item.startsGroup) 6.dp else 14.dp,
                    bottomStart = if (!mine && !item.endsGroup) 6.dp else 14.dp,
                    bottomEnd = if (mine && !item.endsGroup) 6.dp else 14.dp,
                )
                Column(
                    modifier = Modifier
                        .clip(shape)
                        .background(if (mine) colors.bgMessageOwn else colors.bgSurface)
                        .combinedClickable(onClick = {}, onLongClick = onLongPress)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    if (topicChip != null) TopicChipRow(topicChip, onOpenTopic)
                    BubbleBody(m, session, onQuoteClick, onOpenImage, onOpenFile, onLongPress)
                    Row(
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = clockTime(LocalContext.current, m.timestamp) + if (m.lastEditTimestamp > 0) " · edited" else "",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            color = colors.textMuted,
                        )
                        if (readMark != null) {
                            val read = readMark == ReadMark.Read
                            Text(
                                text = if (read) "\u25C9" else "\u25CB",
                                fontSize = 12.sp,
                                color = if (read) colors.accentText else colors.textMuted,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .semantics { contentDescription = if (read) "Read" else "Delivered" },
                            )
                        }
                    }
                }
                if (m.reactions.isNotEmpty()) ReactionChips(m, onReaction)
            }
            if (!mine) Spacer(Modifier.width(24.dp))
        }
    }
}

@Composable
private fun BubbleBody(
    m: ChatMessage,
    session: TalkSession,
    onQuoteClick: (Long) -> Unit,
    onOpenImage: (RichObject) -> Unit,
    onOpenFile: (RichObject) -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = TalqTheme.colors
    if (m.deleted) {
        Text(
            "Message deleted",
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = colors.textMuted,
        )
        return
    }
    m.quoted?.let { QuoteBlock(it, onClick = { onQuoteClick(it.id) }, onLongPress = onLongPress) }
    if (m.quoted == null && m.parentDeleted) {
        Text("Replied to a deleted message", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
    }
    for (file in m.files) {
        when {
            file.isImage && file.previewAvailable -> ImageAttachment(file, session, onClick = { onOpenImage(file) }, onLongPress = onLongPress)
            file.isVideo -> VideoAttachment(file, session, onClick = { onOpenFile(file) }, onLongPress = onLongPress)
            file.isAudio || m.isVoiceMessage -> VoiceCard(file, voiceMessage = m.isVoiceMessage, onLongPress = onLongPress)
            else -> FileCard(file, onClick = { onOpenFile(file) }, onLongPress = onLongPress)
        }
    }
    val richColors = remember(colors) { RichTextColors.from(colors) }
    val blocks = remember(m.message, m.parameters, richColors) { renderMessage(m, richColors, session.user.userId) }
    val big = blocks.size == 1 && m.parameters.isEmpty() && isEmojiOnly(m.message)
    for (block in blocks) {
        when (block) {
            is TextBlock.Rich -> Text(
                text = block.text,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = if (big) 34.sp else 16.sp,
                lineHeight = if (big) 42.sp else 22.sp,
                color = colors.textPrimary,
            )
            is TextBlock.Code -> Text(
                text = block.code,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = colors.textPrimary,
                softWrap = false,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.codeBg)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TopicChipRow(chip: TopicChip, onOpenTopic: (Long) -> Unit) {
    val colors = TalqTheme.colors
    Row(
        modifier = Modifier
            .padding(bottom = 5.dp)
            .clip(CircleShape)
            .background(colors.bgSelected)
            .clickable { onOpenTopic(chip.id) }
            .padding(start = 5.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(topicColor(chip.id)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Forum, contentDescription = null, tint = Color.White, modifier = Modifier.size(9.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = chip.title.ifBlank { "Topic" },
            style = MaterialTheme.typography.labelMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (chip.replies != null) {
            Text(
                text = "  ·  " + if (chip.replies == 1) "1 reply" else "${chip.replies} replies",
                style = MaterialTheme.typography.labelMedium,
                color = colors.accentText,
                maxLines = 1,
            )
        }
    }
}

// The parts of a bubble that take taps of their own also pass a long press on to the message's
// actions; otherwise an image-only message could only be long-pressed on its thin padding.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuoteBlock(quoted: ChatMessage, onClick: () -> Unit, onLongPress: () -> Unit) {
    val colors = TalqTheme.colors
    Row(
        modifier = Modifier
            .padding(bottom = 5.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.bgPrimary.copy(alpha = 0.45f))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.accent))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(
                quoted.actorDisplayName.ifBlank { "Guest" },
                style = MaterialTheme.typography.labelMedium,
                color = colors.accentText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = previewText(quoted),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One line that stands for a message in previews and quotes: what the bubble says, unstyled. */
fun previewText(m: ChatMessage): String = when {
    m.deleted -> "Message deleted"
    else -> plainPreview(m.message, m.parameters, m.markdown, m.isSystem).ifEmpty { m.files.firstOrNull()?.name ?: "" }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageAttachment(file: RichObject, session: TalkSession, onClick: () -> Unit, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val url = session.client.filePreviewUrl(file.id, 720, 720)
    val request = remember(url) { authorizedImage(context, url, session.client.authorizationHeader) }
    val ratio = if (file.width > 0 && file.height > 0) (file.width.toFloat() / file.height).coerceIn(0.6f, 2.2f) else 4f / 3f
    Box(contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = file.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(vertical = 3.dp)
                .width(if (ratio < 1f) 190.dp else 260.dp)
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(10.dp))
                .background(TalqTheme.colors.bgSecondary)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        )
        // Long-press Save / Share fetches the original.
        val fetching = downloadProgress(file.id)
        if (fetching != null) DownloadRing(file.id, fetching)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionChips(m: ChatMessage, onReaction: (String) -> Unit) {
    val colors = TalqTheme.colors
    FlowRow(
        modifier = Modifier.padding(top = 3.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((emoji, count) in m.reactions) {
            val own = emoji in m.reactionsSelf
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (own) colors.accentSoft else colors.bgSecondary)
                    .then(if (own) Modifier.border(1.dp, colors.accentText.copy(alpha = 0.5f), CircleShape) else Modifier)
                    .clickable { onReaction(emoji) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(emoji, fontSize = 14.sp)
                Spacer(Modifier.width(4.dp))
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (own) colors.accentSoftInk else colors.textSecondary,
                )
            }
        }
    }
}

/** A message on its way out, or one that did not make it: never dropped, always retryable. */
@Composable
fun PendingRow(outbox: OutboxMessage, onRetry: () -> Unit, onDiscard: () -> Unit) {
    val colors = TalqTheme.colors
    val failed = outbox.status == OutboxStatus.FAILED
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 50.dp, end = 10.dp, top = 3.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = bubbleMaxWidth())
                .alpha(if (failed) 1f else 0.6f)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.bgMessageOwn)
                .then(if (failed) Modifier.border(1.dp, colors.danger, RoundedCornerShape(14.dp)) else Modifier)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            outbox.replyTo?.let { QuoteBlock(it, onClick = {}, onLongPress = {}) }
            Text(outbox.text, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
            if (!failed) {
                Text(
                    "Sending…",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = colors.textMuted,
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                )
            }
        }
        if (failed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.danger, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    "Not sent. " + (outbox.error?.userMessage() ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.danger,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Row {
                TextButton(onClick = onDiscard) { Text("Delete", color = colors.textSecondary) }
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

/** Drag a message to the right to quote it. The reply arrow fades in under the finger. */
@Composable
private fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(drag, animationSpec = if (dragging) snap() else spring(), label = "swipe")
    val haptics = LocalHapticFeedback.current
    val reply by rememberUpdatedState(onReply)
    Box(
        modifier = Modifier.pointerInput(threshold) {
            detectHorizontalDragGestures(
                onDragStart = { dragging = true },
                onDragEnd = {
                    if (drag >= threshold) reply()
                    dragging = false
                    drag = 0f
                },
                onDragCancel = {
                    dragging = false
                    drag = 0f
                },
                onHorizontalDrag = { change, amount ->
                    val next = (drag + amount).coerceIn(0f, threshold * 1.5f)
                    if (next != drag) {
                        if (drag < threshold && next >= threshold) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        drag = next
                        change.consume()
                    }
                },
            )
        },
    ) {
        if (shown > 1f) {
            Icon(
                Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = TalqTheme.colors.accentText,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp)
                    .size(22.dp)
                    .alpha((shown / threshold).coerceIn(0f, 1f)),
            )
        }
        Box(Modifier.offset { IntOffset(shown.roundToInt(), 0) }) { content() }
    }
}
