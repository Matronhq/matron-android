package chat.matron.android.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Tracker-item deep links (`[#65](matron://item/65)`, tracker item #115;
/// port of matron-apple's `MatronItemLinkTests`). Agents write them into
/// ordinary message bodies, so the parser is the boundary between "open item
/// 65" and "hand an unregistered scheme to the OS" — hence the table test on
/// both the accepted AND the rejected forms.
class MatronItemLinkTest {

    // MARK: - Parsing

    @Test
    fun itemNumberAcceptsCanonicalForm() {
        val accepted = listOf(
            "matron://item/65" to 65,
            "matron://item/1" to 1,
            "matron://item/123456" to 123_456,
            // Scheme + host are case-insensitive per RFC 3986.
            "MATRON://item/65" to 65,
            "Matron://ITEM/65" to 65,
        )
        for ((string, expected) in accepted) {
            assertEquals("$string should parse as item $expected", expected, MatronItemLink.itemNumber(string))
        }
    }

    @Test
    fun itemNumberRejectsEverythingElse() {
        val rejected = listOf(
            "matron://item/", // no number
            "matron://item", // no path at all
            "matron://item/abc", // not a number
            "matron://item/65abc", // trailing junk
            "matron://items/65", // wrong host
            "matron://item/65/extra", // extra path component
            "matron://item/65/", // trailing empty segment
            "matron://item//65", // leading empty segment
            "matron://item///", // nothing but separators
            "matron://item/65//", // trailing separators
            "matron://item/6 5", // internal space
            "matron://item/%36%35", // percent-encoded digits
            "matron://item:80/65", // port
            "matron://dan@item/65", // userinfo
            "matron://item/-5", // negative
            "matron://item/0", // zero is not an item number
            "matron://item/+65", // signed
            "matron://item/99999999999", // overflows Int
            "matron://item/65?x=1", // query
            "matron://item/65#frag", // fragment
            "matron://link/abc", // the pairing-link scheme
            "https://matron.chat/item/65",
            "matrix://item/65",
            "item://65",
            "",
            "not a url at all",
        )
        for (string in rejected) {
            assertNull("$string must not parse as an item link", MatronItemLink.itemNumber(string))
        }
    }

    // MARK: - Link policy (shared by the markdown click handler and the link chips)

    @Test
    fun actionRoutesByScheme() {
        assertEquals(MatronItemLink.Action.OpenTrackerItem(65), MatronItemLink.action("matron://item/65"))
        assertEquals(MatronItemLink.Action.System("https://matron.chat"), MatronItemLink.action("https://matron.chat"))
        assertEquals(MatronItemLink.Action.Swallow, MatronItemLink.action("mxc://server/abc"))
        assertEquals(MatronItemLink.Action.Swallow, MatronItemLink.action("matrix://room/abc"))
        assertEquals("unknown schemes still go to the OS", MatronItemLink.Action.System("mailto:dan@example.com"), MatronItemLink.action("mailto:dan@example.com"))
    }

    /// No `matron://` URL may ever reach the OS: the scheme is registered
    /// with nothing. Malformed item links and linkified pairing URLs are
    /// swallowed, not passed on.
    @Test
    fun actionSwallowsEveryMatronURL() {
        for (string in listOf(
            "matron://item/abc", "matron://item/", "matron://item/65/extra", "matron://items/65",
            "matron://link/abc", "matron://rlink/abc", "MATRON://whatever", "matron://link?v=1&server=x&code=ABCD-1234",
        )) {
            assertEquals("$string must be swallowed, never handed to the OS", MatronItemLink.Action.Swallow, MatronItemLink.action(string))
        }
    }

    // MARK: - handleMessageLink (the markdown click handler's policy)

    @Test
    fun handleItemLinkCallsHandler() {
        val opened = mutableListOf<Int>()
        val external = mutableListOf<String>()
        handleMessageLink("matron://item/65", { opened += it }) { external += it }
        assertEquals(listOf(65), opened)
        assertTrue("matron:// must never reach the URI handler", external.isEmpty())
    }

    @Test
    fun handleNeverRoutesNonItemURLsToTheItemHandler() {
        val opened = mutableListOf<Int>()
        val external = mutableListOf<String>()
        for (string in listOf("https://matron.chat", "mxc://server/abc", "matron://item/abc", "matron://link/abc")) {
            handleMessageLink(string, { opened += it }) { external += it }
        }
        assertTrue(opened.isEmpty())
        assertEquals("only http reaches the OS", listOf("https://matron.chat"), external)
    }

    @Test
    fun handleWithoutHostSwallowsItemLinks() {
        val external = mutableListOf<String>()
        handleMessageLink("matron://item/65", null) { external += it }
        assertTrue("no handler installed ⇒ swallow, never hand matron:// to the OS", external.isEmpty())
        handleMessageLink("https://matron.chat", null) { external += it }
        assertEquals(listOf("https://matron.chat"), external)
    }

    // MARK: - Log redaction

    /// Swallowed links get logged, and a linkified pairing URI carries its
    /// secret in the QUERY. The log form keeps scheme, host and path and
    /// drops everything after them.
    @Test
    fun redactedForLogStripsQueryAndFragment() {
        assertEquals("matron://rlink", MatronItemLink.redactedForLog("matron://rlink?k=abc#x"))
        assertEquals("matron://rlink", MatronItemLink.redactedForLog("matron://rlink?v=2&rid=01JABCDEF&k=c2VjcmV0LWtleQ"))
        assertEquals("matron://link", MatronItemLink.redactedForLog("matron://link?v=1&server=https%3A%2F%2Fj.example&code=ABCD-1234"))
        // The diagnostic part — which item, which host — survives.
        assertEquals("matron://item/65", MatronItemLink.redactedForLog("matron://item/65"))
        assertEquals("https://example.com/a/b", MatronItemLink.redactedForLog("https://example.com/a/b?token=t#frag"))
        // No host means the "path" IS the payload (`mailto:`, `matrix:`), so only the scheme survives.
        assertEquals("mailto:…", MatronItemLink.redactedForLog("mailto:dan@example.com"))
        assertEquals("matrix:…", MatronItemLink.redactedForLog("matrix:u/dan:example.com"))
        assertEquals("(unparseable URL)", MatronItemLink.redactedForLog("not a url"))
        // Whatever it returns, it must never contain the secret.
        for (raw in listOf("matron://rlink?k=abc#x", "matron://link?code=ABCD-1234", "https://e.com/?token=t")) {
            val redacted = MatronItemLink.redactedForLog(raw)
            assertFalse(redacted, redacted.contains("?"))
            assertFalse(redacted, redacted.contains("#"))
        }
    }
}
