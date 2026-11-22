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
