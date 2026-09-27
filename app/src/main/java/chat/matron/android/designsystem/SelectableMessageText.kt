package chat.matron.android.designsystem

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/// Selectable chat message body. On the Mac this needed a bespoke NSTextView so
/// a drag could span markdown blocks; Compose's [SelectionContainer] gives
/// cross-block selection natively, so this is a thin wrapper over [MarkdownText]
/// — the whole message selects as one, spanning paragraphs, lists, and code.
@Composable
fun SelectableMessageText(
    raw: String,
    modifier: Modifier = Modifier,
    /// `null` renders at the chat scale; an explicit style wins (see
    /// [markdownBodyStyle]).
    textStyle: TextStyle? = null,
    onLinkClick: ((String) -> Unit)? = null,
) {
    SelectionContainer(modifier) {
        MarkdownText(raw = raw, textStyle = textStyle, onLinkClick = onLinkClick)
    }
}
