package chat.matron.android.features.items

import chat.matron.android.models.TrackerAttachment
import org.junit.Assert.assertEquals
import org.junit.Test

/// Pins which image blobs a load pass starts. The effect that runs this
/// restarts whenever the attachment list changes, and Compose cancels the
/// previous pass without waiting for it — so "already being loaded" is not a
/// reason to skip a blob: that load is stopping, not arriving.
class ImageBlobsToLoadTest {
    private fun png(blob: String) = TrackerAttachment(blobRef = blob, mime = "image/png", name = "$blob.png", size = 1)

    @Test
    fun startsEverythingNotYetResolvedOnceEach() {
        val attachments = listOf(png("a"), png("b"), png("a"))
        assertEquals(listOf("a", "b"), imageBlobsToLoad(attachments, emptySet()).map { it.blobRef })
        assertEquals("a blob already resolved is left alone", listOf("b"), imageBlobsToLoad(attachments, setOf("a")).map { it.blobRef })
        assertEquals(emptyList<String>(), imageBlobsToLoad(attachments, setOf("a", "b")).map { it.blobRef })
        assertEquals(emptyList<String>(), imageBlobsToLoad(emptyList(), emptySet()).map { it.blobRef })
    }

    @Test
    fun aBlobLeftInFlightByACancelledPassIsStartedAgain() {
        // The previous pass was loading "a" when the list changed; Compose
        // cancelled it, so nothing will deliver those bytes. Only `images`
        // (what actually resolved) may retire a blob — anything else leaves it
        // neither loaded nor queued, with nothing to start it again.
        val resolved = setOf("b")
        val next = imageBlobsToLoad(listOf(png("a"), png("b"), png("c")), resolved)
        assertEquals(listOf("a", "c"), next.map { it.blobRef })
    }
}
