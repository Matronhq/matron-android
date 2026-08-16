package chat.matron.android.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

/// Ports matron-apple's `BoxChipTests` (the logic slice — the chip itself is
/// a composable and composables aren't rendered in unit tests here).
class BoxChipTest {

    /// Ports `testChipIsSingleLineAndTruncates`: the chip must never grow a
    /// row — it renders on the title line, capped to one line. Rows keep a
    /// fixed height that a wrapping chip would break.
    @Test
    fun chipIsSingleLine() {
        assertEquals(1, BOX_CHIP_MAX_LINES)
    }
}
