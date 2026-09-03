package chat.matron.android.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Ported 1:1 from matron-apple's `SessionTagTests` (apple #152). The one
/// store-backed case (`testSummaryStripsTheShortAndGatesTheLetter`) lives in
/// `JournalChatServiceTest` instead — it needs Room under Robolectric, and
/// this suite stays pure JVM.
class SessionTagTest {

    // MARK: splitTitle — peeling the bridge's `[bc] ` prefix

    /// Ports matron-apple's `testSplitTitlePeelsASessionShort`.
    @Test
    fun splitTitlePeelsASessionShort() {
        val (short, title) = SessionTag.splitTitle("[b5] css token migration")
        assertEquals("b5", short)
        assertEquals("css token migration", title)
    }

    /// Ports matron-apple's `testSplitTitleLeavesUnprefixedTitlesAlone`.
    @Test
    fun splitTitleLeavesUnprefixedTitlesAlone() {
        for (raw in listOf(
            "css token migration",         // no prefix at all
            "[b5]no space after bracket",
            "[b5f] three chars is not a short",
            "[b] one char is not a short",
            "[b ] spaces are not a short",
            "[b5] ",                       // nothing after the prefix
            "",
        )) {
            val (short, title) = SessionTag.splitTitle(raw)
            assertNull("expected no short in $raw", short)
            assertEquals("title must come back unchanged for $raw", raw, title)
        }
    }

    /// Ports matron-apple's `testSplitTitleTakesOnlyTheFirstPrefix`: a user
    /// message that itself starts with a bracketed pair stays part of the
    /// visible title.
    @Test
    fun splitTitleTakesOnlyTheFirstPrefix() {
        val (short, title) = SessionTag.splitTitle("[f0] [ok] do the thing")
        assertEquals("f0", short)
        assertEquals("[ok] do the thing", title)
    }

    /// Ports matron-apple's `testSplitTitlePeelsARoomShortBehindTheRoomMarker`:
    /// agent-chat room titles (bridge #225/#228) carry the room short BEHIND
    /// the ↔️ marker, which stays with the title — a single-box user gets no
    /// styled tag but must keep the room marker.
    @Test
    fun splitTitlePeelsARoomShortBehindTheRoomMarker() {
        val (short, title) = SessionTag.splitTitle("↔️ [ab] mac ↔ dev-z — ci triage")
        assertEquals("ab", short)
        assertEquals("↔️ mac ↔ dev-z — ci triage", title)

        // A room title with no short behind the marker comes back untouched.
        val (none, plain) = SessionTag.splitTitle("↔️ mac ↔ dev-z")
        assertNull(none)
        assertEquals("↔️ mac ↔ dev-z", plain)
    }

    /// Ports matron-apple's `testSplitTitleStillPeelsTheLegacyLinkMarker`:
    /// rooms minted before bridge #228 carry 🔗 forever — titles only
    /// rewrite on rename, so the legacy marker must keep parsing.
    @Test
    fun splitTitleStillPeelsTheLegacyLinkMarker() {
        val (short, title) = SessionTag.splitTitle("🔗 [ab] mac ↔ dev-z — ci triage")
        assertEquals("ab", short)
        assertEquals("🔗 mac ↔ dev-z — ci triage", title)
    }

    /// Ports matron-apple's `testTitleBesideRoomTagDropsEitherMarker`.
    @Test
    fun titleBesideRoomTagDropsEitherMarker() {
        assertEquals("mac ↔ dev-z", SessionTag.titleBesideRoomTag("↔️ mac ↔ dev-z"))
        assertEquals("mac ↔ dev-z", SessionTag.titleBesideRoomTag("🔗 mac ↔ dev-z"))
        assertEquals("mac ↔ dev-z", SessionTag.titleBesideRoomTag("mac ↔ dev-z"))
    }

    /// Ports matron-apple's `testSplitTitlePeelsAShortBehindTheSpawnMarker`:
    /// spawned-session titles (bridge #227) are `🐣 [ab] <task>`. The short
    /// is styled into the tag; the 🐣 stays with the visible title so a
    /// spawned chat reads as one at a glance — it is never dropped the way
    /// the room marker is beside a room tag.
    @Test
    fun splitTitlePeelsAShortBehindTheSpawnMarker() {
        val (short, title) = SessionTag.splitTitle("🐣 [f0] port the flaky auth tests")
        assertEquals("f0", short)
        assertEquals("🐣 port the flaky auth tests", title)
        assertEquals(
            "only the room marker drops beside a tag — 🐣 stays",
            title, SessionTag.titleBesideRoomTag(title),
        )

        // A 🐣 title with no short behind it comes back untouched.
        val (none, plain) = SessionTag.splitTitle("🐣 no short here")
        assertNull(none)
        assertEquals("🐣 no short here", plain)
    }

    // MARK: boxLetters — one distinguishing letter per box

    /// Ports matron-apple's `testLettersStripTheCommonPrefix`: the
    /// colleague-with-two-DEV-boxes problem — dev-y / dev-z must come out
    /// Y / Z, not both D.
    @Test
    fun lettersStripTheCommonPrefix() {
        val letters = SessionTag.boxLetters(mapOf(1L to "dev-y", 2L to "dev-z"))
        assertEquals("Y", letters[1L])
        assertEquals("Z", letters[2L])
    }

    /// Ports matron-apple's `testLettersKeepInitialsForUnrelatedNames`.
    @Test
    fun lettersKeepInitialsForUnrelatedNames() {
        val letters = SessionTag.boxLetters(mapOf(1L to "mac-mini", 2L to "dev-3"))
        assertEquals("M", letters[1L])
        assertEquals("D", letters[2L])
    }

    /// Ports matron-apple's `testANameThatIsTheCommonPrefixFallsBackToItsInitial`.
    @Test
    fun aNameThatIsTheCommonPrefixFallsBackToItsInitial() {
        val letters = SessionTag.boxLetters(mapOf(1L to "dev", 2L to "dev-2"))
        assertEquals("D", letters[1L])
        assertEquals("2", letters[2L])
    }

    /// Ports matron-apple's `testPrefixStripIsCaseInsensitive`.
    @Test
    fun prefixStripIsCaseInsensitive() {
        val letters = SessionTag.boxLetters(mapOf(1L to "Dev-y", 2L to "dev-z"))
        assertEquals("Y", letters[1L])
        assertEquals("Z", letters[2L])
    }

    /// Ports matron-apple's `testAnExpandingUppercaseMappingStaysOneCharacter`:
    /// "ß".uppercase() is "SS" — the tag is one character by contract, so an
    /// expanding mapping keeps the original letter instead.
    @Test
    fun anExpandingUppercaseMappingStaysOneCharacter() {
        val letters = SessionTag.boxLetters(mapOf(1L to "box-ß", 2L to "box-z"))
        assertEquals("ß", letters[1L])
        assertEquals("Z", letters[2L])
    }

    /// Ports matron-apple's `testSingleBoxKeepsItsInitial`.
    @Test
    fun singleBoxKeepsItsInitial() {
        assertEquals(mapOf(1L to "M"), SessionTag.boxLetters(mapOf(1L to "mac-mini")))
    }

    /// Ports matron-apple's `testEmptyRegistryYieldsNoLetters`.
    @Test
    fun emptyRegistryYieldsNoLetters() {
        assertEquals(emptyMap<Long, String>(), SessionTag.boxLetters(emptyMap()))
    }
}
