package chat.matron.android.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import chat.matron.android.search.SearchHit

/// Shared rendering primitive for a single message search hit. Renders the
/// chat title + relative timestamp and the FTS snippet with `<mark>…</mark>`
/// spans bolded and accent-tinted. [onTap] opens the containing chat.
@Composable
fun SearchResultRow(
    hit: SearchHit,
    chatTitle: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    /// The colored `A:bc` / `A↔B:bc` tag halves, matching the chat-list
    /// rows (resolved by `SearchViewModel.hitTitle` so every call site
    /// agrees). All defaulted — a hit from an unknown room renders untagged.
    sessionShort: String? = null,
    boxLetter: String? = null,
    boxName: String? = null,
    roomBoxNames: List<String> = emptyList(),
    roomBoxShorts: List<String> = emptyList(),
) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable { onTap() }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Same composition and fallbacks as the chat-list rows'
            // titleLine (apple #154).
            val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
            Text(
                SessionTagText.titleLine(
                    title = chatTitle,
                    boxLetter = boxLetter,
                    boxName = boxName,
                    sessionShort = sessionShort,
                    roomBoxNames = roomBoxNames,
                    roomBoxShorts = roomBoxShorts,
                    darkTheme = darkTheme,
                    secondary = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            RelativeMinuteTimeView(
                source = hit.timestamp,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
        Text(
            markedSnippet(hit.snippet, MaterialTheme.colorScheme.primary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/// Splits the FTS snippet on `<mark>…</mark>` spans, bolding + tinting the
/// matched runs. Crude but effective, mirroring the Swift `Text` concatenation.
private fun markedSnippet(raw: String, highlight: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        var remaining = raw
        val open = "<mark>"
        val close = "</mark>"
        while (true) {
            val openIndex = remaining.indexOf(open)
            if (openIndex == -1) {
                append(remaining)
                break
            }
            append(remaining.substring(0, openIndex))
            remaining = remaining.substring(openIndex + open.length)
            val closeIndex = remaining.indexOf(close)
            if (closeIndex == -1) {
                append(remaining)
                break
            }
            withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Bold)) {
                append(remaining.substring(0, closeIndex))
            }
            remaining = remaining.substring(closeIndex + close.length)
        }
    }
