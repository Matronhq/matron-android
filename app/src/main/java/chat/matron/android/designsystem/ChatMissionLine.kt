package chat.matron.android.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import chat.matron.android.models.ConversationMissionLink

/// What a chat's header says about its work: the mission it is on (its
/// current one, else the newest it is still on, else the latest it was on),
/// how many other missions it touched, and that mission's project.
data class ChatMissionLine(
    val missionID: String,
    val missionNum: Int,
    val missionTitle: String,
    /// Every other mission the conversation touched.
    val others: Int,
    val isCurrent: Boolean,
    val projectID: String? = null,
    val projectTitle: String? = null,
) {
    /// "#20 Docs search +1".
    val missionText: String get() = "#$missionNum $missionTitle" + if (others > 0) " +$others" else ""

    val missionLabel: String
        get() = "Mission $missionNum, $missionTitle" + (if (others > 0) ", and $others more" else "") + ". Opens the mission"

    val projectLabel: String? get() = projectTitle?.let { "Project: $it. Opens the project" }

    companion object {
        /// The line for [links] (`GET /conversations/:id/missions`); null
        /// when the conversation was never on a mission. [projectTitle] names
        /// the headline mission's project, when it is in one.
        fun from(links: List<ConversationMissionLink>, projectTitle: (String) -> String? = { null }): ChatMissionLine? {
            val live = links.filter { !it.isEarlier }
            val current = live.firstOrNull { it.isCurrent }
            val headline = current
                ?: live.maxByOrNull { it.joinedAt?.toEpochMilli() ?: Long.MIN_VALUE }
                ?: links.filter { it.isEarlier }.maxByOrNull { (it.endedAt ?: it.mission.closedAt)?.toEpochMilli() ?: Long.MIN_VALUE }
                ?: return null
            val mission = headline.mission
            return ChatMissionLine(
                missionID = mission.id, missionNum = mission.num, missionTitle = mission.displayName,
                others = links.count { it.mission.id != mission.id }, isCurrent = headline.isCurrent,
                projectID = mission.projectID, projectTitle = mission.projectID?.let(projectTitle),
            )
        }
    }
}

/// The mission and project under a chat's title, each tappable: the mission
/// opens its page, the project its page on the Projects tab. Small, one
/// line; the two share it, each truncating when space is short.
@Composable
fun ChatMissionLineView(line: ChatMissionLine, onOpenMission: ((String) -> Unit)?, onOpenProject: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val color = if (line.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .weight(1f, fill = false)
                .then(if (onOpenMission != null) Modifier.clickable { onOpenMission(line.missionID) } else Modifier)
                .clearAndSetSemantics { openable(line.missionLabel, onOpenMission?.let { open -> { open(line.missionID) } }, actionLabel = "Open mission") }
                .testTag("chat.missionLine.mission"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(Icons.Filled.Flag, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Text(line.missionText, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val projectID = line.projectID
        val projectTitle = line.projectTitle
        if (projectID != null && projectTitle != null) {
            Row(
                // Weighted like the mission, so a long project name shares
                // the line rather than squeezing the mission out of it.
                Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = 160.dp)
                    .then(if (onOpenProject != null) Modifier.clickable { onOpenProject(projectID) } else Modifier)
                    .clearAndSetSemantics { openable(line.projectLabel ?: projectTitle, onOpenProject?.let { open -> { open(projectID) } }) }
                    .testTag("chat.missionLine.project"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(Icons.Outlined.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                Text(projectTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
