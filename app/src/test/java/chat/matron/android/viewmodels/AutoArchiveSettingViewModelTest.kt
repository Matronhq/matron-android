package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.UserSettings
import chat.matron.android.journal.UserSettingsProviding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoArchiveSettingViewModelTest {
    /// A journal whose PATCH answers can be held back one at a time.
    private class Api(var stored: UserSettings = UserSettings(notices = true, autoArchiveDays = 30)) : UserSettingsProviding {
        val patches = mutableListOf<Int>()
        val answers = ArrayDeque<CompletableDeferred<Result<Unit>>>()
        var hold = false
        override suspend fun settings(): UserSettings = stored
        override suspend fun updateSettings(notices: Boolean): UserSettings = stored.copy(notices = notices)
        override suspend fun updateAutoArchiveDays(days: Int): UserSettings {
            patches += days
            if (hold) {
                val answer = CompletableDeferred<Result<Unit>>().also { answers.addLast(it) }
                answer.await().getOrThrow()
            }
            stored = stored.copy(autoArchiveDays = days)
            return stored
        }
    }

    private suspend fun waitFor(cond: () -> Boolean) {
        repeat(200) { if (cond()) return; yield(); kotlinx.coroutines.delay(5) }
        error("timed out")
    }

    @Test
    fun labelsAndChoices() {
        assertEquals("Off", AutoArchiveSettingViewModel.label(0))
        assertEquals("After 1 day", AutoArchiveSettingViewModel.label(1))
        assertEquals("After 30 days", AutoArchiveSettingViewModel.label(30))
        assertEquals(listOf(0, 14, 30, 90), AutoArchiveSettingViewModel.CHOICES)
    }

    @Test
    fun loadsSavesAndIsHiddenOnAJournalWithoutIt() = runBlocking {
        val api = Api()
        val vm = AutoArchiveSettingViewModel(api, { MutableSharedFlow() }, this)
        vm.load()
        assertEquals(AutoArchiveSettingViewModel.State.Loaded(30), vm.state.value)
        vm.setDays(90)
        assertEquals(listOf(90), api.patches)
        assertEquals(AutoArchiveSettingViewModel.State.Loaded(90), vm.state.value)

        val older = AutoArchiveSettingViewModel(Api(UserSettings(notices = true)), { MutableSharedFlow() }, this)
        older.load()
        assertEquals(AutoArchiveSettingViewModel.State.Unsupported, older.state.value)
        val noRoute = object : UserSettingsProviding {
            override suspend fun settings(): UserSettings = throw JournalApiError.NotFound
            override suspend fun updateSettings(notices: Boolean): UserSettings = throw JournalApiError.NotFound
        }
        val none = AutoArchiveSettingViewModel(noRoute, { MutableSharedFlow() }, this)
        none.load()
        assertEquals(AutoArchiveSettingViewModel.State.Unsupported, none.state.value)
    }

    @Test
    fun picksGoOutInOrderAndAFailureGoesBackToTheConfirmedValue() = runBlocking {
        val api = Api().apply { hold = true }
        val vm = AutoArchiveSettingViewModel(api, { MutableSharedFlow() }, this)
        vm.load()
        val first = launch { vm.setDays(14) }
        waitFor { api.answers.size == 1 }
        val second = launch { vm.setDays(90) }
        yield()
        assertEquals("the second waits for the first", listOf(14), api.patches)
        assertEquals(AutoArchiveSettingViewModel.State.Loaded(90), vm.state.value)
        api.answers.removeFirst().complete(Result.success(Unit))
        first.join()
        waitFor { api.answers.size == 1 }
        assertEquals(listOf(14, 90), api.patches)
        api.answers.removeFirst().complete(Result.failure(JournalApiError.Transport("offline")))
        second.join()
        assertEquals("back to what the journal confirmed", AutoArchiveSettingViewModel.State.Loaded(14), vm.state.value)
        assertEquals("Couldn't save the setting: Couldn't reach the server — offline", vm.error.value)
    }

    @Test
    fun aFrameWithoutTheKeyLeavesAPendingPickAloneAndANewerValueWins() = runBlocking {
        val api = Api().apply { hold = true }
        val vm = AutoArchiveSettingViewModel(api, { MutableSharedFlow() }, this)
        vm.load()
        val save = launch { vm.setDays(90) }
        waitFor { api.answers.size == 1 }
        vm.apply(UserSettings(notices = false))
        assertEquals(AutoArchiveSettingViewModel.State.Loaded(90), vm.state.value)
        api.answers.removeFirst().complete(Result.failure(JournalApiError.Transport("offline")))
        save.join()
        assertEquals("a notices-only frame did not stop the rollback", AutoArchiveSettingViewModel.State.Loaded(30), vm.state.value)

        val again = launch { vm.setDays(14) }
        waitFor { api.answers.size == 1 }
        vm.apply(UserSettings(notices = true, autoArchiveDays = 0)) // another device's change
        api.answers.removeFirst().complete(Result.failure(JournalApiError.Transport("offline")))
        again.join()
        assertEquals("the journal's newer value stands", AutoArchiveSettingViewModel.State.Loaded(0), vm.state.value)
        assertNull(vm.error.value)
    }
}
