package chat.matron.android.designsystem

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.composed

/// Reports a finished drag's total translation, in dp, to [onSwipe]. The
/// app shell attaches it to each tab's ROOT list (apple #196): drag
/// detection waits for touch slop in the Main pass AFTER the list, so a
/// vertical drag the list scrolls is consumed before this ever starts and
/// the list keeps its own scroll; only a drag the list leaves alone (a
/// horizontal one, or any drag on a list too short to scroll) reaches here,
/// and the pure rule (`AppShellNavigation.swipeRoot`) decides whether it
/// was a tab swipe.
fun Modifier.tabRootSwipe(onSwipe: (dx: Float, dy: Float) -> Unit): Modifier = composed {
    val density = LocalDensity.current
    pointerInput(onSwipe) {
        var total = Offset.Zero
        detectDragGestures(
            onDragStart = { total = Offset.Zero },
            onDragCancel = { total = Offset.Zero },
            onDragEnd = {
                val dx = with(density) { total.x.toDp().value }
                val dy = with(density) { total.y.toDp().value }
                total = Offset.Zero
                onSwipe(dx, dy)
            },
        ) { change, dragAmount ->
            total += dragAmount
            change.consume()
        }
    }
}
