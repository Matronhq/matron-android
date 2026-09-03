package chat.matron.android.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `AgentCapacityFreshnessTests` (#164).
class AgentCapacityFreshnessTest {
    private val now = 1_754_900_000_000L

    @Test
    fun live_isNotStaleAndHasNoCaption() {
        assertFalse(AgentCapacityFreshness.Live.isStale)
        assertNull(AgentCapacityFreshness.Live.ageText(now))
    }

    @Test
    fun offline_isStaleAndCaptionsItsAge() {
        val two = AgentCapacityFreshness.Offline(now - 2 * 3_600_000)
        assertTrue(two.isStale)
        assertEquals("offline · as of 2h ago", two.ageText(now))
        assertEquals("offline · as of 3d ago", AgentCapacityFreshness.Offline(now - 3 * 86_400_000).ageText(now))
        assertEquals("offline · as of 5m ago", AgentCapacityFreshness.Offline(now - 5 * 60_000).ageText(now))
    }

    /// Clock skew can stamp a capture at or ahead of now; neither may read as
    /// a promise about the future.
    @Test
    fun offline_captureAtOrAheadOfNow_readsJustNow() {
        assertEquals("offline · as of just now", AgentCapacityFreshness.Offline(now).ageText(now))
        assertEquals("offline · as of just now", AgentCapacityFreshness.Offline(now + 3 * 3_600_000).ageText(now))
    }
}
