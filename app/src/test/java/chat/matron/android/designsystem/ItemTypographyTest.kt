package chat.matron.android.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// Pins that the item thread's body really renders a step above the chat
/// body — by resolving the style `MarkdownText` will draw with, not by
/// reading a constant back. The Swift side (apple #218) found its size bump
/// was a silent no-op because the renderer overrode the caller's size; this
/// renderer had the same inversion (`textStyle.merge(bodyLarge)`), which
/// `markdownBodyStyle` now runs the right way round. The Android analogue of
/// `ItemTypographyRenderTests`.
class ItemTypographyTest {
    private val bodyLarge = Typography().bodyLarge
    private val ambient = TextStyle.Default

    @Test
    fun itemBodyIsLargerThanTheChatBody() {
        val chat = markdownBodyStyle(bodyLarge, ambient, caller = null)
        val item = markdownBodyStyle(bodyLarge, ambient, caller = itemBodyStyle(bodyLarge))
        assertEquals("the chat body is untouched — the system size by decision", bodyLarge.fontSize, chat.fontSize)
        assertTrue("item ${item.fontSize} should be larger than chat ${chat.fontSize}", item.fontSize.value > chat.fontSize.value)
        assertEquals(bodyLarge.fontSize.value * ItemTypography.bodyScale, item.fontSize.value, 0.01f)
        assertTrue("more leading too", item.lineHeight.value > chat.lineHeight.value * ItemTypography.bodyScale)
    }

    @Test
    fun aCallerStyleWinsOverTheThemeBody() {
        val title = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        val resolved = markdownBodyStyle(bodyLarge, ambient, caller = title)
        assertEquals(24.sp, resolved.fontSize)
        assertEquals(FontWeight.SemiBold, resolved.fontWeight)
        assertEquals("fields the caller leaves unset still come from the theme body", bodyLarge.letterSpacing, resolved.letterSpacing)
    }

    @Test
    fun noCallerStyleKeepsTheThemeBodyOverTheAmbientStyle() {
        // Inside a button the ambient style is labelLarge; a markdown body
        // rendered there must still be a body, as before.
        val resolved = markdownBodyStyle(bodyLarge, TextStyle(fontSize = 11.sp), caller = null)
        assertEquals(bodyLarge.fontSize, resolved.fontSize)
    }

    @Test
    fun itemBodyStyleFallsBackWhenTheBaseHasNoSize() {
        val style = itemBodyStyle(TextStyle.Default)
        assertEquals(19.sp, style.fontSize)
        assertTrue(style.lineHeight.value > style.fontSize.value)
    }
}
