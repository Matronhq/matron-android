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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NoticesSettingViewModelTest {
    private class Api : UserSettingsProviding {
        var stored = UserSettings(notices = true)
        var getError: Throwable? = null
        var patchError: Throwable? = null
        val patches = mutableListOf<Boolean>()
        override suspend fun settings(): UserSettings { getError?.let { throw it }; return stored }
        override suspend fun updateSettings(notices: Boolean): UserSettings {
            patches += notices
            patchError?.let { throw it }
            stored = UserSettings(notices)
            return stored
        }
    }

    @Test
    fun copy() {
        assertEquals("Send things I need to read to For you", NoticesSettingViewModel.TITLE)
        assertEquals(
            "Agents file things you should read as items with a Seen button, instead of leaving them in chat.",
            NoticesSettingViewModel.HELP,
        )
    }

    @Test
    fun loadsThenSavesThroughPatch() = runBlocking {
        val api = Api(); val frames = MutableSharedFlow<UserSettings>()
        val vm = NoticesSettingViewModel(api, { frames }, this)
        assertEquals(NoticesSettingViewModel.State.Loading, vm.state.value)
        vm.start()
        waitUntil { vm.state.value is NoticesSettingViewModel.State.Loaded }
        assertEquals(NoticesSettingViewModel.State.Loaded(true), vm.state.value)
        vm.setNotices(false)
        assertEquals(listOf(false), api.patches)
        assertEquals(NoticesSettingViewModel.State.Loaded(false), vm.state.value)
        vm.setNotices(false)
        assertEquals("no change, no request", 1, api.patches.size)
        vm.stop()
    }

    @Test
    fun aFailedSavePutsTheSwitchBack() = runBlocking {
        val api = Api().apply { patchError = JournalApiError.Transport("offline") }
        val vm = NoticesSettingViewModel(api, { MutableSharedFlow() }, this)
        vm.load()
        vm.setNotices(false)
        assertEquals(NoticesSettingViewModel.State.Loaded(true), vm.state.value)
        assertNotNull(vm.error.value)
        assertFalse(vm.isSaving.value)
    }

    @Test
    fun a404HidesTheSetting() = runBlocking {
        val api = Api().apply { getError = JournalApiError.NotFound }
        val vm = NoticesSettingViewModel(api, { MutableSharedFlow() }, this)
        vm.load()
        assertEquals(NoticesSettingViewModel.State.Unsupported, vm.state.value)
        vm.setNotices(false)
        assertEquals("nothing to save on a journal without the route", emptyList<Boolean>(), api.patches)
    }

    @Test
    fun followsLiveSettingsFrames() = runBlocking {
        val api = Api(); val frames = MutableSharedFlow<UserSettings>()
        val vm = NoticesSettingViewModel(api, { frames }, this)
        vm.start()
        waitUntil { vm.state.value is NoticesSettingViewModel.State.Loaded && frames.subscriptionCount.value == 1 }
        frames.emit(UserSettings(notices = false))
        waitUntil { vm.state.value == NoticesSettingViewModel.State.Loaded(false) }
        assertEquals("changed on another device", NoticesSettingViewModel.State.Loaded(false), vm.state.value)
        assertNull(vm.error.value)
        vm.stop()
    }

    /// The GET answers only when released, so a frame can land while it is in flight.
    private class SlowApi(val answer: CompletableDeferred<Result<UserSettings>>) : UserSettingsProviding {
        var getStarted = CompletableDeferred<Unit>()
        override suspend fun settings(): UserSettings { getStarted.complete(Unit); return answer.await().getOrThrow() }
        override suspend fun updateSettings(notices: Boolean) = UserSettings(notices)
    }

    @Test
    fun aFrameDuringTheGetIsNotOverwrittenByItsStaleAnswer() = runBlocking {
        val answer = CompletableDeferred<Result<UserSettings>>()
        val api = SlowApi(answer); val frames = MutableSharedFlow<UserSettings>()
        val vm = NoticesSettingViewModel(api, { frames }, this)
        vm.start()
        api.getStarted.await()
        waitUntil { frames.subscriptionCount.value == 1 }
        frames.emit(UserSettings(notices = false))
        waitUntil { vm.state.value == NoticesSettingViewModel.State.Loaded(false) }
        answer.complete(Result.success(UserSettings(notices = true)))
        repeat(10) { yield() } // let the GET's continuation run
        assertEquals("the GET started before the frame", NoticesSettingViewModel.State.Loaded(false), vm.state.value)
        vm.stop()
    }

    @Test
    fun a404AfterAFrameLandedDoesNotHideTheSetting() = runBlocking {
        val answer = CompletableDeferred<Result<UserSettings>>()
        val api = SlowApi(answer)
        val vm = NoticesSettingViewModel(api, { MutableSharedFlow() }, this)
        val load = launch { vm.load() }
        api.getStarted.await()
        vm.apply(UserSettings(notices = true))
        answer.complete(Result.failure(JournalApiError.NotFound))
        load.join()
        assertEquals(NoticesSettingViewModel.State.Loaded(true), vm.state.value)
    }

    @Test
    fun aGetWithNothingNewerStillLands() = runBlocking {
        val answer = CompletableDeferred<Result<UserSettings>>()
        val api = SlowApi(answer)
        val vm = NoticesSettingViewModel(api, { MutableSharedFlow() }, this)
        vm.apply(UserSettings(notices = false))
        val load = launch { vm.load() }
        api.getStarted.await()
        answer.complete(Result.success(UserSettings(notices = true)))
        load.join()
        assertEquals(NoticesSettingViewModel.State.Loaded(true), vm.state.value)
    }

    /// The PATCH answers only when released.
    private class SlowPatchApi(val answer: CompletableDeferred<Result<UserSettings>>) : UserSettingsProviding {
        val patchStarted = CompletableDeferred<Unit>()
        override suspend fun settings() = UserSettings(notices = true)
        override suspend fun updateSettings(notices: Boolean): UserSettings {
            patchStarted.complete(Unit); return answer.await().getOrThrow()
        }
    }

    @Test
    fun aFrameDuringThePatchIsNotOverwrittenByItsAnswer() = runBlocking {
        val answer = CompletableDeferred<Result<UserSettings>>()
        val api = SlowPatchApi(answer)
        val vm = NoticesSettingViewModel(api, { MutableSharedFlow() }, this)
        vm.load()
        val save = launch { vm.setNotices(false) }
        api.patchStarted.await()
        vm.apply(UserSettings(notices = true)) // changed back on another device meanwhile
        answer.complete(Result.success(UserSettings(notices = false)))
        save.join()
        assertEquals(NoticesSettingViewModel.State.Loaded(true), vm.state.value)
        assertFalse(vm.isSaving.value)
    }

    @Test
    fun aFailedPatchDoesNotRevertOverANewerFrame() = runBlocking {
        val answer = CompletableDeferred<Result<UserSettings>>()
        val api = SlowPatchApi(answer)
        val vm = NoticesSettingViewModel(api, { MutableSharedFlow() }, this)
        vm.load()
        val save = launch { vm.setNotices(false) }
        api.patchStarted.await()
        vm.apply(UserSettings(notices = false)) // the journal's own frame for this change
        answer.complete(Result.failure(JournalApiError.Transport("response lost")))
        save.join()
        assertEquals("the frame already confirmed it", NoticesSettingViewModel.State.Loaded(false), vm.state.value)
        assertNull(vm.error.value)
    }
}
