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
                "account":{"email":"bob@example.com"}}""",
        )
        assertEquals(2, c.liveSessions)
        assertEquals(listOf("Current session", "Current week (all models)"), c.limitLines.map { it.label })
        assertEquals(39, c.limitLines[0].percent)
        assertNotNull(c.limitLines[0].resetsAt)
        assertNull(c.limitLines[1].resetsAt)
        assertEquals("bob@example.com", c.accountEmail)
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

    // MARK: BoxStatus (the journal's stored report, journal PR #82)

    private fun status(json: String) = BoxStatus.parse(Json.parseToJsonElement(json))

    @Test
    fun boxStatus_parsesReportedAtAndTheSameBlocks() {
        val s = status(
            """{"reported_at":1754900000000,
                "activity":{"live_sessions":1,"last_hour":[]},
                "limits":{"as_of":"2026-08-11T08:00:00.000Z",
                          "lines":[{"id":"session","label":"Current session","percent":39}]},
                "disk":{"free_bytes":1,"total_bytes":2},
                "account":{"email":"bob@example.com"}}""",
        )
        assertEquals(1_754_900_000_000L, s?.reportedAtMs)
        assertEquals(1, s?.capacity?.liveSessions)
        assertEquals(listOf(39), s?.capacity?.limitLines?.map { it.percent })
        assertEquals("bob@example.com", s?.capacity?.accountEmail)
    }

    /// `reported_at` is what makes a report a report: without it the numbers
    /// have no age, and an unaged number can't be captioned honestly.
    @Test
    fun boxStatus_withoutAUsableReportedAtIsNoReport() {
        assertNull(status("""{"limits":{"lines":[]}}"""))
        assertNull(status("""{"reported_at":"yesterday"}"""))
        assertNull(status("""{"reported_at":true}"""))
        assertNull(status("""{"reported_at":null}"""))
        assertNull(status("""[1754900000000]"""))
    }

    @Test
    fun boxStatus_blocksDegradeIndependently() {
        val s = status("""{"reported_at":1000,"activity":{"live_sessions":"many"}}""")
        assertEquals(1_000L, s?.reportedAtMs)
        assertNull(s?.capacity?.liveSessions)
        assertEquals(emptyList<LimitLine>(), s?.capacity?.limitLines)
    }
}

class BoxCapacityBoxesBlocksTest {
    private fun parse(json: String) = BoxCapacity.parse(Json.parseToJsonElement(json))

    @Test
    fun parse_readsDiskAsOfAndDefaults() {
        val c = parse(
            """{"limits":{"as_of":1754900000000,"lines":[]},
                "disk":{"free_bytes":50465865728,"total_bytes":155692564480},
                "defaults":{"agent":"claude","model":"opus","effort":"high"}}""",
        )
        assertEquals(BoxDisk(50_465_865_728, 155_692_564_480), c.disk)
        assertEquals(1_754_900_000_000L, c.limitsAsOfMs)
        assertEquals(BoxResolvedDefaults("claude", "opus", "high"), c.defaults)
    }

    @Test
    fun parse_dropsMalformedBlocks() {
        val c = parse(
            """{"limits":{"as_of":"yesterday","lines":[]},
                "disk":{"free_bytes":200,"total_bytes":100},
                "defaults":{"agent":""}}""",
        )
        assertNull(c.disk)
        assertNull(c.limitsAsOfMs)
        assertNull(c.defaults)
        assertNull(parse("""{"disk":{"free_bytes":1.5,"total_bytes":100}}""").disk)
        assertNull(parse("""{"disk":{"free_bytes":true,"total_bytes":100}}""").disk)
        assertNull(parse("""{}""").disk)
    }

    @Test
    fun sizeText() {
        val gib = 1024L * 1024 * 1024
        assertEquals("0B", BoxDisk.sizeText(0))
        assertEquals("512B", BoxDisk.sizeText(512))
        assertEquals("7.4G", BoxDisk.sizeText((7.4 * gib).toLong()))
        assertEquals("233G", BoxDisk.sizeText(233 * gib))
        assertEquals("1.8T", BoxDisk.sizeText((1.8 * 1024 * gib).toLong()))
        // Carries to the next unit rather than reading "1024G".
        assertEquals("1.0T", BoxDisk.sizeText((1023.9 * gib).toLong()))
    }

    @Test
    fun resetCountdownAndCaption() {
        val now = 1_754_900_000_000L
        val hour = 3_600_000L
        assertNull(BoxCapacity.resetCountdown(null, now))
        assertNull(BoxCapacity.resetCountdown(now + 5 * hour, now))
        assertEquals("in 30h", BoxCapacity.resetCountdown(now + 30 * hour, now))
        assertEquals("in 3d 4h", BoxCapacity.resetCountdown(now + 76 * hour, now))
        assertEquals("in 3d", BoxCapacity.resetCountdown(now + 72 * hour, now))
        val utc = ZoneId.of("UTC")
        assertEquals("resets Aug 14 · in 3d 4h", BoxCapacity.resetCaption(now + 76 * hour, now, utc))
        assertEquals("reset", BoxCapacity.resetCaption(now - hour, now, utc))
        assertNull(BoxCapacity.resetCaption(null, now, utc))
    }
}
