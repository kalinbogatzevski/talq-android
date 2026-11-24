package talq.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import talq.app.theme.TalqTheme
import talq.app.ui.common.Avatar
import talq.core.TalkError
import talq.core.model.ChatMessage
import talq.core.model.Room
import talq.core.model.isSelectableMessage
import talq.core.repo.TalkSession

/*
 * Several messages selected at once, then copied or forwarded: TalQ for desktop's selection bar
 * (`src/ui/SelectionBarWidget.*`: count, Forward, Copy, Cancel) and its conversation picker
 * (`src/ui/ConversationPickerDialog.*`). What is copied and what is sent is decided in the core
 * (`talq.core.model.Forward.kt`); the room host holds the selection and runs the forward.
 */

/**
 * The messages picked in the open tab. Touch and hold a message, then "Select", starts it; taps
 * add or take away; the last one taken away ends it, as on desktop. System notices and deleted
 * messages cannot be picked. While a forward runs it is [locked]: nothing is picked or copied.
 */
@Stable
class MessageSelection {
    private val picked = mutableStateMapOf<Long, ChatMessage>()

    var active by mutableStateOf(false)
        private set

    /** A forward is running with what was picked. */
    var locked by mutableStateOf(false)

    val count: Int get() = picked.size

    fun isSelected(id: Long): Boolean = id in picked

    /** Start with [m] picked. */
    fun start(m: ChatMessage) {
        if (locked || !isSelectableMessage(m)) return
        picked.clear()
        picked[m.id] = m
        active = true
    }

    fun toggle(m: ChatMessage) {
        if (!active || locked) return
        when {
            m.id in picked -> picked.remove(m.id)
            isSelectableMessage(m) -> picked[m.id] = m
            else -> return
        }
        if (picked.isEmpty()) clear()
    }

    fun clear() {
        picked.clear()
        active = false
    }

    /** The tab's messages changed: picked ones take their new state; deleted ones drop out. */
    fun sync(messages: List<ChatMessage>) {
        if (!active || picked.isEmpty()) return
        val byId = messages.associateBy { it.id }
        for (id in picked.keys.toList()) {
            val fresh = byId[id] ?: continue
            if (!isSelectableMessage(fresh)) picked.remove(id) else if (fresh != picked[id]) picked[id] = fresh
        }
        if (picked.isEmpty()) clear()
    }

    /** What is picked, in chat order. */
    fun messages(): List<ChatMessage> = picked.values.filter(::isSelectableMessage).sortedBy { it.id }
}

/** A message's time for Copy's header: `yyyy-MM-dd HH:mm`, local time, no locale words. Empty without one. */
fun copyStamp(m: ChatMessage): String =
    if (m.timestamp <= 0) "" else Instant.ofEpochSecond(m.timestamp).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT))

/** One line after a forward: how many went, or what stopped it. */
fun forwardResultText(total: Int, sent: Int, error: TalkError?): String {
    if (error == null && sent == total) return if (sent == 1) "Forwarded 1 message" else "Forwarded $sent messages"
    val why = when (error) {
        null -> "nothing to send"
        is TalkError.Network -> "no connection"
        is TalkError.Unauthorized -> "signed out"
        is TalkError.Forbidden -> "not allowed there"
        is TalkError.NotFound -> "not found"
        is TalkError.QuotaExceeded -> "storage full"
        is TalkError.Http -> if (error.status == 413) "too long" else "refused (${error.status})"
        is TalkError.Server -> "server error (${error.status})"
        else -> "unexpected answer"
    }
    return if (sent == 0) "Not forwarded: $why" else "Forwarded $sent of $total: $why"
}

/** The top bar while messages are selected: the count, Copy, Forward (progress while it runs) and Cancel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(count: Int, forwarding: Boolean, onCopy: () -> Unit, onForward: () -> Unit, onCancel: () -> Unit) {
    TopAppBar(
        title = { Text("$count selected", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onCancel, enabled = !forwarding) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
        },
        actions = {
            TextButton(onClick = onCopy, enabled = !forwarding) { Text("Copy") }
            TextButton(onClick = onForward, enabled = !forwarding) {
                if (forwarding) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Forward")
            }
        },
    )
}

/**
 * The round mark at the start of a message row while selecting: filled with a check when picked,
 * an empty ring when not. A message that cannot be picked gets the same space and no mark.
 */
@Composable
fun SelectMark(selectable: Boolean, selected: Boolean, size: Dp = 22.dp) {
    val colors = TalqTheme.colors
    Box(Modifier.padding(start = 10.dp).size(size), contentAlignment = Alignment.Center) {
        if (!selectable) return@Box
        if (selected) {
            Box(Modifier.size(size).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.controlInk, modifier = Modifier.size(size * 0.7f))
            }
        } else {
            Box(Modifier.size(size).clip(CircleShape).background(colors.bgSecondary).border(1.5.dp, colors.textMuted, CircleShape))
        }
    }
}

/**
 * Where to forward: the conversation list (favourites, then the latest first), searchable by
 * name, without the conversation the messages come from, read-only ones and ones where the user
 * may not post (chat permission 128 missing; permissions 0 = not sent, kept). A tap picks and closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardSheet(session: TalkSession, fromToken: String, count: Int, onPick: (Room) -> Unit, onDismiss: () -> Unit) {
    val colors = TalqTheme.colors
    val rooms by session.rooms.rooms.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim()
    val shown = remember(rooms, needle) {
        rooms.filter {
            it.token != fromToken && !it.isReadOnly && (it.permissions == 0 || it.permissions and 128 != 0) &&
                (needle.isEmpty() || it.displayName.contains(needle, ignoreCase = true))
        }
            .sortedWith(compareBy<Room> { it.isArchived }.thenByDescending { it.isFavorite }.thenByDescending { it.lastActivity })
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            Text(
                if (count == 1) "Forward 1 message to…" else "Forward $count messages to…",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.replace('\n', ' ') },
                placeholder = { Text("Search conversations") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.token }) { room ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(room) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        Avatar(
                            url = session.client.roomAvatarUrl(room.token, room.avatarVersion, !colors.isLight),
                            authorization = session.client.authorizationHeader,
                            name = room.displayName,
                            size = 40.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(room.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "none") {
                        Text(
                            if (needle.isEmpty()) "No conversation to forward to" else "No conversation matches",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
