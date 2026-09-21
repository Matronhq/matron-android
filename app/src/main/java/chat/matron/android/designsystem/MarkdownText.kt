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
/// Link policy mirrors the Swift `MarkdownText.handle` ([handleMessageLink]):
/// `matron://item/<n>` opens that tracker item through [LocalOpenTrackerItem]
/// and is swallowed when no host installed one (the scheme is not registered
/// with the OS); `http(s)` links open via [onLinkClick] or the platform URI
/// handler; `matrix:`/`mxc:` links carry no click annotation (rendered as
/// accent text) so they no-op until in-app resolution lands.
///
/// Text size: with no [textStyle] the body renders at the theme's `bodyLarge`
/// (the chat scale); an explicit [textStyle] WINS over it — see
/// [markdownBodyStyle] — so a surface that wants a different size (the item
/// thread's reading scale, a title) actually gets it. [paragraphSpacing]
/// overrides the gap after each paragraph, in dp.
@Composable
fun MarkdownText(
    raw: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle? = null,
    onLinkClick: ((String) -> Unit)? = null,
    paragraphSpacing: Float? = null,
) {
    val colors = rememberMatronMarkdownColors()
    val document = remember(raw, colors) { MarkdownAttributed.parse(raw, colors) }
    val bodyStyle = markdownBodyStyle(MaterialTheme.typography.bodyLarge, LocalTextStyle.current, textStyle)
    val uriHandler = LocalUriHandler.current
    val openItem = LocalOpenTrackerItem.current
    val external: (String) -> Unit = onLinkClick ?: { url -> runCatching { uriHandler.openUri(url) } }
    val click: (String) -> Unit = { url -> handleMessageLink(url, openItem, external) }

    Column(modifier) {
        document.blocks.forEach { block ->
            val after = if (paragraphSpacing != null && block.kind == MarkdownBlockKind.Paragraph) paragraphSpacing else block.spacingAfter
            val blockModifier = Modifier.padding(top = block.spacingBefore.dp, bottom = after.dp)
            val table = block.table
            if (block.kind == MarkdownBlockKind.CodeBlock) {
                CodeBlock(block.language ?: "", block.text.text, modifier = blockModifier)
            } else if (block.kind == MarkdownBlockKind.Table && table != null) {
                MarkdownTableBlock(table, modifier = blockModifier, textStyle = bodyStyle, onLinkClick = click)
            } else {
                ClickableText(
                    text = block.text,
                    modifier = blockModifier,
                    style = bodyStyle,
                    onClick = { offset ->
                        block.text.getStringAnnotations("URL", offset, offset)
                            .firstOrNull()?.let { click(it.item) }
                    },
                )
            }
        }
    }
}

/// The paragraph style [MarkdownText] renders with. No caller style → the
/// theme's `bodyLarge` over whatever text style is ambient (the chat scale,
/// unchanged). A caller style → it wins over `bodyLarge`, field by field.
/// Before this the merge ran the other way round, so a caller's font size
/// was silently discarded (apple #218 found the same silent no-op on the
/// Swift side); `ItemTypographyTest` pins that the item body really renders
/// larger than the chat body.
fun markdownBodyStyle(themeBody: TextStyle, ambient: TextStyle, caller: TextStyle?): TextStyle =
    if (caller == null) ambient.merge(themeBody) else themeBody.merge(caller)
