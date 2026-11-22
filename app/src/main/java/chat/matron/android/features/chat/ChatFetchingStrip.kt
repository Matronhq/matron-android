package chat.matron.android.features.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/// Over the transcript while an opened chat's newest messages are being
/// fetched (the For you only sync scope keeps only some chats on the
/// device). The rows already stored stay on screen underneath.
@Composable
internal fun ChatFetchingStrip(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().testTag(CHAT_FETCHING_STRIP_TAG)) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            CHAT_FETCHING_TEXT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

internal const val CHAT_FETCHING_STRIP_TAG = "chat.fetchingLatest"
internal const val CHAT_FETCHING_TEXT = "Loading the latest messages…"
