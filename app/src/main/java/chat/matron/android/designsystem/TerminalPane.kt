package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/// Terminal-style output pane shared by [LiveOutputCard] (viewer WebSocket) and
/// [ToolStreamCard] (journal tool_stream overlay): a fixed dark palette in both
/// app themes so ANSI colours read the same everywhere, with sticky-tail
/// behaviour — pinned to the newest output. Collapsed is short; [expanded]
/// grows to a bounded height and scrolls.
@Composable
fun TerminalPane(output: AnnotatedString, expanded: Boolean, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    // Sticky tail: follow the newest output as it streams in.
    LaunchedEffect(output) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    SelectionContainer {
        Text(
            output,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = TerminalStyle.foreground,
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 7.dp, bottomEnd = 7.dp))
                .background(TerminalStyle.background)
                .heightIn(max = if (expanded) 600.dp else 76.dp)
                .verticalScroll(scrollState)
                .padding(8.dp),
        )
    }
}
