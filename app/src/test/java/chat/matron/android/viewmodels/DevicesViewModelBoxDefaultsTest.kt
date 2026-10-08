package chat.matron.android.viewmodels

import chat.matron.android.journal.BoxDefaults
import chat.matron.android.journal.BoxDefaultsUpdate
import chat.matron.android.journal.JournalApiError
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `DevicesViewModelBoxDefaultsTests`.
class DevicesViewModelBoxDefaultsTest {
    private val claudeOpus = BoxDefaults(agent = "claude", model = "opus", effort = "high")
    private val box = device(9, kind = "agent", name = "atlas", defaults = claudeOpus)

    private suspend fun loaded(fake: FakeDevicesProvider): DevicesViewModel {
        fake.rosters = mutableListOf(listOf(box, device(1, kind = "client"), device(10, kind = "agent", name = "old")))
        return DevicesViewModel(fake, onSelfRevoked = {}).also { it.refresh() }
    }

    private fun DevicesViewModel.defaultsOf(id: Long) = devices.value.first { it.id == id }.defaults

    @Test
    fun showsEditorOnlyForAgentBoxesTheJournalReportedDefaultsFor() = runBlocking {
        val vm = loaded(FakeDevicesProvider())
        assertTrue(vm.showsBoxDefaults(vm.devices.value.first { it.id == 9L }))
        assertFalse(vm.showsBoxDefaults(vm.devices.value.first { it.id == 1L }))
        // An agent from a journal predating box defaults carries none.
        assertFalse(vm.showsBoxDefaults(vm.devices.value.first { it.id == 10L }))
    }

    @Test
    fun pick_putsOneKey_andTheJournalsAnswerWins() = runBlocking {
        val fake = FakeDevicesProvider()
        val vm = loaded(fake)
        fake.defaultsResult = BoxDefaults(agent = "codex", model = null, effort = "high")
        vm.setBoxDefault(box, BoxDefaults.Key.AGENT, "codex")
        assertEquals(listOf(9L to mapOf(BoxDefaults.Key.AGENT to "codex")), fake.defaultsPuts)
        assertEquals(BoxDefaults(agent = "codex", model = null, effort = "high"), vm.defaultsOf(9))
        assertNull(vm.errorMessage.value)
    }

    @Test
    fun pick_showsAtOnce_withANewAgentClearingTheModel() = runBlocking {
        val fake = FakeDevicesProvider()
        val vm = loaded(fake)
        fake.holdDefaults = true
        val job = launch { vm.setBoxDefault(box, BoxDefaults.Key.AGENT, "codex") }
        while (fake.defaultsPuts.isEmpty()) yield()
        assertEquals(BoxDefaults(agent = "codex", model = null, effort = "high"), vm.defaultsOf(9))
        fake.releaseDefaults()
        job.join()
    }

    @Test
    fun unchangedPick_isNotSent() = runBlocking {
        val fake = FakeDevicesProvider()
        val vm = loaded(fake)
        vm.setBoxDefault(box, BoxDefaults.Key.MODEL, "opus")
        assertTrue(fake.defaultsPuts.isEmpty())
    }

    @Test
    fun refusal_revertsAndSaysWhy() = runBlocking {
        val fake = FakeDevicesProvider()
        val vm = loaded(fake)
        fake.defaultsError = JournalApiError.Http(400, "bad_model")
        vm.setBoxDefault(box, BoxDefaults.Key.MODEL, "sonnet")
        assertEquals(claudeOpus, vm.defaultsOf(9))
        assertEquals(
            "Couldn't save the default model for atlas — that isn't a model name the journal accepts.",
            vm.errorMessage.value,
        )
        assertTrue(vm.boxDefaultsSupported.value)
    }

    @Test
    fun notFound_hidesTheEditor() = runBlocking {
        val fake = FakeDevicesProvider()
        val vm = loaded(fake)
        fake.defaultsError = JournalApiError.NotFound
        vm.setBoxDefault(box, BoxDefaults.Key.EFFORT, "low")
        assertEquals(claudeOpus, vm.defaultsOf(9))
        assertFalse(vm.boxDefaultsSupported.value)
        assertFalse(vm.showsBoxDefaults(vm.devices.value.first { it.id == 9L }))
        assertEquals("This journal can't set defaults for atlas.", vm.errorMessage.value)
    }

    @Test
    fun aFrameLandingMidPut_winsOverTheStaleAnswerAndItsRevert() = runBlocking {
        val fake = FakeDevicesProvider()
        val vm = loaded(fake)
        fake.holdDefaults = true
        fake.defaultsError = JournalApiError.Http(400, "bad_effort")
        val job = launch { vm.setBoxDefault(box, BoxDefaults.Key.EFFORT, "low") }
        while (fake.defaultsPuts.isEmpty()) yield()
        val fromElsewhere = BoxDefaults(agent = "codex", model = "gpt-5.1-codex", effort = "xhigh")
        vm.apply(BoxDefaultsUpdate(9, fromElsewhere))
        fake.releaseDefaults()
        job.join()
        // The revert is skipped; the failure is still reported.
        assertEquals(fromElsewhere, vm.defaultsOf(9))
        assertEquals(
            "Couldn't save the default effort for atlas — the journal doesn't know that effort level.",
            vm.errorMessage.value,
        )
    }

    @Test
    fun listen_appliesLiveFrames_andIgnoresUnknownBoxes() = runBlocking {
        val fake = FakeDevicesProvider()
        val vm = loaded(fake)
        val job = launch { vm.listenForBoxDefaults() }
        while (fake.boxDefaultsFrames.subscriptionCount.value == 0) yield()
        fake.boxDefaultsFrames.emit(BoxDefaultsUpdate(9, BoxDefaults(agent = "codex")))
        fake.boxDefaultsFrames.emit(BoxDefaultsUpdate(77, BoxDefaults(agent = "claude")))
        while (vm.defaultsOf(9) != BoxDefaults(agent = "codex")) yield()
        assertEquals(listOf(9L, 1L, 10L).sorted(), vm.devices.value.map { it.id }.sorted())
        job.cancel()
    }

    /// Cursor Bugbot on PR 94: one-key PUTs racing per box let the agent
    /// change's server-side model clear wipe a model picked right after.
    /// Picks made while a save is on the wire queue and go together next.
    @Test
    fun picksDuringASave_queueAndCoalesce_soAnAgentChangeCarriesItsModel() = runBlocking {
        val fake = FakeDevicesProvider()
        val vm = loaded(fake)
        fake.holdDefaults = true
        val first = launch { vm.setBoxDefault(box, BoxDefaults.Key.EFFORT, "low") }
        while (fake.defaultsPuts.isEmpty()) yield()
        // Made while the effort save is on the wire: no PUT of their own yet.
        val agent = launch { vm.setBoxDefault(box, BoxDefaults.Key.AGENT, "codex") }
        val model = launch { vm.setBoxDefault(vm.devices.value.first { it.id == 9L }, BoxDefaults.Key.MODEL, "gpt-5.1-codex") }
        agent.join(); model.join()
        assertEquals(1, fake.defaultsPuts.size)
        assertEquals(BoxDefaults("codex", "gpt-5.1-codex", "low"), vm.defaultsOf(9))
        fake.holdDefaults = false
        fake.releaseDefaults()
        first.join()
        assertEquals(
            listOf(
                9L to mapOf(BoxDefaults.Key.EFFORT to "low"),
                9L to mapOf(BoxDefaults.Key.AGENT to "codex", BoxDefaults.Key.MODEL to "gpt-5.1-codex"),
            ),
            fake.defaultsPuts,
        )
        assertEquals(BoxDefaults("codex", "gpt-5.1-codex", "low"), vm.defaultsOf(9))
    }

    /// A model queued for the old agent goes when the agent changes after
    /// it — the journal would clear it anyway, and sending it would keep it.
    @Test
    fun anAgentChangeQueuedAfterAModelDropsThatModel() = runBlocking {
        val fake = FakeDevicesProvider()
        val vm = loaded(fake)
        fake.holdDefaults = true
        val first = launch { vm.setBoxDefault(box, BoxDefaults.Key.EFFORT, "low") }
        while (fake.defaultsPuts.isEmpty()) yield()
        vm.setBoxDefault(box, BoxDefaults.Key.MODEL, "sonnet")
        vm.setBoxDefault(box, BoxDefaults.Key.AGENT, "codex")
        fake.holdDefaults = false
        fake.releaseDefaults()
        first.join()
        assertEquals(9L to mapOf(BoxDefaults.Key.AGENT to "codex"), fake.defaultsPuts.last())
        assertEquals(BoxDefaults("codex", null, "low"), vm.defaultsOf(9))
    }

    /// Cursor Bugbot on PR 94: the `box_defaults` echo of the first `PUT`
    /// can land after the coalesced follow-up left; it must not overwrite
    /// that follow-up's pick or have its answer dropped.
    @Test
    fun anEchoOfAnEarlierPutDuringTheFollowUp_doesNotClobberIt() = runBlocking {
        val fake = FakeDevicesProvider()
        val vm = loaded(fake)
        fake.holdDefaults = true
        val save = launch { vm.setBoxDefault(box, BoxDefaults.Key.EFFORT, "low") }
        while (fake.defaultsPuts.isEmpty()) yield()
        vm.setBoxDefault(box, BoxDefaults.Key.MODEL, "sonnet") // queued
        fake.releaseDefaults()
        while (fake.defaultsPuts.size < 2) yield() // the follow-up is on the wire
        // The first PUT's echo, late: the model it names is older news.
        vm.apply(BoxDefaultsUpdate(9, BoxDefaults("claude", "opus", "low")))
        assertEquals(BoxDefaults("claude", "sonnet", "low"), vm.defaultsOf(9))
        fake.releaseDefaults()
        save.join()
        assertEquals(BoxDefaults("claude", "sonnet", "low"), vm.defaultsOf(9))
        // Once the save is done, frames show again.
        vm.apply(BoxDefaultsUpdate(9, BoxDefaults("codex")))
        assertEquals(BoxDefaults("codex"), vm.defaultsOf(9))
    }
}
