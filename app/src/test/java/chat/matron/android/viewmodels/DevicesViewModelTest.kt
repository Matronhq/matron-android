package chat.matron.android.viewmodels

import chat.matron.android.journal.DeviceDTO
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.PairPreview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `DevicesViewModelTests`.
class DevicesViewModelTest {

    @Test
    fun refresh_sortsClientsFirstThenAgents_eachNewestFirst() = runBlocking {
        val fake = FakeDevicesProvider()
        fake.rosters = mutableListOf(
            listOf(
                device(1, kind = "agent", createdAt = 100),
                device(2, kind = "client", createdAt = 50),
                device(3, kind = "agent", createdAt = 300),
                device(4, kind = "client", createdAt = 200),
            ),
        )
        val vm = DevicesViewModel(fake, onSelfRevoked = {})
        vm.refresh()
        assertEquals(listOf(4L, 2L, 3L, 1L), vm.devices.value.map { it.id })
        assertNull(vm.errorMessage.value)
    }

    @Test
    fun revoke_otherDevice_hitsAPIAndRefetches() = runBlocking {
        val fake = FakeDevicesProvider()
        val other = device(9, kind = "agent")
        fake.rosters = mutableListOf(listOf(other), emptyList())
        val vm = DevicesViewModel(fake, onSelfRevoked = {})
        vm.refresh()
        vm.revoke(other)
        assertEquals(listOf(9L), fake.revokedIDs)
        assertEquals(2, fake.devicesCalls)
        assertTrue(vm.devices.value.isEmpty())
    }

    @Test
    fun revoke_notFound_isTreatedAsAlreadyGone() = runBlocking {
        val fake = FakeDevicesProvider()
        val other = device(9)
        fake.rosters = mutableListOf(listOf(other), emptyList())
        fake.revokeError = JournalApiError.NotFound
        val vm = DevicesViewModel(fake, onSelfRevoked = {})
        vm.refresh()
        vm.revoke(other)
        assertNull(vm.errorMessage.value)
        assertEquals(2, fake.devicesCalls)
    }

    @Test
    fun revoke_self_firesCallbackInsteadOfRefetch() = runBlocking {
        val fake = FakeDevicesProvider()
        val me = device(1, isSelf = true)
        fake.rosters = mutableListOf(listOf(me))
        var selfRevoked = false
        val vm = DevicesViewModel(fake, onSelfRevoked = { selfRevoked = true })
        vm.refresh()
        vm.revoke(me)
        assertTrue(selfRevoked)
        assertEquals(1, fake.devicesCalls)
    }

    @Test
    fun revoke_success_refetchFails_rowStillDisappears() = runBlocking {
        val fake = FakeDevicesProvider()
        val other = device(9, kind = "agent")
        fake.rosters = mutableListOf(listOf(other))
        val vm = DevicesViewModel(fake, onSelfRevoked = {})
        vm.refresh()
        fake.devicesError = JournalApiError.Transport("offline")
        vm.revoke(other)
        assertEquals(listOf(9L), fake.revokedIDs)
        assertTrue(vm.devices.value.isEmpty())
        assertNotNull(vm.errorMessage.value)
    }

    @Test
    fun revoke_serverError_surfacesMessageAndKeepsRow() = runBlocking {
        val fake = FakeDevicesProvider()
        val other = device(9)
        fake.rosters = mutableListOf(listOf(other))
        fake.revokeError = JournalApiError.Http(500, "boom")
        var selfRevoked = false
        val vm = DevicesViewModel(fake, onSelfRevoked = { selfRevoked = true })
        vm.refresh()
        vm.revoke(other)
        assertNotNull(vm.errorMessage.value)
        assertFalse(selfRevoked)
        assertEquals(listOf(9L), vm.devices.value.map { it.id })
    }

    @Test
    fun displayHelpers_lastSeenNeverAndLag() {
        val never = device(1, kind = "agent", lastSeenAt = null)
        assertEquals("Never", never.lastSeenText())
        assertEquals("Up to date", never.lagText)
        val behind = device(2, kind = "client", lag = 123, lastSeenAt = 1_784_500_000_000)
        assertEquals("123 events behind", behind.lagText)
        assertNotEquals("Never", behind.lastSeenText())
        assertEquals("1 event behind", device(3, lag = 1).lagText)
    }

    @Test
    fun refresh_errorSurfacesMessage() = runBlocking {
        val failing = object : DevicesProviding {
            override suspend fun devices(): List<DeviceDTO> = throw JournalApiError.Transport("offline")
            override suspend fun revokeDevice(id: Long) {}
            override suspend fun pairPreview(code: String): PairPreview = throw JournalApiError.NotFound
            override suspend fun pairApprove(code: String, agentName: String) {}
        }
        val vm = DevicesViewModel(failing, onSelfRevoked = {})
        vm.refresh()
        assertNotNull(vm.errorMessage.value)
        assertFalse(vm.isLoading.value)
    }
}
