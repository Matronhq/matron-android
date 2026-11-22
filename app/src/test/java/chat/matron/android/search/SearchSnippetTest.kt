package chat.matron.android.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSnippetTest {
    private fun snippet(body: String, typed: String) = SearchSnippet.make(body, SearchTerms.parse(typed)!!)

    @Test
    fun parsesTheWordStillBeingTyped() {
        assertEquals(SearchTerms(listOf("release", "no"), lastTermIsPrefix = true), SearchTerms.parse("release no"))
        assertEquals(SearchTerms(listOf("release"), lastTermIsPrefix = false), SearchTerms.parse("release "))
        assertEquals(SearchTerms(listOf("release"), lastTermIsPrefix = false), SearchTerms.parse("release ++"))
        assertNull(SearchTerms.parse("  ++ "))
    }

    @Test
    fun marksThePhraseAsOneHighlight() {
        assertEquals(
            "Could we keep the <mark>release notes</mark> short",
            snippet("Could we keep the\nrelease   notes short", "release notes "),
        )
    }

    @Test
    fun skipsMidWordMatchesAndPrefixMatchesTheLastWord() {
        assertEquals(
            "sometimes the <mark>tim</mark>e is <mark>tim</mark>ely",
            snippet("sometimes the time is timely", "tim"),
        )
        assertEquals("sometimes the <mark>time</mark> is timely", snippet("sometimes the time is timely", "time "))
    }

    @Test
    fun opensAWindowOnTheFirstMatch() {
        val body = "word ".repeat(40) + "the needle is here" + " tail".repeat(60)
        val s = snippet(body, "needle ")
        assertTrue(s, s.startsWith("…"))
        assertTrue(s, s.endsWith("…"))
        assertTrue(s, s.contains("<mark>needle</mark>"))
    }

    @Test
    fun dropsMarkupTheMessageCarries() {
        assertEquals("a <mark>tag</mark> here", snippet("a <mark>tag</mark> here", "tag "))
    }
}
