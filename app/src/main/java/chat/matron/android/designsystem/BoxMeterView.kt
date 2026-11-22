package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.matron.android.viewmodels.AgentCapacityFreshness
import chat.matron.android.viewmodels.BoxCapacity
import chat.matron.android.viewmodels.BoxConnectionState
import chat.matron.android.viewmodels.LimitLine

/// One usage meter on the Boxes screen: the percent, a thin bar and when it
/// resets. Same colours and de-emphasis rule as the New Chat chooser: an
/// expired line or last-known numbers render muted, never green/orange/red.
@Composable
fun BoxMeterView(
    line: LimitLine,
    freshness: AgentCapacityFreshness,
    modifier: Modifier = Modifier,
    /// Frozen clock (epoch ms) for screenshots; null = now.
    fixedNowMs: Long? = null,
) {
    val nowMs = fixedNowMs ?: System.currentTimeMillis()
    val expired = BoxCapacity.hasReset(line.resetsAt, nowMs)
    val muted = expired || freshness.isStale
    val color = if (muted) MaterialTheme.colorScheme.outline else UsageMetersFormat.barColor(line.percent)
    val reset = BoxCapacity.resetCaption(line.resetsAt, nowMs)
    val spoken = buildString {
        append("${line.label}, ${line.percent} percent used")
        if (expired) append(" before the limit reset")
        if (reset != null) append(", $reset")
        if (freshness.isStale) append(", last known")
    }
    Column(
        modifier.clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            "${line.percent}%",
            style = MaterialTheme.typography.bodyMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
            fontWeight = FontWeight.Medium,
            color = color,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(line.percent.coerceIn(2, 100) / 100f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(color),
            )
        }
        if (reset != null) {
            Text(
                reset,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/// A box's state: a coloured dot and its name.
@Composable
fun BoxStateLabel(state: BoxConnectionState, modifier: Modifier = Modifier) {
    val dot = when (state) {
        BoxConnectionState.Online -> MatronGreen
        BoxConnectionState.Asleep -> Color.Gray
        BoxConnectionState.Offline -> MatronRed
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(
            state.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/// The small tinted pill beside a box's name ("Near limit", "Most spare").
@Composable
fun BoxBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
    )
}
