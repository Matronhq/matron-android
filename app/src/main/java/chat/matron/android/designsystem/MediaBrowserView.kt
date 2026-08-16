package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.Instant

/// The browser's three tabs. Ports apple #142's `MediaBrowserView.Tab`
/// (SwiftUI segmented picker → M3 [TabRow]).
enum class MediaBrowserTab(val title: String) {
    Media("Media"), Files("Files"), Links("Links"),
}

/// One grid cell of the Media tab. Ports apple #142's
/// `MediaBrowserView.MediaCell`; the URL is a plain `String` per the app-wide
/// media-URL representation.
data class MediaBrowserMediaCell(
    val id: Long,
    val url: String?,
    val expired: Boolean,
    /// Whether this cell's full-size open is currently in flight — drives the
    /// spinner overlay so a re-tap reads as "hold on" rather than a dead tap.
    val isLoading: Boolean = false,
)

/// One row of the Files tab. Ports apple #142's `MediaBrowserView.FileRow`.
data class MediaBrowserFileRow(
    val id: Long,
    val url: String?,
    val name: String,
    val sizeBytes: Long?,
    val expired: Boolean,
    val isLoading: Boolean,
)

/// One row of the Links tab. Ports apple #142's `MediaBrowserView.LinkRow`.
data class MediaBrowserLinkRow(
    val id: String,
    val url: String,
    val context: String,
    val timestamp: Instant,
)

/// Per-chat "Media, Files and Links" browser body — WhatsApp's media browser,
/// Matron-shaped. Pure data + closures (design-system components cannot see
/// view models); the feature sheet owns the `MediaBrowserViewModel` and maps
/// its entries into the row/cell structs. Ports apple #142's
/// `MediaBrowserView` (SwiftUI segmented picker + LazyVGrid → M3 TabRow +
/// LazyVerticalGrid; thumbnails render byte payloads through Coil, which
/// decodes to the cell's target size — the Swift ImageIO downscale lives in
/// the VM there and is unnecessary here).
@Composable
fun MediaBrowserView(
    media: List<MediaBrowserMediaCell>,
    files: List<MediaBrowserFileRow>,
    links: List<MediaBrowserLinkRow>,
    modifier: Modifier = Modifier,
    loadFailed: Boolean = false,
    initialTab: MediaBrowserTab = MediaBrowserTab.Media,
    thumbnail: suspend (String) -> ByteArray? = { null },
    /// Bumped by the caller when [thumbnail]'s backing cache gains new bytes —
    /// re-keys the loaders of still-empty cells so a transiently-failed
    /// placeholder retries after a later successful fetch.
    thumbnailVersion: Int = 0,
    onMediaTap: (MediaBrowserMediaCell) -> Unit = {},
    onFileTap: (MediaBrowserFileRow) -> Unit = {},
    onLinkTap: (MediaBrowserLinkRow) -> Unit = {},
) {
    var tab by remember { mutableStateOf(initialTab) }
    Column(modifier) {
        TabRow(selectedTabIndex = tab.ordinal) {
            MediaBrowserTab.entries.forEach { candidate ->
                Tab(
                    selected = tab == candidate,
                    onClick = { tab = candidate },
                    text = { Text(candidate.title) },
                )
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (loadFailed) {
                BrowserEmptyState(label = MEDIA_BROWSER_LOAD_FAILED_LABEL, icon = Icons.Filled.Warning)
            } else {
                when (tab) {
                    MediaBrowserTab.Media -> MediaGrid(media, thumbnail, thumbnailVersion, onMediaTap)
                    MediaBrowserTab.Files -> FileList(files, onFileTap)
                    MediaBrowserTab.Links -> LinkList(links, onLinkTap)
                }
            }
        }
    }
}

@Composable
private fun MediaGrid(
    media: List<MediaBrowserMediaCell>,
    thumbnail: suspend (String) -> ByteArray?,
    thumbnailVersion: Int,
    onMediaTap: (MediaBrowserMediaCell) -> Unit,
) {
    if (media.isEmpty()) {
        BrowserEmptyState(
            label = mediaBrowserEmptyLabel(MediaBrowserTab.Media),
            icon = Icons.Filled.PhotoLibrary,
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
    ) {
        items(media, key = { it.id }) { cell ->
            MediaThumbCell(
                cell = cell,
                thumbnail = thumbnail,
                thumbnailVersion = thumbnailVersion,
                onTap = { onMediaTap(cell) },
            )
        }
    }
}

@Composable
private fun FileList(
    files: List<MediaBrowserFileRow>,
    onFileTap: (MediaBrowserFileRow) -> Unit,
) {
    if (files.isEmpty()) {
        BrowserEmptyState(
            label = mediaBrowserEmptyLabel(MediaBrowserTab.Files),
            icon = Icons.Filled.InsertDriveFile,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(files.size, key = { files[it].id }) { index ->
            val row = files[index]
            AttachmentFile(
                filename = row.name,
                sizeBytes = row.sizeBytes,
                isLoading = row.isLoading,
                isExpired = row.expired,
                onTap = { onFileTap(row) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun LinkList(
    links: List<MediaBrowserLinkRow>,
    onLinkTap: (MediaBrowserLinkRow) -> Unit,
) {
    if (links.isEmpty()) {
        BrowserEmptyState(
            label = mediaBrowserEmptyLabel(MediaBrowserTab.Links),
            icon = Icons.Filled.Link,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(links.size, key = { links[it].id }) { index ->
            val row = links[index]
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onLinkTap(row) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    row.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.context,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Coarse relative age, per the chat-list rows (Apple renders
                // "2 days ago"; RelativeMinuteTime is this app's precedent).
                Text(
                    RelativeMinuteTime.format(row.timestamp, Instant.now()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            HorizontalDivider(Modifier.padding(start = 16.dp))
        }
    }
}

/// One grid cell: async thumbnail with a placeholder; expired renders the
/// dimmed Schedule treatment (mirrors [AttachmentFile]'s expired glyph —
/// Apple's clock-badge fallback). Ports apple #142's `MediaThumbCell`.
@Composable
private fun MediaThumbCell(
    cell: MediaBrowserMediaCell,
    thumbnail: suspend (String) -> ByteArray?,
    thumbnailVersion: Int,
    onTap: () -> Unit,
) {
    // Not produceState: a version bump must retry only still-empty cells (a
    // one-shot transient failure otherwise pins the placeholder forever, even
    // after the bytes land in the cache via a full-size open — Bugbot, PR
    // #45), and restarting a producer would blank already-filled cells for a
    // frame and refetch them.
    var bytes by remember(cell.url) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(cell.url, cell.expired, thumbnailVersion) {
        if (bytes == null && !cell.expired && cell.url != null) bytes = thumbnail(cell.url)
    }
    val description = mediaCellContentDescription(expired = cell.expired, isLoading = cell.isLoading)
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .then(
                if (!cell.expired && !cell.isLoading) Modifier.clickable { onTap() } else Modifier
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when {
            cell.expired -> Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            bytes != null -> AsyncImage(
                model = bytes,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Icon(
                Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        // While the full-size open is in flight, mirror AttachmentFile's
        // spinner so a re-tap reads as "hold on" rather than a dead tap.
        if (cell.isLoading) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun BrowserEmptyState(label: String, icon: ImageVector) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

internal const val MEDIA_BROWSER_LOAD_FAILED_LABEL = "Couldn't load"

/// Empty-state copy per tab. Pure so the copy apple #142 pins with snapshot
/// baselines (`MediaBrowserSnapshotTests.test_*_empty`) is unit-testable —
/// this project's conventions replace snapshots with pure-function tests.
internal fun mediaBrowserEmptyLabel(tab: MediaBrowserTab): String = when (tab) {
    MediaBrowserTab.Media -> "No media yet"
    MediaBrowserTab.Files -> "No files yet"
    MediaBrowserTab.Links -> "No links yet"
}

/// A grid cell's accessibility label: expired wins over loading (an expired
/// cell never loads). Pure port of apple #142's `MediaThumbCell`
/// accessibilityLabel expression.
internal fun mediaCellContentDescription(expired: Boolean, isLoading: Boolean): String = when {
    expired -> "Image, expired"
    isLoading -> "Image, loading"
    else -> "Image"
}
