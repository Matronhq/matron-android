package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionState

/// The meta line's text after `#num ·` when the mission has no last
/// milestone: the number must survive every state — a closed mission with
/// no milestone still needs its `#num` (apple #213).
fun missionRowEmptyMeta(mission: Mission): String =
    if (mission.state == MissionState.OPEN) "No milestones yet" else MissionGlyph.label(MissionState.CLOSED)

/// The row's TalkBack announcement: "Mission 61, Title" plus a grammatical
/// needs-you phrase — "1 item needs you" / "N items need you" (apple #216).
fun missionRowAccessibilityLabel(mission: Mission): String =
    "Mission ${mission.num}, ${mission.title}" +
        when {
            mission.needsYou <= 0 -> ""
            mission.needsYou == 1 -> ", 1 item needs you"
            else -> ", ${mission.needsYou} items need you"
        }

/// One row in the Missions list (apple #209, laid out per #213): the state
/// glyph, the title on its own line (2 lines max) with the needs-you badge
/// beside it, then a meta line that leads with `#num ·` and the last
/// milestone (kind glyph + title, one line) with its relative age pinned
/// trailing and never collapsed; a closed mission's summary as a third
/// line. Ported from matron-apple's `MissionRowView`.
@Composable
fun MissionRowView(mission: Mission, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = missionRowAccessibilityLabel(mission) },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            MissionGlyph.icon(mission.state),
            contentDescription = null,
            tint = MissionGlyph.tint(mission.state),
            modifier = Modifier.padding(top = 2.dp).size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(
                    mission.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                NeedsYouBadge(count = mission.needsYou, modifier = Modifier.padding(top = 2.dp))
            }
            val secondary = MaterialTheme.colorScheme.onSurfaceVariant
            val tertiary = secondary.copy(alpha = 0.75f)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("#${mission.num}", style = MaterialTheme.typography.labelSmall, color = secondary)
                Text("·", style = MaterialTheme.typography.labelSmall, color = tertiary)
                val last = mission.lastMilestone
                if (last != null) {
                    Icon(
                        MissionGlyph.icon(last.kind), contentDescription = null,
                        tint = MissionGlyph.tint(last.kind), modifier = Modifier.size(12.dp),
                    )
                    Text(
                        last.title, style = MaterialTheme.typography.bodyMedium, color = secondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    RelativeMinuteTimeView(
                        last.createdAt, style = MaterialTheme.typography.labelSmall.copy(color = tertiary),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                } else {
                    Text(missionRowEmptyMeta(mission), style = MaterialTheme.typography.bodyMedium, color = tertiary)
                }
            }
            val summary = mission.closeSummary
            if (mission.state == MissionState.CLOSED && !summary.isNullOrEmpty()) {
                Text(
                    summary.replace("\n", " "), style = MaterialTheme.typography.labelSmall, color = tertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
