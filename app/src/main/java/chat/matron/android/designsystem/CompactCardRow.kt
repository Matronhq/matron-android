package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.events.CompactCardLine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/// What a [CompactCardRow]'s line announces: the summary, then the status.
fun compactCardAccessibilityLabel(line: CompactCardLine): String = listOfNotNull(line.summary, line.status).joinToString(", ")

/// The tap label a [CompactCardRow] announces: what a tap will do next.
fun compactCardToggleLabel(expanded: Boolean): String = if (expanded) "Hide the details" else "Show the details"

private val compactCardTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/// A bulky timeline card folded to one quiet line: chevron, icon, summary,
/// status, time. Tap the line to show [detail], the whole card; actions that
/// still need the user ([trailing], e.g. a reminder's Send now) stay on the
/// line. Expansion belongs to the card that owns the row. Ported from
/// matron-apple's `CompactCardRow`.
@Composable
fun CompactCardRow(
    line: CompactCardLine,
    timestamp: Instant?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    trailing: @Composable RowScope.() -> Unit = {},
    detail: @Composable () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(onClickLabel = compactCardToggleLabel(expanded), onClick = onToggle)
                    .semantics(mergeDescendants = true) { contentDescription = compactCardAccessibilityLabel(line) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = muted.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp),
                )
                Text(line.icon, style = MaterialTheme.typography.labelMedium)
                Text(
                    line.summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The outcome is what the line is for: the summary gives way.
                    modifier = Modifier.weight(1f, fill = false),
                )
                line.status?.let {
                    Text(
                        "· $it",
                        style = MaterialTheme.typography.labelMedium,
                        color = muted.copy(alpha = 0.7f),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            trailing()
            if (timestamp != null) {
                Text(
                    compactCardTimeFormatter.format(timestamp.atZone(zone)),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted.copy(alpha = 0.7f),
                )
            }
        }
        if (expanded) {
            Box(Modifier.padding(start = 16.dp)) { detail() }
        }
    }
}

/// A folded card's text detail: the full text beside a thin rule, line
/// breaks kept and selectable.
@Composable
fun CompactCardTextDetail(text: String) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)))
        SelectionContainer(Modifier.padding(start = 8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
