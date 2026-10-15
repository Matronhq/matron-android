package chat.matron.android.viewmodels

import chat.matron.android.journal.CoordinatorConsentProviding
import chat.matron.android.journal.JournalApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinatorConsentSettingViewModelTest {
    private class Api(var stored: Boolean? = true) : CoordinatorConsentProviding {
        var getError: Throwable? = null
        var putError: Throwable? = null
        var getGate: CompletableDeferred<Unit>? = null
        val puts = mutableListOf<Boolean>()
        override suspend fun coordinatorConsent(): Boolean? {
            getGate?.await()
            getError?.let { throw it }
            return stored
        }
        override suspend fun setCoordinatorConsent(enabled: Boolean): Boolean? {
            puts += enabled
            putError?.let { throw it }
            stored = enabled
            return stored
        }
    }

    @Test
    fun copy() {
        assertEquals("Let the Coordinator approve chats and spawns", CoordinatorConsentSettingViewModel.TITLE)
    }

    @Test
    fun loadsThenSavesThroughPut() = runBlocking {
        val api = Api()
        val vm = CoordinatorConsentSettingViewModel(api, this)
        assertNull("hidden until the journal answers", vm.enabled.value)
        vm.load()
        assertEquals(true, vm.enabled.value)
        vm.setEnabled(false)
        assertEquals(listOf(false), api.puts)
        assertEquals(false, vm.enabled.value)
        vm.setEnabled(false)
        assertEquals("no change, no request", 1, api.puts.size)
    }

    @Test
    fun aJournalWithoutTheSwitchHidesIt() = runBlocking {
        val api = Api(stored = null)
        val vm = CoordinatorConsentSettingViewModel(api, this)
        vm.load()
        assertNull(vm.enabled.value)
        vm.setEnabled(false)
        assertEquals("nothing to write without a loaded value", emptyList<Boolean>(), api.puts)
    }

    @Test
    fun aFailedLoadKeepsItHidden() = runBlocking {
        val api = Api().apply { getError = JournalApiError.Transport("offline") }
        val vm = CoordinatorConsentSettingViewModel(api, this)
        vm.load()
        assertNull(vm.enabled.value)
    }

    @Test
    fun aFailedSavePutsTheSwitchBackAndSaysWhy() = runBlocking {
        val api = Api().apply { putError = JournalApiError.Transport("offline") }
        val vm = CoordinatorConsentSettingViewModel(api, this)
        vm.load()
        vm.setEnabled(false)
        assertEquals(true, vm.enabled.value)
        assertNotNull(vm.error.value)
        assertEquals(false, vm.isSaving.value)

        api.putError = null
        vm.setEnabled(false)
        assertEquals(false, vm.enabled.value)
        assertNull("the next write clears the error", vm.error.value)
    }

    @Test
    fun aGetThatStartedBeforeATapDoesNotUndoIt() = runBlocking {
        val api = Api()
        val vm = CoordinatorConsentSettingViewModel(api, this)
        vm.load()
        val gate = CompletableDeferred<Unit>()
        api.getGate = gate
        val slowLoad = launch { vm.load() }
        yield()
        vm.setEnabled(false)
        api.stored = true // the stale answer the slow GET would bring back
        gate.complete(Unit)
        slowLoad.join()
        assertEquals(false, vm.enabled.value)
    }
}
