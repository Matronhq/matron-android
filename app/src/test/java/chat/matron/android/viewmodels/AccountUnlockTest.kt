package chat.matron.android.viewmodels

import chat.matron.android.journal.AccountLock
import chat.matron.android.journal.AccountNotLockedError
import chat.matron.android.journal.AccountUnlockResult
import chat.matron.android.journal.AccountUnlocking
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.approvals.ApprovalNonce
import chat.matron.android.journal.approvals.SignedApproval
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.journal.stringOrNull
import chat.matron.android.platform.ApprovalKeyStore
import chat.matron.android.platform.SoftwareKeyProvider
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountLockMonitorTest {
    private var now = 0L
    private val monitor = AccountLockMonitor { now }

    @Test
    fun aFrozenDeviceCanUnlockAndAnHttpAnswerDoesNotClearASocketLock() {
        var cleared = 0
        monitor.whenCleared { cleared += 1 }
        now = 10
        monitor.noteLockedBySocket(AccountLock(5, true))
        assertTrue(monitor.state.value.canUnlock)
        // The nonce a frozen device fetches says nothing about the lock.
        monitor.noteStatus(200)
        assertTrue(monitor.state.value.isLocked)
        monitor.cleared()
        assertFalse(monitor.state.value.isLocked)
        assertEquals(1, cleared)
    }

    @Test
    fun aSignedOutLockIsClearedByAnyAnswerOnlyASignedInDeviceGets() {
        monitor.noteLock(AccountLock(5, false))
        assertTrue(monitor.state.value.isLocked)
        assertFalse(monitor.state.value.canUnlock)
        // 401, throttles and server errors say nothing.
        listOf(401, 429, 503).forEach { monitor.noteStatus(it) }
        assertTrue(monitor.state.value.isLocked)
        monitor.noteStatus(200)
        assertFalse(monitor.state.value.isLocked)
    }

    @Test
    fun aRefusalWithoutSinceKeepsTheKnownOne() {
        monitor.noteLock(AccountLock(5, true))
        monitor.noteLock(AccountLock(null, true))
        assertEquals(5L, monitor.state.value.lock?.since)
    }

    @Test
    fun anAcceptedHelloEndsTheLockUnlessTheLockCameAfterIt() {
        now = 10
        monitor.noteLockedBySocket(AccountLock(5, false))
        now = 20
        monitor.noteSocketAccepted()
        assertFalse(monitor.state.value.isLocked)
        now = 30
        monitor.noteLockedBySocket(AccountLock(6, false))
        // An acceptance stamped before the lock was reported cannot clear it.
        now = 25
        monitor.noteSocketAccepted()
        assertTrue(monitor.state.value.isLocked)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountUnlockModelTest {
    private val journal = FakeApprovalJournal()
    private val store = ApprovalKeyStore(InMemoryKeyValueStore(), "journal.example", "alice")
    private val center = ApprovalCenter(journal, SoftwareKeyProvider(), store, "alice", 7, "Pixel", "127.0.0.1:9811")
    private val monitor = AccountLockMonitor()

    private class Unlocking(var answer: () -> AccountUnlockResult) : AccountUnlocking {
        val sent = mutableListOf<SignedApproval>()
        override suspend fun approvalNonce() = ApprovalNonce("n", Instant.now())
        override suspend fun unlockAccount(approval: SignedApproval): AccountUnlockResult { sent += approval; return answer() }
    }

    private val adopted = mutableListOf<String>()
    private var saves = true

    private fun model(api: Unlocking) = AccountUnlockModel(
        monitor, api,
        sign = { since -> center.signUnlock(since, AccountUnlockModel.unlockDetail(since)) },
        adoptToken = { adopted += it; saves },
    )

    private suspend fun frozen() {
        center.refresh()
        center.enrol("secret", "123456")
        monitor.noteLockedBySocket(AccountLock(1_789_000_000_000, true))
    }

    @Test
    fun unlockSignsClearPanicOverTheLocksStartAndAdoptsTheNewToken() = runTest {
        frozen()
        val api = Unlocking { AccountUnlockResult("new-token", 2) }
        val model = model(api)
        val unlocking = async { model.unlock() }
        runCurrent()
        assertEquals("Unlock this account", center.prompt.value!!.request.headline)
        center.confirm()
        unlocking.await()
        val st = parseJsonObjectOrNull(api.sent.single().statement)!!
        assertEquals("account", st.stringOrNull("type"))
        // The journal's own name, remembered from the last read of the keys,
        // not the address the app connects to.
        assertEquals("journal.example", st.stringOrNull("journal"))
        assertEquals(mapOf("op" to "clear_panic", "since" to "1789000000000"),
            st.objectOrNull("subject")!!.mapValues { it.value.toString().trim('"') })
        assertEquals(listOf("new-token"), adopted)
        assertFalse(monitor.state.value.isLocked)
        assertNull(monitor.warning.value)
    }

    @Test
    fun cancellingLeavesTheLock() = runTest {
        frozen()
        val api = Unlocking { AccountUnlockResult("x", 0) }
        val model = model(api)
        val unlocking = async { model.unlock() }
        runCurrent()
        center.cancel()
        unlocking.await()
        assertTrue(api.sent.isEmpty())
        assertEquals(AccountUnlockModel.Phase.Idle, model.phase.value)
        assertTrue(monitor.state.value.canUnlock)
    }

    @Test
    fun aRefusalIsShownAndNotLockedClears() = runTest {
        frozen()
        val api = Unlocking { throw JournalApiError.ApprovalRequired }
        val model = model(api)
        var unlocking = async { model.unlock() }
        runCurrent(); center.confirm(); unlocking.await()
        assertTrue((model.phase.value as AccountUnlockModel.Phase.Failed).message.contains("can't unlock this lock"))
        api.answer = { throw AccountNotLockedError() }
        unlocking = async { model.unlock() }
        runCurrent(); center.confirm(); unlocking.await()
        assertFalse(monitor.state.value.isLocked)
        assertTrue(adopted.isEmpty())
    }

    @Test
    fun aTokenThatCouldNotBeSavedLeavesAWarning() = runTest {
        frozen()
        saves = false
        val model = model(Unlocking { AccountUnlockResult("new", 0) })
        val unlocking = async { model.unlock() }
        runCurrent(); center.confirm(); unlocking.await()
        assertEquals(AccountLockMonitor.UNSAVED_TOKEN_WARNING, monitor.warning.value)
        assertFalse(monitor.state.value.isLocked)
    }

    @Test
    fun withoutTheLocksStartNothingIsSigned() = runTest {
        center.refresh()
        val model = model(Unlocking { AccountUnlockResult("x", 0) })
        monitor.noteLockedBySocket(AccountLock(null, true))
        model.unlock()
        assertTrue(model.phase.value is AccountUnlockModel.Phase.Failed)
        assertNull(center.prompt.value)
    }

    @Test
    fun anUnusableKeyIsAFailureNotACancel() = runTest {
        frozen()
        store.delete()
        val model = model(Unlocking { AccountUnlockResult("x", 0) })
        model.unlock()
        assertTrue(model.phase.value is AccountUnlockModel.Phase.Failed)
    }
}
