package chat.matron.android.journal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RendezvousURITest {
    private val rid = "23456789BCDFGHJKMNPQRSTVWX" // 26 chars, all in alphabet
    private val key = ByteArray(32) { it.toByte() } // 0x00..0x1f
    private val keyB64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    @Test
    fun format_roundTripsThroughParse() {
        val uri = RendezvousURI.format(rid, key)
        assertEquals("matron://rlink?v=2&rid=$rid&k=$keyB64", uri)
        val parsed = RendezvousURI.parse(uri)
        assertEquals(rid, parsed.rid)
        assertArrayEquals(key, parsed.key)
    }

    @Test
    fun parse_rejectsNonRlinkPayloads_asNotALink() {
        for (raw in listOf("https://example.com", "matron://link?v=2&server=x&code=ABCD-2345", "random text", "")) {
            try {
                RendezvousURI.parse(raw); fail("expected NotALink for $raw")
            } catch (e: RendezvousURI.ParseError.NotALink) { /* expected */ }
        }
    }

    @Test
    fun parse_v1IsNowUnsupported_andOtherFutureVersionsToo() {
        // Hard cutover: v=1 (the shipped cleartext format) is no longer honored.
        for (raw in listOf("matron://rlink?v=1&rid=$rid&k=$keyB64", "matron://rlink?v=3&rid=$rid&k=$keyB64")) {
            try {
                RendezvousURI.parse(raw); fail("expected UnsupportedVersion for $raw")
            } catch (e: RendezvousURI.ParseError.UnsupportedVersion) { /* expected */ }
        }
    }

    @Test
    fun parse_missingVersionIsMalformed() {
        try {
            RendezvousURI.parse("matron://rlink?rid=$rid&k=$keyB64"); fail("expected Malformed")
        } catch (e: RendezvousURI.ParseError.Malformed) { /* expected */ }
    }

    @Test
    fun parse_ridShapeIsEnforced() {
        for (bad in listOf(
            "matron://rlink?v=2&k=$keyB64",                                   // missing rid
            "matron://rlink?v=2&rid=SHORT&k=$keyB64",                         // wrong length
            "matron://rlink?v=2&rid=${"A".repeat(26)}&k=$keyB64",             // A not in alphabet
            "matron://rlink?v=2&rid=${rid}X&k=$keyB64",                       // 27 chars
        )) {
            try {
                RendezvousURI.parse(bad); fail("expected Malformed for $bad")
            } catch (e: RendezvousURI.ParseError.Malformed) { /* expected */ }
        }
    }

    @Test
    fun parse_keyIsRequiredAndMustBe32Bytes() {
        for (bad in listOf(
            "matron://rlink?v=2&rid=$rid",                    // missing k
            "matron://rlink?v=2&rid=$rid&k=",                 // empty k → decodes to 0 bytes, rejected by size != 32
            "matron://rlink?v=2&rid=$rid&k=!!!notb64",        // undecodable
            "matron://rlink?v=2&rid=$rid&k=AAEC",             // decodes to 3 bytes, not 32
        )) {
            try {
                RendezvousURI.parse(bad); fail("expected Malformed for $bad")
            } catch (e: RendezvousURI.ParseError.Malformed) { /* expected */ }
        }
    }

    @Test
    fun parse_isCaseInsensitiveOnSchemeAndHost() {
        val parsed = RendezvousURI.parse("MATRON://RLINK?v=2&rid=$rid&k=$keyB64")
        assertEquals(rid, parsed.rid)
        assertArrayEquals(key, parsed.key)
    }
}
