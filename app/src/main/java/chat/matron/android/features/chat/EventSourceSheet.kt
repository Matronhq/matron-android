package chat.matron.android.features.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import chat.matron.android.chat.TimelineItem
import chat.matron.android.chat.prettyJSON

/**
 * "View source" sheet content. Ports Features/Chat/Rendering/EventSourceSheet.swift:
 * the row's reconstructed DTO as pretty-printed, selectable JSON.
 */
@Composable
fun EventSourceSheet(item: TimelineItem) {
    SelectionContainer {
        Text(
            text = item.prettyJSON(),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        )
    }
}
