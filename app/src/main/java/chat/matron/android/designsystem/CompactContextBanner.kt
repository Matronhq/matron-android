package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.models.SessionStatus

/// Absolute context size (in tokens) past which the compact-context header
/// appears. Absolute, not a fraction of the model's window: the concern is
/// cost/latency/recall at large sizes, which a 1M-window model shares.
const val COMPACT_HEADER_TOKEN_THRESHOLD = 200_000

/// Whether the compact-context header should show for [context]. Null (no status
/// yet) and exactly-at-threshold do not show; strictly above does.
fun shouldShowCompactHeader(context: SessionStatus.Context?): Boolean =
    context != null && context.tokens > COMPACT_HEADER_TOKEN_THRESHOLD

/// Visible copy — exposed for tests so the wording stays pinned (and in step
/// with the Apple apps' `CompactContextBanner`). The token count and the
/// action verb are separate pieces: the title may truncate on narrow phones,
/// the trailing [COMPACT_BANNER_ACTION] verb never does.
fun compactBannerTitle(tokens: Int): String =
    "Large conversation (${UsageMetersFormat.compactTokens(tokens)})"

/// Trailing action label, rendered next to the compress glyph.
const val COMPACT_BANNER_ACTION = "Compact"

/// Tappable strip pinned at the top of a large conversation nudging the user to
/// compact it. Always renders the coloured strip for [tokens]; the show/hide
/// decision belongs to the caller (see [shouldShowCompactHeader]). Mirrors
/// [ConnectionStatusBanner]'s offline state — opaque red with white content —
/// so the two attention strips share one vocabulary (matches the Apple apps).
/// Tapping calls [onCompact], which sends a bare `/compact`.
@Composable
fun CompactContextBanner(
    tokens: Int,
    onCompact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spoken = "Large conversation, ${UsageMetersFormat.spokenTokens(tokens)} tokens, tap to compact"
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onCompact)
            .background(MatronRed.copy(alpha = 0.9f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            // Merge the children so TalkBack announces the single spoken label
            // below, not the inner Text's abbreviated visible token string.
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            compactBannerTitle(tokens),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // weight() makes the title the flexible child: it yields to the
            // trailing glyph + verb, which keep intrinsic size, so a narrow
            // phone truncates the token count, never the action.
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        // Same glyph as elsewhere for the compact action, so the trailing
        // pair reads as the button half of the strip.
        Icon(
            Icons.Filled.Compress,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            COMPACT_BANNER_ACTION,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 1,
        )
    }
}
