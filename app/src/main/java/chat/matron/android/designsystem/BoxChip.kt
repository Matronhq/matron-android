package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/// The chip must never grow a row: it renders on the title line, capped to one
/// line (matron-apple pins the same invariant in `BoxChipTests`).
const val BOX_CHIP_MAX_LINES = 1

/**
 * The agent box that owns a conversation, as a small capsule beside the
 * title. Shown only when the user has two or more boxes — the decision is
 * made upstream in `JournalChatService`, so this composable just renders
 * whatever name it is handed. Ports MatronShared/Sources/DesignSystem/
 * BoxChip.swift.
 *
 * Single-line and truncating by construction: chat rows keep a fixed height
 * and a wrapping chip would break it.
 */
@Composable
fun BoxChip(name: String, modifier: Modifier = Modifier) {
    Text(
        name,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        maxLines = BOX_CHIP_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                RoundedCornerShape(percent = 50),
            )
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .semantics { contentDescription = "Agent box $name" },
    )
}
