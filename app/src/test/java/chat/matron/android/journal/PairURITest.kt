package chat.matron.android.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PairURITest {
    @Test
    fun roundTrip() {
        val uri = PairURI.format("https://chat.example.com", "KTNM-3VQ8")
        assertTrue(uri.startsWith("matron://pair?"))
        val parsed = PairURI.parse(uri)
        assertEquals("https://chat.example.com", parsed.serverURL)
        assertEquals("KTNM-3VQ8", parsed.code)
    }

    @Test
    fun parse_bridgeFormat() {
        // Exactly what the bridge renders: fully percent-encoded server.
        val parsed = PairURI.parse("matron://pair?v=1&server=https%3A%2F%2Fmatron.example.com&code=BCDF-2345")
        assertEquals("https://matron.example.com", parsed.serverURL)
        assertEquals("BCDF-2345", parsed.code)
    }

    @Test
    fun parse_normalizesSloppyCode() {
        val parsed = PairURI.parse("matron://pair?v=1&server=https%3A%2F%2Fchat.example.com&code=ktnm3vq8")
        assertEquals("KTNM-3VQ8", parsed.code)
    }

    @Test
    fun parse_acceptsPartiallyEncodedServerValue() {
        val parsed = PairURI.parse("matron://pair?v=1&server=https://chat.example.com&code=KTNM-3VQ8")
        assertEquals("https://chat.example.com", parsed.serverURL)
    }

    @Test
    fun parse_acceptsUppercaseScheme() {
        val parsed = PairURI.parse("MATRON://PAIR?v=1&server=https%3A%2F%2Fchat.example.com&code=KTNM-3VQ8")
        assertEquals("KTNM-3VQ8", parsed.code)
    }

    @Test
    fun parse_wrongSchemeOrHost_isNotAPairURI() {
        for (raw in listOf(
            "https://chat.example.com",
            "matron://link?v=1&server=https%3A%2F%2Fx.example&code=KTNM-3VQ8",
            "otp://x",
            "KTNM-3VQ8",
        )) {
            try {
                PairURI.parse(raw); fail("expected NotAPairURI for $raw")
            } catch (e: PairURI.ParseError.NotAPairURI) { /* expected */ }
        }
    }

    @Test
    fun linkURI_stillRejectsPairPayloads() {
        // The two payloads are deliberately not interchangeable.
        try {
            LinkURI.parse(PairURI.format("https://chat.example.com", "KTNM-3VQ8"))
            fail("expected NotALink")
        } catch (e: LinkURI.ParseError.NotALink) { /* expected */ }
    }

    @Test
    fun parse_otherVersion_isUnsupported() {
        try {
            PairURI.parse("matron://pair?v=2&server=https%3A%2F%2Fx.example&code=KTNM-3VQ8")
            fail("expected UnsupportedVersion")
        } catch (e: PairURI.ParseError.UnsupportedVersion) { /* expected */ }
    }

    @Test
    fun parse_missingOrBadParts_isMalformed() {
        for (raw in listOf(
            "matron://pair?server=https%3A%2F%2Fx.example&code=KTNM-3VQ8",        // no v
            "matron://pair?v=1&code=KTNM-3VQ8",                                    // no server
            "matron://pair?v=1&server=ftp%3A%2F%2Fx.example&code=KTNM-3VQ8",       // non-http(s) server
            "matron://pair?v=1&server=http%3A%2F%2F192.168.1.10&code=KTNM-3VQ8",   // http, not localhost
            "matron://pair?v=1&server=https%3A%2F%2Fx.example",                    // no code
            "matron://pair?v=1&server=https%3A%2F%2Fx.example&code=KTN",           // short code
            "matron://pair?v=1&server=https%3A%2F%2Fx.example&code=KTNM-3VQ8X",    // long code
        )) {
            try {
                PairURI.parse(raw); fail("expected Malformed for $raw")
            } catch (e: PairURI.ParseError.Malformed) { /* expected */ }
        }
    }

    @Test
    fun parse_ignoresSurroundingWhitespace_likeIsPairURI() {
        val raw = "  matron://pair?v=1&server=https%3A%2F%2Fchat.example.com&code=KTNM-3VQ8\n"
        assertTrue(PairURI.isPairURI(raw))
        assertEquals("KTNM-3VQ8", PairURI.parse(raw).code)
    }

    @Test
    fun parse_httpLocalhost_isAccepted() {
        val parsed = PairURI.parse("matron://pair?v=1&server=http%3A%2F%2F127.0.0.1%3A8787&code=KTNM-3VQ8")
        assertEquals("http://127.0.0.1:8787", parsed.serverURL)
    }

    @Test
    fun isPairURI_prefixOnly() {
        assertTrue(PairURI.isPairURI("matron://pair?v=9"))
        assertTrue(PairURI.isPairURI("  MATRON://PAIR?v=1"))
        assertFalse(PairURI.isPairURI("matron://link?v=1"))
        assertFalse(PairURI.isPairURI("KTNM-3VQ8"))
    }

    @Test
    fun sameJournal_normalisesCaseDefaultPortsAndTrailingSlash() {
        assertEquals(true, PairURI.sameJournal("https://Chat.Example.com", "https://chat.example.com/"))
        assertEquals(true, PairURI.sameJournal("https://chat.example.com:443/journal/", "https://chat.example.com/journal"))
        assertEquals(false, PairURI.sameJournal("https://chat.example.com", "https://other.example.com"))
        assertEquals(false, PairURI.sameJournal("https://chat.example.com", "https://chat.example.com:8443"))
        assertEquals(false, PairURI.sameJournal("http://localhost:8787", "https://localhost:8787"))
        assertNull(PairURI.sameJournal("not a url", "https://chat.example.com"))
    }

    @Test
    fun sameJournal_distinguishesBasePathsOnOneHost() {
        assertEquals(false, PairURI.sameJournal("https://chat.example.com/alice/", "https://chat.example.com/bob/"))
        assertEquals(false, PairURI.sameJournal("https://chat.example.com/alice", "https://chat.example.com"))
    }

    @Test
    fun displayJournal_showsNonDefaultPortAndBasePath() {
        assertEquals("chat.example.com", PairURI.displayJournal("https://chat.example.com/"))
        assertEquals("127.0.0.1:8787", PairURI.displayJournal("http://127.0.0.1:8787"))
        assertEquals("chat.example.com/alice", PairURI.displayJournal("https://chat.example.com/alice/"))
    }
}
