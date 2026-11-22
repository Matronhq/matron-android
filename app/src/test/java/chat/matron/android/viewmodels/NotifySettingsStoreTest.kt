package chat.matron.android.viewmodels

import chat.matron.android.features.chat.ConvoNotifyMenuRow
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.NotifyChange
import chat.matron.android.journal.NotifyDeviceLevel
import chat.matron.android.journal.NotifyMuteDuration
import chat.matron.android.journal.NotifySettings
import chat.matron.android.journal.NotifySettingsProviding
import chat.matron.android.journal.NotifyView
import chat.matron.android.models.SyncConnectionState
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifySettingsStoreTest {
    private val coordinator = NotifyView(
        NotifySettings(NotifySettings.Mode.COORDINATOR, NotifySettings.preset(NotifySettings.Mode.COORDINATOR)!!, true, emptyList()),
        NotifyDeviceLevel.ALL,
    )

    private class Api(var stored: NotifyView) : NotifySettingsProviding {
        var getError: Throwable? = null
        var putError: Throwable? = null
        /// When set, the next PUT waits on it before answering.
        var gate: CompletableDeferred<Unit>? = null
        val puts = mutableListOf<NotifyChange>()
        override suspend fun notifySettings(): NotifyView { getError?.let { throw it }; return stored }
        override suspend fun updateNotify(change: NotifyChange): NotifyView {
            puts += change
            gate?.let { gate = null; it.await() }
            putError?.let { throw it }
            stored = change.appliedTo(stored)
            return stored
        }
    }

    private fun store(api: Api, now: Long = 1_000L, updates: MutableSharedFlow<NotifySettings> = MutableSharedFlow()) =
        NotifySettingsStore(api, { updates }, { emptyFlow<SyncConnectionState>() }, now = { now }, dispatcher = Dispatchers.Unconfined)

    @Test
    fun aChangeShowsAtOnceAndTheAnswerStands() = runBlocking {
        val api = Api(coordinator); val store = store(api)
        store.refresh()
        assertEquals(coordinator, store.view.value)
        val gate = CompletableDeferred<Unit>().also { api.gate = it }
        val write = launch { store.setMode(NotifySettings.Mode.ALL) }
        waitUntil { api.puts.isNotEmpty() }
        assertEquals("optimistic", NotifySettings.Mode.ALL, store.view.value!!.settings.mode)
        gate.complete(Unit); write.join()
        assertEquals(NotifySettings.Mode.ALL, store.view.value!!.settings.mode)
        assertNull(store.error.value)
    }

    @Test
    fun aRefusedChangeIsTakenBack() = runBlocking {
        val api = Api(coordinator).apply { putError = JournalApiError.Transport("offline") }
        val store = store(api)
        store.refresh()
        store.setEvent(NotifySettings.Event.ROOMS, true)
        assertEquals(coordinator, store.view.value)
        assertNotNull(store.error.value)
    }

    @Test
    fun promptsCannotBeSwitchedOff() = runBlocking {
        val api = Api(coordinator); val store = store(api)
        store.refresh()
        store.setEvent(NotifySettings.Event.PROMPTS, false)
        assertTrue(api.puts.isEmpty())
    }

    @Test
    fun aFailedWriteKeepsAnotherInFlightWrite() = runBlocking {
        val api = Api(coordinator); val store = store(api)
        store.refresh()
        val gate = CompletableDeferred<Unit>().also { api.gate = it }
        val first = launch { store.setDeviceLevel(NotifyDeviceLevel.OFF) }
        waitUntil { api.puts.size == 1 }
        api.putError = JournalApiError.Transport("offline")
        store.setLevel(NotifySettings.ConvoLevel.NONE, "c-alice")
        assertEquals("the failed level is gone", ConvoNotifyState(), store.state("c-alice"))
        assertEquals("the other write still shows", NotifyDeviceLevel.OFF, store.view.value!!.deviceLevel)
        api.putError = null
        gate.complete(Unit); first.join()
        assertEquals(NotifyDeviceLevel.OFF, store.view.value!!.deviceLevel)
    }

    @Test
    fun aLiveFrameKeepsThisDevicesLevelAndAPendingWrite() = runBlocking {
        val api = Api(coordinator.copy(deviceLevel = NotifyDeviceLevel.NEEDS_ME))
        val frames = MutableSharedFlow<NotifySettings>()
        val store = store(api, updates = frames)
        store.start()
        store.refresh()
        val gate = CompletableDeferred<Unit>().also { api.gate = it }
        val write = launch { store.setLevel(NotifySettings.ConvoLevel.NEEDS_ME, "c-bob") }
        waitUntil { api.puts.isNotEmpty() }
        frames.emit(coordinator.settings.copy(mode = NotifySettings.Mode.ALL))
        assertEquals(NotifySettings.Mode.ALL, store.view.value!!.settings.mode)
        assertEquals(NotifyDeviceLevel.NEEDS_ME, store.view.value!!.deviceLevel)
        assertEquals(NotifySettings.ConvoLevel.NEEDS_ME, store.state("c-bob").level)
        gate.complete(Unit); write.join()
        store.stop()
    }

    @Test
    fun aJournalWithoutTheRouteSaysSo() = runBlocking {
        val store = store(Api(coordinator).apply { getError = JournalApiError.NotFound })
        store.refresh()
        assertEquals(false, store.isSupported.value)
        assertNull(store.view.value)
    }

    @Test
    fun aLapsedMuteNoLongerCounts() = runBlocking {
        val api = Api(coordinator.copy(settings = coordinator.settings.copy(convos = listOf(
            NotifySettings.ConvoOverride("c-alice", muteUntil = 5_000),
            NotifySettings.ConvoOverride("c-bob", muteUntil = 500),
        ))))
        val store = store(api, now = 1_000)
        store.refresh()
        assertTrue(store.state("c-alice").isSilenced)
        assertEquals(ConvoNotifyState(), store.state("c-bob"))
        assertEquals(listOf("c-alice"), store.activeOverrides().map { it.convoID })
        assertFalse(store.state("c-alice", nowMs = 6_000).isSilenced)
    }

    @Test
    fun muteCountsFromNow() = runBlocking {
        val api = Api(coordinator); val store = store(api, now = 1_000)
        store.refresh()
        store.mute("c-alice", NotifyMuteDuration.ONE_HOUR)
        assertEquals(NotifyChange.ConvoMute("c-alice", 1_000 + 3_600_000), api.puts.single())
        store.unmute("c-alice")
        assertEquals(ConvoNotifyState(), store.state("c-alice"))
    }

    @Test
    fun summariesAndMenuRows() {
        val zone = ZoneId.of("UTC")
        val now = LocalDateTime.of(2026, 3, 9, 10, 0).atZone(zone).toInstant().toEpochMilli()
        val later = LocalDateTime.of(2026, 3, 9, 14, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("14:00", NotifySettingsTitles.time(later, now, zone, Locale.UK))
        assertEquals("Tue 08:00", NotifySettingsTitles.time(later + 18 * 3_600_000, now, zone, Locale.UK))
        assertEquals(
            "Needs me · muted until ${NotifySettingsTitles.time(later, now, zone)}",
            NotifySettingsTitles.summary(ConvoNotifyState(NotifySettings.ConvoLevel.NEEDS_ME, later), now, zone),
        )
        assertEquals(
            "Muted until ${NotifySettingsTitles.time(later, now, zone)}",
            NotifySettingsTitles.summary(ConvoNotifyState(mutedUntil = later), now, zone),
        )

        val plain = ConvoNotifyMenuRow.rows(ConvoNotifyState(), now)
        assertEquals(
            listOf("All", "Needs me", "None", "Default (follow mode)", "Mute for 1 hour", "Mute for 8 hours", "Mute until tomorrow 08:00"),
            plain.map {
                when (it) {
                    is ConvoNotifyMenuRow.Level -> it.title
                    is ConvoNotifyMenuRow.Unmute -> it.title
                    is ConvoNotifyMenuRow.Mute -> it.title
                }
            },
        )
        assertTrue((plain[3] as ConvoNotifyMenuRow.Level).selected)
        val muted = ConvoNotifyMenuRow.rows(ConvoNotifyState(NotifySettings.ConvoLevel.NONE, later), now)
        assertTrue((muted[2] as ConvoNotifyMenuRow.Level).selected)
        assertTrue(muted[4] is ConvoNotifyMenuRow.Unmute)
    }
}

class NotifySettingsStoreOrderingTest {
    private val base = NotifyView(
        NotifySettings(NotifySettings.Mode.COORDINATOR, NotifySettings.preset(NotifySettings.Mode.COORDINATOR)!!, true, emptyList()),
        NotifyDeviceLevel.OFF,
    )

    /// Answers each call only when its own gate opens, so tests choose the order.
    private class GatedApi(val base: NotifyView) : NotifySettingsProviding {
        val gets = mutableListOf<CompletableDeferred<NotifyView>>()
        val puts = mutableListOf<CompletableDeferred<NotifyView>>()
        override suspend fun notifySettings(): NotifyView = CompletableDeferred<NotifyView>().also { gets += it }.await()
        override suspend fun updateNotify(change: NotifyChange): NotifyView =
            CompletableDeferred<NotifyView>().also { puts += it }.await()
    }

    private fun store(api: GatedApi, frames: MutableSharedFlow<NotifySettings>) =
        NotifySettingsStore(api, { frames }, { emptyFlow<SyncConnectionState>() }, now = { 1_000L }, dispatcher = Dispatchers.Unconfined)

    @Test
    fun anOlderPutAnswerDoesNotOverwriteANewerOne() = runBlocking {
        val api = GatedApi(base); val store = store(api, MutableSharedFlow())
        val get = launch { store.refresh() }
        waitUntil { api.gets.size == 1 }
        api.gets[0].complete(base); get.join()
        val first = launch { store.setMode(NotifySettings.Mode.ALL) }
        waitUntil { api.puts.size == 1 }
        val second = launch { store.setMode(NotifySettings.Mode.CUSTOM) }
        waitUntil { api.puts.size == 2 }
        val afterAll = NotifyChange.SetMode(NotifySettings.Mode.ALL).appliedTo(base)
        val afterBoth = NotifyChange.SetMode(NotifySettings.Mode.CUSTOM).appliedTo(afterAll)
        api.puts[1].complete(afterBoth); second.join()
        assertEquals("the older write is not replayed over the newer answer", NotifySettings.Mode.CUSTOM, store.view.value!!.settings.mode)
        api.puts[0].complete(afterAll)
        // The older answer is dropped and settled by a re-read.
        waitUntil { api.gets.size == 2 }
        assertEquals("the newer answer stands", NotifySettings.Mode.CUSTOM, store.view.value!!.settings.mode)
        api.gets[1].complete(afterBoth); first.join()
        assertEquals(NotifySettings.Mode.CUSTOM, store.view.value!!.settings.mode)
    }

    @Test
    fun aFrameBeforeTheFirstGetKeepsTheGetsDeviceLevel() = runBlocking {
        val api = GatedApi(base); val frames = MutableSharedFlow<NotifySettings>()
        val store = store(api, frames)
        store.start()
        val get = launch { store.refresh() }
        waitUntil { api.gets.size == 1 }
        frames.emit(base.settings.copy(mode = NotifySettings.Mode.ALL))
        api.gets[0].complete(base); get.join()
        assertEquals("the frame is newer", NotifySettings.Mode.ALL, store.view.value!!.settings.mode)
        assertEquals("the level only the GET knows", NotifyDeviceLevel.OFF, store.view.value!!.deviceLevel)
        store.stop()
    }

    @Test
    fun anOlderGetAnswerIsDropped() = runBlocking {
        val api = GatedApi(base); val store = store(api, MutableSharedFlow())
        val older = launch { store.refresh() }
        waitUntil { api.gets.size == 1 }
        val newer = launch { store.refresh() }
        waitUntil { api.gets.size == 2 }
        api.gets[1].complete(base.copy(settings = base.settings.copy(mode = NotifySettings.Mode.ALL))); newer.join()
        api.gets[0].complete(base); older.join()
        assertEquals(NotifySettings.Mode.ALL, store.view.value!!.settings.mode)
    }
}

class NotifySettingsStoreRereadTest {
    private val base = NotifyView(
        NotifySettings(NotifySettings.Mode.COORDINATOR, NotifySettings.preset(NotifySettings.Mode.COORDINATOR)!!, true, emptyList()),
        NotifyDeviceLevel.ALL,
    )

    @Test
    fun aReReadThatRacedASaveDoesNotRevertIt() = runBlocking {
        val gets = mutableListOf<CompletableDeferred<NotifyView>>()
        val puts = mutableListOf<CompletableDeferred<NotifyView>>()
        val api = object : NotifySettingsProviding {
            override suspend fun notifySettings() = CompletableDeferred<NotifyView>().also { gets += it }.await()
            override suspend fun updateNotify(change: NotifyChange) = CompletableDeferred<NotifyView>().also { puts += it }.await()
        }
        val store = NotifySettingsStore(api, { emptyFlow() }, { emptyFlow<SyncConnectionState>() }, now = { 1_000L }, dispatcher = Dispatchers.Unconfined)
        val first = launch { store.refresh() }
        waitUntil { gets.size == 1 }
        gets[0].complete(base); first.join()

        val save = launch { store.setMode(NotifySettings.Mode.ALL) }
        waitUntil { puts.size == 1 }
        // A reconnect re-read goes out after the save and reads the old state.
        val reread = launch { store.refresh() }
        waitUntil { gets.size == 2 }
        gets[1].complete(base); reread.join()
        assertEquals("the save still shows", NotifySettings.Mode.ALL, store.view.value!!.settings.mode)
        val saved = NotifyChange.SetMode(NotifySettings.Mode.ALL).appliedTo(base)
        puts[0].complete(saved)
        waitUntil { gets.size == 3 }
        gets[2].complete(saved); save.join()
        assertEquals("the follow-up read restores the save", NotifySettings.Mode.ALL, store.view.value!!.settings.mode)
    }
}

class NotifySettingsStoreReadDuringSaveTest {
    private val base = NotifyView(
        NotifySettings(NotifySettings.Mode.COORDINATOR, NotifySettings.preset(NotifySettings.Mode.COORDINATOR)!!, true, emptyList()),
        NotifyDeviceLevel.ALL,
    )

    @Test
    fun aReadThatPredatesAnAnsweredSaveIsReadAgain() = runBlocking {
        val gets = mutableListOf<CompletableDeferred<NotifyView>>()
        val puts = mutableListOf<CompletableDeferred<NotifyView>>()
        val api = object : NotifySettingsProviding {
            override suspend fun notifySettings() = CompletableDeferred<NotifyView>().also { gets += it }.await()
            override suspend fun updateNotify(change: NotifyChange) = CompletableDeferred<NotifyView>().also { puts += it }.await()
        }
        val store = NotifySettingsStore(api, { emptyFlow() }, { emptyFlow<SyncConnectionState>() }, now = { 1_000L }, dispatcher = Dispatchers.Unconfined)
        val first = launch { store.refresh() }
        waitUntil { gets.size == 1 }
        gets[0].complete(base); first.join()

        val save = launch { store.setMode(NotifySettings.Mode.ALL) }
        waitUntil { puts.size == 1 }
        val read = launch { store.refresh() }
        waitUntil { gets.size == 2 }
        val saved = NotifyChange.SetMode(NotifySettings.Mode.ALL).appliedTo(base)
        puts[0].complete(saved); save.join()
        // The read was answered from before the save.
        gets[1].complete(base)
        waitUntil { gets.size == 3 }
        assertEquals("the stale read is not taken", NotifySettings.Mode.ALL, store.view.value!!.settings.mode)
        gets[2].complete(saved); read.join()
        assertEquals(NotifySettings.Mode.ALL, store.view.value!!.settings.mode)
    }
}

class NotifySettingsStoreSupersededSaveTest {
    @Test
    fun aSupersededSaveDoesNotHideOlderPendingWrites() = runBlocking {
        val base = NotifyView(
            NotifySettings(NotifySettings.Mode.COORDINATOR, NotifySettings.preset(NotifySettings.Mode.COORDINATOR)!!, true, emptyList()),
            NotifyDeviceLevel.ALL,
        )
        val gets = mutableListOf<CompletableDeferred<NotifyView>>()
        val puts = mutableListOf<CompletableDeferred<NotifyView>>()
        val frames = MutableSharedFlow<NotifySettings>()
        val api = object : NotifySettingsProviding {
            override suspend fun notifySettings() = CompletableDeferred<NotifyView>().also { gets += it }.await()
            override suspend fun updateNotify(change: NotifyChange) = CompletableDeferred<NotifyView>().also { puts += it }.await()
        }
        val store = NotifySettingsStore(api, { frames }, { emptyFlow<SyncConnectionState>() }, now = { 1_000L }, dispatcher = Dispatchers.Unconfined)
        store.start()
        val first = launch { store.refresh() }
        waitUntil { gets.size == 1 }
        gets[0].complete(base); first.join()

        val older = launch { store.setDeviceLevel(NotifyDeviceLevel.OFF) }
        waitUntil { puts.size == 1 }
        val newer = launch { store.setMode(NotifySettings.Mode.ALL) }
        waitUntil { puts.size == 2 }
        // A live frame lands first, so the newer save's answer is not taken.
        frames.emit(base.settings.copy(mode = NotifySettings.Mode.ALL))
        puts[1].complete(NotifyChange.SetMode(NotifySettings.Mode.ALL).appliedTo(base))
        waitUntil { gets.size == 2 }
        assertEquals("the older write still shows", NotifyDeviceLevel.OFF, store.view.value!!.deviceLevel)
        puts[0].complete(NotifyChange.SetDeviceLevel(NotifyDeviceLevel.OFF).appliedTo(base))
        // The journal now holds both; every follow-up read says so.
        val both = NotifyChange.SetMode(NotifySettings.Mode.ALL).appliedTo(base).copy(deviceLevel = NotifyDeviceLevel.OFF)
        waitUntil {
            gets.forEach { if (!it.isCompleted) it.complete(both) }
            older.isCompleted && newer.isCompleted
        }
        assertTrue(older.isCompleted && newer.isCompleted)
        assertEquals(NotifyDeviceLevel.OFF, store.view.value!!.deviceLevel)
        assertEquals(NotifySettings.Mode.ALL, store.view.value!!.settings.mode)
        store.stop()
    }
}

class NotifySettingsStoreLaterChoiceTest {
    @Test
    fun anOlderWriteDoesNotUndoALaterSavedChoice() = runBlocking {
        val base = NotifyView(
            NotifySettings(NotifySettings.Mode.COORDINATOR, NotifySettings.preset(NotifySettings.Mode.COORDINATOR)!!, true, emptyList()),
            NotifyDeviceLevel.ALL,
        )
        val gets = mutableListOf<CompletableDeferred<NotifyView>>()
        val puts = mutableListOf<CompletableDeferred<NotifyView>>()
        val frames = MutableSharedFlow<NotifySettings>()
        val api = object : NotifySettingsProviding {
            override suspend fun notifySettings() = CompletableDeferred<NotifyView>().also { gets += it }.await()
            override suspend fun updateNotify(change: NotifyChange) = CompletableDeferred<NotifyView>().also { puts += it }.await()
        }
        val store = NotifySettingsStore(api, { frames }, { emptyFlow<SyncConnectionState>() }, now = { 1_000L }, dispatcher = Dispatchers.Unconfined)
        store.start()
        val first = launch { store.refresh() }
        waitUntil { gets.size == 1 }
        gets[0].complete(base); first.join()

        val older = launch { store.setMode(NotifySettings.Mode.ALL) }
        waitUntil { puts.size == 1 }
        val newer = launch { store.setMode(NotifySettings.Mode.CUSTOM) }
        waitUntil { puts.size == 2 }
        val custom = NotifyChange.SetMode(NotifySettings.Mode.CUSTOM).appliedTo(NotifyChange.SetMode(NotifySettings.Mode.ALL).appliedTo(base))
        // The later save's own frame lands before its answer, so the answer is not taken.
        frames.emit(custom.settings)
        puts[1].complete(custom)
        waitUntil { gets.size == 2 }
        assertEquals("the later choice stands", NotifySettings.Mode.CUSTOM, store.view.value!!.settings.mode)
        // A level write is unrelated and still shows while in flight.
        val level = launch { store.setDeviceLevel(NotifyDeviceLevel.OFF) }
        waitUntil { puts.size == 3 }
        assertEquals(NotifyDeviceLevel.OFF, store.view.value!!.deviceLevel)
        puts[0].complete(custom)
        puts[2].complete(custom.copy(deviceLevel = NotifyDeviceLevel.OFF))
        val settled = custom.copy(deviceLevel = NotifyDeviceLevel.OFF)
        waitUntil {
            gets.forEach { if (!it.isCompleted) it.complete(settled) }
            older.isCompleted && newer.isCompleted && level.isCompleted
        }
        assertEquals(settled, store.view.value)
        store.stop()
    }
}
