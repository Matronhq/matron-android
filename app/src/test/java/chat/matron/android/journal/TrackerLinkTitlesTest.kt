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
        assertTrue(TrackerLinkTitleMatch.titleMatches("Merge: release notes export", "Merge: release notes export"))
        assertTrue(TrackerLinkTitleMatch.titleMatches("Merge: release notes…", "Merge: release notes export for the launch"))
        assertTrue(TrackerLinkTitleMatch.titleMatches("merge release notes exp...", "Merge: release notes export"))
        assertTrue(TrackerLinkTitleMatch.titleMatches("holiday-rota item", "Holiday rota for December: options"))
        assertTrue(TrackerLinkTitleMatch.titleMatches("CAFÉ menu", "Cafe menu update"))
    }

    @Test
    fun titleMatchesRefusesATitleAboutSomethingElseAndAnEmptyOne() {
        assertFalse(TrackerLinkTitleMatch.titleMatches("the new merge question", "Holiday rota for December"))
        assertFalse(TrackerLinkTitleMatch.titleMatches("Release notes", "Holiday rota for December"))
        assertFalse(TrackerLinkTitleMatch.titleMatches("here", "Merge: release notes export"))
        assertFalse(TrackerLinkTitleMatch.titleMatches("", "Merge"))
    }

    @Test
    fun titleMatchesComparesAnAddressAsOneWholeWord() {
        val actual = "Merge https://example.com/org/repo/pull/10 (live tracking fix)"
        assertFalse(TrackerLinkTitleMatch.titleMatches("Merge https://example.com/org/repo/pull/11 (search fix)", actual))
        assertFalse(TrackerLinkTitleMatch.titleMatches("Merge https://example.com/org/repo/pull/1", actual))
        assertFalse(TrackerLinkTitleMatch.titleMatches("https://example.com/org/repo/pull/10", "Merge https://example.com/org/repo/pull/11"))
        assertTrue(TrackerLinkTitleMatch.titleMatches(actual, actual))
        assertTrue(TrackerLinkTitleMatch.titleMatches("Merge https://example.com/org/repo/pull/10", actual))
        assertTrue(TrackerLinkTitleMatch.titleMatches("Merge https://example.com/org/repo/pu…", actual))
        assertTrue(TrackerLinkTitleMatch.titleMatches("Merge (live tracking fix)", actual))
        assertTrue(TrackerLinkTitleMatch.titleMatches("https://example.com/org/repo/pull/1...", actual))
        // Every other word the same: the address alone tells them apart.
        assertFalse(TrackerLinkTitleMatch.titleMatches("Merge https://example.com/org/repo/pull/11 (live tracking fix)", actual))
        assertFalse(TrackerLinkTitleMatch.titleMatches("Fix https://example.com/Files/A", "Fix https://example.com/Files/a"))
        assertFalse(TrackerLinkTitleMatch.titleMatches("Fix https://example.com/item(1) docs", "Fix https://example.com/item(2) docs"))
        assertTrue(TrackerLinkTitleMatch.titleMatches("Fix [https://example.com/a]", "Fix https://example.com/a"))
        // An address cut short inside wrapping punctuation is still cut short.
        assertTrue(TrackerLinkTitleMatch.titleMatches("Merge (https://example.com/org/repo/pull/1...)", "Merge https://example.com/org/repo/pull/10"))
        assertTrue(
            TrackerLinkTitleMatch.titleMatches(
                "Merge https://example.com/org/repo/pull/1..., then ship",
                "Merge https://example.com/org/repo/pull/10 then ship",
            ),
        )
        assertTrue(TrackerLinkTitleMatch.titleMatches("Fix (see https://example.com/a)", "Fix: see https://example.com/a"))
    }

    // MARK: - writtenLink

    @Test
    fun writtenLinkSplitsTheNumberFromTheTitle() {
        assertEquals(WrittenLink(12, "Ship it"), TrackerLinkTitleMatch.writtenLink("#12 Ship it"))
        assertEquals(WrittenLink(7, "Holiday rota"), TrackerLinkTitleMatch.writtenLink("item #7 — Holiday rota"))
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
    fun infoKeepsOnlyAFoundAnswer() = runTest {
        var calls = 0
        val t = titles(lookup = { _, num ->
            calls++
            when {
                num == 1 && calls == 1 -> throw JournalApiError.NotFound
                calls == 3 -> throw JournalApiError.Transport("offline")
                else -> NumberLookup(NumberKind.MISSION, "ms_$num", num, "Rota")
            }
        })
        // A missing number may be made a moment later: asked again.
        assertEquals(TrackerLinkInfo.Missing, t.info(TrackerLinkTarget("alice", 1)))
        assertEquals(TrackerLinkInfo.Found(NumberKind.MISSION, "Rota"), t.info(TrackerLinkTarget("alice", 1)))
        // A failure is asked again too.
        assertEquals(TrackerLinkInfo.Failed, t.info(TrackerLinkTarget("alice", 2)))
        assertEquals(TrackerLinkInfo.Found(NumberKind.MISSION, "Rota"), t.info(TrackerLinkTarget("alice", 2)))
        // A found one is kept.
        t.info(TrackerLinkTarget("alice", 2))
        assertEquals(4, calls)
    }

    @Test
    fun aWaiterAsksAgainWhenTheLookupItSharedIsCancelled() = runTest {
        var calls = 0
        val never = CompletableDeferred<Unit>()
        val t = titles(lookup = { _, num ->
            calls++
            if (calls == 1) never.await()
            NumberLookup(NumberKind.ITEM, "it_$num", num, "Rotate the API keys")
        })
        val owner = async { t.info(TrackerLinkTarget("alice", 41)) }
        testScheduler.runCurrent()
        val waiter = async { t.info(TrackerLinkTarget("alice", 41)) }
        testScheduler.runCurrent()
        owner.cancel()
        assertEquals(TrackerLinkInfo.Found(NumberKind.ITEM, "Rotate the API keys"), waiter.await())
        assertEquals(2, calls)
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
