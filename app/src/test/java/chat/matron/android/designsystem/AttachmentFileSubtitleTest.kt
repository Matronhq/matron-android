package chat.matron.android.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Pins the file chip's subtitle precedence. The Apple PRs cover these states
/// with snapshot tests (`AttachmentFileSnapshotTests.test_downloading` /
/// `test_expired`, apple #138/#139); this project extracts the logic into a
/// pure function instead of rendering composables.
class AttachmentFileSubtitleTest {

    @Test
    fun downloading_overridesSize() {
        assertEquals("Downloading…", attachmentFileSubtitle(isLoading = true, sizeBytes = 12_515_546))
    }

    /// Reaped server-side: "Expired" wins over everything — size is
    /// deliberately still known (the tombstone keeps name/size/caption), and a
    /// stray loading flag must not out-rank the permanent state.
    @Test
    fun expired_winsOverLoadingAndSize() {
        assertEquals(
            "Expired",
            attachmentFileSubtitle(isLoading = true, sizeBytes = 12_515_546, isExpired = true),
        )
        assertEquals(
            "Expired",
            attachmentFileSubtitle(isLoading = false, sizeBytes = null, isExpired = true),
        )
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
