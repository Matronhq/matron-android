package chat.matron.android.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LinkURITest {
    @Test
    fun roundTrip() {
        val uri = LinkURI.format("https://chat.example.com", "KTNM-3VQ8")
        assertTrue(uri.startsWith("matron://link?"))
        val parsed = LinkURI.parse(uri)
        assertEquals("https://chat.example.com", parsed.serverURL)
        assertEquals("KTNM-3VQ8", parsed.code)
    }

    @Test
    fun roundTrip_serverWithPathPrefixAndPort() {
        // The server URL is embedded exactly as the session stores it —
        // subpath-hosted and non-443 servers must survive the round trip.
        val parsed = LinkURI.parse(LinkURI.format("http://127.0.0.1:9810/journal", "KTNM-3VQ8"))
        assertEquals("http://127.0.0.1:9810/journal", parsed.serverURL)
    }

    @Test
    fun parse_normalizesSloppyCode() {
        val parsed = LinkURI.parse("matron://link?v=1&server=https%3A%2F%2Fchat.example.com&code=ktnm3vq8")
        assertEquals("KTNM-3VQ8", parsed.code)
    }

    @Test
    fun parse_acceptsPartiallyEncodedServerValue() {
        // Apple's URLComponents leaves `:` and `/` unescaped in query values;
        // the parser must accept both that style and the fully-percent-encoded
        // one this file's own `format` produces.
        val parsed = LinkURI.parse("matron://link?v=1&server=https://chat.example.com&code=KTNM-3VQ8")
        assertEquals("https://chat.example.com", parsed.serverURL)
        assertEquals("KTNM-3VQ8", parsed.code)
    }

    @Test
    fun parse_wrongSchemeOrHost_isNotALink() {
        for (raw in listOf("https://chat.example.com", "matron://pair?v=1", "otp://x", "not a uri at all")) {
            try {
                LinkURI.parse(raw); fail("expected NotALink for $raw")
            } catch (e: LinkURI.ParseError.NotALink) { /* expected */ }
        }
    }

    @Test
    fun parse_otherVersion_isUnsupported() {
        try {
            LinkURI.parse("matron://link?v=2&server=https%3A%2F%2Fx.example&code=KTNM-3VQ8")
            fail("expected UnsupportedVersion")
        } catch (e: LinkURI.ParseError.UnsupportedVersion) { /* expected */ }
    }

    @Test
    fun parse_missingOrBadParts_isMalformed() {
        for (raw in listOf(
            "matron://link?server=https%3A%2F%2Fx.example&code=KTNM-3VQ8",        // no v
            "matron://link?v=1&code=KTNM-3VQ8",                                    // no server
            "matron://link?v=1&server=ftp%3A%2F%2Fx.example&code=KTNM-3VQ8",       // non-http(s) server
            "matron://link?v=1&server=https%3A%2F%2Fx.example",                    // no code
            "matron://link?v=1&server=https%3A%2F%2Fx.example&code=KTN",           // short code
        )) {
            try {
                LinkURI.parse(raw); fail("expected Malformed for $raw")
            } catch (e: LinkURI.ParseError.Malformed) { /* expected */ }
        }
    }

    // --- Plan-owner amendment: http is only ever accepted to localhost-ish
    // dev hosts (mirrors ServerURLValidator's typed-entry carve-out); any
    // other http host is a Malformed parse, never a silently-accepted plaintext
    // link. https is unrestricted regardless of host. ---

    @Test
    fun parse_httpNonLocalhost_isMalformed() {
        try {
            LinkURI.parse("matron://link?v=1&server=http%3A%2F%2F192.168.1.10%3A8787&code=KTNM-3VQ8")
            fail("expected Malformed for non-localhost http server")
        } catch (e: LinkURI.ParseError.Malformed) { /* expected */ }
    }

    @Test
    fun parse_httpLocalhostVariants_areAccepted() {
        for (raw in listOf(
            "matron://link?v=1&server=http%3A%2F%2F127.0.0.1%3A8787&code=KTNM-3VQ8",
            "matron://link?v=1&server=http%3A%2F%2Flocalhost%3A8787&code=KTNM-3VQ8",
        )) {
            val parsed = LinkURI.parse(raw)
            assertEquals("KTNM-3VQ8", parsed.code)
        }
    }

    // --- Controller amendment (parity with matron-apple's RendezvousURI):
    // scheme/host matching is case-insensitive (RFC 3986 schemes/hosts are
    // case-insensitive; QR alphanumeric mode is uppercase-only). Query
    // values stay case-sensitive. ---

    @Test
    fun parse_acceptsUppercaseScheme() {
        val parsed = LinkURI.parse("MATRON://LINK?v=1&server=https%3A%2F%2Fchat.example.com&code=KTNM-3VQ8")
        assertEquals("https://chat.example.com", parsed.serverURL)
        assertEquals("KTNM-3VQ8", parsed.code)
    }

    // --- Controller amendment: pins LinkURI's pre-existing duplicate-query-key
    // behavior. The param map is built with `List<Pair<String, String>>.toMap()`,
    // whose documented semantics are last-pair-wins (a later `put` for the same
    // key overwrites the earlier one) — so this is last-wins, not first-wins.
    // RendezvousURI mirrors this same behavior for in-repo consistency. ---

    @Test
    fun parse_duplicateVersionKey_lastWins() {
        try {
            LinkURI.parse("matron://link?v=1&v=2&server=https%3A%2F%2Fx.example&code=KTNM-3VQ8")
            fail("expected UnsupportedVersion (last v= wins)")
        } catch (e: LinkURI.ParseError.UnsupportedVersion) { /* expected */ }
    }
}
