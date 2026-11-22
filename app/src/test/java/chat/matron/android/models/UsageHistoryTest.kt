package chat.matron.android.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class UsageHistoryTest {
    private fun parse(raw: String) = UsageHistory.parse(Json.parseToJsonElement(raw).jsonObject)
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val reply = """{"accounts":[
        {"key":"claude:login:alice@example.com","agent":"claude","email":"alice@example.com","boxes":["box-a","box-b"],
         "meters":[
           {"id":"week_all","label":"Week (all models)","primary":true,
            "current":{"resets_at":"2026-11-26T10:00:00.000Z","percent":30,"unused":70,"as_of":1795000000000},
            "windows":[
              {"resets_at":"2026-11-12T10:00:00.000Z","unused":40,"last_at":1794000000000},
              {"resets_at":"2026-11-19T10:00:00.000Z","unused":25,"peak":80},
              {"resets_at":"not a date","unused":10},
              {"resets_at":"2026-11-05T10:00:00.000Z","unused":130}]},
           {"id":"week_example","label":"Week (Example)","primary":false,
            "windows":[{"resets_at":"2026-11-19T10:00:00.000Z","unused":90}]}]},
        {"key":"codex:device:4:1","agent":"codex","boxes":["box-d"],
         "meters":[{"id":"codex:codex:secondary","label":"Codex · Weekly","primary":true,
            "windows":[{"resets_at":"2026-11-18T08:00:00.000Z","unused":60}]}]},
        {"agent":"claude"}]}"""

    @Test
    fun parse_readsAccountsMetersAndWeeksNewestFirst() {
        val history = parse(reply)!!
        assertEquals(2, history.accounts.size)
        val alice = history.accounts[0]
        assertEquals("alice@example.com", alice.title)
        assertEquals("Claude · 2 boxes", alice.subtitle)
        val meter = alice.meters[0]
        assertEquals(
            listOf(ms("2026-11-19T10:00:00Z"), ms("2026-11-12T10:00:00Z"), ms("2026-11-05T10:00:00Z")),
            meter.weeks.map { it.resetsAt },
        )
        // Clamped, and a missing peak is read from what was left.
        assertEquals(100, meter.weeks[2].unused)
        assertEquals(80, meter.weeks[0].peak)
        assertEquals(0, meter.weeks[2].peak)
        assertEquals(60, meter.weeks[1].peak)
        assertEquals(1_794_000_000_000L, meter.weeks[1].lastReadingAt)
        assertEquals(UsageWeekInProgress(ms("2026-11-26T10:00:00Z"), 70, 1_795_000_000_000), meter.current)
        val codex = history.accounts[1]
        assertEquals("box-d", codex.title)
        assertEquals("Codex", codex.subtitle)
        assertNull(parse("""{"weeks":12}"""))
    }

    @Test
    fun choices_headlineFirstThenEachModelMeter() {
        assertEquals(
            listOf(UsageMeterChoice.primary, UsageMeterChoice("Week (Example)", "Example")),
            UsageHistoryTable.choices(parse(reply)!!),
        )
    }

    @Test
    fun build_placesWeeksInUTCCalendarColumnsWithAverages() {
        // Saturday 21 Nov 2026: this week's column starts Monday 16 Nov.
        val now = ms("2026-11-21T12:00:00Z")
        val table = UsageHistoryTable.build(parse(reply)!!, UsageMeterChoice.primary, weeks = 4, nowMs = now)
        assertEquals(
            listOf("2026-10-26", "2026-11-02", "2026-11-09", "2026-11-16").map { ms("${it}T00:00:00Z") },
            table.columns,
        )
        val alice = table.rows[0]
        assertEquals(listOf(null, 100, 40, 25), alice.cells.map { it?.unused })
        assertEquals(55, alice.averageUnused)
        assertEquals(25, alice.latest?.unused)
        assertTrue(alice.latestIsLastWeek)
        assertEquals(3, alice.latestColumn)
        val codex = table.rows[1]
        assertEquals(listOf(null, null, null, 60), codex.cells.map { it?.unused })
        assertEquals(listOf(null, 100, 40, 43), table.columnAverages)
    }

    @Test
    fun latest_afterAWeekWithNoReadingIsNotLastWeek() {
        val now = ms("2026-11-21T12:00:00Z")
        val alice = UsageHistoryTable.build(parse(reply)!!, UsageMeterChoice.primary, weeks = 4, nowMs = now).rows[0]
        // The week that reset on 19 Nov has no reading: 12 Nov's is older.
        val gap = alice.copy(cells = alice.cells.dropLast(1) + null)
        assertEquals(40, gap.latest?.unused)
        assertFalse(gap.latestIsLastWeek)
        assertEquals(2, gap.latestColumn)
    }

    @Test
    fun latest_beforeThisCalendarWeeksResetIsStillLastWeek() {
        // Monday 23 Nov: the week that reset on Thursday 19 Nov is in the
        // previous column, and this column has no reset yet.
        val now = ms("2026-11-23T08:00:00Z")
        val alice = UsageHistoryTable.build(parse(reply)!!, UsageMeterChoice.primary, weeks = 4, nowMs = now).rows[0]
        assertNull(alice.cells.last())
        assertEquals(25, alice.latest?.unused)
        assertTrue(alice.latestIsLastWeek)
    }

    @Test
    fun build_aModelMeterLeavesOutAccountsWithoutIt() {
        val now = ms("2026-11-21T12:00:00Z")
        val table = UsageHistoryTable.build(
            parse(reply)!!, UsageMeterChoice("Week (Example)", "Example"), weeks = 2, nowMs = now,
        )
        assertEquals(listOf("alice@example.com"), table.rows.map { it.title })
        assertEquals(listOf(null, 90), table.rows[0].cells.map { it?.unused })
    }
}
