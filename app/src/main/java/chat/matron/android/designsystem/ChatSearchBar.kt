package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/// In-conversation search bar (WhatsApp-style). Renders the query field, the
/// "n of m" position, and older/newer chevrons; all state lives in
/// `ChatViewModel.chatSearch` — this is a dumb projection over plain values +
/// callbacks so the design system stays ignorant of view models. Port of
/// matron-apple's `ChatSearchBar` (#172).
///
/// Chevron semantics follow the transcript, not the list: ∧ steps OLDER (up
/// into history), ∨ steps back toward the newest match. Matches are ordered
/// newest-first upstream, so "older" is the higher index.
@Composable
fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    /// Total matches for the submitted query.
    matchCount: Int,
    /// 0-based index of the focused match in the newest-first order.
    matchIndex: Int,
    onSubmit: () -> Unit,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            // Deliberately NOT auto-focused: the bar usually appears mid-
            // jump-to-match, and popping the keyboard would cover the very
            // message the jump landed on. Tap the field to edit.
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search in chat") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            // Fixed at the widest realistic label so stepping through matches
            // doesn't wobble the chevrons.
            Text(
                chatSearchPositionLabel(matchCount, matchIndex),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 64.dp),
            )
            IconButton(onClick = onOlder, enabled = matchIndex + 1 < matchCount) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Older match")
            }
            IconButton(onClick = onNewer, enabled = matchIndex > 0) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Newer match")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/// "3 of 12", or "No matches" for an empty result. Pure so it's unit-testable.
internal fun chatSearchPositionLabel(matchCount: Int, matchIndex: Int): String =
    if (matchCount == 0) "No matches" else "${matchIndex + 1} of $matchCount"
