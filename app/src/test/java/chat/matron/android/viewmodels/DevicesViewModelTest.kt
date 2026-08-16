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
            override suspend fun renameDevice(id: Long, name: String): DeviceDTO =
                throw JournalApiError.Transport("offline")
            override suspend fun pairPreview(code: String): PairPreview = throw JournalApiError.NotFound
            override suspend fun pairApprove(code: String, agentName: String) {}
        }
        val vm = DevicesViewModel(failing, onSelfRevoked = {})
        vm.refresh()
        assertNotNull(vm.errorMessage.value)
        assertFalse(vm.isLoading.value)
    }

    /// Ports matron-apple's `test_rename_updatesTheRosterAndSurfacesFailures`.
    @Test
    fun rename_updatesTheRosterAndSurfacesFailures() = runBlocking {
        val fake = FakeDevicesProvider()
        fake.rosters = mutableListOf(
            listOf(device(7, kind = "agent", name = "dev-9", createdAt = 1)),
        )
        val vm = DevicesViewModel(fake, onSelfRevoked = {})
        vm.refresh()

        vm.rename(vm.devices.value[0], to = "dev-y")
        assertEquals(listOf(7L to "dev-y"), fake.renamed)
        assertEquals("dev-y", vm.devices.value.first().name)
        assertNull(vm.errorMessage.value)

        // A server refusal leaves the roster alone and explains itself.
        fake.renameError = JournalApiError.Forbidden
        vm.rename(vm.devices.value[0], to = "dev-z")
        assertEquals("dev-y", vm.devices.value.first().name)
        assertTrue(vm.errorMessage.value?.contains("dev-y") == true)
    }

    /// A rename that lands but whose follow-up roster re-fetch fails must
    /// still show the new name — the server HAS renamed the device, and the
    /// echo carries the sanitised name to show meanwhile.
    @Test
    fun rename_succeeds_refetchFails_newNameStillShows() = runBlocking {
        val fake = FakeDevicesProvider()
        fake.rosters = mutableListOf(
            listOf(device(7, kind = "agent", name = "dev-9", createdAt = 1)),
        )
        val vm = DevicesViewModel(fake, onSelfRevoked = {})
        vm.refresh()

        fake.devicesError = JournalApiError.Transport("offline")
        vm.rename(vm.devices.value[0], to = "dev-y")
        assertEquals(listOf(7L to "dev-y"), fake.renamed)
        assertEquals("dev-y", vm.devices.value.first().name)
        assertNotNull(vm.errorMessage.value)
    }

    /// Ports matron-apple's `test_validateName_matchesTheServerRules`: mirrors
    /// the journal's own check so the user gets told before a 400.
    @Test
    fun validateName_matchesTheServerRules() {
        assertNull(DevicesViewModel.validate("dev-y"))
        assertNull(DevicesViewModel.validate("y".repeat(40)))
        assertNotNull(DevicesViewModel.validate(""))
        assertNotNull(DevicesViewModel.validate("   "))
        assertNotNull(DevicesViewModel.validate("y".repeat(41)))
    }
}
