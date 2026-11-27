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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.File
import talq.app.files.AttachTrays
import talq.app.files.TrayItem
import talq.app.theme.TalqTheme
import talq.app.ui.common.UiLocale

/** The tray as the composer sees it: whether Send may go, whether it waits for copies, and how it is drawn. */
class ComposerTray(val ready: Boolean, val preparing: Boolean, val content: @Composable () -> Unit)

/** "Too long: 1,234 over", the one line above the box while its text is [over] characters past the server's limit. */
fun tooLongText(over: Int): String = "Too long: ${String.format(UiLocale, "%,d", over)} over"

/**
 * The files waiting above the message box ([AttachTrays]): a thumbnail for a picture, a tile with the name
 * for anything else, each with an x; "+" for more ([onAdd], null: not now); "Original" while a photo or a
 * video is among them (they go smaller and without their location unless it is on).
 */
@Composable
fun AttachTray(trays: AttachTrays, key: ComposerDrafts.Key, onAdd: (() -> Unit)?) {
    val colors = TalqTheme.colors
    val items = trays.items(key)
    val list = rememberLazyListState()
    // A new file at the end comes into view.
    var shown by remember { mutableIntStateOf(items.size) }
    LaunchedEffect(items.size) {
        if (items.size > shown) list.animateScrollToItem(items.size - 1)
        shown = items.size
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                state = list,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    Tile(item, trays, onRemove = { trays.remove(key, item.id) })
                }
                if (onAdd != null) item(key = "add") { AddTile(onAdd) }
            }
            if (items.any { it.isPhoto || it.isVideo }) OriginalToggle(trays.sendOriginal, trays::useOriginals)
        }
        if (items.any(trays::blocked)) TrayLine("Tap the marked photo to retry, or turn on Original.")
    }
}

/** A line about what keeps Send off. */
@Composable
internal fun TrayLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = TalqTheme.colors.danger,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp),
    )
}

@Composable
private fun Tile(item: TrayItem, trays: AttachTrays, onRemove: () -> Unit) {
    val colors = TalqTheme.colors
    val file = item.original
    val mime = file?.mimeType.orEmpty()
    val picture = mime.startsWith("image/")
    val blocked = trays.blocked(item)
    val preparing = !blocked && !trays.ready(item)
    val name = file?.name.orEmpty()
    val shape = RoundedCornerShape(12.dp)
    val frame = Modifier
        .height(TileSize)
        .clip(shape)
        .background(colors.bgSurface)
        .border(1.dp, if (blocked) colors.danger else colors.divider, shape)
    val state = when {
        preparing -> "Preparing"
        blocked -> "Not made smaller"
        else -> ""
    }
    if (picture || file == null) {
        // A marked photo: a tap tries once more.
        val again = if (blocked) Modifier.clickable(onClickLabel = "Retry") { trays.shrinkAgain(item.id) } else Modifier
        Box(
            frame.width(TileSize).then(again).semantics {
                contentDescription = if (picture) "Photo $name" else "File"
                if (state.isNotEmpty()) stateDescription = state
            },
        ) {
            if (picture) {
                Icon(Icons.Outlined.Image, contentDescription = null, tint = colors.accentText, modifier = Modifier.align(Alignment.Center).size(26.dp))
                // The smaller copy once it is there: upright and quick to draw.
                AsyncImage(
                    model = File((item.small ?: file)?.path.orEmpty()),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (preparing) Ring(Modifier.align(Alignment.Center))
            if (blocked) {
                Box(Modifier.align(Alignment.BottomStart).padding(4.dp).clip(CircleShape).background(colors.bgPrimary)) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.danger, modifier = Modifier.size(20.dp))
                }
            }
            RemoveMark(name, onRemove, Modifier.align(Alignment.TopEnd))
        }
    } else {
        Row(
            frame.width(FileTileWidth).padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = when {
                mime.startsWith("video/") -> Icons.Outlined.Movie
                mime.startsWith("audio/") -> Icons.Outlined.AudioFile
                else -> Icons.AutoMirrored.Outlined.InsertDriveFile
            }
            Icon(icon, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
            RemoveMark(name, onRemove, Modifier.align(Alignment.Top))
        }
    }
}

@Composable
private fun Ring(modifier: Modifier) {
    val colors = TalqTheme.colors
    Box(
        modifier.size(30.dp).clip(CircleShape).background(colors.bgSurface),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accentText) }
}

/** The x in a tile's corner: its own fill, so it reads on any picture; the touch target is larger than it looks. */
@Composable
private fun RemoveMark(name: String, onRemove: () -> Unit, modifier: Modifier) {
    val colors = TalqTheme.colors
    Box(
        modifier
            .size(36.dp)
            .clickable(role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = if (name.isEmpty()) "Remove" else "Remove $name" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(colors.bgPrimary), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun AddTile(onAdd: () -> Unit) {
    val colors = TalqTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(TileSize)
            .clip(shape)
            .border(1.dp, colors.divider, shape)
            .clickable(role = Role.Button, onClick = onAdd),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.Add, contentDescription = "Add more", tint = colors.textSecondary, modifier = Modifier.size(24.dp)) }
}

/** Send photos and videos as they are, with their location and camera details. */
@Composable
private fun OriginalToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = TalqTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .padding(end = 10.dp)
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (on) colors.accentSoft else colors.bgSurface)
            .border(1.dp, if (on) colors.accent else colors.divider, shape)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accentSoftInk, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text("Original", style = MaterialTheme.typography.labelLarge, color = if (on) colors.accentSoftInk else colors.textSecondary, maxLines = 1)
    }
}

private val TileSize = 64.dp
private val FileTileWidth = 168.dp
