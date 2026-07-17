package chat.matron.android.events

import chat.matron.android.journal.parseJsonObjectOrNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffEventTest {
    private fun payload(json: String): JsonObject = parseJsonObjectOrNull(json)!!

    @Test
    fun parseRichPayload() {
        val evt = DiffEvent.parse(payload("""{"file_path":"/Users/dan/Dev/x/Sources/A.swift","display_path":"Sources/A.swift","viewer_url":"https://viewer.example/view?token=abc","tool":"Edit","label":"code-reviewer","diff":"@@ -1,1 +1,1 @@\n-a\n+b","added":1,"removed":1,"truncated":true,"new_file":false}"""))
        assertEquals("/Users/dan/Dev/x/Sources/A.swift", evt.filePath)
        assertEquals("Sources/A.swift", evt.displayPath)
        assertEquals("https://viewer.example/view?token=abc", evt.viewerURL)
        assertEquals("Edit", evt.tool)
        assertEquals("code-reviewer", evt.label)
        assertEquals("@@ -1,1 +1,1 @@\n-a\n+b", evt.diff)
        assertEquals(1, evt.added)
        assertEquals(1, evt.removed)
        assertTrue(evt.truncated)
        assertFalse(evt.newFile)
        assertEquals("A.swift", evt.filename)
    }

    @Test
    fun parseBareLegacyShape() {
        val evt = DiffEvent.parse(payload("""{"diff":"+added line"}"""))
        assertEquals("+added line", evt.diff)
        assertNull(evt.filePath)
        assertNull(evt.viewerURL)
        assertNull(evt.added)
        assertFalse(evt.truncated)
        assertNull(evt.filename)
    }

    @Test
    fun parseSnippetFallbackAndEmpty() {
        assertEquals("+x", DiffEvent.parse(payload("""{"snippet":"+x"}""")).diff)
        assertEquals("", DiffEvent.parse(payload("""{}""")).diff)
    }

    @Test
    fun filenameFallsBackToFilePath() {
        val evt = DiffEvent.parse(payload("""{"diff":"x","file_path":"/a/b/c.txt"}"""))
        assertEquals("c.txt", evt.filename)
    }

    @Test
    fun nonStringViewerURLIgnored() {
        val evt = DiffEvent.parse(payload("""{"diff":"x","viewer_url":42}"""))
        assertNull(evt.viewerURL)
    }
}
