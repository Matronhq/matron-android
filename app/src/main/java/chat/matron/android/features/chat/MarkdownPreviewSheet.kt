package chat.matron.android.features.chat

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import chat.matron.android.chat.MediaFetchOutcome
import chat.matron.android.designsystem.ItemTypography
import chat.matron.android.designsystem.LocalOpenTrackerItem
import chat.matron.android.designsystem.MarkdownPreview
import chat.matron.android.designsystem.MarkdownPreviewCache
import chat.matron.android.designsystem.MarkdownPreviewTarget
import chat.matron.android.designsystem.MarkdownText
import chat.matron.android.models.MatronDebug
import chat.matron.android.viewmodels.ChatViewModel
import java.io.File

/// Full-screen preview of a markdown attachment (chat file, item body or
/// comment attachment). Fetches the bytes through [fetch] — the app's
/// authenticated `GET /media/:blob_ref` path — renders them with the chat's
/// own [MarkdownText] after [MarkdownPreview.sanitise] has turned every image
/// into text (nothing remote ever loads), and offers Source, Copy, Share and
/// Download. [onDownload] is the attachment's existing download/open path;
/// it also takes over when the file turns out larger than the preview cap.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownPreviewSheet(
    target: MarkdownPreviewTarget,
    fetch: suspend () -> MediaFetchOutcome,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    cache: MarkdownPreviewCache = MarkdownPreviewCache.shared,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val currentFetch by rememberUpdatedState(fetch)
    val currentDownload by rememberUpdatedState(onDownload)
    val currentDismiss by rememberUpdatedState(onDismiss)
    var attempt by remember(target) { mutableIntStateOf(0) }
    var content by remember(target) { mutableStateOf<MarkdownPreview.Content>(MarkdownPreview.Content.Loading) }
    var showSource by rememberSaveable(target.key) { mutableStateOf(false) }
    // A `matron://item` link opens through the surrounding host (chat or
    // item stack); the preview closes first so the item isn't behind it.
    val hostOpenItem = LocalOpenTrackerItem.current
    val openItem: ((Int) -> Unit)? = hostOpenItem?.let { open -> { num -> currentDismiss(); open(num) } }

    LaunchedEffect(target, attempt) {
        content = MarkdownPreview.Content.Loading
        val result = MarkdownPreview.load(target.key, cache) { currentFetch() }
        if (result is MarkdownPreview.Content.TooLarge) {
            // Over the cap once fetched (no size was known up front): the
            // download/open path, as for any large file.
            currentDownload()
            currentDismiss()
        } else {
            content = result
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val loaded = content as? MarkdownPreview.Content.Loaded
        CompositionLocalProvider(LocalOpenTrackerItem provides openItem) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    },
                    title = { Text(target.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        IconButton(onClick = { showSource = !showSource }, enabled = loaded != null) {
                            if (showSource) Icon(Icons.Outlined.Article, contentDescription = "Show rendered")
                            else Icon(Icons.Filled.Code, contentDescription = "Show source")
                        }
                        IconButton(
                            onClick = {
                                loaded?.let {
                                    clipboard.setText(AnnotatedString(it.raw))
                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = loaded != null,
                        ) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy") }
                        IconButton(
                            onClick = { loaded?.let { shareMarkdown(context, target, it.raw) } },
                            enabled = loaded != null,
                        ) { Icon(Icons.Filled.Share, contentDescription = "Share") }
                        IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, contentDescription = "Download") }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (val c = content) {
                    MarkdownPreview.Content.Loading, MarkdownPreview.Content.TooLarge ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is MarkdownPreview.Content.Failed -> Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            if (c.expired) "This file is no longer available." else "Couldn't load \"${target.name}\".",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!c.expired) Button(onClick = { attempt++ }) { Text("Retry") }
                            OutlinedButton(onClick = onDownload) { Text("Download") }
                        }
                    }
                    is MarkdownPreview.Content.Loaded -> Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        if (showSource) {
                            SelectionContainer {
                                Text(
                                    c.raw,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            MarkdownText(
                                raw = c.rendered,
                                modifier = Modifier.widthIn(max = 720.dp),
                                textStyle = ItemTypography.bodyStyle(),
                                paragraphSpacing = ItemTypography.paragraphSpacing,
                                // Only http(s)/mailto/matron links survive the
                                // sanitiser; this guard is the second fence.
                                onLinkClick = { url ->
                                    if (MarkdownPreview.isAllowedLink(url)) runCatching { uriHandler.openUri(url) }
                                },
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

/// Shares the raw markdown through the platform share sheet: as a file under
/// the app's FileProvider root (`cache/matron-attachments/`) when it can be
/// written, otherwise as plain text.
private fun shareMarkdown(context: Context, target: MarkdownPreviewTarget, raw: String) {
    val name = ChatViewModel.sanitisedAttachmentFilename(target.name.ifBlank { "document.md" })
    val fileIntent = runCatching {
        val dir = File(File(File(context.cacheDir, "matron-attachments"), "md-preview"), ChatViewModel.attachmentURLDigest(target.key))
            .apply { mkdirs() }
        val file = File(dir, name).also { it.writeText(raw, Charsets.UTF_8) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        Intent(Intent.ACTION_SEND)
            .setType("text/markdown")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .also { it.clipData = ClipData.newUri(context.contentResolver, name, uri) }
    }.onFailure { MatronDebug.breadcrumb("markdown preview: share as file failed: $it") }.getOrNull()
    val intent = fileIntent ?: Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, name)
        .putExtra(Intent.EXTRA_TEXT, raw)
    runCatching { context.startActivity(Intent.createChooser(intent, name)) }
        .onFailure { MatronDebug.breadcrumb("markdown preview: share failed: $it") }
}

/// A tap on a chat file chip: everything the tap handler needs to choose
/// between the markdown preview and the download/open path.
data class FileTap(val url: String, val filename: String, val mime: String? = null, val sizeBytes: Long? = null) {
    val isPreviewableMarkdown: Boolean
        get() = MarkdownPreview.isPreviewableMarkdown(filename, mime, sizeBytes?.takeIf { it > 0 })
}
