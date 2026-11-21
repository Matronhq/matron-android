package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.approvals.ApprovalError
import chat.matron.android.journal.approvals.ApprovalKeyDTO
import chat.matron.android.journal.approvals.ApprovalKeyError
import chat.matron.android.journal.approvals.ApprovalKeyFailure
import chat.matron.android.journal.approvals.ApprovalRequest
import chat.matron.android.journal.approvals.ApprovalStatement
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.journal.stringOrNull
import chat.matron.android.platform.ApprovalKeyAvailability
import chat.matron.android.platform.ApprovalKeyStore
import chat.matron.android.platform.SoftwareKeyProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalCenterTest {
    private val journal = FakeApprovalJournal()
    private val keys = SoftwareKeyProvider()
    private val prefs = InMemoryKeyValueStore()
    private val store = ApprovalKeyStore(prefs, "journal.example", "alice")

    private fun center(deviceID: Long? = 7, provider: SoftwareKeyProvider = keys) = ApprovalCenter(
        api = journal, keys = provider, store = store, user = "alice", deviceID = deviceID,
        deviceName = "Pixel of alice", journalHost = "journal.example", now = { 1_790_000_000_000 },
    )

    private val tap = ApprovalRequest(
        kind = ApprovalStatement.Kind.ItemTap("it_1", 7, null, "Go", "digest"),
        subject = mapOf("box" to "box-a"), headline = "Deploy box-a", reference = "#7", button = "Go",
    )

    /// This device's key, enrolled with password and code.
    private suspend fun enrolled(center: ApprovalCenter): ApprovalKeyDTO {
        center.refresh()
        center.enrol("secret", "123456")
        return journal.keys.last()
    }

    @Test
    fun noKeyOnTheAccountSignsNothing() = runTest {
        val center = center()
        assertNull(center.sign(tap))
        assertNull(center.prompt.value)
        assertEquals(ApprovalCenter.Status.NoAccountKey, center.status.value)
    }

    @Test
    fun aJournalWithoutTheRouteSignsNothing() = runTest {
        journal.keysReadError = JournalApiError.NotFound
        val center = center()
        assertNull(center.sign(tap))
        assertEquals(ApprovalCenter.Status.Disabled, center.status.value)
    }

    @Test
    fun aKeysReadThatFailsIsNeverSilentlyUnsigned() = runTest {
        journal.keysReadError = JournalApiError.Transport("down")
        try {
            center().sign(tap)
            fail("expected a throw")
        } catch (e: JournalApiError.Transport) {
            // expected
        }
    }

    @Test
    fun enrolMakesAKeyAndTheDeviceIsReady() = runTest {
        val center = center()
        val key = enrolled(center)
        assertEquals("Pixel of alice", key.name)
        assertEquals(store.load()!!.keyID, key.keyID)
        assertEquals(ApprovalCenter.Status.Ready, center.status.value)
        assertEquals(key.keyID.take(8), center.deviceFingerprint)
    }

    @Test
    fun aConfirmedTapIsSignedOverWhatWasShown() = runTest {
        val center = center()
        enrolled(center)
        val signing = async { center.sign(tap) }
        runCurrent()
        val prompt = center.prompt.value
        assertNotNull(prompt)
        assertEquals(tap, prompt!!.request)
        assertEquals(false, prompt.attachesFirst)
        center.confirm()
        val approval = signing.await()!!
        assertNull(center.prompt.value)
        val st = parseJsonObjectOrNull(approval.statement)!!
        assertEquals("item_tap", st.stringOrNull("type"))
        assertEquals("journal.example", st.stringOrNull("journal"))
        assertEquals("alice", st.stringOrNull("user"))
        assertEquals("nonce-1", st.stringOrNull("nonce"))
        assertEquals("Go", st.stringOrNull("action"))
        assertEquals("box-a", st.objectOrNull("subject")!!.stringOrNull("box"))
        assertTrue(FakeApprovalJournal.verifies(journal.keys.last().publicKey, approval))
    }

    @Test
    fun cancelSendsNothing() = runTest {
        val center = center()
        enrolled(center)
        val signing = async { runCatching { center.sign(tap) } }
        runCurrent()
        center.cancel()
        assertEquals(ApprovalKeyFailure.Cancelled, signing.await().exceptionOrNull())
        assertNull(center.prompt.value)
    }

    @Test
    fun aKeyFromAnEarlierSignInIsReattachedFirst() = runTest {
        enrolled(center())
        // Signed in again: a new device id, the key still the account's.
        journal.callerDevice = 8
        val center = center(deviceID = 8)
        center.refresh()
        assertEquals(ApprovalCenter.Status.NeedsAttach, center.status.value)
        val signing = async { center.sign(tap) }
        runCurrent()
        assertEquals(true, center.prompt.value!!.attachesFirst)
        center.confirm()
        val approval = signing.await()!!
        // The attach went first, signed by the key itself, and moved the key
        // to this sign-in.
        assertEquals("attach_key", journal.accepted.single().objectOrNull("subject")!!.stringOrNull("op"))
        assertEquals(8L, journal.keys.single().deviceID)
        // The tap's own signature is one the journal takes from device 8.
        journal.check(approval.copy(), "item_tap", mapOf("box" to "box-a"))
        assertEquals(ApprovalCenter.Status.Ready, center.status.value)
    }

    @Test
    fun aLostEnrolmentAnswerNeverCostsTheKey() = runTest {
        val center = center()
        center.refresh()
        // The journal enrolled the key, but the answer never came.
        journal.loseEnrolAnswer = true
        assertTrue(runCatching { center.enrol("secret", "123456") }.isFailure)
        val kept = store.load()!!
        assertEquals(kept.keyID, journal.keys.single().keyID)
        // Trying again finds it already on the account: kept, and ready.
        center.enrol("secret", "123456")
        assertEquals(kept, store.load())
        assertEquals(ApprovalCenter.Status.Ready, center.status.value)
    }

    @Test
    fun keyExistsForALiveKeyKeepsIt() = runTest {
        val center = center()
        center.refresh()
        val made = SoftwareKeyProvider().create()
        store.save(made)
        // Listed live on the server, but this centre's last read predates it.
        journal.keys += ApprovalKeyDTO(made.keyID, made.publicKey, "Pixel", 7)
        journal.refuseEnrolWith = ApprovalKeyError.KeyExists
        center.enrol("secret", "123456")
        assertEquals(made, store.load())
        assertEquals(ApprovalCenter.Status.Ready, center.status.value)
    }

    @Test
    fun anotherDevicesKeyLeavesThisOneUnableToSign() = runTest {
        journal.keys += ApprovalKeyDTO("ab".repeat(32), "", "Tablet", 99)
        val center = center()
        center.refresh()
        assertEquals(ApprovalCenter.Status.NoDeviceKey, center.status.value)
        try {
            center.sign(tap)
            fail("expected a throw")
        } catch (e: ApprovalError.NoKeyOnThisDevice) {
            // expected
        }
    }

    @Test
    fun aRevokedKeyIsSaidSo() = runTest {
        val center = center()
        val key = enrolled(center)
        journal.revokeApprovalKey(key.keyID, "secret", "123456")
        journal.keys += ApprovalKeyDTO("cd".repeat(32), "", "Tablet", 99)
        center.refresh()
        assertEquals(ApprovalCenter.Status.Revoked, center.status.value)
        try {
            center.sign(tap)
            fail("expected a throw")
        } catch (e: ApprovalError.KeyRevoked) {
            // expected
        }
    }

    @Test
    fun aKeyDestroyedByABiometricChangeIsInvalidated() = runTest {
        val center = center()
        val key = enrolled(center)
        keys.invalidated += key.keyID
        center.refresh()
        assertEquals(ApprovalCenter.Status.Invalidated, center.status.value)
    }

    @Test
    fun aDeviceWithoutStrongBiometricsIsUnavailable() = runTest {
        val center = center(provider = SoftwareKeyProvider(ApprovalKeyAvailability.Unavailable("Set one up.")))
        center.refresh()
        assertEquals(ApprovalCenter.Status.Unavailable("Set one up."), center.status.value)
    }

    @Test
    fun aKeyThatExistedIsDroppedSoTheNextTryMakesANewOne() = runTest {
        val center = center()
        center.refresh()
        journal.refuseEnrolWith = ApprovalKeyError.KeyExists
        runCatching { center.enrol("secret", "123456") }
        assertNull(store.load())
        journal.refuseEnrolWith = ApprovalKeyError.BadCode
        runCatching { center.enrol("secret", "123456") }
        val kept = store.load()
        assertNotNull(kept)
        // A refused try keeps the key for the next.
        journal.refuseEnrolWith = null
        center.enrol("secret", "123456")
        assertEquals(kept!!.keyID, journal.keys.single().keyID)
    }

    @Test
    fun askingAnotherDeviceWaitsThenAnApprovalFromAKeyedDeviceAddsIt() = runTest {
        // Device 7 has a key; the new phone (its own store) asks.
        val first = center()
        enrolled(first)
        val newStore = ApprovalKeyStore(InMemoryKeyValueStore(), "journal.example", "alice")
        val second = ApprovalCenter(journal, SoftwareKeyProvider(), newStore, "alice", 8, "New phone", "journal.example")
        second.refresh()
        second.requestFromAnotherDevice()
        assertEquals(ApprovalCenter.Status.WaitingForApproval, second.status.value)
        first.refresh()
        val request = journal.requests.single()
        assertEquals(newStore.load()!!.keyID, request.keyID)
        val approving = async { first.approve(request) }
        runCurrent()
        assertEquals(mapOf("key_id" to request.keyID, "op" to "enrol_key"), first.prompt.value!!.request.subject)
        first.confirm()
        approving.await()
        assertTrue(journal.keys.any { it.keyID == request.keyID && it.enrolledVia == ApprovalKeyDTO.EnrolledVia.DEVICE })
    }

    @Test
    fun revokingThisDevicesKeyDeletesIt() = runTest {
        val center = center()
        val key = enrolled(center)
        val revoking = async { center.revoke(key) }
        runCurrent()
        center.confirm()
        revoking.await()
        assertNull(store.load())
        assertTrue(journal.keys.single().revokedAt != null)
    }

    @Test
    fun unlockSignsWithTheHeldKeyWithoutReadingTheKeys() = runTest {
        val center = center()
        enrolled(center)
        journal.keysReadError = JournalApiError.Http(423, "account_locked")
        val signing = async { center.signUnlock(1_789_000_000_000, "The lock ends.") }
        runCurrent()
        assertEquals(mapOf("op" to "clear_panic", "since" to "1789000000000"), center.prompt.value!!.request.subject)
        center.confirm()
        val approval = signing.await()
        assertEquals("account", parseJsonObjectOrNull(approval.statement)!!.stringOrNull("type"))
    }

    @Test
    fun journalHostKeepsOnlyANonDefaultPort() {
        assertEquals("journal.example", ApprovalCenter.journalHost("https://Journal.Example/"))
        assertEquals("journal.example", ApprovalCenter.journalHost("https://journal.example:443"))
        assertEquals("journal.example:8443", ApprovalCenter.journalHost("https://journal.example:8443/x"))
        assertEquals("localhost:8080", ApprovalCenter.journalHost("http://localhost:8080"))
        assertEquals("localhost", ApprovalCenter.journalHost("http://localhost:80"))
    }
}
