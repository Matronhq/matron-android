package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/// Design-system primitive for a non-image file attachment in the chat
/// timeline. Renders a generic doc icon, the [filename], and (optionally) the
/// formatted [sizeBytes]. Tapping the chip invokes [onTap] (e.g. to
/// share/export). An optional [caption] renders underneath, outside the chip —
/// it's the message, not a handle for opening the file.
@Composable
fun AttachmentFile(
    filename: String,
    sizeBytes: Long?,
    modifier: Modifier = Modifier,
    caption: String? = null,
    onTap: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MatronThemeColors.current.codeBg)
                .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    filename,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sizeBytes != null) {
                    Text(
                        formatFileSize(sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (caption != null && caption.isNotEmpty()) {
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/// Decimal (1000-based) size string — "12 bytes", "3.4 kB", "1.2 MB" — matching
/// the tray's `StagedAttachment.formattedSize` so a staged file and a sent one
/// read the same.
private fun formatFileSize(bytes: Long): String {
    if (bytes < 1000) return "$bytes bytes"
    val units = listOf("kB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1000.0
    var unit = 0
    while (value >= 1000.0 && unit < units.lastIndex) {
        value /= 1000.0
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}
