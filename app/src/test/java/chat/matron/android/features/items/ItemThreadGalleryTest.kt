package chat.matron.android.features.items

import chat.matron.android.designsystem.itemThreadImages
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemKind
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/// Tapping an image in an item thread opens a viewer on every image the
/// thread shows, in screen order, starting on the tapped one.
class ItemThreadGalleryTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private fun png(blob: String) = TrackerAttachment(blobRef = blob, mime = "image/png", name = "$blob.png", size = 1)
    private val pdf = TrackerAttachment(blobRef = "doc", mime = "application/pdf", name = "spec.pdf", size = 1)
    private fun url(a: TrackerAttachment) = "https://example.com/media/${a.blobRef}"

    private fun comment(id: String, body: String, vararg attachments: TrackerAttachment, kind: TrackerComment.Kind = TrackerComment.Kind.COMMENT) =
        TrackerComment(id, "it_1", ItemAuthor.AGENT, body = body, createdAt = now, attachments = attachments.toList(), kind = kind)

    private val item = TrackerItem(
        "it_1", 1, ItemKind.TASK, title = "Pick a layout", body = "Two options:", originConvoID = "c1", createdAt = now,
        attachments = listOf(png("item-a"), pdf),
    )

    @Test
    fun imagesInScreenOrderSkippingFilesAndHiddenCards() {
        val comments = listOf(
            // The second image is placed in the text, so it is drawn before the trailing first one.
            comment("c1", "Before ![b](attachment:c1-b) after", png("c1-a"), png("c1-b")),
            comment("c2", "", png("hidden"), kind = TrackerComment.Kind.STATUS),
            comment("c3", "Done.", png("c3-a")),
        )
        assertEquals(
            listOf("item-a", "c1-b", "c1-a", "c3-a"),
            itemThreadImages(item, comments).map { it.blobRef },
        )
        assertEquals(listOf("c3-a"), itemThreadImages(null, comments.drop(2)).map { it.blobRef })
    }

    @Test
    fun galleryStartsOnTheTappedImageAndReusesLoadedBytes() = runBlocking {
        val shown = listOf(png("a"), png("b"), png("c"))
        val fetched = mutableListOf<String>()
        val loadedB = byteArrayOf(2)
        val gallery = itemThreadGallery(shown, png("b"), mapOf("b" to loadedB), ::url) { u -> fetched += u; byteArrayOf(9) }
        assertEquals(3, gallery.entries.size)
        assertEquals(1, gallery.startIndex)
        assertEquals(loadedB, gallery.initial)
        assertEquals(listOf("a.png", "b.png", "c.png"), gallery.entries.map { it.name })
        assertEquals(loadedB, gallery.load(url(png("b"))))
        gallery.load(url(png("c")))
        assertEquals(listOf(url(png("c"))), fetched)
    }

    @Test
    fun anImageNotYetLoadedOpensOnItsURL() {
        val gallery = itemThreadGallery(listOf(png("a")), png("a"), emptyMap(), ::url) { null }
        assertEquals(url(png("a")), gallery.initial)
    }

    @Test
    fun anImageMissingFromTheListOpensAlone() {
        val gallery = itemThreadGallery(listOf(png("a")), png("z"), emptyMap(), ::url) { null }
        assertEquals(1, gallery.entries.size)
        assertEquals(0, gallery.startIndex)
        assertEquals(url(png("z")), gallery.initial)
    }
}
