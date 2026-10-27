package chat.matron.android.journal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// What a tracker link points at, for the title shown beside it: the title
/// rule (the same one the bridge refuses agents' wrong links with and the web
/// client flags them with), the link-text reading, the link targets and the
/// per-session cache.
class TrackerLinkTitlesTest {

    // MARK: - titleMatches

    @Test
    fun titleMatchesAcceptsTheTitleAPrefixAnAbbreviationOrAParaphrase() {
        assertTrue(TrackerLinkTitleMatch.titleMatches("Merge: printer XML export", "Merge: printer XML export"))
        assertTrue(TrackerLinkTitleMatch.titleMatches("Merge: printer XML…", "Merge: printer XML export for the run"))
        assertTrue(TrackerLinkTitleMatch.titleMatches("merge printer xml exp...", "Merge: printer XML export"))
        assertTrue(TrackerLinkTitleMatch.titleMatches("late-orders item", "Late orders shipped after the cut-off: options"))
        assertTrue(TrackerLinkTitleMatch.titleMatches("CAFÉ menu", "Cafe menu update"))
    }

    @Test
    fun titleMatchesRefusesATitleAboutSomethingElseAndAnEmptyOne() {
        assertFalse(TrackerLinkTitleMatch.titleMatches("the new merge question", "Late orders shipped after the cut-off"))
        assertFalse(TrackerLinkTitleMatch.titleMatches("Printer XML", "Holiday rota for December"))
        assertFalse(TrackerLinkTitleMatch.titleMatches("here", "Merge: printer XML export"))
        assertFalse(TrackerLinkTitleMatch.titleMatches("", "Merge"))
    }

    // MARK: - writtenLink

    @Test
    fun writtenLinkSplitsTheNumberFromTheTitle() {
        assertEquals(WrittenLink(12, "Ship it"), TrackerLinkTitleMatch.writtenLink("#12 Ship it"))
        assertEquals(WrittenLink(7, "Late orders"), TrackerLinkTitleMatch.writtenLink("item #7 — Late orders"))
        assertEquals(WrittenLink(9, "Ship the thing"), TrackerLinkTitleMatch.writtenLink("**#9** \"Ship the thing\""))
        assertEquals(WrittenLink(4, ""), TrackerLinkTitleMatch.writtenLink("#4"))
        assertEquals(WrittenLink(null, "the merge question"), TrackerLinkTitleMatch.writtenLink("the merge question"))
    }

    // MARK: - targets

    private fun titles(lookup: suspend (String, Int) -> NumberLookup = { _, _ -> error("unused") }, now: () -> Long = { 0L }) =
        TrackerLinkTitles(journalBaseURL = "https://chat.example", sessionUser = "alice", lookup = lookup, now = now)

    @Test
    fun targetReadsOwnJournalLinksAndTheOldInAppForms() {
        val t = titles()
        assertEquals(TrackerLinkTarget("alice", 12), t.target("https://chat.example/u/alice/12"))
        assertEquals(TrackerLinkTarget("bob", 3), t.target("https://chat.example/u/bob/3"))
        assertEquals(TrackerLinkTarget("alice", 6), t.target("matron://item/6"))
        assertEquals(TrackerLinkTarget("alice", 7), t.target("matron://mission/7"))
        assertEquals(TrackerLinkTarget("alice", 5), t.target("matron://project/5"))
        assertNull(t.target("https://other.example/u/alice/12"))
        assertNull(t.target("matron://item/0"))
        assertNull(t.target("https://github.com/acme/x/pull/1"))
    }

    // MARK: - cache

    @Test
    fun infoAsksOncePerNumberAndKeepsTheAnswer() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val t = titles(lookup = { user, num ->
            calls++
            gate.await()
            NumberLookup(NumberKind.ITEM, "it_$num", num, "Ship it", ownerName = user)
        })
        val a = async { t.info(TrackerLinkTarget("alice", 12)) }
        val b = async { t.info(TrackerLinkTarget("alice", 12)) }
        testScheduler.runCurrent()
        gate.complete(Unit)
        assertEquals(TrackerLinkInfo.Found(NumberKind.ITEM, "Ship it"), a.await())
        assertEquals(TrackerLinkInfo.Found(NumberKind.ITEM, "Ship it"), b.await())
        t.info(TrackerLinkTarget("alice", 12))
        assertEquals(1, calls)
    }

    @Test
    fun infoKeepsAMissingNumberButAsksAgainAfterAFailure() = runTest {
        var calls = 0
        val t = titles(lookup = { _, num ->
            calls++
            when {
                num == 1 -> throw JournalApiError.NotFound
                calls == 2 -> throw JournalApiError.Transport("offline")
                else -> NumberLookup(NumberKind.MISSION, "ms_$num", num, "Rota")
            }
        })
        assertEquals(TrackerLinkInfo.Missing, t.info(TrackerLinkTarget("alice", 1)))
        assertEquals(TrackerLinkInfo.Missing, t.info(TrackerLinkTarget("alice", 1)))
        assertEquals(TrackerLinkInfo.Failed, t.info(TrackerLinkTarget("alice", 2)))
        assertEquals(TrackerLinkInfo.Found(NumberKind.MISSION, "Rota"), t.info(TrackerLinkTarget("alice", 2)))
        assertEquals(3, calls)
    }

    @Test
    fun infoAsksAgainOnceTheAnswerIsOld() = runTest {
        var clock = 0L
        var calls = 0
        val t = titles(lookup = { _, num -> calls++; NumberLookup(NumberKind.ITEM, "it", num, "T$calls") }, now = { clock })
        assertEquals(TrackerLinkInfo.Found(NumberKind.ITEM, "T1"), t.info(TrackerLinkTarget("alice", 4)))
        clock += TrackerLinkTitles.TTL_MILLIS + 1
        assertEquals(TrackerLinkInfo.Found(NumberKind.ITEM, "T2"), t.info(TrackerLinkTarget("alice", 4)))
    }
}
