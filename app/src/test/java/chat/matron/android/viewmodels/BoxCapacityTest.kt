package chat.matron.android.viewmodels

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class BoxCapacityTest {
    private fun parse(json: String) = BoxCapacity.parse(Json.parseToJsonElement(json))

    @Test
    fun parse_fullBlock() {
        val c = parse(
            """{"folders":[],
                "activity":{"live_sessions":2,"last_hour":[{"path":"/w","sessions":1}]},
                "limits":{"as_of":1754900000000,"lines":[
                   {"id":"session","label":"Current session","percent":39,"resets_at":"2026-08-11T23:59:00Z"},
                   {"id":"week","label":"Current week (all models)","percent":66}]},
                "account":{"email":"pat@yearbook.com"}}""",
        )
        assertEquals(2, c.liveSessions)
        assertEquals(listOf("Current session", "Current week (all models)"), c.limitLines.map { it.label })
        assertEquals(39, c.limitLines[0].percent)
        assertNotNull(c.limitLines[0].resetsAt)
        assertNull(c.limitLines[1].resetsAt)
        assertEquals("pat@yearbook.com", c.accountEmail)
    }

    @Test
    fun parse_missingBlocks_degradeToEmpty() {
        val c = parse("""{"folders":[]}""")
        assertNull(c.liveSessions)
        assertTrue(c.limitLines.isEmpty())
        assertNull(c.accountEmail)
    }

    @Test
    fun parse_malformedEntries_dropLineNotBlock() {
        val c = parse(
            """{"limits":{"lines":[
                 {"id":"ok","label":"Fine","percent":10},
                 {"id":"bad","label":"No percent"},
                 {"label":"No id","percent":5}]},
                "account":{"email":42},
                "activity":{"live_sessions":"two"}}""",
        )
        assertEquals(listOf("ok"), c.limitLines.map { it.id })
        assertNull(c.accountEmail)
        assertNull(c.liveSessions)
    }

    @Test
    fun parse_negativeLiveSessions_readsAsAbsent() {
        val c = parse("""{"activity":{"live_sessions":-1}}""")
        assertNull(c.liveSessions)
    }

    @Test
    fun parse_percentClamped() {
        val c = parse("""{"limits":{"lines":[{"id":"a","label":"A","percent":-5},{"id":"b","label":"B","percent":5000}]}}""")
        assertEquals(listOf(0, 999), c.limitLines.map { it.percent })
    }

    @Test
    fun resetText_todayVsLater() {
        val zone = ZoneId.of("UTC")
        val now = 1_754_900_000_000L // 2026-08-11 UTC
        val today = now + 2 * 3_600_000L
        val nextWeek = now + 4 * 86_400_000L
        assertTrue(BoxCapacity.resetText(today, now, zone)!!.startsWith("resets "))
        assertFalse(BoxCapacity.resetText(today, now, zone)!!.contains("Aug"))
        assertTrue(BoxCapacity.resetText(nextWeek, now, zone)!!.contains("Aug"))
        assertNull(BoxCapacity.resetText(null, now, zone))
    }

    /// Port of apple's BoxCapacityTests
    /// `test_resetText_passedResetReadsReset_notAPastMomentAsUpcoming`.
    @Test
    fun resetText_passedResetReadsReset_notAPastMomentAsUpcoming() {
        val zone = ZoneId.of("UTC")
        val now = 1_754_900_000_000L
        // Earlier today AND a previous day: both used to format as if
        // upcoming ("resets 5:30 PM" the day after the fact — the stale
        // cache-line bug); both must read as already reset.
        assertEquals("reset", BoxCapacity.resetText(now - 3_600_000L, now, zone))
        assertEquals("reset", BoxCapacity.resetText(now - 2 * 86_400_000L, now, zone))
    }

    /// Port of apple's BoxCapacityTests `test_hasReset_pastTrue_futureAndUnknownFalse`.
    @Test
    fun hasReset_pastTrue_futureAndUnknownFalse() {
        val now = 1_754_900_000_000L
        assertTrue(BoxCapacity.hasReset(now - 1, now))
        assertTrue("the boundary instant counts as reset", BoxCapacity.hasReset(now, now))
        assertFalse(BoxCapacity.hasReset(now + 60_000L, now))
        assertFalse(
            "no timestamp means no expiry claim — the line renders normally",
            BoxCapacity.hasReset(null, now),
        )
    }
}
