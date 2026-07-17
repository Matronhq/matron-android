package chat.matron.android.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/// A bot-aligned typing / tool-use indicator: three softly pulsing dots
/// followed by a [label] ("Thinking…", "Running <tool>"). Rendered as a
/// trailing timeline row while the agent is working, then removed.
@Composable
fun ActivityIndicatorRow(label: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "activityDots")
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { contentDescription = label.ifEmpty { "Agent is working" } },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Each dot shares the same pulse, offset in time so they ripple.
            for (index in 0..2) {
                val alpha by transition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 600, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(index * 200),
                    ),
                    label = "dot$index",
                )
                Box(
                    Modifier
                        .size(6.dp)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
        }
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
