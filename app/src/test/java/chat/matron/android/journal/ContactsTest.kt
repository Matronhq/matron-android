package chat.matron.android.journal

import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Contacts on other journals: the invite link, the payloads of the
/// contacts routes, and the plain words for each refusal.
class ContactsTest {
    private val token = "A".repeat(43)
    private val fp = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val link = "matron://contact?v=1&j=b.example&u=bob&t=$token&fp=$fp"

    private fun obj(json: String) = parseJsonObjectOrNull(json)!!

    // MARK: - The link

    @Test
    fun aWellFormedLinkIsValid() {
        assertTrue(ContactInviteLink.isValid(link))
        val fields = ContactInviteLink.parse(link)!!
        assertEquals("b.example", fields.journal)
        assertEquals("bob", fields.user)
        assertEquals(token, fields.token)
        assertEquals("the fingerprint is compared lowercased", fp.lowercase(), fields.fingerprint)
    }

    @Test
    fun aLinkWithAnyOtherFieldOrOneTwiceIsRefused() {
        assertFalse(ContactInviteLink.isValid("$link&x=1"))
        assertFalse(ContactInviteLink.isValid("$link&u=carol"))
        assertFalse(ContactInviteLink.isValid(link.replace("v=1", "v=2")))
        assertFalse(ContactInviteLink.isValid(link.replace("&fp=$fp", "")))
        assertFalse(ContactInviteLink.isValid(link.replace("u=bob", "u=")))
        assertFalse(ContactInviteLink.isValid(link.replace("matron://contact", "matron://consent")))
        assertFalse(ContactInviteLink.isValid(link.replace("matron://", "https://")))
        assertFalse(ContactInviteLink.isValid("$link#frag"))
        assertFalse(ContactInviteLink.isValid("not a link"))
    }

    @Test
    fun theMatronFormChecksEachFieldsShape() {
        assertFalse("a short token", ContactInviteLink.isValid(link.replace("t=$token", "t=${"A".repeat(42)}")))
        assertFalse("a long token", ContactInviteLink.isValid(link.replace("t=$token", "t=${"A".repeat(44)}")))
        assertFalse("not base64url", ContactInviteLink.isValid(link.replace("t=$token", "t=${"A".repeat(42)}+")))
        assertTrue(ContactInviteLink.isValid(link.replace("t=$token", "t=${"-_".repeat(21)}9")))
        assertFalse("a short fingerprint", ContactInviteLink.isValid(link.replace("fp=$fp", "fp=${fp.drop(1)}")))
        assertFalse("not base32", ContactInviteLink.isValid(link.replace("fp=$fp", "fp=${fp.dropLast(1)}1")))
        assertFalse("not a name", ContactInviteLink.isValid(link.replace("u=bob", "u=.bob")))
        assertFalse("not a host", ContactInviteLink.isValid(link.replace("j=b.example", "j=b_example")))
        assertFalse("too long", ContactInviteLink.isValid(link + "&".repeat(1024)))
    }

    // MARK: - The https link

    private val webLink = "https://b.example/contact#v=1&j=b.example&u=bob&t=$token&fp=$fp"

    @Test
    fun aWellFormedHttpsLinkIsValid() {
        val fields = ContactInviteLink.parse(webLink)!!
        assertEquals(ContactInviteLink.Fields("b.example", "bob", token, fp.lowercase()), fields)
        assertEquals("both forms read alike", ContactInviteLink.parse(link), fields)
        assertTrue("field order is free", ContactInviteLink.isValid("https://b.example/contact#fp=$fp&t=$token&u=bob&j=b.example&v=1"))
        assertTrue("the host is compared case-insensitively", ContactInviteLink.isValid(webLink.replace("https://b.example", "https://B.Example")))
        assertTrue(ContactInviteLink.isValid(webLink.replace("j=b.example", "j=B.EXAMPLE")))
        assertTrue("the default port is the same host", ContactInviteLink.isValid(webLink.replace("b.example/", "b.example:443/")))
        assertTrue("a journal on a port", ContactInviteLink.isValid("https://b.example:8443/contact#v=1&j=b.example:8443&u=bob&t=$token&fp=$fp"))
        assertTrue(ContactInviteLink.isValid("  $webLink\n"))
    }

    @Test
    fun anHttpsLinkAnywhereButItsJournalsContactPageIsRefused() {
        val refused = mapOf(
            "http" to webLink.replace("https://", "http://"),
            "another scheme" to webLink.replace("https://", "ftp://"),
            "another path" to webLink.replace("/contact#", "/contacts#"),
            "a trailing slash" to webLink.replace("/contact#", "/contact/#"),
            "a deeper path" to webLink.replace("/contact#", "/contact/x#"),
            "no path" to webLink.replace("/contact#", "#"),
            "a case-changed path" to webLink.replace("/contact#", "/Contact#"),
            "a query" to webLink.replace("/contact#", "/contact?x=1#"),
            "the fields in the query" to "https://b.example/contact?v=1&j=b.example&u=bob&t=$token&fp=$fp",
            "user info" to webLink.replace("https://", "https://alice@"),
            "user info with a password" to webLink.replace("https://", "https://alice:pw@"),
            "another host than j" to webLink.replace("https://b.example", "https://a.example"),
            "a j on another host" to webLink.replace("j=b.example", "j=a.example"),
            "a subdomain of j" to webLink.replace("https://b.example", "https://x.b.example"),
            "another port than j" to webLink.replace("b.example/", "b.example:8443/"),
            "no fragment" to "https://b.example/contact",
            "an empty fragment" to "https://b.example/contact#",
            "a missing key" to webLink.replace("&u=bob", ""),
            "an extra key" to "$webLink&x=1",
            "a duplicate key" to "$webLink&u=carol",
            "the same key twice with the same value" to "$webLink&v=1",
            "v=2" to webLink.replace("v=1", "v=2"),
            "a bad token" to webLink.replace("t=$token", "t=${"A".repeat(42)}"),
            "a bad fingerprint" to webLink.replace("fp=$fp", "fp=${fp.dropLast(1)}0"),
            "an empty user" to webLink.replace("u=bob", "u="),
        )
        for ((why, url) in refused) assertNull(why, ContactInviteLink.parse(url))
    }

    @Test
    fun theHttpsLinkIsFoundInPastedText() {
        assertEquals(webLink, ContactInviteLink.extract("Join me: $webLink"))
        assertEquals(webLink, ContactInviteLink.extract("<$webLink>."))
        assertEquals("trailing punctuation is not the link's", webLink, ContactInviteLink.extract("Here it is: $webLink."))
        assertEquals(webLink, ContactInviteLink.extract("($webLink)"))
        assertEquals("the first valid link", link, ContactInviteLink.extract("https://b.example/ then $link then $webLink"))
        assertNull(ContactInviteLink.extract(webLink.replace("https://b.example", "https://a.example")))
    }

    @Test
    fun anInviteShapedLinkOpensTheCardEvenWhenItIsNotValid() {
        assertTrue(ContactInviteLink.isInviteShaped(webLink))
        assertTrue(ContactInviteLink.isInviteShaped(link))
        assertTrue(ContactInviteLink.isInviteShaped("https://b.example/contact#garbage"))
        assertTrue(ContactInviteLink.isInviteShaped("https://b.example/contact"))
        assertTrue(ContactInviteLink.isInviteShaped("matron://contact?x=1"))
        assertFalse(ContactInviteLink.isInviteShaped("https://b.example/u/bob/4"))
        assertFalse(ContactInviteLink.isInviteShaped("https://b.example/contact/"))
        assertFalse(ContactInviteLink.isInviteShaped("https://b.example/contacts"))
        assertFalse(ContactInviteLink.isInviteShaped("http://b.example/contact#v=1"))
        assertFalse(ContactInviteLink.isInviteShaped("matron://contacts?x=1"))
        assertFalse(ContactInviteLink.isInviteShaped("matron://item/4"))
    }

    @Test
    fun theLinkIsFoundInPastedText() {
        assertEquals(link, ContactInviteLink.extract("  $link\n"))
        assertEquals(link, ContactInviteLink.extract("Here is my invite: $link — see you"))
        assertEquals(link, ContactInviteLink.extract("\"$link\""))
        assertNull(ContactInviteLink.extract("https://b.example/"))
    }

    // MARK: - Contacts

    @Test
    fun aContactOnAnotherJournalShowsItsFullAddress() {
        val list = Contact.listFromJson(obj("""
            {"contacts":[
              {"id":"ct_1","address":"bob@b.example","peer_user":"bob","peer_journal":"b.example",
               "display_name":"bob","state":"active","key_changed":false},
              {"id":"ct_2","address":"carol","peer_user":"carol","peer_journal":null,
               "display_name":"Carol","state":"pending_out","key_changed":false},
              {"address":"no-id"}
            ]}
        """))
        assertEquals(2, list.size)
        val bob = list[0]
        assertTrue(bob.isOtherJournal)
        assertEquals("bob", bob.name)
        assertEquals("bob@b.example", bob.addressLine)
        assertFalse(bob.isPendingOut)
        assertNull("the API does not send it yet", bob.unreachableSince)
        val carol = list[1]
        assertFalse(carol.isOtherJournal)
        assertEquals("Carol", carol.name)
        assertNull("a same-journal contact shows just the name", carol.addressLine)
        assertTrue(carol.isPendingOut)
    }

    @Test
    fun keyChangedAndUnreachableSinceAreRead() {
        val c = Contact.fromJson(obj("""
            {"id":"ct_1","address":"bob@b.example","peer_user":"bob","peer_journal":"b.example",
             "state":"active","key_changed":true,"unreachable_since":1794067941161}
        """))!!
        assertTrue(c.keyChanged)
        assertEquals(1794067941161L, c.unreachableSince)
        assertEquals("bob", c.name)
    }

    @Test
    fun onlyOpenInvitesAreListed() {
        val invites = ContactInvite.openFromJson(obj("""
            {"invites":[
              {"id":"inv_3","state":"open","created_at":1000,"expires_at":2000},
              {"id":"inv_2","state":"used","created_at":900,"expires_at":1900},
              {"id":"inv_1","state":"revoked","created_at":800,"expires_at":1800},
              {"id":"inv_0","state":"expired","created_at":700,"expires_at":1700}
            ]}
        """))
        assertEquals(listOf("inv_3"), invites.map { it.id })
        assertEquals(1000L, invites[0].createdAt)
        assertEquals(2000L, invites[0].expiresAt)
    }

    @Test
    fun theMintedLinkAndTheAsksAnswerAreRead() {
        val minted = MintedInvite.fromJson(obj("""{"invite":{"id":"inv_1","state":"open","created_at":1,"expires_at":2},"link":"$link"}"""))!!
        assertEquals(link, minted.link)
        assertEquals("inv_1", minted.invite?.id)
        assertNull(MintedInvite.fromJson(obj("""{"invite":{"id":"inv_1"}}""")))
        assertEquals("no web_link: the matron:// link is shared", link, minted.shareLink)

        val approved = InviteAskAnswer.fromJson(obj("""{"ask":{"id":"ia_1","state":"approved"},"invite":{"id":"inv_1"},"link":"$link"}"""))
        assertEquals("approved", approved.state)
        assertEquals(link, approved.link)
        val declined = InviteAskAnswer.fromJson(obj("""{"ask":{"id":"ia_1","state":"declined"}}"""))
        assertEquals("declined", declined.state)
        assertNull(declined.link)
    }

    @Test
    fun theHttpsFormIsSharedWhenTheJournalSendsIt() {
        val minted = MintedInvite.fromJson(obj("""{"invite":{"id":"inv_1","state":"open","created_at":1,"expires_at":2},"link":"$link","web_link":"$webLink"}"""))!!
        assertEquals(link, minted.link)
        assertEquals(webLink, minted.webLink)
        assertEquals(webLink, minted.shareLink)
        assertEquals("an empty web_link is no web_link", link, MintedInvite.fromJson(obj("""{"link":"$link","web_link":""}"""))!!.shareLink)

        val approved = InviteAskAnswer.fromJson(obj("""{"ask":{"id":"ia_1","state":"approved"},"link":"$link","web_link":"$webLink"}"""))
        assertEquals(webLink, approved.link)
        assertEquals("a web_link alone is shared too", webLink, InviteLinks.shareableOf(obj("""{"web_link":"$webLink"}""")))
        assertNull(InviteLinks.shareableOf(obj("""{"ask":{}}""")))
    }

    @Test
    fun theOpenPreviewIsRead() {
        val p = InvitePreview.fromJson(obj("""
            {"inviter":{"address":"bob@b.example","name":"bob","journal":"b.example","fingerprint":"ABCD EFGH IJKL MNOP QRST UVWX YZ"},
             "key_changed":false,"contact":null}
        """))!!
        assertEquals("bob@b.example invites you to be contacts", p.headline)
        assertEquals("b.example", p.journal)
        assertEquals("ABCD EFGH IJKL MNOP QRST UVWX YZ", p.fingerprint)
        assertFalse(p.alreadyContacts)
        assertFalse(p.blocked)

        val existing = InvitePreview.fromJson(obj("""
            {"inviter":{"name":"bob","journal":"b.example","fingerprint":"X"},"key_changed":true,
             "contact":{"id":"ct_1","address":"bob@b.example","state":"active"}}
        """))!!
        assertEquals("bob@b.example", existing.address)
        assertTrue(existing.keyChanged)
        assertTrue(existing.alreadyContacts)
        val blocked = InvitePreview.fromJson(obj("""
            {"inviter":{"name":"bob","journal":"b.example","fingerprint":"X"},
             "contact":{"id":"ct_1","address":"bob@b.example","state":"blocked"}}
        """))!!
        assertTrue(blocked.blocked)
    }

    // MARK: - Refusals

    @Test
    fun everyRefusalReadsInPlainWords() {
        val expected = mapOf(
            "fingerprint_mismatch" to "This link's fingerprint does not match their journal. Do not accept it; ask them for a new link.",
            "journal_blocked" to "Your journal has blocked that journal.",
            "own_journal" to "This link is from your own journal. Add people here by name instead.",
            "federation_off" to "Contacts on other Matrons are turned off on your journal.",
            "already_contact" to "You are already contacts.",
            "pending" to "You have already accepted; waiting for their journal.",
            "blocked" to "You have blocked this person. Unblock them first.",
            "too_many_contacts" to "You have too many contacts on other journals.",
        )
        for ((reason, words) in expected) {
            assertEquals(reason, words, ContactsWording.linkError(ContactRefusal(409, reason)))
        }
        assertEquals(
            "Your journal doesn't allow contacts on b.example.",
            ContactsWording.linkError(ContactRefusal(409, "journal_not_allowed"), "b.example"),
        )
        assertEquals("Their journal could not be reached. Try again later.", ContactsWording.linkError(ContactRefusal(502, "unreachable")))
        assertEquals("That address is not a Matron journal.", ContactsWording.linkError(ContactRefusal(502, "bad_journal")))
        assertEquals("This is not a valid invite link.", ContactsWording.linkError(JournalApiError.Http(400, "bad_request")))
        assertEquals("Something went wrong. Try again.", ContactsWording.linkError(ContactRefusal(409, "something_new")))
        assertEquals("Something went wrong. Try again.", ContactsWording.linkError(JournalApiError.Transport("")))
    }

    @Test
    fun makingAnInviteHasItsOwnWords() {
        assertEquals("You already have 20 open invites. Withdraw one first.", ContactsWording.inviteError(ContactRefusal(409, "too_many_invites")))
        assertEquals("Contacts on other Matrons are turned off on your journal.", ContactsWording.inviteError(ContactRefusal(409, "federation_off")))
        assertEquals("This account cannot make invites.", ContactsWording.inviteError(JournalApiError.Forbidden))
        assertEquals("Something went wrong. Try again.", ContactsWording.inviteError(JournalApiError.Transport("")))
    }

    @Test
    fun datesReadAsTheWordingSays() {
        val invite = ContactInvite("inv_1", "open", createdAt = 1_793_923_200_000, expiresAt = 1_794_528_000_000)
        assertEquals("Made Nov 6, 2026 · expires Nov 13, 2026", ContactsDates.inviteRow(invite, ZoneOffset.UTC, Locale.US))
        assertEquals("Their journal hasn't answered since Nov 6, 2026.", ContactsDates.unreachable(1_793_923_200_000, ZoneOffset.UTC, Locale.US))
    }
}
