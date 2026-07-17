package chat.matron.android.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/// Warm-up spinner for the chat timeline. Overlaid while the timeline is
/// waiting for its first snapshot. Appearance is delayed [delayMillis]: a
/// conversation already mirrored locally paints well under the delay, and
/// flashing a spinner for a few frames reads as jank — only the genuinely
/// slow path (history fetch, cold store) ever sees it.
@Composable
fun TimelineLoadingIndicator(modifier: Modifier = Modifier, delayMillis: Long = 300) {
    var visible by remember { mutableStateOf(false) }
    val opacity by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "spinnerFade",
    )
    LaunchedEffect(Unit) {
        delay(delayMillis)
        visible = true
    }
    Box(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = "Loading messages" },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(Modifier.alpha(opacity).size(36.dp))
    }
}
