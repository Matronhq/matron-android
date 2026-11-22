package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.events.SystemNoticeEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/// The tap label a [SystemNoticeRow] announces: what a tap will do next.
fun systemNoticeToggleLabel(expanded: Boolean): String = if (expanded) "Hide the full text" else "Show the full text"

/// Whether an expanded row has any text to show under its line: a notice
/// with an empty body only toggles its chevron.
fun systemNoticeShowsBody(notice: SystemNoticeEvent, expanded: Boolean): Boolean = expanded && notice.body.isNotEmpty()

private val noticeTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/// The timeline row for a bridge notice about a turn it injected (a consent
/// request, a routine fire, a room message…): one quiet line — chevron, icon,
/// summary, time. Tap to show the full text, line breaks kept; lines written
/// only for the agent stay out of sight until then. The open state is the
/// row's own and survives scrolling away. Ported from matron-apple's
/// `SystemNoticeRow`.
@Composable
fun SystemNoticeRow(
    notice: SystemNoticeEvent,
    timestamp: Instant?,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = systemNoticeToggleLabel(expanded)) { expanded = !expanded }
                .semantics(mergeDescendants = true) { contentDescription = notice.summary }
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
            Text(notice.icon, style = MaterialTheme.typography.labelMedium)
            Text(
                notice.summary,
                style = MaterialTheme.typography.labelMedium,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (timestamp != null) {
                Text(
                    noticeTimeFormatter.format(timestamp.atZone(zone)),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted.copy(alpha = 0.7f),
                )
            }
        }
        if (systemNoticeShowsBody(notice, expanded)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(muted.copy(alpha = 0.3f)))
                SelectionContainer(Modifier.padding(start = 8.dp)) {
                    Text(notice.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
