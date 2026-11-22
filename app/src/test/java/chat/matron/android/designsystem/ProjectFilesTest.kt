package chat.matron.android.designsystem

import chat.matron.android.models.ProjectFile
import chat.matron.android.models.ProjectFileSource
import chat.matron.android.models.ProjectFilesPage
import chat.matron.android.models.TimelineProjectPage
import chat.matron.android.models.TimelineProjectRef
import chat.matron.android.viewmodels.FakeProjects
import chat.matron.android.viewmodels.FakeTimelineBase
import chat.matron.android.viewmodels.ProjectPageViewModel
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFilesTest {
    private val now = Instant.parse("2026-11-20T12:00:00Z")
    private fun file(blob: String, name: String? = null, type: String? = null, source: ProjectFileSource = ProjectFileSource.Item(41), posted: Instant? = null, caption: String? = null) =
        ProjectFile(blobID = blob, name = name, contentType = type, caption = caption, source = source, postedAt = posted)

    @Test
    fun tilesSayWhereAndWhen() {
        assertEquals("#41 · 1d", ProjectFileFormat.meta(file("b", posted = now.minusSeconds(86_400)), now))
        assertEquals("chat · 5h", ProjectFileFormat.meta(file("b", source = ProjectFileSource.Chat("c1", 9), posted = now.minusSeconds(5 * 3600)), now))
        assertEquals("#41", ProjectFileFormat.meta(file("b"), now))
        assertEquals("chat · 12d", ProjectFileFormat.meta(file("b", source = ProjectFileSource.Chat("c1", 9), posted = now.minusSeconds(12 * 86_400)), now))
        assertEquals("Launch plan", ProjectFileFormat.name(file("b", caption = "Launch\nplan")))
        assertEquals("Image", ProjectFileFormat.name(file("b", type = "image/png")))
        assertEquals("PDF", ProjectFileFormat.extension(file("b", "brief.pdf", "application/pdf")))
        assertEquals("ZIP", ProjectFileFormat.extension(file("b", "assets", "application/zip")))
        assertEquals("FILE", ProjectFileFormat.extension(file("b", "notes.markdown", null)))
        assertEquals("Video tour.mp4, #41", ProjectFileFormat.label(file("b", "tour.mp4", "video/mp4"), now))
    }

    @Test
    fun picturesOpenInTheViewerAndTheRestWhereTheyWerePosted() {
        val files = listOf(file("a", type = "image/png"), file("v", type = "video/mp4"), file("a", type = "image/png", source = ProjectFileSource.Chat("c1", 3)), file("d", "x.pdf", "application/pdf"))
        assertEquals(ProjectFileOpening.PICTURE, projectFileOpening(files[0]))
        assertEquals(ProjectFileOpening.SOURCE, projectFileOpening(files[1]))
        assertEquals(ProjectFileOpening.SOURCE, projectFileOpening(files[3]))
        assertEquals(listOf("a"), projectPictureBlobIDs(files))
    }

    @Test
    fun filesPageOnWithoutRepeats() = runBlocking {
        val first = file("a", type = "image/png")
        val projects = FakeProjects().apply {
            files[null] = ProjectFilesPage(3, listOf(first), "c1")
            files["c1"] = ProjectFilesPage(3, listOf(first, file("b", "brief.pdf"), file("c", "plan.pdf")), null)
        }
        val timeline = object : FakeTimelineBase() {
            override suspend fun timelineProject(id: String) = TimelineProjectPage(TimelineProjectRef(id, 12, "Docs site launch"))
        }
        val vm = ProjectPageViewModel("pj_docs", timeline, projects)
        vm.loadFiles()
        vm.loadMoreFiles()
        assertEquals(listOf("a", "b", "c"), vm.state.value.files?.rows?.map { it.blobID })
        assertFalse(vm.state.value.files!!.hasMore)
    }

    @Test
    fun aFailedOlderPageOffersRetryAndAReloadPagesAgain() = runBlocking {
        val first = file("a", type = "image/png")
        var fail = true
        val projects = object : FakeProjects() {
            override suspend fun projectFiles(id: String, before: String?, limit: Int?): ProjectFilesPage {
                if (before == null) return ProjectFilesPage(2, listOf(first), "c1")
                if (fail) throw chat.matron.android.journal.JournalApiError.Transport("offline")
                return ProjectFilesPage(2, listOf(file("b", "brief.pdf")), null)
            }
        }
        val timeline = object : FakeTimelineBase() {
            override suspend fun timelineProject(id: String) = TimelineProjectPage(TimelineProjectRef(id, 12, "Docs site launch"))
        }
        val vm = ProjectPageViewModel("pj_docs", timeline, projects)
        vm.loadFiles()
        val version = vm.state.value.filesVersion
        vm.loadMoreFiles()
        assertTrue(vm.state.value.filesPagingFailed)
        vm.refresh()
        assertFalse(vm.state.value.filesPagingFailed)
        assertEquals(version + 1, vm.state.value.filesVersion)
        fail = false
        vm.loadMoreFiles()
        assertEquals(listOf("a", "b"), vm.state.value.files?.rows?.map { it.blobID })
    }
}
