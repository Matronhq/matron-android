package chat.matron.android.viewmodels

import chat.matron.android.journal.approvals.ApprovalKeyDTO
import chat.matron.android.journal.approvals.ApprovalKeyRequestDTO
import chat.matron.android.platform.ApprovalKeyStore
import chat.matron.android.platform.SoftwareKeyProvider
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalKeysScreenModelTest {
    private val journal = FakeApprovalJournal()
    private val store = ApprovalKeyStore(InMemoryKeyValueStore(), "journal.example", "alice")
    private val center = ApprovalCenter(journal, SoftwareKeyProvider(), store, "alice", 7, "Pixel", "journal.example")
    private val model = ApprovalKeysScreenModel(center, Locale.UK, ZoneOffset.UTC)

    @Test
    fun aNewAccountOffersSetUpOnly() = runTest {
        model.load()
        assertEquals("No approval key yet", model.statusTitle)
        assertTrue(model.canSetUp)
        assertFalse(model.canAskAnotherDevice)
        assertEquals("Not set up", model.summary)
        assertFalse(model.needsAttention)
        assertNull(model.fingerprint)
    }

    @Test
    fun theFormNeedsAPasswordAndSixDigitsAndForgetsThem() = runTest {
        model.load()
        model.beginSetUp()
        model.setPassword("secret")
        model.setCode("12 34-5")
        assertFalse(model.canSubmitForm)
        model.setCode("123456")
        assertTrue(model.canSubmitForm)
        assertTrue(model.submitForm())
        assertNull(model.form.value)
        assertEquals("", model.password.value)
        assertEquals("This device can approve", model.statusTitle)
        assertEquals(center.deviceFingerprint, model.fingerprint)
    }

    @Test
    fun aWrongPasswordStaysInTheFormWithTheReason() = runTest {
        model.load()
        model.beginSetUp()
        model.setPassword("wrong")
        model.setCode("123456")
        assertFalse(model.submitForm())
        assertEquals(ApprovalKeysScreenModel.Form.SetUp, model.form.value)
        assertEquals("That password is incorrect.", model.error.value)
        assertEquals("", model.password.value)
    }

    @Test
    fun anotherDevicesKeyAsksForAttentionAndOffersAsking() = runTest {
        journal.keys += ApprovalKeyDTO("ab".repeat(32), "", "Tablet", 99, createdAt = Instant.parse("2026-11-12T10:00:00Z"))
        model.load()
        assertEquals("This device has no key", model.statusTitle)
        assertTrue(model.canAskAnotherDevice)
        assertTrue(model.needsAttention)
        val row = model.rows.single()
        assertEquals("Added with password and code · 12 Nov 2026", row.detail)
        assertNull(row.state)
        assertFalse(row.isThisDevice)
    }

    @Test
    fun removingWithoutAKeyOpensThePasswordForm() = runTest {
        val other = ApprovalKeyDTO("ab".repeat(32), "", "Tablet", null)
        journal.keys += other
        model.load()
        assertEquals("Not attached to a signed-in device", model.rows.single().state)
        model.remove(other)
        assertEquals(ApprovalKeysScreenModel.Form.Remove(other), model.form.value)
        model.setPassword("secret")
        model.setCode("123456")
        assertTrue(model.submitForm())
        assertEquals("Removed", model.rows.single().state?.take(7))
    }

    @Test
    fun thisDevicesOwnRequestIsItsStatusNotARowToApprove() = runTest {
        journal.keys += ApprovalKeyDTO("ab".repeat(32), "", "Tablet", 99)
        model.load()
        model.askAnotherDevice()
        journal.requests += ApprovalKeyRequestDTO("kr_x", "cd".repeat(32), "Laptop")
        model.load()
        assertEquals("Waiting for another device to approve", model.statusTitle)
        assertEquals(listOf("kr_x"), model.requests.map { it.id })
        assertEquals(center.deviceFingerprint, model.fingerprint)
    }

    @Test
    fun fingerprintsAreSplitInTwo() {
        assertEquals("7734 f909", ApprovalKeysScreenModel.grouped("7734f909"))
        assertEquals("abc", ApprovalKeysScreenModel.grouped("abc"))
    }
}
