package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.models.ProjectFile
import chat.matron.android.models.ProjectFileSource
import chat.matron.android.models.TimelineMedia
import coil.compose.AsyncImage
import java.time.Instant

/// A project's files in words: a tile's caption, name and extension. Ported
/// from matron-apple's `ProjectFeedFormat`.
object ProjectFileFormat {
    const val EMPTY = "No files yet."

    /// A tile's caption under its name: "#41 · 1d", "chat · 5d"; no age when
    /// the journal sent no `posted_at`.
    fun meta(file: ProjectFile, now: Instant): String {
        val source = when (val s = file.source) {
            is ProjectFileSource.Item -> "#${s.num}"
            is ProjectFileSource.Chat -> "chat"
        }
        val posted = file.postedAt ?: return source
        return "$source · ${ago(posted, now)}"
    }

    /// "now", "12m", "5h", "9d": how long ago, at most in days.
    fun ago(at: Instant, now: Instant): String {
        val seconds = (now.epochSecond - at.epochSecond).coerceAtLeast(0)
        return when {
            seconds < 60 -> "now"
            seconds < 3600 -> "${seconds / 60}m"
            seconds < 86_400 -> "${seconds / 3600}h"
            else -> "${seconds / 86_400}d"
        }
    }

    /// A file's name, falling back to its caption, then its kind.
    fun name(file: ProjectFile): String = file.name?.takeIf { it.isNotEmpty() }
        ?: file.caption?.takeIf { it.isNotEmpty() }?.let(ProjectFormat::oneLine)
        ?: when {
            file.isVideo -> "Video"
            file.isImage -> "Image"
            else -> "File"
        }

    /// The document tile's label: the name's extension ("PDF"), else the
    /// content type's subtype ("ZIP"), else "FILE". At most 4 characters.
    fun extension(file: ProjectFile): String {
        val name = file.name ?: ""
        val fromName = if ('.' in name) name.substringAfterLast('.') else ""
        val fromType = file.contentType?.substringAfterLast('/') ?: ""
        val raw = fromName.ifEmpty { fromType }
        if (raw.isEmpty() || raw.length > 4 || !raw.all { it.isLetterOrDigit() }) return "FILE"
        return raw.uppercase()
    }

    /// What a tile says aloud: its kind, name and caption.
    fun label(file: ProjectFile, now: Instant): String {
        val kind = when {
            file.isVideo -> "Video"
            file.isImage -> "Image"
            else -> "File"
        }
        // A file with no name of its own is named by its kind: said once.
        val name = name(file)
        return if (name == kind) "$kind, ${meta(file, now)}" else "$kind $name, ${meta(file, now)}"
    }
}

/// How a file of the Files section opens.
enum class ProjectFileOpening { PICTURE, SOURCE }

/// A picture opens in the image viewer; anything else (a video, a document)
/// opens where it was posted.
fun projectFileOpening(file: ProjectFile): ProjectFileOpening = if (file.isImage) ProjectFileOpening.PICTURE else ProjectFileOpening.SOURCE

/// The pictures the viewer steps through, in the grid's order: a picture
/// attached twice is one picture.
fun projectPictureBlobIDs(files: List<ProjectFile>): List<String> = files.filter { it.isImage }.map { it.blobID }.distinct()

/// A project's files, newest first: three to a row on a wide page, two on a
/// narrow one. A picture is its thumbnail, a video the dark play panel, and
/// anything else a tile naming its kind; each has its name and where it was
/// posted under it. Reaching the end asks for more while [hasMore]. Ported
/// from matron-apple's `ProjectFilesGridView`.
@Composable
fun ProjectFilesGridView(
    files: List<ProjectFile>,
    images: Map<String, Any>,
    now: Instant,
    hasMore: Boolean,
    /// A tile was tapped: the file, and the picture the tile shows (null
    /// while it hasn't loaded), so the viewer can open on it at once.
    onOpenFile: (ProjectFile, Any?) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    /// Bumped by each reload of the first page, so the end asks again.
    version: Int = 0,
    /// The last next-page read failed: the end offers Retry instead.
    pagingFailed: Boolean = false,
    /// Loads a picture's bytes while its tile is on screen; null shows only
    /// what [images] holds.
    loadImage: (suspend (String) -> Any?)? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 700.dp) 3 else 2
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().testTag("projects.files"),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (files.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(ProjectFileFormat.EMPTY, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(files, key = { it.id }) { file ->
                // A tile loads its picture while it is on screen and lets go of
                // it when it scrolls away; the host's cache bounds what is kept.
                // Keyed on the file alone: the host's loader is a new lambda
                // on each recomposition, and a load must not restart with it.
                val load by rememberUpdatedState(loadImage)
                val image by produceState(images[file.blobID], file.blobID) {
                    if (value == null && file.isImage) load?.let { value = it(file.blobID) }
                }
                ProjectFileTile(file, image, now, onOpen = { onOpenFile(file, image) })
            }
            if (hasMore && pagingFailed) {
                item(key = "retry:${files.size}", span = { GridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(TimelineListCopy.MORE_FAILED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onLoadMore, modifier = Modifier.testTag("projects.files.retry")) { Text(TimelineFormat.RETRY) }
                    }
                }
            } else if (hasMore) {
                item(key = "more:$version:${files.size}", span = { GridItemSpan(maxLineSpan) }) {
                    LaunchedEffect(version, files.size) { onLoadMore() }
                    Spacer(Modifier.height(1.dp))
                }
            }
        }
    }
}

/// One tile of a project's files: the picture's thumbnail (a grey
/// placeholder until loaded), a video's dark play panel, or a document tile
/// naming the extension; then the name and "#41 · 1d" / "chat · 5d".
@Composable
fun ProjectFileTile(file: ProjectFile, image: Any?, now: Instant, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .testTag("projects.files.${file.id}")
            .clearAndSetSemantics { openable(ProjectFileFormat.label(file, now), onOpen, actionLabel = "Open") },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(shape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), shape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                file.isVideo -> TimelineMediaView(TimelineMedia(file.blobID, file.contentType ?: "", file.name ?: ""), null, Modifier.fillMaxSize())
                image != null -> AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                file.isImage -> Icon(Icons.Outlined.Image, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                else -> Text(
                    ProjectFileFormat.extension(file), color = MatronRed, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.border(1.5.dp, MatronRed, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 4.dp),
                )
            }
        }
        Text(ProjectFileFormat.name(file), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(ProjectFileFormat.meta(file, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
