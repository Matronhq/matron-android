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

    @Test
    fun bucketsNoActivityWhenDateIsNull() {
        assertEquals(ChatRecencyGroup.NO_ACTIVITY, bucket(null))
    }
}
