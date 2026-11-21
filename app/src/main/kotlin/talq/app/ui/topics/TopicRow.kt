package talq.app.ui.topics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import talq.app.theme.TalqTheme
import talq.app.ui.common.firstName
import talq.app.ui.message.previewText
import talq.core.model.ChatMessage
import talq.core.repo.TalkSession

/** "Ana: text", "You: text", or the bare text of a system message. */
internal fun previewLine(session: TalkSession, m: ChatMessage): String {
    val text = previewText(m)
    if (m.isSystem) return text
    val who = if (session.isMe(m.actorType, m.actorId)) "You" else firstName(m.actorDisplayName)
    return if (who.isEmpty()) text else "$who: $text"
}

/**
 * One row of a topic list: disc, title, preview, time and unread marks. Used by the topics sheet.
 */
@Composable
internal fun TopicRow(
    letter: String,
    fill: Color,
    letterInk: Color,
    title: String,
    preview: String,
    time: String,
    unreadCount: Int,
    unreadDot: Boolean,
    mentioned: Boolean,
    muted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TalqTheme.colors
    val unread = unreadCount > 0 || unreadDot
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(if (muted) 0.6f else 1f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            Text(letter, color = letterInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (muted) {
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.Filled.NotificationsOff, contentDescription = "Muted", tint = colors.textMuted, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread && !muted) colors.accentText else colors.textMuted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (mentioned) {
                    Spacer(Modifier.width(6.dp))
                    CountBadge("@", muted = false)
                }
                if (unreadCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    CountBadge(if (unreadCount > 99) "99+" else unreadCount.toString(), muted)
                } else if (unreadDot) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (muted) colors.textMuted else colors.accent))
                }
            }
        }
    }
}

@Composable
internal fun CountBadge(text: String, muted: Boolean) {
    val colors = TalqTheme.colors
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .clip(CircleShape)
            .background(if (muted) colors.bgSelected else colors.accent)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (muted) colors.textSecondary else colors.controlInk,
            maxLines = 1,
        )
    }
}
