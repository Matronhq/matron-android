package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.approvals.ApprovalError
import chat.matron.android.journal.approvals.ApprovalKeyDTO
import chat.matron.android.journal.approvals.ApprovalKeyEncoding
import chat.matron.android.journal.approvals.ApprovalKeyError
import chat.matron.android.journal.approvals.ApprovalKeyFailure
import chat.matron.android.journal.approvals.ApprovalKeyRequestDTO
import chat.matron.android.journal.approvals.ApprovalKeysProviding
import chat.matron.android.journal.approvals.ApprovalKeysState
import chat.matron.android.journal.approvals.ApprovalRequest
import chat.matron.android.journal.approvals.ApprovalSigner
import chat.matron.android.journal.approvals.ApprovalSigningKey
import chat.matron.android.journal.approvals.ApprovalStatement
import chat.matron.android.journal.approvals.ApprovalSubjects
import chat.matron.android.journal.approvals.SignedApproval
import chat.matron.android.platform.ApprovalKeyProviding
import chat.matron.android.platform.ApprovalKeyStore
import chat.matron.android.platform.StoredApprovalKey
import java.util.UUID
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/// The one place that knows the account's approval keys and this device's
/// own, and turns "sign this" into `{statement, signature}`: it reads the
/// keys, shows the user what is being approved ([prompt], drawn by the
/// confirm sheet), and only once they confirm fetches a nonce and asks the
/// device's key, behind a strong biometric, to sign.
///
/// View models ask it through [sign] (or [signer]); none of them builds a
/// statement. Ported from matron-apple's `ApprovalCenter`.
class ApprovalCenter(
    private val api: ApprovalKeysProviding,
    private val keys: ApprovalKeyProviding,
    private val store: ApprovalKeyStore,
    /// The account's name, as signed in with.
    private val user: String,
    /// This sign-in's device id.
    private val deviceID: Long?,
    /// What this device's key is called on the account.
    deviceName: String,
    /// The journal as statements name it, from the server address, for when
    /// the keys cannot be read (a locked account answers every read with
    /// 423). The journal's own answer wins whenever there is one.
    private val journalHost: String? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /// Where this device stands.
    sealed interface Status {
        /// The keys have not been read yet.
        data object Unknown : Status
        /// The server has approval keys switched off.
        data object Disabled : Status
        /// This device cannot hold a key; [advice] says what to do instead.
        data class Unavailable(val advice: String) : Status
        /// No key on the account: nothing needs a signature yet.
        data object NoAccountKey : Status
        /// The account has a key; this device has none and cannot approve.
        data object NoDeviceKey : Status
        /// This device asked for its key to be added and waits for a device
        /// that has one to approve it.
        data object WaitingForApproval : Status
        /// This device holds a key the account has, but it is not attached
        /// to this sign-in (the device signed in again). It is attached
        /// before it signs anything else.
        data object NeedsAttach : Status
        data object Ready : Status
        /// This device's key was removed from the account.
        data object Revoked : Status
        /// This device's key was destroyed by a change to its biometrics.
        data object Invalidated : Status
    }

    /// What the confirm sheet shows.
    data class Prompt(
        val id: String,
        val request: ApprovalRequest,
        /// "fingerprint" or "biometrics".
        val method: String,
        /// The fingerprint of the key that will sign.
        val fingerprint: String,
        /// This device's key is re-attached to the sign-in first, which asks
        /// for the biometric once more.
        val attachesFirst: Boolean,
    )

    private val deviceName = deviceName.take(40)

    private val _state = MutableStateFlow<ApprovalKeysState?>(null)
    val state: StateFlow<ApprovalKeysState?> = _state.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Unknown)
    val status: StateFlow<Status> = _status.asStateFlow()

    /// The request waiting for the user's confirmation, if any.
    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    /// True from the user's confirmation until the signature is made.
    private val _isSigning = MutableStateFlow(false)
    val isSigning: StateFlow<Boolean> = _isSigning.asStateFlow()

    /// Why the last try at signing failed, shown on the sheet, which stays up
    /// for another try.
    private val _promptError = MutableStateFlow<String?>(null)
    val promptError: StateFlow<String?> = _promptError.asStateFlow()

    /// The fingerprint and id of this device's key, enrolled or not.
    private val _deviceKeyID = MutableStateFlow<String?>(null)
    val deviceKeyID: StateFlow<String?> = _deviceKeyID.asStateFlow()
    val deviceFingerprint: String? get() = _deviceKeyID.value?.let(ApprovalKeyEncoding::fingerprint)

    /// The last read of the keys failed with this.
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private var decision: CompletableDeferred<Boolean>? = null
    private val turns = Mutex()

    init {
        noteDeviceKey(store.load())
    }

    /// The biometric's name in sentences, when this device can hold a key.
    val method: String? get() = keys.availability.method

    /// Whether a guarded request will need a signature: the account has a
    /// key. False until the keys have been read.
    val accountHasKey: Boolean get() = _state.value?.hasActiveKey ?: false

    /// Whether this device can sign right now (after an attach, if needed).
    val canSign: Boolean get() = _status.value == Status.Ready || _status.value == Status.NeedsAttach

    // MARK: Reading

    /// Reads the account's keys. Called after sign-in, when the keys screen
    /// opens and before every signature.
    suspend fun refresh() {
        runCatching { load() }.onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun load(): ApprovalKeysState {
        val mine = loads.incrementAndGet()
        try {
            val fresh = api.approvalKeys()
            // Only the latest read publishes: an older answer arriving last
            // must not rewind the list (a key just added or removed).
            if (loads.get() != mine) return fresh
            _state.value = fresh
            _loadError.value = null
            recompute()
            return fresh
        } catch (_: JournalApiError.NotFound) {
            // A server from before approval keys has no such route: no
            // account on it has a key, so nothing needs a signature. (A
            // server that has keys enforces them itself: an unsigned request
            // it needed signed is refused, never applied.)
            val absent = ApprovalKeysState(enabled = false, journal = null, keys = emptyList())
            _state.value = absent
            _loadError.value = null
            recompute()
            return absent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (loads.get() == mine) _loadError.value = e.message ?: e.toString()
            throw e
        }
    }
    private val loads = java.util.concurrent.atomic.AtomicLong(0)

    private fun noteDeviceKey(stored: StoredApprovalKey?) {
        _deviceKeyID.value = stored?.keyID
    }

    private fun usable(stored: StoredApprovalKey): Boolean = runCatching { keys.signingKey(stored) }.isSuccess

    private fun recompute() {
        val stored = store.load()
        noteDeviceKey(stored)
        val state = _state.value ?: run { _status.value = Status.Unknown; return }
        if (!state.enabled) { _status.value = Status.Disabled; return }
        val listed = stored?.let { state.key(it.keyID) }
        if (stored != null && listed != null) {
            _status.value = when {
                !listed.isLive -> Status.Revoked
                !usable(stored) -> Status.Invalidated
                listed.deviceID == deviceID && deviceID != null -> Status.Ready
                else -> Status.NeedsAttach
            }
            return
        }
        if (stored != null && state.requests.any { it.keyID == stored.keyID }) {
            _status.value = Status.WaitingForApproval
            return
        }
        val availability = keys.availability
        if (availability is chat.matron.android.platform.ApprovalKeyAvailability.Unavailable) {
            _status.value = Status.Unavailable(availability.advice)
            return
        }
        _status.value = if (state.hasActiveKey) Status.NoDeviceKey else Status.NoAccountKey
    }

    // MARK: Signing

    /// Signs [request] after the user confirms it. Returns null when the
    /// account has no approval key: the request then goes unsigned, as it
    /// always did. Throws [ApprovalKeyFailure.Cancelled] when the user backs
    /// out, [ApprovalError] when the account has a key and this device cannot
    /// sign, and the read's own error when the keys cannot be read (never
    /// silently unsigned).
    suspend fun sign(request: ApprovalRequest): SignedApproval? {
        val state = load()
        if (!state.hasActiveKey) return null
        val journal = state.journal
        if (!state.enabled || journal == null) throw ApprovalError.Disabled
        val stored = store.load() ?: throw ApprovalError.NoKeyOnThisDevice
        val listed = state.key(stored.keyID) ?: throw ApprovalError.NoKeyOnThisDevice
        if (!listed.isLive) throw ApprovalError.KeyRevoked
        val key = keys.signingKey(stored)
        return confirmAndSign(request, stored, key, journal, attached = listed.deviceID == deviceID && deviceID != null)
    }

    /// The signer the view models call.
    val signer: ApprovalSigner get() = { request -> sign(request) }

    /// Signs a statement that unlocks the account, from a device the lock
    /// froze. The account's keys cannot be read while it is locked, so this
    /// signs with the key this device holds, without reading them, and never
    /// re-attaches first (the journal only lets a device unlock with a key
    /// already attached to it).
    suspend fun signUnlock(since: Long, detail: String): SignedApproval {
        val stored = store.load() ?: throw ApprovalError.NoKeyOnThisDevice
        val journal = _state.value?.journal ?: journalHost ?: throw ApprovalError.Disabled
        val key = keys.signingKey(stored)
        val request = ApprovalRequest(
            kind = ApprovalStatement.Kind.Account, subject = ApprovalSubjects.clearPanic(since),
            headline = "Unlock this account", detail = detail,
        )
        return confirmAndSign(request, stored, key, journal, attached = true)
    }

    /// Shows [request] on the confirm sheet and signs it once the user
    /// confirms, re-attaching the key first when it is not attached.
    private suspend fun confirmAndSign(
        request: ApprovalRequest,
        stored: StoredApprovalKey,
        key: ApprovalSigningKey,
        journal: String,
        attached: Boolean,
    ): SignedApproval = turns.withLock {
        // What the sheet shows is what is signed: a copy the caller cannot
        // change while the user reads it.
        @Suppress("NAME_SHADOWING")
        val request = request.copy(subject = request.subject.toMap())
        // Another signature may have re-attached the key while this one
        // waited for its turn.
        var isAttached = attached || _state.value?.key(stored.keyID)?.let { it.deviceID == deviceID && deviceID != null } == true
        _promptError.value = null
        _prompt.value = Prompt(
            id = UUID.randomUUID().toString(), request = request, method = method ?: "biometrics",
            fingerprint = ApprovalKeyEncoding.fingerprint(stored.keyID), attachesFirst = !isAttached,
        )
        try {
            while (true) {
                val answer = CompletableDeferred<Boolean>()
                decision = answer
                val confirmed = answer.await()
                if (!confirmed) throw ApprovalKeyFailure.Cancelled
                _isSigning.value = true
                _promptError.value = null
                try {
                    if (!isAttached) {
                        attach(key, journal)
                        isAttached = true
                    }
                    return@withLock statement(request, key, journal)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: ApprovalKeyFailure.Cancelled) {
                    // They backed out of the biometric prompt, not of the
                    // request: the sheet stays, for another try or its Cancel.
                    _isSigning.value = false
                } catch (e: Throwable) {
                    _isSigning.value = false
                    _promptError.value = e.message ?: e.toString()
                    if (e is ApprovalKeyFailure.Invalidated) recompute()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        } finally {
            decision = null
            _prompt.value = null
            _promptError.value = null
            _isSigning.value = false
        }
    }

    /// The confirm sheet's primary button.
    fun confirm() {
        decision?.complete(true)
    }

    /// The confirm sheet's Cancel, and its dismissal.
    fun cancel() {
        decision?.complete(false)
    }

    private suspend fun statement(request: ApprovalRequest, key: ApprovalSigningKey, journal: String): SignedApproval {
        val nonce = api.approvalNonce()
        val statement = ApprovalStatement(
            kind = request.kind, journal = journal, user = user, keyID = key.keyID,
            nonce = nonce.nonce, signedAt = now(), subject = request.subject,
        )
        return statement.signed(key, reason(request))
    }

    private suspend fun attach(key: ApprovalSigningKey, journal: String) {
        val request = ApprovalRequest(
            kind = ApprovalStatement.Kind.Account, subject = ApprovalSubjects.attachKey(key.keyID),
            headline = "Re-attach this device's approval key",
        )
        api.attachApprovalKey(statement(request, key, journal))
        runCatching { load() }
    }

    // MARK: Keys

    /// The key this device will enrol: one made earlier that the account has
    /// never seen (a refused try), else a new one. Null when the account
    /// already lists this device's key as live: there is nothing to enrol,
    /// and that key is never replaced behind the user's back. Read from a
    /// fresh list, so a stale one can never destroy a key the account trusts.
    private suspend fun keyToEnrol(): StoredApprovalKey? {
        val state = load()
        val stored = store.load()
        if (stored != null) {
            val listed = state.key(stored.keyID)
            if (listed?.isLive == true) return null
            if (listed == null && usable(stored)) return stored
            // Removed from the account (it can never be added back), or
            // destroyed by a biometric change: a new one replaces it.
            keys.destroy(stored)
        }
        val made = keys.create()
        store.save(made)
        noteDeviceKey(made)
        return made
    }

    /// Sets this device's key up with the password and a code from the
    /// authenticator: the first key on an account, and the way back when no
    /// device with a key is left.
    suspend fun enrol(password: String, code: String) {
        val stored = keyToEnrol() ?: return refresh()
        try {
            api.enrolApprovalKey(stored.publicKey, deviceName, password, code)
        } catch (e: Throwable) {
            if (settledKeyExists(e, stored)) return
            refresh()
            throw e
        }
        refresh()
    }

    /// Asks for this device's key to be added by a device that already has
    /// one. The other device shows this key's fingerprint; the user compares
    /// the two and approves there, within ten minutes.
    suspend fun requestFromAnotherDevice() {
        val stored = keyToEnrol() ?: return refresh()
        try {
            api.requestApprovalKey(stored.publicKey, deviceName)
        } catch (e: Throwable) {
            if (settledKeyExists(e, stored)) return
            refresh()
            throw e
        }
        refresh()
    }

    /// `409 key_exists`: this key is, or was, enrolled. Decided on a fresh
    /// read of the account's keys, never on the refusal alone: listed and live
    /// (an earlier enrolment whose answer was lost), it is kept and the
    /// enrolment counts as done (true); listed as removed, or not listed (it
    /// can never be added here), it is dropped so the next try makes a new
    /// one. When the keys cannot be read, the key is kept.
    private suspend fun settledKeyExists(error: Throwable, stored: StoredApprovalKey): Boolean {
        if (error !is ApprovalKeyError.KeyExists) return false
        val state = runCatching { load() }.getOrNull() ?: return false
        if (state.key(stored.keyID)?.isLive == true) return true
        if (store.load()?.keyID == stored.keyID) {
            keys.destroy(stored)
            store.delete()
            recompute()
        }
        return false
    }

    /// Approves another device's request, signing for the fingerprint the
    /// user has compared.
    suspend fun approve(request: ApprovalKeyRequestDTO) {
        val approval = sign(
            ApprovalRequest(
                kind = ApprovalStatement.Kind.Account, subject = ApprovalSubjects.enrolKey(request.keyID),
                headline = "Add an approval key for ${request.name}",
                detail = "Check that ${request.name} shows the fingerprint ${request.fingerprint}. " +
                    "Once added, that device can approve dangerous actions.",
            ),
        ) ?: throw ApprovalError.NoKeyOnThisDevice
        api.approveApprovalKeyRequest(request.id, approval)
        refresh()
    }

    /// Removes a key with this device's signature. A removed key can never be
    /// added again; when it is this device's own, it is deleted.
    suspend fun revoke(key: ApprovalKeyDTO) {
        val approval = sign(
            ApprovalRequest(
                kind = ApprovalStatement.Kind.Account, subject = ApprovalSubjects.revokeKey(key.keyID),
                headline = "Remove the approval key ${key.name}",
                detail = "Fingerprint ${key.fingerprint}. It will no longer be able to approve anything, and can't be added back.",
            ),
        ) ?: throw ApprovalError.NoKeyOnThisDevice
        api.revokeApprovalKey(key.keyID, approval)
        finishRevoke(key)
        refresh()
    }

    /// Removes a key with the password and a code, for when this device has
    /// no key to sign with (the only keyed phone is lost).
    suspend fun revoke(key: ApprovalKeyDTO, password: String, code: String) {
        api.revokeApprovalKey(key.keyID, password, code)
        finishRevoke(key)
        refresh()
    }

    private fun finishRevoke(key: ApprovalKeyDTO) {
        val stored = store.load() ?: return
        if (stored.keyID == key.keyID) {
            keys.destroy(stored)
            store.delete()
            // Not a signer any more, whether or not the next read works.
            recompute()
        }
    }

    /// Re-attaches this device's key to the sign-in, on its own (the keys
    /// screen's button). [sign] does the same before its first signature.
    suspend fun attach() {
        val state = load()
        val journal = state.journal
        if (!state.enabled || journal == null) throw ApprovalError.Disabled
        val stored = store.load() ?: throw ApprovalError.NoKeyOnThisDevice
        val listed = state.key(stored.keyID) ?: throw ApprovalError.NoKeyOnThisDevice
        if (!listed.isLive) throw ApprovalError.KeyRevoked
        if (listed.deviceID == deviceID && deviceID != null) return
        val key = keys.signingKey(stored)
        // Shown on the sheet like any other signature: the user sees what the
        // biometric prompt is for.
        confirmAndSign(
            ApprovalRequest(
                kind = ApprovalStatement.Kind.Account, subject = ApprovalSubjects.attachKey(key.keyID),
                headline = "Re-attach this device's approval key",
                detail = "Attaches the key ${key.fingerprint} to this sign-in, so this device can approve again.",
            ),
            stored, key, journal, attached = true,
        ).let { api.attachApprovalKey(it) }
        refresh()
    }

    /// Forgets a key this device holds that the account no longer takes
    /// (removed, or destroyed by a biometric change), so a new one can be set up.
    suspend fun discardDeviceKey() {
        store.load()?.let(keys::destroy)
        store.delete()
        refresh()
        recompute()
    }

    companion object {
        /// What the system's biometric prompt says.
        fun reason(request: ApprovalRequest): String =
            request.button?.let { "$it: ${request.headline}" } ?: request.headline

        /// The journal as a statement names it: the server's host,
        /// lowercased, with a port that is not the scheme's default.
        fun journalHost(serverURL: String): String? {
            // HttpUrl canonicalises the host: lowercase, and an international
            // name in its ASCII form, so every spelling of a server is one.
            val url = serverURL.toHttpUrlOrNull() ?: return null
            val host = url.host.takeIf { it.isNotEmpty() } ?: return null
            return if (url.port == HttpUrl.defaultPort(url.scheme)) host else "$host:${url.port}"
        }
    }
}
