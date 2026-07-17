package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.matron.android.models.SessionStatus
import kotlinx.coroutines.delay
import java.time.Instant

/// "Context: 265k/1m" — the context-window gauge from the last status frame.
/// Caption-sized secondary text.
@Composable
fun ContextGaugeLabel(context: SessionStatus.Context, modifier: Modifier = Modifier) {
    val spoken = "Context: ${UsageMetersFormat.spokenTokens(context.tokens)} of " +
        "${UsageMetersFormat.spokenTokens(context.window)} tokens"
    Text(
        "Context: ${UsageMetersFormat.compactTokens(context.tokens)}/" +
            UsageMetersFormat.compactTokens(context.window),
        modifier = modifier.semantics { contentDescription = spoken },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/// Density of the usage bars. [Compact] fits a toolbar; [Regular] is the roomier
/// sheet form.
enum class UsageBarScale(
    val fontSize: TextUnit,
    val barWidth: Dp,
    val barHeight: Dp,
    val rowSpacing: Dp,
) {
    Compact(9.sp, 90.dp, 3.dp, 2.dp),
    Regular(12.sp, 160.dp, 6.dp, 8.dp),
}

/// Stacked horizontal usage bars (Session / Week / model) with the reset time
/// trailing each bar. A 1-minute self-refresh keeps countdown text ("3h20")
/// fresh between status frames; pass a non-null [fixedNow] to freeze the clock
/// (deterministic tests / previews).
@Composable
fun UsageBarsView(
    limits: List<SessionStatus.Limit>,
    modifier: Modifier = Modifier,
    scale: UsageBarScale = UsageBarScale.Compact,
    fixedNow: Instant? = null,
) {
    val now by produceState(initialValue = fixedNow ?: Instant.now(), fixedNow) {
        if (fixedNow != null) {
            value = fixedNow
        } else {
            while (true) {
                value = Instant.now()
                delay(60_000)
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(scale.rowSpacing)) {
        // Server order, capped at three — sized for session / week / per-model.
        limits.take(3).forEach { limit ->
            UsageBarRow(limit, scale, now)
        }
    }
}

@Composable
private fun UsageBarRow(limit: SessionStatus.Limit, scale: UsageBarScale, now: Instant) {
    val reset = UsageMetersFormat.resetDisplay(limit.resetsAt, limit.resets, now)
    val accessibility = buildString {
        append("${UsageMetersFormat.barLabel(limit.label)}, ${limit.percent} percent used")
        if (reset != null) append(", resets $reset")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = accessibility },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            "${UsageMetersFormat.barLabel(limit.label)}:",
            fontSize = scale.fontSize,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
        UsageBar(limit.percent, scale)
        Text(
            reset ?: "",
            fontSize = scale.fontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UsageBar(percent: Int, scale: UsageBarScale) {
    val fraction = (percent.coerceIn(0, 100)) / 100f
    Box(
        Modifier
            .width(scale.barWidth)
            .height(scale.barHeight)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(scale.barHeight)
                .clip(RoundedCornerShape(percent = 50))
                .background(UsageMetersFormat.barColor(percent)),
        )
    }
}
