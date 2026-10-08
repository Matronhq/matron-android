package chat.matron.android.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemResolution

/// The tracker's per-kind glyph, tint and copy — one place so the list row,
/// the detail header and the pending rows agree. Ported from matron-apple's
/// `ItemGlyph` (`questionmark.circle.fill` / `checklist` / `scalemass.fill`
/// → their Material equivalents). A notice — something to read, not to
/// decide — gets an outlined eye in the secondary text colour, so it reads
/// lighter than the questions beside it in For you.
object ItemGlyph {
    fun icon(kind: ItemKind): ImageVector = when (kind) {
        ItemKind.QUESTION -> Icons.Filled.Help
        ItemKind.TASK -> Icons.Filled.Checklist
        ItemKind.DECISION -> Icons.Filled.Balance
        ItemKind.NOTICE -> Icons.Outlined.Visibility
    }

    /// Orange for a question (the "needs you" colour), the accent for a
    /// task, purple for a decision — the Swift `.orange / .accentColor / .purple`.
    @Composable
    fun tint(kind: ItemKind): Color = when (kind) {
        ItemKind.QUESTION -> MatronOrange
        ItemKind.TASK -> MaterialTheme.colorScheme.primary
        ItemKind.DECISION -> ItemDecisionPurple
        ItemKind.NOTICE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    fun label(kind: ItemKind): String = when (kind) {
        ItemKind.QUESTION -> "Question"
        ItemKind.TASK -> "Task"
        ItemKind.DECISION -> "Decision"
        ItemKind.NOTICE -> "Notice"
    }

    fun label(resolution: ItemResolution): String = when (resolution) {
        ItemResolution.DONE -> "Done"
        ItemResolution.ANSWERED -> "Answered"
        ItemResolution.DECIDED -> "Decided"
        ItemResolution.REVERSED -> "Reversed"
        ItemResolution.CANCELLED -> "Cancelled"
    }
}

/// iOS system purple, fixed like [MatronOrange] for the same reason.
val ItemDecisionPurple: Color = Color(175, 82, 222)
