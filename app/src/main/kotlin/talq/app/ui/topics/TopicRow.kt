package talq.app.ui.topics

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import talq.app.theme.TalqTheme
import talq.app.ui.chatlist.Badge
import talq.app.ui.chatlist.unreadFill
import talq.app.ui.chatlist.unreadWords
import talq.app.ui.common.firstName
import talq.app.ui.common.onSecondaryClick
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
 * One row of a topic list: disc, title, preview, time and unread marks. Used by the topics sheet. A
 * muted topic is dimmed, its unread marks are grey (except a mention of the user by name). [onMenu]
 * (touch and hold, a right-click): the topic's chip menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TopicRow(
    letter: String,
    fill: Color,
    letterInk: Color,
    title: String,
    preview: String,
    time: String,
    unread: TabUnread,
    muted: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TalqTheme.colors
    val bold = unread.count > 0 || unread.dot
    val dim = if (muted) Modifier.alpha(0.6f) else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSecondaryClick(onMenu)
            .combinedClickable(onClick = onClick, onLongClick = onMenu)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(dim.size(46.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            Text(letter, color = letterInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(dim, verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
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
                    color = if (bold && !muted) colors.accentText else colors.textMuted,
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
                    modifier = dim.weight(1f),
                )
                // The conversation list's marks and colours (its row's `unreadFill`).
                if (unread.mentioned || unread.count > 0) {
                    val badgeFill = colors.unreadFill(unread.mentioned, unread.direct)
                    Row(
                        Modifier.clearAndSetSemantics { contentDescription = unreadWords(unread.count, unread.mentioned, unread.direct) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (unread.mentioned) {
                            Spacer(Modifier.width(6.dp))
                            Badge("@", muted = muted && !unread.direct, fill = badgeFill)
                        }
                        if (unread.count > 0) {
                            Spacer(Modifier.width(6.dp))
                            Badge(if (unread.count > 99) "99+" else unread.count.toString(), muted, fill = badgeFill)
                        }
                    }
                } else if (unread.dot) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (muted) colors.textMuted else colors.accent))
                }
            }
        }
    }
}
