package chat.matron.android.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

/// Pins the media browser's pure UI logic: empty-state copy per tab and the
/// grid cell's accessibility label (expired > loading > plain). Apple #142
/// pins the same states via `MediaBrowserSnapshotTests` baselines and the
/// `MediaThumbCell` accessibilityLabel; this project's conventions replace
/// snapshots with pure-function tests.
class MediaBrowserViewTest {
    @Test
    fun emptyLabelsPerTab() {
        assertEquals("No media yet", mediaBrowserEmptyLabel(MediaBrowserTab.Media))
        assertEquals("No files yet", mediaBrowserEmptyLabel(MediaBrowserTab.Files))
        assertEquals("No links yet", mediaBrowserEmptyLabel(MediaBrowserTab.Links))
    }

    @Test
    fun cellDescription_expiredWinsOverLoading() {
        assertEquals("Image", mediaCellContentDescription(expired = false, isLoading = false))
        assertEquals("Image, loading", mediaCellContentDescription(expired = false, isLoading = true))
        assertEquals("Image, expired", mediaCellContentDescription(expired = true, isLoading = false))
        assertEquals(
            "an expired cell never loads — expired wins",
            "Image, expired",
            mediaCellContentDescription(expired = true, isLoading = true),
        )
    }

    @Test
    fun tabTitles() {
        assertEquals(listOf("Media", "Files", "Links"), MediaBrowserTab.entries.map { it.title })
    }
}
