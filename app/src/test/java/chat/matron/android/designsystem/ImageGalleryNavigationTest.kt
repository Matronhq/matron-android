package chat.matron.android.designsystem

import chat.matron.android.designsystem.ImageGalleryNavigation.SwipeIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Ported from matron-apple's `ImageGalleryNavigationTests` (#175).
class ImageGalleryNavigationTest {
    @Test
    fun step_movesWithinTheGallery_andEndsAreHardStops() {
        assertEquals(1, ImageGalleryNavigation.step(0, 1, 3))
        assertEquals(0, ImageGalleryNavigation.step(1, -1, 3))
        assertNull(ImageGalleryNavigation.step(2, 1, 3))
        assertNull(ImageGalleryNavigation.step(0, -1, 3))
        assertNull(ImageGalleryNavigation.step(0, 0, 3))
        assertNull(ImageGalleryNavigation.step(0, 1, 1))
    }

    @Test
    fun preloadIndices_areTheInRangeNeighbours() {
        assertEquals(listOf(0, 2), ImageGalleryNavigation.preloadIndices(1, 3))
        assertEquals(listOf(1), ImageGalleryNavigation.preloadIndices(0, 3))
        assertEquals(listOf(1), ImageGalleryNavigation.preloadIndices(2, 3))
        assertEquals(emptyList<Int>(), ImageGalleryNavigation.preloadIndices(0, 1))
    }

    @Test
    fun retainedIndices_clampToTheGallery() {
        assertEquals(setOf(1, 2, 3), ImageGalleryNavigation.retainedIndices(2, 10, 1))
        assertEquals(setOf(0, 1), ImageGalleryNavigation.retainedIndices(0, 10, 1))
        assertEquals(setOf(8, 9), ImageGalleryNavigation.retainedIndices(9, 10, 1))
        assertEquals(emptySet<Int>(), ImageGalleryNavigation.retainedIndices(0, 0, 1))
        assertEquals(setOf(0), ImageGalleryNavigation.retainedIndices(0, 1, 3))
    }

    @Test
    fun swipeIntent_dominantAxisDecides() {
        assertEquals(SwipeIntent.NEXT, ImageGalleryNavigation.swipeIntent(-400f, 10f, 300f))
        assertEquals(SwipeIntent.PREVIOUS, ImageGalleryNavigation.swipeIntent(400f, -10f, 300f))
        assertEquals(SwipeIntent.DISMISS, ImageGalleryNavigation.swipeIntent(10f, 400f, 300f))
        assertEquals("upward is not a dismiss", SwipeIntent.NONE, ImageGalleryNavigation.swipeIntent(10f, -400f, 300f))
        assertEquals("short flicks do nothing", SwipeIntent.NONE, ImageGalleryNavigation.swipeIntent(-100f, 10f, 300f))
        assertEquals("a diagonal pages OR dismisses, never both", SwipeIntent.NEXT, ImageGalleryNavigation.swipeIntent(-400f, 350f, 300f))
        assertEquals(SwipeIntent.DISMISS, ImageGalleryNavigation.swipeIntent(-300f, 400f, 300f))
    }

    @Test
    fun counterLabel_isOneBased_andNullForALoneImage() {
        assertEquals("3 of 12", ImageGalleryNavigation.counterLabel(2, 12))
        assertNull(ImageGalleryNavigation.counterLabel(0, 1))
        assertNull(ImageGalleryNavigation.counterLabel(0, 0))
    }

    @Test
    fun gallery_clampsStartAndNeverIsEmpty() {
        val empty = ImageGallery(emptyList(), 5, null) { null }
        assertEquals(1, empty.entries.size)
        assertEquals(0, empty.startIndex)
        val three = ImageGallery((1..3).map { ImageGallery.Entry("$it", "u$it", false) }, 7, null) { null }
        assertEquals(2, three.startIndex)
        assertEquals(0, ImageGallery.single(byteArrayOf(1)).startIndex)
    }
}
