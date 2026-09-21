package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToLong

/// The Settings › Storage rows. A leaf over a plain [Model]: it reads no
/// store and knows no `AppDependencies`, so it renders with no journal at
/// all. Port of matron-apple's `StorageSettingsRows` (#212).
///
/// `model == null` is the in-flight state: `StoreDiagnostics.sizes` stats
/// two file groups and runs two `COUNT(*)`s, which on a large mirror is
/// visibly slow, so the section shows a spinner rather than zeros.
object StorageSettingsRows {
    data class Model(
        val journalBytes: Long,
        val searchBytes: Long,
        val events: Int,
        val conversations: Int,
        /// Pre-formatted by the caller from `LaunchTimeline.summary(...)`.
        val launchText: String,
        /// Pre-formatted by the caller from `StoreDiagnostics.lastMaintenanceText`.
        val maintenanceText: String,
    )

    /// Below 1 KB: the raw byte count ("0 B", "500 B"). At or above 1 KB:
    /// the value in the largest whole unit that keeps the leading digit
    /// non-zero (decimal, 1000-based, like Apple's `ByteCountFormatter`),
    /// rounded to one decimal place with the trailing ".0" dropped when the
    /// rounded value is whole — so `440_000_000` renders "440 MB", not
    /// "440.0 MB". The decimal point is pinned to "." regardless of locale:
    /// these numbers are read back in bug reports next to [countsText]'s
    /// "," grouping separator, and a locale comma decimal would make the
    /// two ambiguous. When rounding would carry a value to 1000 of its unit
    /// (`999_999_999` → "1000 MB"), the unit is bumped one tier first.
    fun byteText(bytes: Long): String {
        if (bytes < 1_000) return "$bytes B"
        var divisor: Double
        var suffix: String
        when {
            bytes >= 1_000_000_000 -> { divisor = 1e9; suffix = "GB" }
            bytes >= 1_000_000 -> { divisor = 1e6; suffix = "MB" }
            else -> { divisor = 1e3; suffix = "KB" }
        }
        fun roundedToOneDecimal(value: Double): Double = (value * 10).roundToLong() / 10.0
        if (suffix != "GB" && roundedToOneDecimal(bytes / divisor) >= 1000) {
            divisor *= 1000
            suffix = if (suffix == "KB") "MB" else "GB"
        }
        val rounded = roundedToOneDecimal(bytes / divisor)
        val text = if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
        return "$text $suffix"
    }

    /// `457,102 / 6,214`. The separator is pinned: these are diagnostic
    /// numbers read back to us in bug reports, and a grouping separator that
    /// changes with the device's region makes them ambiguous.
    fun countsText(events: Int, conversations: Int): String =
        "${String.format(Locale.US, "%,d", events)} / ${String.format(Locale.US, "%,d", conversations)}"
}

@Composable
fun StorageSettingsRows(model: StorageSettingsRows.Model?) {
    if (model == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Journal store", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        return
    }
    StorageRow("Journal store", StorageSettingsRows.byteText(model.journalBytes))
    StorageRow("Search index", StorageSettingsRows.byteText(model.searchBytes))
    StorageRow("Events / Conversations", StorageSettingsRows.countsText(model.events, model.conversations))
    StorageRow("This launch", model.launchText)
    StorageRow("Last maintenance", model.maintenanceText)
}

@Composable
private fun StorageRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
