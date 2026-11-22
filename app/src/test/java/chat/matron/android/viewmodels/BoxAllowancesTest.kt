package chat.matron.android.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxAllowancesTest {
    private val now = 1_754_900_000_000L
    private val day = 86_400_000L

    private fun week(percent: Int, resetsInDays: Double?) = LimitLine(
        BoxAllowances.ALL_MODELS_LINE_ID, "Week (all models)", percent,
        resetsInDays?.let { now + (it * day).toLong() },
    )

    private fun reading(id: Long, email: String?, percent: Int, resetsInDays: Double?) =
        BoxAllowanceReading(id, email, listOf(week(percent, resetsInDays)))

    @Test
    fun mostSpare_picksTheHighestPercentLeftPerDay() {
        val spare = BoxAllowances.mostSpare(
            listOf(
                reading(1, "alice@example.com", 20, 4.0), // 80 left / 4 days = 20
                reading(2, "bob@example.com", 50, 1.0), // 50 / 1 = 50
            ),
            now,
        )!!
        assertEquals("bob@example.com", spare.accountEmail)
        assertEquals(listOf(2L), spare.boxIDs)
        assertEquals(50, spare.percentLeft)
        assertEquals(50, spare.percentPerDay)
    }

    @Test
    fun mostSpare_countsBoxesOnOneLoginOnceAtTheirHighestReading() {
        val spare = BoxAllowances.mostSpare(
            listOf(
                reading(1, "alice@example.com", 10, 2.0),
                reading(2, "alice@example.com", 60, 3.0),
                reading(3, "bob@example.com", 30, 2.0), // 70 / 2 = 35
            ),
            now,
        )!!
        // alice: 40 left over the earlier reset (2 days) = 20 a day, so bob wins.
        assertEquals("bob@example.com", spare.accountEmail)

        val alice = BoxAllowances.mostSpare(
            listOf(reading(1, "alice@example.com", 10, 2.0), reading(2, "alice@example.com", 60, 3.0)),
            now,
        )!!
        assertEquals(listOf(1L, 2L), alice.boxIDs)
        assertEquals(40, alice.percentLeft)
        assertEquals(now + 2 * day, alice.resetsAt)
    }

    @Test
    fun mostSpare_aResetUnderADayAwayCountsAsAFullDay() {
        val spare = BoxAllowances.mostSpare(
            listOf(reading(1, "alice@example.com", 70, 1.0 / 24), reading(2, "bob@example.com", 10, 3.0)),
            now,
        )!!
        // alice would be 30 / (1/24) = 720 a day without the floor; with it, 30.
        assertEquals("alice@example.com", spare.accountEmail)
        assertEquals(30, spare.percentPerDay)
        val bob = BoxAllowances.mostSpare(
            listOf(reading(1, "alice@example.com", 80, 1.0 / 24), reading(2, "bob@example.com", 10, 3.0)),
            now,
        )!!
        assertEquals("bob@example.com", bob.accountEmail)
    }

    @Test
    fun mostSpare_skipsExpiredOrUntimedLinesAndExhaustedLogins() {
        assertNull(BoxAllowances.mostSpare(listOf(reading(1, null, 10, -1.0), reading(2, null, 10, null)), now))
        assertNull(BoxAllowances.mostSpare(listOf(reading(1, null, 100, 2.0)), now))
        assertNull(BoxAllowances.mostSpare(emptyList(), now))
    }

    @Test
    fun mostSpare_boxesWithoutALoginAreEachTheirOwnAccount() {
        val spare = BoxAllowances.mostSpare(listOf(reading(1, null, 50, 2.0), reading(2, null, 0, 2.0)), now)!!
        assertNull(spare.accountEmail)
        assertEquals(listOf(2L), spare.boxIDs)
    }

    @Test
    fun isNearLimit_readsTheAllModelsWeekOnly() {
        assertTrue(BoxAllowances.isNearLimit(listOf(week(80, 2.0)), now))
        assertFalse(BoxAllowances.isNearLimit(listOf(week(79, 2.0)), now))
        // Past its reset, the number says nothing about today.
        assertFalse(BoxAllowances.isNearLimit(listOf(week(95, -0.5)), now))
        assertFalse(BoxAllowances.isNearLimit(listOf(LimitLine("session", "Session", 99, null)), now))
    }

    @Test
    fun ageText() {
        assertEquals("just now", BoxAllowances.ageText(now - 30_000, now))
        assertEquals("4 min ago", BoxAllowances.ageText(now - 4 * 60_000, now))
        assertEquals("2 h ago", BoxAllowances.ageText(now - 2 * 3_600_000, now))
        assertEquals("3 d ago", BoxAllowances.ageText(now - 3 * day, now))
    }

    @Test
    fun defaultsText() {
        assertEquals("Claude · Opus · high", BoxAllowances.defaultsText("claude", "opus", "high"))
        assertEquals("Codex · gpt-example-1", BoxAllowances.defaultsText("codex", "gpt-example-1", null))
        assertEquals("Claude · Claude Code's own model", BoxAllowances.defaultsText("claude", null, null))
        assertEquals("Codex · Codex's own model", BoxAllowances.defaultsText("codex", "", null))
    }
}
