package chat.matron.android.designsystem

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// Pins the visibility rule for the top-trailing chat overlay stack's jump
/// pill (apple #211). The Apple suite also snapshots the three visual states;
/// the Android unit suite has no Compose snapshot harness.
class ChatTopTrailingControlsTest {
    @Test
    fun showsJumpRule() {
        assertTrue(chatTopTrailingShowsJump(isFollowingTail = false))
        assertFalse(chatTopTrailingShowsJump(isFollowingTail = true))
    }
}
