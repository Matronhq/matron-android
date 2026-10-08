package chat.matron.android.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSearchBarTest {
    @Test
    fun positionLabel() {
        assertEquals("No matches", chatSearchPositionLabel(0, 0))
        assertEquals("1 of 12", chatSearchPositionLabel(12, 0))
        assertEquals("12 of 12", chatSearchPositionLabel(12, 11))
    }
}
