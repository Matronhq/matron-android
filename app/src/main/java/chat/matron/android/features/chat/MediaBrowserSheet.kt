package chat.matron.android.features.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.AttachmentFullscreenViewer
import chat.matron.android.designsystem.MediaBrowserFileRow
import chat.matron.android.designsystem.MediaBrowserLinkRow
import chat.matron.android.designsystem.MediaBrowserMediaCell
import chat.matron.android.designsystem.MediaBrowserView
import chat.matron.android.viewmodels.ChatViewModel
import chat.matron.android.viewmodels.MediaBrowserViewModel
import kotlinx.coroutines.launch

/// Android presentation of the per-chat media & links browser. Ports apple
/// #142's `MediaBrowserSheet` (+ #144's Files-tab preview routing): owns the
/// [MediaBrowserViewModel], maps its entries into the design-system structs,
/// and routes taps into the same paths the timeline uses —
///
/// - images: fetch (spinner while in flight, re-taps join the running fetch)
///   → [AttachmentFullscreenViewer];
/// - files: `ChatViewModel.writeTempFile` (shared digest-namespaced temp
///   cache + `downloadingFiles` spinner) → [openAttachment]. Where iOS
///   previews in-app via QuickLook (apple #143/#144), Android has no
///   in-process universal previewer — the downloaded file goes to the system
///   viewer through the app's FileProvider (`ACTION_VIEW`, share-sheet
///   fallback), the platform's substitution for the same "tap → see the
///   attachment" contract;
/// - links: the system URI handler, per the MarkdownText link precedent.
///
/// Expired items (reaper tombstones, and 404s discovered by either this
/// sheet's fetches or the timeline's) render dimmed with no tap affordance.
@Composable
fun MediaBrowserSheet(
    chatVM: ChatViewModel,
    viewModelFactory: (kotlinx.coroutines.CoroutineScope) -> MediaBrowserViewModel,
) {
    val scope = rememberCoroutineScope()
    val vm = remember { viewModelFactory(scope) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(vm) {
        vm.load()
        loaded = true
    }

    val mediaItems by vm.mediaItems.collectAsStateWithLifecycle()
    val fileItems by vm.fileItems.collectAsStateWithLifecycle()
    val links by vm.links.collectAsStateWithLifecycle()
    val loadFailed by vm.loadFailed.collectAsStateWithLifecycle()
    // The browser's own 404 discoveries…
    val browserUnavailable by vm.unavailableMedia.collectAsStateWithLifecycle()
    // …plus the timeline's (a chip tap may have learned of a reaped blob
    // before this sheet ever opened), and the shared download spinner state.
    val timelineUnavailable by chatVM.unavailableMedia.collectAsStateWithLifecycle()
    val downloadingFiles by chatVM.downloadingFiles.collectAsStateWithLifecycle()
    // Surfaced here because the timeline's banner (TimelineList) sits behind
    // this sheet — a failed download would otherwise be a silent dead tap.
    val attachmentError by chatVM.attachmentError.collectAsStateWithLifecycle()

    /// Media URLs whose full-size fetch is currently in flight — guards the
    /// image tap against re-entrant taps and drives the grid cell's spinner.
    /// Sheet-local (not `ChatViewModel`) because the browser owns its own
    /// media fetches, mirroring apple #142's `openingMedia`.
    var openingMedia by remember { mutableStateOf(setOf<String>()) }
    var preview by remember { mutableStateOf<ByteArray?>(null) }

    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    fun isUnavailable(url: String?): Boolean =
        url != null && (url in browserUnavailable || url in timelineUnavailable)

    Box(Modifier.fillMaxSize()) {
        if (!loaded) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            MediaBrowserView(
                media = mediaItems.map { entry ->
                    MediaBrowserMediaCell(
                        id = entry.id,
                        url = entry.url,
                        expired = entry.expired || isUnavailable(entry.url),
                        isLoading = entry.url != null && entry.url in openingMedia,
                    )
                },
                files = fileItems.map { entry ->
                    MediaBrowserFileRow(
                        id = entry.id,
                        url = entry.url,
                        name = entry.name,
                        sizeBytes = entry.sizeBytes,
                        expired = entry.expired || isUnavailable(entry.url),
                        isLoading = entry.url != null && entry.url in downloadingFiles,
                    )
                },
                links = links.map { MediaBrowserLinkRow(it.id, it.url, it.context, it.timestamp) },
                modifier = Modifier.fillMaxSize(),
                loadFailed = loadFailed,
                thumbnail = { url -> vm.thumbnail(url) },
                onMediaTap = { cell ->
                    val url = cell.url
                    if (url != null && !cell.expired && url !in openingMedia) {
                        scope.launch {
                            openingMedia = openingMedia + url
                            try {
                                // Same bytes/cache as the grid thumbnail (the
                                // VM caches the full fetch); the viewer's Coil
                                // load decodes them at screen size.
                                vm.thumbnail(url)?.let { preview = it }
                            } finally {
                                openingMedia = openingMedia - url
                            }
                        }
                    }
                },
                onFileTap = { row ->
                    val url = row.url
                    if (url != null && !row.expired) {
                        // Download through the shared temp-file cache, then
                        // hand to the system viewer — the QuickLook
                        // substitution (apple #143/#144); spinner + tap-dedup
                        // + error banner come from writeTempFile's contract.
                        scope.launch {
                            chatVM.writeTempFile(url, row.name, context.cacheDir)
                                ?.let { openAttachment(context, it) }
                        }
                    }
                },
                onLinkTap = { row -> runCatching { uriHandler.openUri(row.url) } },
            )
        }

        attachmentError?.let { message ->
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    preview?.let { bytes ->
        AttachmentFullscreenViewer(model = bytes, onDismiss = { preview = null })
    }
}
