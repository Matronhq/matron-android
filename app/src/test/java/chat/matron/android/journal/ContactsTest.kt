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
        val fields = ContactInviteLink.fields(link)!!
        assertEquals("b.example", fields["j"])
        assertEquals("bob", fields["u"])
        assertEquals(fp, fields["fp"])
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

        val approved = InviteAskAnswer.fromJson(obj("""{"ask":{"id":"ia_1","state":"approved"},"invite":{"id":"inv_1"},"link":"$link"}"""))
        assertEquals("approved", approved.state)
        assertEquals(link, approved.link)
        val declined = InviteAskAnswer.fromJson(obj("""{"ask":{"id":"ia_1","state":"declined"}}"""))
        assertEquals("declined", declined.state)
        assertNull(declined.link)
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
