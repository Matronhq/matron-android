package chat.matron.android.chat

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRecencyGroupTest {
    private val now = Instant.ofEpochSecond(1_745_000_000)
    // Pinned to UTC so the day-boundary buckets are deterministic across the
    // machine's local timezone (the Swift original used the current calendar).
    private val zone = ZoneId.of("UTC")

    private fun bucket(date: Instant?) = ChatRecencyGroup.bucket(date, now, zone)

    @Test
    fun bucketsToday() {
        assertEquals(ChatRecencyGroup.TODAY, bucket(now.minusSeconds(3600)))
    }

    @Test
    fun bucketsYesterday() {
        assertEquals(ChatRecencyGroup.YESTERDAY, bucket(now.minus(Duration.ofDays(1))))
    }

    @Test
    fun bucketsLastSevenDays() {
        assertEquals(ChatRecencyGroup.LAST_SEVEN_DAYS, bucket(now.minus(Duration.ofDays(3))))
    }

    @Test
    fun bucketsEarlier() {
        assertEquals(ChatRecencyGroup.EARLIER, bucket(now.minus(Duration.ofDays(30))))
    }

    /// Regression: the seven-day boundary must be computed by subtracting 7
    /// zone-aware calendar days from `now` (matching matron-apple), not a flat
    /// 168-hour `Duration` — the two diverge by an hour across a DST transition.
    /// `now` sits a week after America/New_York's 2026-03-08 spring-forward, so
    /// a naive Duration-based threshold lands an hour earlier than the
    /// zone-aware one.
    @Test
    fun bucketsLastSevenDays_isZoneAwareAcrossDstTransition() {
        val dstZone = ZoneId.of("America/New_York")
        val nowAfterSpringForward = Instant.parse("2026-03-15T05:00:00Z")

        // Between the zone-aware threshold (2026-03-08T06:00:00Z) and the
        // flat-168h threshold (2026-03-08T05:00:00Z) a naive implementation
        // would call this LAST_SEVEN_DAYS; the zone-aware one calls it EARLIER.
        val betweenThresholds = Instant.parse("2026-03-08T05:30:00Z")
        assertEquals(
            ChatRecencyGroup.EARLIER,
            ChatRecencyGroup.bucket(betweenThresholds, nowAfterSpringForward, dstZone),
        )

        // Right at the zone-aware threshold: still within the last 7 days.
        val atZoneAwareThreshold = Instant.parse("2026-03-08T06:00:00Z")
        assertEquals(
            ChatRecencyGroup.LAST_SEVEN_DAYS,
            ChatRecencyGroup.bucket(atZoneAwareThreshold, nowAfterSpringForward, dstZone),
        )
    }

    @Test
    fun bucketsNoActivityWhenDateIsNull() {
        assertEquals(ChatRecencyGroup.NO_ACTIVITY, bucket(null))
    }
}
