package chat.matron.android.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import chat.matron.android.models.UsageHistoryTable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/// The colour usage history is drawn in. One hue, never the red/green "how
/// full" of the live meters: the blue is what was left unused.
object UsageHistoryStyle {
    val tint: Color = Color(0, 122, 255)

    /// "Nov 3": the Monday a column's week starts on, read in UTC, since a
    /// column's week is a UTC calendar week.
    fun columnText(mondayMs: Long): String =
        Instant.ofEpochMilli(mondayMs).atZone(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
}

/// An account's weeks as bars, oldest first: the grey part was used, the
/// blue cap was left unused. Ends with the week in progress, dashed, since
/// it is not a result yet. Decorative: the card around it speaks the numbers.
@Composable
fun UsageHistoryBars(row: UsageHistoryTable.Row, modifier: Modifier = Modifier) {
    val used = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val empty = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
    val currentFill = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val dash = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Canvas(modifier.fillMaxWidth().height(54.dp).clearAndSetSemantics {}) {
        val count = row.cells.size + 1
        val gap = 4.dp.toPx()
        val width = (size.width - gap * (count - 1)) / count
        val radius = CornerRadius(3.dp.toPx())
        row.cells.forEachIndexed { index, week ->
            val left = index * (width + gap)
            if (week == null) {
                drawRoundRect(empty, Offset(left, 0f), Size(width, size.height), radius)
            } else {
                drawRoundRect(used, Offset(left, 0f), Size(width, size.height), radius)
                val cap = size.height * week.unused.coerceIn(0, 100) / 100f
                if (cap > 0f) drawRoundRect(UsageHistoryStyle.tint, Offset(left, 0f), Size(width, cap), radius)
            }
        }
        val left = (count - 1) * (width + gap)
        row.current?.let { current ->
            val usedHeight = size.height * (100 - current.unused.coerceIn(0, 100)) / 100f
            drawRoundRect(currentFill, Offset(left, size.height - usedHeight), Size(width, usedHeight), radius)
        }
        val stroke = 1.dp.toPx()
        drawRoundRect(
            dash,
            Offset(left + stroke / 2, stroke / 2),
            Size(width - stroke, size.height - stroke),
            radius,
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
        )
    }
}
