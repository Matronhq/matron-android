package chat.matron.android.designsystem

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import chat.matron.android.models.MatronDebug

/// What the user sees after tapping a link the app won't open
/// ([MatronItemLink.Action.Blocked]): a short dialog saying so, with the
/// link itself and a way to copy it. Nothing is opened from here.
///
/// One prompt for every surface that opens links from text (markdown bodies,
/// tables, item link chips, the media browser's link list), so they all say
/// the same thing.
object BlockedLinkPrompt {
    const val TITLE = "This link can't be opened from Matron"
    const val COPY = "Copy Link"
    const val CANCEL = "Cancel"

    /// Longest stretch of the link shown in the dialog. A `data:` link can
    /// run to megabytes; Copy Link still copies all of it.
    const val MAX_SHOWN_LENGTH = 300

    /// The dialog's message: the link as written, shortened if it is long.
    fun message(url: String): String =
        if (url.length <= MAX_SHOWN_LENGTH) url else url.take(MAX_SHOWN_LENGTH) + "…"
}

/// Shows [BlockedLinkPrompt] for a link. Installed once at the app root by
/// [BlockedLinkHost]; with no host installed a blocked link is logged and
/// dropped — still never handed to the OS.
val LocalPresentBlockedLink = staticCompositionLocalOf<(String) -> Unit> {
    { url -> MatronDebug.breadcrumb("Blocked link with no prompt host: ${MatronItemLink.redactedForLog(url)}") }
}

/// Installs the blocked-link prompt for everything in [content].
@Composable
fun BlockedLinkHost(content: @Composable () -> Unit) {
    var blocked by rememberSaveable { mutableStateOf<String?>(null) }
    val present: (String) -> Unit = remember { { url -> blocked = url } }
    CompositionLocalProvider(LocalPresentBlockedLink provides present) { content() }
    blocked?.let { url -> BlockedLinkDialog(url, onDismiss = { blocked = null }) }
}

/// The dialog itself, apart from its host so screen tests can draw it.
@Composable
fun BlockedLinkDialog(url: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(BlockedLinkPrompt.TITLE) },
        text = { Text(BlockedLinkPrompt.message(url)) },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(url))
                onDismiss()
            }) { Text(BlockedLinkPrompt.COPY) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(BlockedLinkPrompt.CANCEL) } },
    )
}
