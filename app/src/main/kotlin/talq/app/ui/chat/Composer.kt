package talq.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import talq.app.theme.TalqTheme
import talq.app.ui.message.previewText
import talq.core.model.ChatMessage

/**
 * The message field. Sending does not touch focus, so the keyboard stays up for the next
 * message. Enter makes a new line; the button sends. With [onAttach] a paperclip sits before
 * the field; what is typed then goes out as the caption of the first file picked.
 */
@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    replyTo: ChatMessage?,
    onCancelReply: () -> Unit,
    onSend: () -> Unit,
    placeholder: String,
    onAttach: (() -> Unit)? = null,
) {
    val colors = TalqTheme.colors
    val canSend = text.isNotBlank()
    Column(Modifier.fillMaxWidth().background(colors.bgSidebar)) {
        HorizontalDivider(color = colors.divider)
        if (replyTo != null) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 14.dp, top = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colors.accent))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Replying to ${replyTo.actorDisplayName.ifBlank { "Guest" }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accentText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        previewText(replyTo),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onCancelReply) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel reply", tint = colors.textSecondary)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = if (onAttach != null) 2.dp else 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (onAttach != null) {
                IconButton(onClick = onAttach, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "Attach", tint = colors.textSecondary)
                }
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 6,
                modifier = Modifier.weight(1f),
                decorationBox = { field ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(colors.bgSurface)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) {
                            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
                        }
                        field()
                    }
                },
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (canSend) colors.accent else colors.bgSelected),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onSend, enabled = canSend) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) colors.controlInk else colors.textMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
