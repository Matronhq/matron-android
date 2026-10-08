package chat.matron.android.models

import org.junit.Assert.assertEquals
import org.junit.Test

/// The photo picker now offers videos (apple #160): their containers must map
/// to a `video/*` mime so the tray, the send path and the bridge all agree a
/// screen recording is not an image and not an opaque blob.
class StagedAttachmentTest {
    @Test
    fun videoExtensionsMapToVideoMimes() {
        assertEquals("video/mp4", StagedAttachment.mimeType(forExtension = "mp4"))
        assertEquals("video/mp4", StagedAttachment.mimeType(forExtension = "MP4"))
        assertEquals("video/quicktime", StagedAttachment.mimeType(forExtension = "mov"))
        assertEquals("video/webm", StagedAttachment.mimeType(forExtension = "webm"))
    }

    @Test
    fun imageAndUnknownExtensionsAreUnchanged() {
        assertEquals("image/png", StagedAttachment.mimeType(forExtension = "png"))
        assertEquals("application/octet-stream", StagedAttachment.mimeType(forExtension = ""))
        assertEquals("application/octet-stream", StagedAttachment.mimeType(forExtension = "zzz"))
    }
}
