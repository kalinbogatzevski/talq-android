package talq.app.ui.adaptive

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.emptyFlow
import talq.app.data.AppearanceStore
import talq.app.theme.TalqTheme

/** A window at least this wide shows the conversation list beside the open conversation... */
val TwoPaneMinWidth = 600.dp

/**
 * ...when it is also at least this tall: a phone turned sideways (wide but short) keeps the
 * phone layout. Taken from the configuration, which the on-screen keyboard does not change.
 */
const val TWO_PANE_MIN_HEIGHT_DP = 480

/**
 * True below a [TwoPane] that shows both panes (a tablet, an unfolded phone, a wide window):
 * the list is already on screen, so the conversation needs no back arrow, and its messages may
 * be wider.
 */
val LocalTwoPane = compositionLocalOf { false }

private val ListMin = 280.dp
/** How far the list gives way in the narrowest two-pane windows, so the conversation keeps [DetailMin]. */
private val ListFloor = 240.dp
private val ListMax = 560.dp
private val DetailMin = 320.dp
private val Gap = 24.dp
private val Step = 40.dp

/**
 * The conversation list beside [detail] when the window is at least [TwoPaneMinWidth] wide and
 * [TWO_PANE_MIN_HEIGHT_DP] tall and [enabled]; otherwise [detail] alone, full size. [detail] keeps its place in the composition
 * either way, so folding, unfolding or resizing keeps everything in it (the open chat, its tab,
 * its draft).
 *
 * The list is as wide as it was last dragged to (kept in [store]), within 280 to 560 dp and
 * always leaving 320 dp for [detail] (in the narrowest windows the list gives way, down to 256 dp
 * at 600 dp, and cannot be dragged). On a foldable held half open like a book,
 * the divider sits on the hinge instead and does not move.
 */
@Composable
fun TwoPane(
    enabled: Boolean,
    store: AppearanceStore,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    val activity = LocalActivity.current
    val layout by remember(activity) {
        activity?.let { WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it) } ?: emptyFlow()
    }.collectAsState(initial = null)
    val hinge = layout?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull {
        it.isSeparating && it.orientation == FoldingFeature.Orientation.VERTICAL
    }
    var originX by remember { mutableFloatStateOf(0f) }
    var preferred by remember { mutableStateOf(store.listPaneWidthDp.dp) }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val tallEnough = LocalConfiguration.current.screenHeightDp >= TWO_PANE_MIN_HEIGHT_DP

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { originX = it.positionInWindow().x },
    ) {
        val total = maxWidth
        val wide = enabled && tallEnough && total >= TwoPaneMinWidth
        // The hinge, measured from the start edge of this layout (the list's side).
        val hingeStart: Dp? = hinge?.let {
            with(density) {
                if (rtl) total - (it.bounds.right - originX).toDp() else (it.bounds.left - originX).toDp()
            }
        }?.takeIf { it > 0.dp && it < total }
        val hingeWidth = hinge?.let { with(density) { it.bounds.width().toDp() } } ?: 0.dp
        val gap = if (hingeStart != null) max(Gap, hingeWidth) else Gap
        val room = total - gap - DetailMin
        val lower = max(ListFloor, min(ListMin, room))
        val upper = max(lower, min(ListMax, room))
        val listWidth = when (hingeStart) {
            null -> preferred.coerceIn(lower, upper)
            else -> (hingeStart + hingeWidth / 2 - gap / 2).coerceIn(0.dp, total - gap)
        }
        val canDrag = hingeStart == null && upper > lower
        val currentLower by rememberUpdatedState(lower)
        val currentUpper by rememberUpdatedState(upper)

        CompositionLocalProvider(LocalTwoPane provides wide) {
            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    Box(
                        Modifier
                            .width(listWidth)
                            .fillMaxHeight()
                            // The system bars at the far edge belong to the other pane.
                            .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.End)),
                    ) { list() }
                    PaneDivider(
                        width = gap,
                        canDrag = canDrag,
                        reverse = rtl,
                        onDrag = { delta ->
                            val from = preferred.coerceIn(currentLower, currentUpper)
                            preferred = (from + with(density) { delta.toDp() }).coerceIn(currentLower, currentUpper)
                        },
                        onStep = { step ->
                            val to = (listWidth + step).coerceIn(currentLower, currentUpper)
                            val moved = to != listWidth
                            if (moved) {
                                preferred = to
                                store.setListPaneWidthDp(to.value)
                            }
                            moved
                        },
                        onDragStopped = { store.setListPaneWidthDp(preferred.value) },
                    )
                }
                // Always called here, at this one place: see the header.
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (wide) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                            else Modifier,
                        ),
                ) { detail() }
            }
        }
    }
}

/** The line between the panes, with a grip to drag it (and two TalkBack actions to move it). */
@Composable
private fun PaneDivider(
    width: Dp,
    canDrag: Boolean,
    reverse: Boolean,
    onDrag: (Float) -> Unit,
    /** Moves the divider by the step; false when it is already at that end. */
    onStep: (Dp) -> Boolean,
    onDragStopped: () -> Unit,
) {
    val colors = TalqTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val drag = rememberDraggableState(onDrag)
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .then(
                if (!canDrag) Modifier else Modifier
                    .draggable(
                        state = drag,
                        orientation = Orientation.Horizontal,
                        interactionSource = interaction,
                        reverseDirection = reverse,
                        onDragStopped = { onDragStopped() },
                    )
                    .semantics {
                        contentDescription = "Divider between the conversation list and the conversation"
                        customActions = listOf(
                            CustomAccessibilityAction("Make the list wider") { onStep(Step) },
                            CustomAccessibilityAction("Make the list narrower") { onStep(-Step) },
                        )
                    },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
        if (canDrag) {
            Box(
                Modifier
                    .size(width = 4.dp, height = 40.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (dragging) colors.textSecondary else colors.textMuted),
            )
        }
    }
}
