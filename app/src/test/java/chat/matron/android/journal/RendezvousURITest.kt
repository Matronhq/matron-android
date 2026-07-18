package chat.matron.android.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RendezvousURITest {
    private val rid = "23456789BCDFGHJKMNPQRSTVWX" // 26 chars, all in alphabet

    @Test
    fun format_roundTripsThroughParse() {
        val uri = RendezvousURI.format(rid)
        assertEquals("matron://rlink?v=1&rid=$rid", uri)
        assertEquals(rid, RendezvousURI.parse(uri))
    }

    @Test
    fun parse_rejectsNonRlinkPayloads_asNotALink() {
        for (raw in listOf("https://example.com", "matron://link?v=1&server=x&code=ABCD-2345", "random text", "")) {
            try {
                RendezvousURI.parse(raw); fail("expected NotALink for $raw")
            } catch (e: RendezvousURI.ParseError.NotALink) { /* expected */ }
        }
    }

    @Test
    fun parse_futureVersionIsUnsupported() {
        try {
            RendezvousURI.parse("matron://rlink?v=2&rid=$rid")
            fail("expected UnsupportedVersion")
        } catch (e: RendezvousURI.ParseError.UnsupportedVersion) { /* expected */ }
    }

    @Test
    fun parse_missingVersionIsMalformed() {
        try {
            RendezvousURI.parse("matron://rlink?rid=$rid")
            fail("expected Malformed")
        } catch (e: RendezvousURI.ParseError.Malformed) { /* expected */ }
    }

    @Test
    fun parse_ridShapeIsEnforced() {
        for (bad in listOf(
            "matron://rlink?v=1",                      // missing rid
            "matron://rlink?v=1&rid=SHORT",            // wrong length
            "matron://rlink?v=1&rid=${"A".repeat(26)}", // A not in alphabet
            "matron://rlink?v=1&rid=${rid}X",          // 27 chars
            "matron://rlink?v=1&rid=",                 // empty rid
        )) {
            try {
                RendezvousURI.parse(bad); fail("expected Malformed for $bad")
            } catch (e: RendezvousURI.ParseError.Malformed) { /* expected */ }
        }
    }

    // --- Controller amendment (parity with matron-apple's RendezvousURI):
    // scheme/host matching is case-insensitive (RFC 3986 schemes/hosts are
    // case-insensitive; QR alphanumeric mode is uppercase-only). Query
    // values stay case-sensitive. ---

    @Test
    fun parse_acceptsUppercaseScheme() {
        assertEquals(rid, RendezvousURI.parse("MATRON://RLINK?v=1&rid=$rid"))
    }

    @Test
    fun parse_acceptsMixedCaseScheme() {
        assertEquals(rid, RendezvousURI.parse("Matron://RLink?v=1&rid=$rid"))
    }

    // --- Controller amendment: duplicate query keys must resolve the same
    // way as LinkURI.kt does, for in-repo consistency. LinkURI.kt builds its
    // param map via `List<Pair<String, String>>.toMap()`, whose documented
    // behavior is last-pair-wins (a later `put` for the same key overwrites
    // the earlier one) — so RendezvousURI, built the same way, is last-wins
    // too, not first-wins. This test pins that. ---

    @Test
    fun parse_duplicateRidKey_lastWins() {
        val secondRid = rid.reversed() // still 26 chars, all in alphabet, distinct from `rid`
        assertEquals(secondRid, RendezvousURI.parse("matron://rlink?v=1&rid=$rid&rid=$secondRid"))
    }
}
