package chat.matron.android.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// A fence whose info string has a later word `collapsed` starts folded
/// behind a one-line header; the first word stays the language.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CodeBlockCollapsedTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val json = "{\n  \"command\": \"ls -la\"\n}"

    @Test
    fun fenceInfoSplitsTheLanguageFromTheFoldWord() {
        assertEquals(FenceInfo("json", true), FenceInfo.parse("json collapsed"))
        assertEquals(FenceInfo("json", true), FenceInfo.parse("  json\tcollapsed  "))
        assertEquals(FenceInfo("json", false), FenceInfo.parse("json"))
        assertEquals(FenceInfo("", false), FenceInfo.parse(""))
        // A lone `collapsed` is the language, not the fold word.
        assertEquals(FenceInfo("collapsed", false), FenceInfo.parse("collapsed"))
        assertEquals(FenceInfo("json", false), FenceInfo.parse("json collapsedish"))
    }

    @Test
    fun labelsNameTheLanguageOrCode() {
        assertEquals("Show json", FenceInfo.parse("json collapsed").toggleLabel(expanded = false))
        assertEquals("Hide json", FenceInfo.parse("json collapsed").toggleLabel(expanded = true))
        assertEquals("Show code", FenceInfo("", true).toggleLabel(expanded = false))
        assertEquals("python", FenceInfo.parse("python").label)
    }

    @Test
    fun collapsedFenceStartsFoldedAndOpensOnTap() {
        compose.setContent { MaterialTheme { MarkdownText("Run a command.\n\n```json collapsed\n$json\n```") } }
        compose.onNodeWithText("Show json").assertIsDisplayed()
        compose.onNodeWithText(json).assertDoesNotExist()

        compose.onNodeWithText("Show json").performClick()
        compose.onNodeWithText(json).assertIsDisplayed()
        compose.onNodeWithText("Hide json").performClick()
        compose.onNodeWithText(json).assertDoesNotExist()
        compose.onNodeWithText("Show json").assertIsDisplayed()
    }

    @Test
    fun plainFenceIsUnchanged() {
        compose.setContent { MaterialTheme { MarkdownText("```json\n$json\n```") } }
        compose.onNodeWithText(json).assertIsDisplayed()
        compose.onNodeWithText("json").assertIsDisplayed()
        compose.onNodeWithText("Show json").assertDoesNotExist()
    }

    @Test
    fun copyWhileFoldedCopiesTheWholeCode() {
        var copied: String? = null
        val clipboard = object : ClipboardManager {
            override fun getText() = null
            override fun setText(annotatedString: androidx.compose.ui.text.AnnotatedString) {
                copied = annotatedString.text
            }
        }
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                MaterialTheme { CodeBlock("json collapsed", json) }
            }
        }
        compose.onNodeWithContentDescription("Copy code").performClick()
        assertEquals(json, copied)
        compose.onNodeWithText(json).assertDoesNotExist()
        assertTrue(FenceInfo.parse("json collapsed").collapsed)
        assertFalse(FenceInfo.parse("json").collapsed)
    }
}
