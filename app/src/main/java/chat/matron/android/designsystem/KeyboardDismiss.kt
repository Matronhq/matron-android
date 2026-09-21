package chat.matron.android.designsystem

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlin.math.abs

/// Hiding the keyboard from a composer row (apple #200). Dragging the
/// composer row itself downward is the gesture people actually reach for
/// when they want the screen back to read; it has no framework equivalent,
/// so it lives here. Ported from matron-apple's `KeyboardDismiss`.
object KeyboardDismiss {
    /// Whether a drag on the composer row has become a "hide the keyboard"
    /// gesture: mostly vertical and downward by at least [minimumDropDp].
    /// Upward and sideways drags (text selection, a slip while reaching
    /// for the send button) never count. Translation in dp.
    fun shouldDismiss(dx: Float, dy: Float, minimumDropDp: Float = 24f): Boolean =
        dy >= minimumDropDp && dy > abs(dx)
}

/// Dragging this view downward hides the keyboard and clears the field's
/// focus. Detection waits for touch slop AFTER the field's own touches
/// (caret placement, selection, scrolling a tall draft keep working); once
/// the drag reads as a deliberate pull-down the keyboard drops, and the
/// gesture is consumed from there on.
fun Modifier.dragDownDismissesKeyboard(): Modifier = composed {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    pointerInput(keyboard, focus) {
        var total = Offset.Zero
        var dismissed = false
        detectDragGestures(
            onDragStart = { total = Offset.Zero; dismissed = false },
            onDragCancel = { total = Offset.Zero },
            onDragEnd = { total = Offset.Zero },
        ) { change, dragAmount ->
            total += dragAmount
            val dx = with(density) { total.x.toDp().value }
            val dy = with(density) { total.y.toDp().value }
            if (!dismissed && KeyboardDismiss.shouldDismiss(dx, dy)) {
                dismissed = true
                keyboard?.hide()
                focus.clearFocus()
            }
            if (dismissed) change.consume()
        }
    }
}
