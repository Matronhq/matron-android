package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/// Thin labelled progress strip for attachment uploads, rendered by the
/// composer above the input while a send's upload is in flight. Exists because
/// a slow uplink turns a multi-MB screenshot into many seconds of dead air — a
/// determinate bar plus a percentage is the difference between "working" and
/// "frozen". Takes plain values (label + fraction) so this surface stays free
/// of any view-model dependency. Ported from matron-apple's
/// `UploadProgressBar`.
@Composable
fun UploadProgressBar(label: String, fraction: Double, modifier: Modifier = Modifier) {
    val clamped = fraction.coerceIn(0.0, 1.0)
    val percent = (clamped * 100).toInt()
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label, $percent percent uploaded"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinearProgressIndicator(
            progress = { clamped.toFloat() },
            modifier = Modifier.weight(1f),
        )
        Text(
            "$label $percent%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
