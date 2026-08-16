package chat.matron.android.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Pins the file chip's subtitle precedence. The Apple PR covers these states
/// with snapshot tests (`AttachmentFileSnapshotTests.test_downloading`,
/// apple #138); this project extracts the logic into a pure function instead
/// of rendering composables.
class AttachmentFileSubtitleTest {

    @Test
    fun downloading_overridesSize() {
        assertEquals("Downloading…", attachmentFileSubtitle(isLoading = true, sizeBytes = 12_515_546))
    }

    @Test
    fun idleWithSize_showsFormattedSize() {
        assertEquals("12.5 MB", attachmentFileSubtitle(isLoading = false, sizeBytes = 12_515_546))
    }

    @Test
    fun idleWithoutSize_showsNothing() {
        assertNull(attachmentFileSubtitle(isLoading = false, sizeBytes = null))
    }
}
