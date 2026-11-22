package chat.matron.android.features.share

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareItemLoaderTest {
    private val uri = Uri.parse("content://com.example.docs/1")
    private val work = Files.createTempDirectory("share-work").toFile()

    private class Source(
        val name: String? = "report.pdf",
        val size: Long? = null,
        val mime: String? = "application/pdf",
        val bytes: ByteArray? = ByteArray(10) { it.toByte() },
    ) : SharedItemSource {
        override fun displayName(uri: Uri) = name
        override fun declaredSize(uri: Uri) = size
        override fun type(uri: Uri) = mime
        override fun open(uri: Uri): InputStream? = bytes?.let(::ByteArrayInputStream)
    }

    @Test
    fun copiesIntoADirectoryOfItsOwnUnderItsName() {
        val file = ShareItemLoader.load(uri, Source(), work)
        assertEquals("report.pdf", file.filename)
        assertEquals("application/pdf", file.mimeType)
        assertEquals(10L, file.sizeBytes)
        assertEquals(work, file.file.parentFile!!.parentFile)
        assertTrue(file.file.readBytes().contentEquals(ByteArray(10) { it.toByte() }))
        file.delete()
        assertTrue(work.listFiles()!!.isEmpty())
    }

    @Test
    fun aNamelessPictureIsNamedAndTypedFromItsType() {
        val file = ShareItemLoader.load(uri, Source(name = null, mime = "image/png"), work)
        assertEquals("shared-image.png", file.filename)
        assertTrue(file.isImage)
    }

    @Test
    fun aVagueTypeFallsBackToTheExtension() {
        val file = ShareItemLoader.load(uri, Source(name = "clip.mov", mime = "application/octet-stream"), work)
        assertEquals("video/quicktime", file.mimeType)
    }

    @Test
    fun aPathInTheNameCannotLeaveTheDirectory() {
        val file = ShareItemLoader.load(uri, Source(name = "../../escape.txt", mime = "text/plain"), work)
        assertEquals("escape.txt", file.filename)
        assertEquals(work, file.file.parentFile!!.parentFile)
    }

    @Test
    fun aDeclaredSizeOverTheLimitIsRefusedBeforeCopying() {
        try {
            ShareItemLoader.load(uri, Source(size = ShareItemLoader.MAX_BYTES + 1), work)
            fail("expected TooLarge")
        } catch (error: ShareLoadError.TooLarge) {
            assertEquals("report.pdf is too large to send. The limit is 100.0 MB.", error.message)
        }
        assertTrue(work.listFiles()!!.isEmpty())
    }

    @Test
    fun aCopyThatRunsOverTheLimitStopsAndLeavesNothing() {
        try {
            ShareItemLoader.load(uri, Source(size = null, bytes = ByteArray(200)), work, limit = 100)
            fail("expected TooLarge")
        } catch (_: ShareLoadError.TooLarge) {
        }
        assertTrue(work.listFiles()!!.isEmpty())
    }

    @Test
    fun anUnopenableItemIsUnreadable() {
        try {
            ShareItemLoader.load(uri, Source(bytes = null), work)
            fail("expected Unreadable")
        } catch (error: ShareLoadError) {
            assertEquals(ShareLoadError.Unreadable, error)
        }
        assertTrue(work.listFiles()!!.isEmpty())
    }

    @Test
    fun onlyOldWorkDirectoriesAreCleared() {
        val now = System.currentTimeMillis()
        val keep = java.io.File(work, "keep").apply { mkdirs(); setLastModified(now - 2 * ShareItemLoader.STALE_AFTER_MILLIS) }
        java.io.File(work, "old").apply { mkdirs(); setLastModified(now - 2 * ShareItemLoader.STALE_AFTER_MILLIS) }
        // Another share, still on screen.
        java.io.File(work, "live").apply { mkdirs(); setLastModified(now - 60_000) }
        ShareItemLoader.clearStale(work, keep, now)
        assertEquals(setOf("keep", "live"), work.listFiles()!!.map { it.name }.toSet())
    }
}
