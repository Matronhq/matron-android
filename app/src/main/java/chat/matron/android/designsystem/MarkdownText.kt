package chat.matron.android.designsystem

import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/// Resolves the theme colours the markdown parser bakes into spans.
@Composable
fun rememberMatronMarkdownColors(): MarkdownColors {
    val scheme = MaterialTheme.colorScheme
    val matron = MatronThemeColors.current
    return remember(scheme, matron) {
        MarkdownColors(
            onSurface = scheme.onSurface,
            secondary = scheme.onSurfaceVariant,
            codeBg = matron.codeBg,
            link = matron.accent,
        )
    }
}

/// Renders Markdown [raw] as a column of styled blocks using the Matron theme.
/// The parsing lives in [MarkdownAttributed] (custom, cached); this composable
/// is the render seam — code blocks route through [CodeBlock] (copy button,
/// horizontal scroll), everything else renders as tappable text.
///
/// Link policy mirrors the Swift `MarkdownText.handle`: `http(s)` links open via
/// the platform URI handler; `matrix:`/`mxc:` links carry no click annotation
/// (rendered as accent text) so they no-op until in-app resolution lands.
@Composable
fun MarkdownText(
    raw: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val colors = rememberMatronMarkdownColors()
    val document = remember(raw, colors) { MarkdownAttributed.parse(raw, colors) }
    val uriHandler = LocalUriHandler.current
    val click: (String) -> Unit = onLinkClick ?: { url -> runCatching { uriHandler.openUri(url) } }

    Column(modifier) {
        document.blocks.forEach { block ->
            val blockModifier = Modifier.padding(top = block.spacingBefore.dp, bottom = block.spacingAfter.dp)
            val table = block.table
            if (block.kind == MarkdownBlockKind.CodeBlock) {
                CodeBlock(block.language ?: "", block.text.text, modifier = blockModifier)
            } else if (block.kind == MarkdownBlockKind.Table && table != null) {
                MarkdownTableBlock(table, modifier = blockModifier, textStyle = textStyle, onLinkClick = click)
            } else {
                ClickableText(
                    text = block.text,
                    modifier = blockModifier,
                    style = textStyle.merge(MaterialTheme.typography.bodyLarge),
                    onClick = { offset ->
                        block.text.getStringAnnotations("URL", offset, offset)
                            .firstOrNull()?.let { click(it.item) }
                    },
                )
            }
        }
    }
}
