package chat.matron.android.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

/// The Storage rows' pure copy, pinned so the numbers read back from bug
/// reports are unambiguous whatever the device's locale. Ported from the
/// formatting assertions in matron-apple's `StorageSettingsRowsSnapshotTests`.
class StorageSettingsRowsTest {
    @Test
    fun byteTextUsesDecimalUnitsAndDropsAWholeNumbersFraction() {
        assertEquals("0 B", StorageSettingsRows.byteText(0))
        assertEquals("500 B", StorageSettingsRows.byteText(500))
        assertEquals("1 KB", StorageSettingsRows.byteText(1_000))
        assertEquals("1.5 KB", StorageSettingsRows.byteText(1_500))
        assertEquals("440 MB", StorageSettingsRows.byteText(440_000_000))
        assertEquals("1.2 GB", StorageSettingsRows.byteText(1_200_000_000))
        assertEquals("543.9 MB", StorageSettingsRows.byteText(543_900_000))
    }

    /// The unit is picked from the raw byte count; rounding can carry it to
    /// 1000 of that unit, in which case the tier bumps first.
    @Test
    fun byteTextBumpsTheUnitWhenRoundingWouldReachOneThousand() {
        assertEquals("1 GB", StorageSettingsRows.byteText(999_999_999))
        assertEquals("1 MB", StorageSettingsRows.byteText(999_999))
    }

    @Test
    fun countsTextGroupsWithACommaRegardlessOfLocale() {
        assertEquals("457,102 / 6,214", StorageSettingsRows.countsText(457_102, 6_214))
        assertEquals("0 / 0", StorageSettingsRows.countsText(0, 0))
    }
}
