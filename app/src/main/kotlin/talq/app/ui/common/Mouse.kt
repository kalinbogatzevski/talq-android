package talq.app.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement

/**
 * A right-click (the mouse's secondary button, or a stylus with its button held) runs [onClick]:
 * the same menu as touch and hold, as TalQ for desktop opens it on a right-click
 * (`SidebarPainter::mouseReleaseEvent` for rows and folder headers, `ChatPainter::mouseReleaseEvent`
 * for messages). It fires on the release, inside the target, like desktop. Put it BEFORE the
 * target's `combinedClickable`: it takes the press on the first pass, so nothing inside (a picture,
 * a quote, a reaction) and not the target's own click sees it. Null = nothing (touch is never
 * affected). Not checked with a mouse on a device: some systems may also turn an unused right-click
 * into Back.
 */
fun Modifier.onSecondaryClick(onClick: (() -> Unit)?): Modifier =
    if (onClick == null) this else this.then(SecondaryClickElement(onClick))

private data class SecondaryClickElement(val onClick: () -> Unit) : ModifierNodeElement<SecondaryClickNode>() {
    override fun create() = SecondaryClickNode(onClick)
    override fun update(node: SecondaryClickNode) {
        node.onClick = onClick
    }
}

private class SecondaryClickNode(var onClick: () -> Unit) : DelegatingNode() {
    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitEachGesture {
                    var event: PointerEvent
                    do {
                        event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.type != PointerEventType.Press)
                    if (!event.buttons.isSecondaryPressed) return@awaitEachGesture
                    event.changes.forEach { it.consume() }
                    while (true) {
                        val next = awaitPointerEvent(PointerEventPass.Initial)
                        next.changes.forEach { it.consume() }
                        if (next.changes.none { it.pressed }) {
                            val at = next.changes.firstOrNull()?.position
                            val inside = at != null && at.x >= 0f && at.y >= 0f &&
                                at.x <= size.width && at.y <= size.height
                            if (inside) onClick()
                            break
                        }
                    }
                }
            },
        )
    }
}
