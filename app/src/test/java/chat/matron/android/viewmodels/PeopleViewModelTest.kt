package chat.matron.android.viewmodels

import chat.matron.android.journal.Contact
import chat.matron.android.journal.ContactInvite
import chat.matron.android.journal.ContactRefusal
import chat.matron.android.journal.ContactsProviding
import chat.matron.android.journal.InvitePreview
import chat.matron.android.journal.MintedInvite
import chat.matron.android.journal.RevealedInviteLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Settings → People: contacts, open invites, the invite sheet and the
/// accept card.
class PeopleViewModelTest {
    private val link = "matron://contact?v=1&j=b.example&u=bob&t=${"A".repeat(43)}&fp=${"B".repeat(26)}"
    private val webLink = "https://b.example/contact#v=1&j=b.example&u=bob&t=${"A".repeat(43)}&fp=${"B".repeat(26)}"

    private fun vmTest(body: suspend CoroutineScope.(CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try { body(scope) } finally { scope.cancel() }
        Unit
    }

    private suspend fun waitUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            delay(5)
        }
    }

    private class FakeContacts : ContactsProviding {
        var contactList = listOf(Contact("ct_1", "bob@b.example", "bob", "b.example", "bob", "active"))
        var inviteList = listOf(ContactInvite("inv_1", "open", 1, 2))
        var mintError: Throwable? = null
        var openError: Throwable? = null
        var acceptError: Throwable? = null
        val opened = mutableListOf<String>()
        val accepted = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        var preview = InvitePreview("bob@b.example", "bob", "b.example", "BBBB BBBB")
        var mintedWebLink: String? = null

        override suspend fun contacts() = contactList
        override suspend fun contactInvites() = inviteList
        override suspend fun createContactInvite(): MintedInvite {
            mintError?.let { throw it }
            inviteList = listOf(ContactInvite("inv_2", "open", 3, 4)) + inviteList
            return MintedInvite(inviteList.first(), "matron://contact?minted", mintedWebLink)
        }
        override suspend fun revokeContactInvite(id: String) {
            revoked += id
            inviteList = inviteList.filterNot { it.id == id }
        }
        override suspend fun openContactInvite(link: String): InvitePreview {
            opened += link
            openError?.let { throw it }
            return preview
        }
        override suspend fun acceptContactInvite(link: String) {
            accepted += link
            acceptError?.let { throw it }
        }
    }

    @Test
    fun refreshLoadsContactsAndOpenInvites() = vmTest { scope ->
        val vm = PeopleViewModel(FakeContacts(), scope, RevealedInviteLinks(scope))
        vm.refresh()
        assertTrue(vm.hasLoaded.value)
        assertEquals(listOf("bob@b.example"), vm.contacts.value.map { it.address })
        assertEquals(listOf("inv_1"), vm.invites.value.map { it.id })
    }

    @Test
    fun aRefusedInviteListLeavesTheContactsStanding() = vmTest { scope ->
        val api = object : ContactsProviding by FakeContacts() {
            override suspend fun contactInvites(): List<ContactInvite> = throw ContactRefusal(409, "federation_off")
        }
        val vm = PeopleViewModel(api, scope, RevealedInviteLinks(scope))
        vm.refresh()
        assertEquals(1, vm.contacts.value.size)
        assertEquals(emptyList<ContactInvite>(), vm.invites.value)
        assertNull(vm.errorMessage.value)
    }

    @Test
    fun aNewInvitesLinkGoesToTheStoreToBeShownOnce() = vmTest { scope ->
        val links = RevealedInviteLinks(scope)
        val vm = PeopleViewModel(FakeContacts(), scope, links)
        vm.makeInvite()
        waitUntil { links.current.value.isNotEmpty() }
        assertEquals(
            listOf(RevealedInviteLinks.Reveal("matron://contact?minted", RevealedInviteLinks.Source.INVITE)),
            links.current.value,
        )
        assertNull(vm.inviteSheet.value)
        waitUntil { vm.invites.value.size == 2 }
        links.done(links.current.value.single())
        assertTrue(links.current.value.isEmpty())
    }

    @Test
    fun theHttpsFormIsTheOneShownWhenTheJournalSendsIt() = vmTest { scope ->
        val links = RevealedInviteLinks(scope)
        val vm = PeopleViewModel(FakeContacts().apply { mintedWebLink = webLink }, scope, links)
        vm.makeInvite()
        waitUntil { links.current.value.isNotEmpty() }
        assertEquals(listOf(RevealedInviteLinks.Reveal(webLink, RevealedInviteLinks.Source.INVITE)), links.current.value)
    }

    @Test
    fun aPastedHttpsLinkOpensTheCard() = vmTest { scope ->
        val api = FakeContacts()
        val vm = PeopleViewModel(api, scope, RevealedInviteLinks(scope))
        vm.openLink("Join me on Matron: $webLink")
        waitUntil { vm.acceptCard.value is AcceptCardState.Preview }
        assertEquals("the link goes to the journal as given", listOf(webLink), api.opened)
        vm.accept()
        waitUntil { vm.acceptCard.value == AcceptCardState.Sent }
        assertEquals(listOf(webLink), api.accepted)
    }

    @Test
    fun anHttpsLinkWithABadFragmentIsRefusedWithoutAsking() = vmTest { scope ->
        val api = FakeContacts()
        val vm = PeopleViewModel(api, scope, RevealedInviteLinks(scope))
        for (bad in listOf(
            "https://b.example/contact#garbage",
            "https://b.example/contact",
            webLink.replace("https://b.example", "https://a.example"),
            webLink.replace("/contact#", "/contact/#"),
        )) {
            vm.openLink(bad)
            assertEquals(bad, AcceptCardState.Refused("This is not a valid invite link."), vm.acceptCard.value)
        }
        assertTrue(api.opened.isEmpty())
    }

    @Test
    fun aRefusedInviteSaysWhy() = vmTest { scope ->
        val api = FakeContacts().apply { mintError = ContactRefusal(409, "too_many_invites") }
        val vm = PeopleViewModel(api, scope, RevealedInviteLinks(scope))
        vm.makeInvite()
        waitUntil { vm.inviteSheet.value is InviteSheetState.Failed }
        assertEquals(
            InviteSheetState.Failed("You already have 20 open invites. Withdraw one first."),
            vm.inviteSheet.value,
        )
    }

    @Test
    fun withdrawRemovesTheInvite() = vmTest { scope ->
        val api = FakeContacts()
        val vm = PeopleViewModel(api, scope, RevealedInviteLinks(scope))
        vm.refresh()
        vm.withdraw(vm.invites.value.single())
        waitUntil { vm.invites.value.isEmpty() && vm.withdrawing.value.isEmpty() }
        assertEquals(listOf("inv_1"), api.revoked)
    }

    @Test
    fun anUnreadableLinkIsRefusedWithoutAsking() = vmTest { scope ->
        val api = FakeContacts()
        val vm = PeopleViewModel(api, scope, RevealedInviteLinks(scope))
        vm.openLink("hello")
        assertEquals(AcceptCardState.Refused("This is not a valid invite link."), vm.acceptCard.value)
        assertTrue(api.opened.isEmpty())
    }

    @Test
    fun openThenAccept() = vmTest { scope ->
        val api = FakeContacts()
        val vm = PeopleViewModel(api, scope, RevealedInviteLinks(scope))
        vm.openLink("Here: $link")
        waitUntil { vm.acceptCard.value is AcceptCardState.Preview }
        assertEquals(listOf(link), api.opened)
        assertTrue("opening sends nothing", api.accepted.isEmpty())
        vm.accept()
        waitUntil { vm.acceptCard.value == AcceptCardState.Sent }
        assertEquals(listOf(link), api.accepted)
    }

    @Test
    fun aDismissedLinksLateAnswerIsDropped() = vmTest { scope ->
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val api = object : ContactsProviding by FakeContacts() {
            override suspend fun openContactInvite(link: String): InvitePreview {
                gate.await()
                return InvitePreview("bob@b.example", "bob", "b.example", "BBBB")
            }
        }
        val vm = PeopleViewModel(api, scope, RevealedInviteLinks(scope))
        vm.openLink(link)
        vm.closeAcceptCard()
        gate.complete(Unit)
        delay(50)
        assertNull(vm.acceptCard.value)
    }

    @Test
    fun cancelSendsNothing() = vmTest { scope ->
        val api = FakeContacts()
        val vm = PeopleViewModel(api, scope, RevealedInviteLinks(scope))
        vm.openLink(link)
        waitUntil { vm.acceptCard.value is AcceptCardState.Preview }
        vm.closeAcceptCard()
        assertNull(vm.acceptCard.value)
        assertTrue(api.accepted.isEmpty())
    }

    @Test
    fun refusalsReadInPlainWords() = vmTest { scope ->
        val api = FakeContacts().apply { openError = ContactRefusal(409, "own_journal") }
        val vm = PeopleViewModel(api, scope, RevealedInviteLinks(scope))
        vm.openLink(link)
        waitUntil { vm.acceptCard.value is AcceptCardState.Refused }
        assertEquals(
            AcceptCardState.Refused("This link is from your own journal. Add people here by name instead."),
            vm.acceptCard.value,
        )

        api.openError = null
        api.acceptError = ContactRefusal(409, "pending")
        vm.openLink(link)
        waitUntil { vm.acceptCard.value is AcceptCardState.Preview }
        vm.accept()
        waitUntil { (vm.acceptCard.value as? AcceptCardState.Preview)?.error != null }
        assertEquals(
            "You have already accepted; waiting for their journal.",
            (vm.acceptCard.value as AcceptCardState.Preview).error,
        )
    }

    @Test
    fun aJournalNotAllowedNamesTheLinksJournal() = vmTest { scope ->
        val api = FakeContacts().apply { openError = ContactRefusal(409, "journal_not_allowed") }
        val vm = PeopleViewModel(api, scope, RevealedInviteLinks(scope))
        vm.openLink(link)
        waitUntil { vm.acceptCard.value is AcceptCardState.Refused }
        assertEquals(AcceptCardState.Refused("Your journal doesn't allow contacts on b.example."), vm.acceptCard.value)
    }

    @Test
    fun aBlockedPersonCannotBeAccepted() = vmTest { scope ->
        val api = FakeContacts().apply {
            preview = preview.copy(existing = Contact("ct_1", "bob@b.example", "bob", "b.example", "bob", "blocked"))
        }
        val vm = PeopleViewModel(api, scope, RevealedInviteLinks(scope))
        vm.openLink(link)
        waitUntil { vm.acceptCard.value is AcceptCardState.Preview }
        vm.accept()
        assertTrue(api.accepted.isEmpty())
    }
}
