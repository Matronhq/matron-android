package chat.matron.android.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import chat.matron.android.models.MilestoneKind
import chat.matron.android.models.MissionState

/// Symbols, labels and tints for missions and milestones — the single place
/// the two vocabularies are named, so a row, a card and a page can never
/// disagree. Sibling of [ItemGlyph]. Ported from matron-apple's
/// `MissionGlyph` (`flag.checkered` → the Material checkered flag,
/// `SportsScore`).
object MissionGlyph {
    /// The bare mission glyph, for call sites that only know a mission
    /// exists — not its open/closed state.
    fun icon(): ImageVector = icon(MissionState.OPEN)

    fun icon(state: MissionState): ImageVector = when (state) {
        MissionState.OPEN -> Icons.Outlined.SportsScore
        MissionState.CLOSED -> Icons.Filled.SportsScore
    }

    fun label(state: MissionState): String = when (state) {
        MissionState.OPEN -> "Open"
        MissionState.CLOSED -> "Closed"
    }

    @Composable
    fun tint(state: MissionState): Color = when (state) {
        MissionState.OPEN -> MaterialTheme.colorScheme.primary
        MissionState.CLOSED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    /// `user_input` gets the person glyph — the spec's "kind glyph (person
    /// for `user_input`)" — because finding Dan's own inputs is the point.
    fun icon(kind: MilestoneKind): ImageVector = when (kind) {
        MilestoneKind.USER_INPUT -> Icons.Filled.Person
        MilestoneKind.PROGRESS -> Icons.Filled.Circle
    }

    fun label(kind: MilestoneKind): String = when (kind) {
        MilestoneKind.USER_INPUT -> "Your input"
        MilestoneKind.PROGRESS -> "Progress"
    }

    @Composable
    fun tint(kind: MilestoneKind): Color = when (kind) {
        MilestoneKind.USER_INPUT -> MatronOrange
        MilestoneKind.PROGRESS -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
