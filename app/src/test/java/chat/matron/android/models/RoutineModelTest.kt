package chat.matron.android.models

import chat.matron.android.journal.intOrNull
import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.journal.stringOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.arrayOrNull
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineModelTest {
    companion object {
        const val SCHEDULED_JSON =
            """{"id":"rt_0a1b","user_id":1,"name":"daily-sweep","title":"Daily sweep","schedule":"5 7 * * *","trigger":null,"tz":"Europe/London","prompt":"Do the sweep.","enabled":true,"origin":"seed","next_at":1760000000000,"last_fired_at":1759990000000,"last_outcome":"applied now","envelope":null,"created_at":1750000000000,"updated_at":1750000000001}"""
        const val TRIGGERED_JSON =
            """{"id":"rt_0a1c","name":"context-over","title":"Session context","schedule":null,"trigger":{"kind":"context_over","pct":40},"tz":"Europe/London","prompt":"Look at it.","enabled":false,"origin":"seed","next_at":null,"last_fired_at":null,"last_outcome":null,"created_at":1750000000000,"updated_at":1750000000000}"""
    }

    private fun obj(text: String) = parseJsonObjectOrNull(text)!!

    @Test
    fun decodesAScheduledRoutine() {
        val r = Routine.fromJson(obj(SCHEDULED_JSON))!!
        assertEquals("daily-sweep", r.name)
        assertEquals("5 7 * * *", r.schedule)
        assertNull(r.trigger)
        assertEquals(Instant.ofEpochMilli(1760000000000), r.nextAt)
        assertEquals("Every day at 07:05 (Europe/London)", r.whenWords)
        assertEquals("Delivered", r.lastOutcomeWords)
        assertFalse(r.lastFireFailed)
    }

    @Test
    fun decodesATriggeredRoutine() {
        val r = Routine.fromJson(obj(TRIGGERED_JSON))!!
        assertEquals(RoutineTrigger.ContextOver(40), r.trigger)
        assertFalse(r.enabled)
        assertEquals("When a session passes 40% of its context window", r.whenWords)
    }

    @Test
    fun dropsRowsWithBothOrNeitherOrAnUnknownTrigger() {
        assertNull(Routine.fromJson(obj(SCHEDULED_JSON.replace("\"trigger\":null", "\"trigger\":{\"kind\":\"stalled\"}"))))
        assertNull(Routine.fromJson(obj(SCHEDULED_JSON.replace("\"5 7 * * *\"", "null"))))
        assertNull(Routine.fromJson(obj(TRIGGERED_JSON.replace("context_over", "moon_phase"))))
        assertNull(Routine.fromJson(obj(TRIGGERED_JSON.replace("\"created_at\":1750000000000,", ""))))
    }

    @Test
    fun decodesTheTieredAllowanceTriggerAndSendsItBackUnchanged() {
        val raw = obj("""{"kind":"allowance_expiring","tiers":[{"unused_pct":50,"hours_left":12},{"unused_pct":25,"hours_left":2}]}""")
        val t = RoutineTrigger.fromJson(raw)!!
        assertTrue(t is RoutineTrigger.AllowanceTiers)
        assertFalse(t.isEditable)
        assertNull(t.threshold)
        assertEquals("When a weekly limit has 50% left within 12 h or 25% left within 2 h of its reset", t.words)
        val back = t.toJson()
        assertNull(back["pct"])
        assertEquals(2, back.arrayOrNull("tiers")!!.size)
    }

    @Test
    fun allowanceWindowRidesBackOnlyAsReceived() {
        val plain = RoutineTrigger.fromJson(obj("""{"kind":"allowance_expiring","pct":70}"""))!!
        assertNull(plain.toJson()["from_hours"])
        assertEquals("When a weekly limit resets within 6–12 h and is under 70%", plain.words)
        val windowed = RoutineTrigger.fromJson(obj("""{"kind":"allowance_expiring","pct":70,"from_hours":2,"to_hours":8}"""))!!
        assertEquals("2", windowed.toJson()["from_hours"].toString())
        assertEquals("When a weekly limit resets within 2–8 h and is under 70%", windowed.words)
    }

    @Test
    fun stalledDefaultsAndWords() {
        assertEquals(RoutineTrigger.Stalled(120), RoutineTrigger.fromJson(obj("""{"kind":"stalled"}""")))
        assertEquals("When a session stalls on a usage limit with no reset within 2 h", RoutineTrigger.Stalled(120).words)
        assertEquals("When a session stalls on a usage limit", RoutineTrigger.Stalled(0).words)
        assertEquals("1 h 30 min", RoutineTrigger.duration(90))
        assertEquals("3 d", RoutineTrigger.duration(3 * 1440))
    }

    @Test
    fun outcomeWords() {
        fun r(outcome: String?) = Routine.fromJson(obj(SCHEDULED_JSON))!!.copy(lastOutcome = outcome)
        assertEquals("Queued for the next idle point", r("applied deferred").lastOutcomeWords)
        assertEquals("Failed: agent_unreachable", r("failed agent_unreachable").lastOutcomeWords)
        assertTrue(r("failed agent_unreachable").lastFireFailed)
        assertEquals("Not delivered: no Coordinator box", r("no_coordinator").lastOutcomeWords)
        assertTrue(r("missed").lastFireFailed)
        assertNull(r(null).lastOutcomeWords)
    }

    @Test
    fun cronWords() {
        assertEquals("Every 2 hours, on the hour", CronWords.describe("0 */2 * * *"))
        assertEquals("Every day at 08:00 and 17:00", CronWords.describe("0 8,17 * * *"))
        assertEquals("Monday to Friday at 18:30", CronWords.describe("30 18 * * 1-5"))
        assertEquals("Every Monday at 09:00", CronWords.describe("0 9 * * 1"))
        assertEquals("On day 1 of the month at 06:00", CronWords.describe("0 6 1 * *"))
        assertEquals("Every 20 minutes", CronWords.describe("*/20 * * * *"))
        assertEquals("Every day in January at 07:05", CronWords.describe("5 7 * 1 *"))
        assertNull(CronWords.describe("0 9 1 * 1"))
        assertNull(CronWords.describe("0 9 * *"))
    }

    @Test
    fun formRules() {
        val ok = RoutineRules.formError("weekly-review", "Weekly", "Do it", "Europe/London", "0 9 * * 1", null)
        assertNull(ok)
        assertNotNull(RoutineRules.formError("Weekly", "Weekly", "Do it", "Europe/London", "0 9 * * 1", null))
        assertEquals("Give the routine a title.", RoutineRules.formError("a", " ", "p", "UTC", null, RoutineTrigger.DiskUnder(20)))
        assertEquals("The title must be one line.", RoutineRules.formError("a", "x\ny", "p", "UTC", null, RoutineTrigger.DiskUnder(20)))
        assertTrue(RoutineRules.formError("a", "t", "p", "Mars/Olympus", "0 9 * * *", null)!!.contains("not a time zone"))
        assertEquals("A routine fires at most every 15 minutes.", RoutineRules.formError("a", "t", "p", "UTC", "*/5 * * * *", null))
        assertEquals("A routine fires at most every 15 minutes.", RoutineRules.formError("a", "t", "p", "UTC", "* * * * *", null))
        assertEquals("The threshold is a percentage from 1 to 99.", RoutineRules.formError("a", "t", "p", "UTC", null, RoutineTrigger.DiskUnder(100)))
        // A triggered routine has no clock: its zone is not checked.
        assertNull(RoutineRules.formError("a", "t", "p", "", null, RoutineTrigger.Stalled(0)))
    }

    @Test
    fun draftBodyNormalisesAndSendsZoneOnlyWithASchedule() {
        val scheduled = RoutineDraft(title = " T ", prompt = " P ", tz = "UTC", schedule = " 0  9 * *   1 ", enabled = true).requestBody()
        assertEquals("T", scheduled.stringOrNull("title"))
        assertEquals("0 9 * * 1", scheduled.stringOrNull("schedule"))
        assertEquals("UTC", scheduled.stringOrNull("tz"))
        val triggered = RoutineDraft(title = "T", prompt = "P", tz = "UTC", trigger = RoutineTrigger.DiskUnder(20)).requestBody()
        assertNull(triggered["tz"])
        assertNull(triggered["enabled"])
        assertEquals(20, triggered.objectOrNull("trigger")!!.intOrNull("pct"))
    }
}
