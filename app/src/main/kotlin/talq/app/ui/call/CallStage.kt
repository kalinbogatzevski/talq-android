package talq.app.ui.call

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import talq.app.call.CallController
import talq.app.theme.TalqTheme
import talq.app.ui.common.Avatar
import talq.core.call.CallParticipant
import talq.core.call.MediaConnection
import talq.core.call.StreamKind
import talq.core.call.SubscriberKey
import talq.core.call.substreamFor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/*
 * The call screen's middle when more than one other person is in the call, or someone shares a
 * screen: tiles by count (2 stacked, 3-4 in two columns, 5 and more in a scrolling grid of two
 * columns), and a shared screen on top with the people as a strip under it. Tiles and the screen
 * are drawn on TextureViews (libwebrtc's EglRenderer): unlike a SurfaceView they clip, scroll and
 * scale with the rest of the screen. Each camera tile asks for the simulcast layer its height
 * deserves (`substreamFor`); a tile that leaves the screen, and every camera while a screen is
 * shown, asks for the lowest.
 */

/** The newest person sharing a screen whose picture has arrived, by sid; null when nobody shares. */
fun shownScreen(participants: List<CallParticipant>, videos: Map<SubscriberKey, VideoTrack>, picked: String?): CallParticipant? {
    val sharing = participants.filter { it.sharing && videos[SubscriberKey(it.sid, StreamKind.Screen)] != null }
    return sharing.firstOrNull { it.sid == picked } ?: sharing.lastOrNull()
}

/** The tiles of everyone else in a group call (two or more people, nobody sharing). */
@Composable
fun PeopleGrid(call: CallController.ActiveCall, participants: List<CallParticipant>, videos: Map<SubscriberKey, VideoTrack>, modifier: Modifier) {
    val gap = 6.dp
    when (participants.size) {
        2 -> Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
            for (p in participants) PersonTile(call, p, videos, Modifier.weight(1f).fillMaxWidth())
        }
        3, 4 -> Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in participants.chunked(2)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (p in row) PersonTile(call, p, videos, Modifier.weight(1f).fillMaxSize())
                }
            }
        }
        else -> BoxWithConstraints(modifier) {
            val tileHeight = (maxHeight - gap * 2) / 3
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalArrangement = Arrangement.spacedBy(gap),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(participants, key = { it.sid }) { p ->
                    PersonTile(call, p, videos, Modifier.fillMaxWidth().height(tileHeight))
                }
            }
        }
    }
}

/** One person: their camera, or their picture and name; their name, a mic-off mark, a frame while they speak. */
@Composable
fun PersonTile(call: CallController.ActiveCall, p: CallParticipant, videos: Map<SubscriberKey, VideoTrack>, modifier: Modifier) {
    val colors = TalqTheme.colors
    val density = LocalDensity.current
    val track = videos[SubscriberKey(p.sid, StreamKind.Camera)]
    var layer by remember(p.sid) { mutableIntStateOf(-1) }
    // Off the screen (scrolled away, or the grid gave way to a screen share): the lowest layer.
    DisposableEffect(p.sid) {
        onDispose { call.session.setSubstream(p.sid, 0) }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .clip(shape)
            .background(colors.bgSecondary)
            .then(if (p.speaking) Modifier.border(3.dp, colors.amber, shape) else Modifier)
            .onSizeChanged { size ->
                val heightDp = with(density) { size.height.toDp().value }
                val next = substreamFor(heightDp, layer)
                if (next != layer) {
                    layer = next
                    call.session.setSubstream(p.sid, next)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (p.video && track != null) {
            VideoTexture(track, call.engine.eglBase, fill = true, modifier = Modifier.fillMaxSize())
        } else {
            PersonPicture(call, p, size = 72)
        }
        val waiting = (p.audio || p.video) && p.camera != MediaConnection.Connected
        if (waiting) {
            Text(
                if (p.camera == MediaConnection.Failed) "Reconnecting…" else "Connecting…",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            )
        }
        NameLabel(p, Modifier.align(Alignment.BottomStart).padding(8.dp))
    }
}

/** A person's avatar (their account's picture, else their initials). */
@Composable
fun PersonPicture(call: CallController.ActiveCall, p: CallParticipant, size: Int) {
    val client = call.account.client
    val url = if (p.userId.isNotEmpty()) client.userAvatarUrl(p.userId, 256) else ""
    Avatar(url, client.authorizationHeader, p.displayName, size = size.dp)
}

@Composable
private fun NameLabel(p: CallParticipant, modifier: Modifier) {
    val colors = TalqTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.bgPrimary.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!p.audio) {
            Icon(Icons.Filled.MicOff, contentDescription = "Microphone off", tint = colors.danger, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(p.displayName, style = MaterialTheme.typography.labelMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A shared screen on top, the people as a strip of small pictures under it. Pinch to zoom (up to
 * 4x) and drag; a double tap goes back to the whole screen. [landscape]: the screen alone, a tap
 * shows the strip. Several shares at once: chips above switch between them.
 */
@Composable
fun ScreenStage(
    call: CallController.ActiveCall,
    sharer: CallParticipant,
    participants: List<CallParticipant>,
    videos: Map<SubscriberKey, VideoTrack>,
    landscape: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = TalqTheme.colors
    val track = videos[SubscriberKey(sharer.sid, StreamKind.Screen)]
    var stripShown by remember { mutableStateOf(!landscape) }
    // Every camera at its lowest while a screen is shown: the strip shows pictures, not video.
    LaunchedEffect(participants.map { it.sid }) {
        for (p in participants) call.session.setSubstream(p.sid, 0)
    }
    Column(modifier) {
        val sharers = participants.filter { it.sharing }
        if (sharers.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in sharers) {
                    FilterChip(
                        selected = s.sid == sharer.sid,
                        onClick = { onPick(s.sid) },
                        label = { Text(s.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ScreenShare, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().background(colors.bgPrimary),
            contentAlignment = Alignment.Center,
        ) {
            if (track != null) ZoomableScreen(track, call.engine.eglBase, onTap = { if (landscape) stripShown = !stripShown })
            Text(
                "${sharer.displayName}'s screen",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .clip(RoundedCornerShape(8.dp)).background(colors.bgPrimary.copy(alpha = 0.72f)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (!landscape || stripShown) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (p in participants) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(26.dp))
                                .then(if (p.speaking) Modifier.border(3.dp, colors.amber, RoundedCornerShape(26.dp)) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            PersonPicture(call, p, size = 48)
                            if (!p.audio) {
                                Icon(
                                    Icons.Filled.MicOff, contentDescription = "Microphone off", tint = colors.danger,
                                    modifier = Modifier.align(Alignment.BottomEnd).size(16.dp).clip(RoundedCornerShape(8.dp)).background(colors.bgPrimary),
                                )
                            }
                        }
                        Text(p.displayName, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A screen fitted into the space (its own aspect ratio), with pinch to zoom, drag and double tap. */
@Composable
private fun ZoomableScreen(track: VideoTrack, egl: EglBase, onTap: () -> Unit) {
    var aspect by remember(track) { mutableFloatStateOf(16f / 9f) }
    var scale by remember(track) { mutableFloatStateOf(1f) }
    var offset by remember(track) { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .pointerInput(track) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                )
            }
            .pointerInput(track) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                    scale = next
                    val maxX = size.width * (next - 1f) / 2f
                    val maxY = size.height * (next - 1f) / 2f
                    offset = if (next == 1f) Offset.Zero else Offset(
                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                        (offset.y + pan.y).coerceIn(-maxY, maxY),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        VideoTexture(
            track, egl, fill = false,
            onFrameSize = { w, h -> if (w > 0 && h > 0) aspect = w.toFloat() / h },
            modifier = Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = maxWidth / maxHeight > aspect)
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}

/**
 * A video track on a TextureView. [fill] crops to the view (tiles); otherwise the view is meant
 * to have the picture's own aspect ratio ([onFrameSize] tells it). The engine disposes a track
 * only a moment after it stops offering it, so the view lets go of it first.
 */
@Composable
fun VideoTexture(track: VideoTrack, egl: EglBase, fill: Boolean, modifier: Modifier, onFrameSize: (Int, Int) -> Unit = { _, _ -> }) {
    var view by remember { mutableStateOf<VideoTextureView?>(null) }
    AndroidView(
        factory = { ctx -> VideoTextureView(ctx, egl.eglBaseContext, fill).also { view = it } },
        update = { it.onFrameSize = onFrameSize },
        onRelease = { it.release() },
        modifier = modifier,
    )
    val v = view
    DisposableEffect(track, v) {
        if (v != null) runCatching { track.addSink(v) }
        onDispose { if (v != null) runCatching { track.removeSink(v) } }
    }
}

/**
 * libwebrtc's EglRenderer drawing into a TextureView. [fill] crops the picture to the view's
 * aspect ratio; otherwise the whole picture is drawn (the view has its aspect ratio). Frame sizes
 * are reported on the main thread.
 */
@SuppressLint("ViewConstructor")
class VideoTextureView(context: Context, shared: EglBase.Context, private val fill: Boolean) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = EglRenderer("talq-tile")
    @Volatile var onFrameSize: (Int, Int) -> Unit = { _, _ -> }
    private var frameWidth = 0
    private var frameHeight = 0
    private var released = false

    init {
        renderer.init(shared, EglBase.CONFIG_PLAIN, GlRectDrawer())
        surfaceTextureListener = this
    }

    override fun onFrame(frame: VideoFrame) {
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w != frameWidth || h != frameHeight) {
            frameWidth = w
            frameHeight = h
            post { onFrameSize(w, h) }
        }
        renderer.onFrame(frame)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        aspect(width, height)
        renderer.createEglSurface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = aspect(width, height)

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val done = CountDownLatch(1)
        renderer.releaseEglSurface { done.countDown() }
        done.await(SURFACE_RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun aspect(width: Int, height: Int) {
        if (width > 0 && height > 0) renderer.setLayoutAspectRatio(if (fill) width.toFloat() / height else 0f)
    }

    fun release() {
        if (released) return
        released = true
        renderer.release()
    }

    private companion object {
        const val SURFACE_RELEASE_WAIT_MS = 1_000L
    }
}

private const val MAX_ZOOM = 4f
