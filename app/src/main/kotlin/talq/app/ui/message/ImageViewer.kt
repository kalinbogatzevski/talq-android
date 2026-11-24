package talq.app.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.memory.MemoryCache
import talq.app.ui.common.BUBBLE_PREVIEW_PX
import talq.app.ui.common.GRID_PREVIEW_PX
import talq.app.ui.common.authorizedImage
import talq.app.ui.common.thumbnailKey
import talq.core.model.RichObject
import talq.core.repo.TalkSession

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
/** The sharp preview the viewer asks for (long edge). */
private const val VIEWER_PREVIEW_PX = 2048

/**
 * Pictures on a black ground. Swipe sideways through [images] (the loaded pictures of the tab
 * it was opened from, in the order given), pinch to zoom, drag to pan, double tap to zoom in
 * and back out. Save and Share act on the picture in front, in its original form. A page shows
 * the thumbnail already in memory at once and the sharp preview over it when it arrives; a ring
 * while it loads, one line with Retry when it fails.
 */
@Composable
fun ImageViewer(
    images: List<RichObject>,
    start: Int,
    session: TalkSession,
    onClose: () -> Unit,
    onSave: (RichObject) -> Unit,
    onShare: (RichObject) -> Unit,
    snackbar: SnackbarHostState? = null,
    onPage: (Int) -> Unit = {},
) {
    if (images.isEmpty()) return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, images.lastIndex)) { images.size }
        // While the picture in front is zoomed, a sideways drag pans it instead of turning the page.
        var zoomed by remember { mutableStateOf(false) }
        LaunchedEffect(pager.currentPage) {
            zoomed = false
            onPage(pager.currentPage)
        }
        val current = images[pager.currentPage.coerceIn(0, images.lastIndex)]
        // Save and Share fetch the original first: show that something is happening.
        val fetching = downloadProgress(current.id)

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize(), key = { images[it].id }) { page ->
                ZoomablePage(
                    file = images[page],
                    session = session,
                    active = page == pager.currentPage,
                    quiet = page == pager.currentPage && fetching != null,
                    onZoomed = { if (page == pager.currentPage) zoomed = it },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .systemBarsPadding()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(
                        current.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (images.size > 1) {
                        Text(
                            "${pager.currentPage + 1} of ${images.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
                IconButton(onClick = { onSave(current) }) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = "Save to Pictures", tint = Color.White)
                }
                IconButton(onClick = { onShare(current) }) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color.White)
                }
            }
            if (fetching != null) DownloadRing(current.id, fetching, Modifier.align(Alignment.Center))
            if (snackbar != null) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).systemBarsPadding())
        }
    }
}

/** Where the sharp picture of a page is: on its way, on screen, or failed (Retry asks again). */
private enum class PagePhase { Loading, Shown, Failed }

@Composable
private fun ZoomablePage(
    file: RichObject,
    session: TalkSession,
    active: Boolean,
    /** Another ring is showing (Save / Share fetching the original): this page shows none. */
    quiet: Boolean,
    onZoomed: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val auth = session.client.authorizationHeader
    val url = session.client.filePreviewUrl(file.id, VIEWER_PREVIEW_PX, VIEWER_PREVIEW_PX)
    // The copy the chat bubble (or the shared-files grid) already holds, shown at once while the
    // sharp one loads; it stays on screen when the sharp one fails.
    val thumbnail = remember(url) {
        val cache = SingletonImageLoader.get(context).memoryCache
        listOf(BUBBLE_PREVIEW_PX, GRID_PREVIEW_PX)
            .map { thumbnailKey(session.client.filePreviewUrl(file.id, it, it)) }
            .firstOrNull { cache?.get(MemoryCache.Key(it)) != null }
    }
    // A new attempt is a new image node: an equal request would not be asked again.
    var attempt by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(PagePhase.Loading) }
    val sharp = remember(url, thumbnail, attempt) { authorizedImage(context, url, auth, placeholderKey = thumbnail) }
    val fallback = remember(thumbnail) { thumbnail?.let { authorizedImage(context, it, auth, memoryKey = it) } }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    fun set(newScale: Float, newPan: Offset, width: Int, height: Int) {
        scale = newScale.coerceIn(1f, MAX_ZOOM)
        // The picture never leaves the screen: at most the part that sticks out can be moved in.
        val maxX = width * (scale - 1f) / 2f
        val maxY = height * (scale - 1f) / 2f
        pan = if (scale == 1f) Offset.Zero else Offset(newPan.x.coerceIn(-maxX, maxX), newPan.y.coerceIn(-maxY, maxY))
        onZoomed(scale > 1f)
    }
    // Swiped away while zoomed: it comes back whole.
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            pan = Offset.Zero
        }
    }

    Box(Modifier.fillMaxSize()) {
        key(attempt) {
            AsyncImage(
                model = if (phase == PagePhase.Failed && fallback != null) fallback else sharp,
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                // Only the sharp picture moves the phase; the fallback after a failure does not.
                onState = { state ->
                    if (phase != PagePhase.Failed) {
                        phase = when (state) {
                            is AsyncImagePainter.State.Success -> PagePhase.Shown
                            is AsyncImagePainter.State.Error -> PagePhase.Failed
                            else -> PagePhase.Loading
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val fingers = event.changes.count { it.pressed }
                                // One finger on a picture that is not zoomed belongs to the pager.
                                if (fingers > 1 || scale > 1f) {
                                    set(scale * event.calculateZoom(), pan + event.calculatePan(), size.width, size.height)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) set(1f, Offset.Zero, size.width, size.height)
                            else set(DOUBLE_TAP_ZOOM, Offset.Zero, size.width, size.height)
                        })
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = pan.x
                        translationY = pan.y
                    },
            )
        }
        when {
            phase == PagePhase.Loading && !quiet -> CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.85f),
                strokeWidth = 2.5.dp,
                modifier = Modifier.align(Alignment.Center).size(32.dp),
            )
            phase == PagePhase.Failed -> Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (fallback != null) "Couldn't load the full picture." else "Couldn't load the picture.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = {
                    phase = PagePhase.Loading
                    attempt++
                }) { Text("Retry", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
