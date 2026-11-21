package chat.matron.android.designsystem

import chat.matron.android.events.CompactCardLine
import org.junit.Assert.assertEquals
import org.junit.Test

class CompactCardRowTest {
    @Test
    fun theLineAnnouncesItsSummaryThenItsStatus() {
        assertEquals("Spawn on box-b: Run the build, Started", compactCardAccessibilityLabel(CompactCardLine("x", "Spawn on box-b: Run the build", "Started")))
        assertEquals("Reminder in 10m", compactCardAccessibilityLabel(CompactCardLine("x", "Reminder in 10m", null)))
    }

    @Test
    fun theTapSaysWhatItWillDo() {
        assertEquals("Show the details", compactCardToggleLabel(expanded = false))
        assertEquals("Hide the details", compactCardToggleLabel(expanded = true))
    }
}
