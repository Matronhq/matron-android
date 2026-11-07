package chat.matron.android.viewmodels

import chat.matron.android.journal.Contact
import chat.matron.android.journal.ContactInvite
import chat.matron.android.journal.ContactInviteLink
import chat.matron.android.journal.ContactsProviding
import chat.matron.android.journal.ContactsWording
import chat.matron.android.journal.InvitePreview
import chat.matron.android.journal.RevealedInviteLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// The invite sheet while a link is being made, or why it could not be.
/// The link itself goes to [RevealedInviteLinks], which shows it once.
sealed class InviteSheetState {
    data object Making : InviteSheetState()
    data class Failed(val message: String) : InviteSheetState()
}

/// The accept card for an invite link someone sent the user.
sealed class AcceptCardState {
    data object Opening : AcceptCardState()
    data class Preview(
        val link: String,
        val preview: InvitePreview,
        val accepting: Boolean = false,
        val error: String? = null,
    ) : AcceptCardState()
    /// The link could not be opened: a refusal, in plain words.
    data class Refused(val message: String) : AcceptCardState()
    data object Sent : AcceptCardState()
}

/// Drives Settings → People: the user's contacts, their open invites, the
/// invite sheet and the accept card. Pull-based: [refresh] on screen enter
/// and after every change. Lives for the signed-in session, so a deep link
/// can open the accept card before the screen is up.
class PeopleViewModel(
    private val api: ContactsProviding,
    private val scope: CoroutineScope,
    private val links: RevealedInviteLinks,
) {
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _invites = MutableStateFlow<List<ContactInvite>>(emptyList())
    val invites: StateFlow<List<ContactInvite>> = _invites.asStateFlow()

    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _withdrawing = MutableStateFlow<Set<String>>(emptySet())
    val withdrawing: StateFlow<Set<String>> = _withdrawing.asStateFlow()

    private val _inviteSheet = MutableStateFlow<InviteSheetState?>(null)
    val inviteSheet: StateFlow<InviteSheetState?> = _inviteSheet.asStateFlow()

    private var previewJob: Job? = null

    private val _acceptCard = MutableStateFlow<AcceptCardState?>(null)
    val acceptCard: StateFlow<AcceptCardState?> = _acceptCard.asStateFlow()

    suspend fun refresh() {
        try {
            _contacts.value = api.contacts()
            _errorMessage.value = null
            _hasLoaded.value = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _errorMessage.value = ContactsWording.OTHER
        }
        // Open invites are a second list: a journal with federation off
        // refuses it, which leaves the contacts standing.
        _invites.value = runCatching { api.contactInvites() }.getOrElse {
            if (it is CancellationException) throw it
            emptyList()
        }
    }

    /// "Invite someone on another Matron": make a link and show it once.
    /// Runs on the link store's scope, so the app lock cannot cancel it
    /// after the journal has made the link.
    fun makeInvite() {
        if (_inviteSheet.value is InviteSheetState.Making) return
        _inviteSheet.value = InviteSheetState.Making
        links.scope.launch {
            try {
                val minted = api.createContactInvite()
                links.show(minted.link, RevealedInviteLinks.Source.INVITE)
                _inviteSheet.value = null
                refreshInvites()
            } catch (cancel: CancellationException) {
                _inviteSheet.value = null
                throw cancel
            } catch (error: Throwable) {
                _inviteSheet.value = InviteSheetState.Failed(ContactsWording.inviteError(error))
            }
        }
    }

    fun closeInviteSheet() {
        _inviteSheet.value = null
    }

    fun withdraw(invite: ContactInvite) {
        if (invite.id in _withdrawing.value) return
        _withdrawing.value = _withdrawing.value + invite.id
        scope.launch {
            try {
                api.revokeContactInvite(invite.id)
                _invites.value = _invites.value.filterNot { it.id == invite.id }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _errorMessage.value = ContactsWording.OTHER
            } finally {
                _withdrawing.value = _withdrawing.value - invite.id
                refreshInvites()
            }
        }
    }

    private suspend fun refreshInvites() {
        runCatching { api.contactInvites() }.onSuccess { _invites.value = it }
            .onFailure { if (it is CancellationException) throw it }
    }

    /// A pasted or deep-linked invite link: ask the journal who it is from.
    /// Opening sends nothing to anyone.
    fun openLink(text: String) {
        // A newer link (or Cancel) wins over an answer still on its way.
        previewJob?.cancel()
        val link = ContactInviteLink.extract(text)
        if (link == null || !ContactInviteLink.isValid(link)) {
            _acceptCard.value = AcceptCardState.Refused(ContactsWording.INVALID_LINK)
            return
        }
        _acceptCard.value = AcceptCardState.Opening
        previewJob = scope.launch {
            _acceptCard.value = try {
                AcceptCardState.Preview(link, api.openContactInvite(link))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AcceptCardState.Refused(ContactsWording.linkError(error, journalOf(link)))
            }
        }
    }

    /// Accept: the user's consent. The contact turns active when their
    /// journal confirms.
    fun accept() {
        val card = _acceptCard.value as? AcceptCardState.Preview ?: return
        if (card.accepting || card.preview.blocked) return
        _acceptCard.value = card.copy(accepting = true, error = null)
        scope.launch {
            try {
                api.acceptContactInvite(card.link)
                _acceptCard.value = AcceptCardState.Sent
                refresh()
            } catch (cancel: CancellationException) {
                _acceptCard.value = card
                throw cancel
            } catch (error: Throwable) {
                _acceptCard.value = card.copy(accepting = false, error = ContactsWording.linkError(error, card.preview.journal))
            }
        }
    }

    private fun journalOf(link: String): String? = ContactInviteLink.fields(link)?.get("j")

    /// Cancel sends nothing.
    fun closeAcceptCard() {
        previewJob?.cancel()
        _acceptCard.value = null
    }
}
