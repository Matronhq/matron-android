package chat.matron.android.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Which URLs count as a shareable tracker link on the session's journal.
/// Getting this wrong either way costs: too loose and an agent-written URL
/// is swallowed by in-app navigation; too strict and a real link leaves the
/// app for the browser.
class TrackerLinkURLTest {
    private val base = "https://journal.example.com"

    @Test
    fun readsTheUserAndTheNumber() {
        assertEquals(TrackerLinkURL("alice", 412), TrackerLinkURL.parse("https://journal.example.com/u/alice/412", base))
        assertEquals(TrackerLinkURL("alice", 1), TrackerLinkURL.parse("https://journal.example.com/u/alice/1", base))
    }

    @Test
    fun schemeAndHostCompareCaseInsensitivelyAndTheDefaultPortMatches() {
        assertEquals(TrackerLinkURL("alice", 7), TrackerLinkURL.parse("HTTPS://Journal.Example.COM/u/alice/7", base))
        assertEquals(TrackerLinkURL("alice", 7), TrackerLinkURL.parse("https://journal.example.com:443/u/alice/7", base))
        assertEquals(TrackerLinkURL("alice", 7), TrackerLinkURL.parse("https://journal.example.com/u/alice/7", "https://journal.example.com:443"))
    }

    @Test
    fun aQueryOrFragmentDoesNotChangeWhatTheLinkNames() {
        assertEquals(TrackerLinkURL("alice", 7), TrackerLinkURL.parse("https://journal.example.com/u/alice/7?from=share", base))
        assertEquals(TrackerLinkURL("alice", 7), TrackerLinkURL.parse("https://journal.example.com/u/alice/7#c12", base))
    }

    @Test
    fun theUserIsPercentDecodedAndAPlusStaysAPlus() {
        assertEquals(TrackerLinkURL("bob smith", 3), TrackerLinkURL.parse("https://journal.example.com/u/bob%20smith/3", base))
        assertEquals(TrackerLinkURL("bob+ci", 3), TrackerLinkURL.parse("https://journal.example.com/u/bob+ci/3", base))
    }

    @Test
    fun aJournalUnderAPathKeepsItsPathInTheLink() {
        val prefixed = "https://example.com/journal"
        assertEquals(TrackerLinkURL("alice", 5), TrackerLinkURL.parse("https://example.com/journal/u/alice/5", prefixed))
        assertEquals(TrackerLinkURL("alice", 5), TrackerLinkURL.parse("https://example.com/journal/u/alice/5", "$prefixed/"))
        assertNull(TrackerLinkURL.parse("https://example.com/u/alice/5", prefixed))
        assertNull(TrackerLinkURL.parse("https://example.com/other/u/alice/5", prefixed))
    }

    @Test
    fun aLocalDevJournalOverPlainHttpWorks() {
        assertEquals(TrackerLinkURL("alice", 2), TrackerLinkURL.parse("http://localhost:6167/u/alice/2", "http://localhost:6167"))
        assertNull(TrackerLinkURL.parse("http://localhost:6168/u/alice/2", "http://localhost:6167"))
    }

    @Test
    fun anotherServerIsNotThisJournal() {
        for (url in listOf(
            "https://other.example.com/u/alice/412",
            "https://journal.example.com.evil.example/u/alice/412",
            "http://journal.example.com/u/alice/412",
            "https://journal.example.com:8443/u/alice/412",
            "https://alice@journal.example.com/u/alice/412",
        )) {
            assertNull(url, TrackerLinkURL.parse(url, base))
        }
    }

    @Test
    fun onlyTheExactShapeIsALink() {
        for (url in listOf(
            "https://journal.example.com/u/alice/412/",
            "https://journal.example.com/u/alice/412/comments",
            "https://journal.example.com/u/alice",
            "https://journal.example.com/u/alice/",
            "https://journal.example.com/u//412",
            "https://journal.example.com/u/alice/0",
            "https://journal.example.com/u/alice/-4",
            "https://journal.example.com/u/alice/+4",
            "https://journal.example.com/u/alice/4a",
            "https://journal.example.com/u/alice/%34",
            "https://journal.example.com/u/alice/99999999999",
            "https://journal.example.com/u/a%2Fb/4",
            "https://journal.example.com/u/${"a".repeat(65)}/4",
            "https://journal.example.com/app/u/alice/4",
            "https://journal.example.com/U/alice/4",
            "matron://item/4",
            "mailto:alice@example.com",
            "not a url",
        )) {
            assertNull(url, TrackerLinkURL.parse(url, base))
        }
    }
}
