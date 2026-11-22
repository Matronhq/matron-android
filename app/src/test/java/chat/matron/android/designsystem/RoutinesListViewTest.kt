package chat.matron.android.designsystem

import chat.matron.android.models.Routine
import chat.matron.android.models.RoutineTrigger
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutinesListViewTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-20T10:00:00Z")
    private fun routine(
        enabled: Boolean = true, nextAt: Instant? = null, lastFiredAt: Instant? = null, outcome: String? = null,
        trigger: RoutineTrigger? = null,
    ) = Routine(
        id = "rt_1", name = "a", title = "A", schedule = if (trigger == null) "0 9 * * *" else null, trigger = trigger,
        prompt = "p", enabled = enabled, nextAt = nextAt, lastFiredAt = lastFiredAt, lastOutcome = outcome,
        createdAt = now, updatedAt = now,
    )

    @Test
    fun statePrecedence() {
        assertEquals(RoutinesListState.UNSUPPORTED, routinesListState(RoutinesListModel(null, isSupported = false, isLoading = false)))
        assertEquals(RoutinesListState.LOADING, routinesListState(RoutinesListModel(null, isSupported = true, isLoading = true)))
        assertEquals(RoutinesListState.LOAD_FAILED, routinesListState(RoutinesListModel(null, true, false, loadError = "x")))
        // Try again after that failure: loading, not the old error.
        assertEquals(RoutinesListState.LOADING, routinesListState(RoutinesListModel(null, true, true, loadError = "x")))
        assertEquals(RoutinesListState.EMPTY, routinesListState(RoutinesListModel(emptyList(), true, false)))
        assertEquals(RoutinesListState.POPULATED, routinesListState(RoutinesListModel(listOf(routine()), true, false, loadError = "x")))
    }

    @Test
    fun statusLines() {
        val next = Instant.parse("2026-10-20T18:30:00Z")
        val last = Instant.parse("2026-10-20T08:00:00Z")
        assertEquals(
            listOf("Next: today at 18:30", "Last: 2 h ago · Delivered"),
            routineStatusLines(routine(nextAt = next, lastFiredAt = last, outcome = "applied now"), now, zone, Locale.UK),
        )
        // Paused: no next fire. Failed: the outcome is its own line.
        assertEquals(
            listOf("Last: 2 h ago"),
            routineStatusLines(routine(enabled = false, nextAt = next, lastFiredAt = last, outcome = "missed"), now, zone, Locale.UK),
        )
        assertEquals(listOf("Never fired"), routineStatusLines(routine(trigger = RoutineTrigger.DiskUnder(20), nextAt = next), now, zone, Locale.UK))
    }

    @Test
    fun whenWords() {
        assertEquals("tomorrow at 07:05", routineWhen(Instant.parse("2026-10-21T07:05:00Z"), now, zone, Locale.UK))
        assertEquals("Fri 23 Oct at 08:00", routineWhen(Instant.parse("2026-10-23T08:00:00Z"), now, zone, Locale.UK))
    }

    @Test
    fun editorDraftKeepsTheKindAndStepsInsideTheRange() {
        val d = RoutineEditorDraft(mode = RoutineEditorDraft.Mode.TRIGGER).withKind(RoutineTrigger.Kind.STALLED)
        assertEquals(120, d.threshold)
        assertEquals(0, d.copy(threshold = 10).stepped(up = false).threshold)
        assertEquals(99, RoutineEditorDraft(threshold = 97).stepped(up = true).threshold)
        val draft = d.routineDraft(isNew = false)
        assertEquals(RoutineTrigger.Stalled(120), draft.trigger)
        assertEquals(null, draft.schedule)
        assertEquals(null, draft.enabled)
        assertEquals(true, RoutineEditorDraft().routineDraft(isNew = true).enabled)
    }

    @Test
    fun aTieredTriggerRidesBackUnchanged() {
        val tiers = RoutineTrigger.AllowanceTiers(listOf(RoutineTrigger.AllowanceTiers.Tier(50, 12)))
        val draft = RoutineEditorDraft.from(routine(trigger = tiers))
        assertEquals(tiers, draft.routineDraft(isNew = false).trigger)
    }
}
