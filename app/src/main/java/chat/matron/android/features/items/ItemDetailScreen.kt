package chat.matron.android.features.items

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.chat.MediaService
import chat.matron.android.designsystem.AttachmentFullscreenViewer
import chat.matron.android.designsystem.ItemDetailModel
import chat.matron.android.designsystem.ItemDetailView
import chat.matron.android.designsystem.ItemGlyph
import chat.matron.android.designsystem.ItemResolveControl
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.designsystem.PendingCommentModel
import chat.matron.android.features.chat.openAttachment
import chat.matron.android.journal.ItemsSync
import chat.matron.android.journal.MatronJson
import chat.matron.android.models.ItemState
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.viewmodels.ChatViewModel
import chat.matron.android.viewmodels.ItemDetailViewModel
import chat.matron.android.viewmodels.ItemReadMemory
import chat.matron.android.viewmodels.MediaRecorderAudioRecording
import chat.matron.android.viewmodels.OutgoingAttachment
import chat.matron.android.viewmodels.VoiceRecorder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/// `runCatching` for suspending work: a real failure becomes `null` (the
/// caller turns that into a banner), while cancellation is rethrown —
/// `runCatching` would swallow it, letting a cancelled load carry on writing
/// to state and be reported to the reader as a failure. The same rule
/// `ItemsSync` and the tracker view models already follow. `internal` as a
/// test seam.
internal suspend fun <T> nullOnFailure(op: suspend () -> T): T? = try {
    op()
} catch (cancel: CancellationException) {
    throw cancel
} catch (_: Throwable) {
    null
}

/// One tracker item as its own navigation route (the iOS `ItemDetailHost`
/// pushed inside the drawer's `NavigationStack`; here a top-level
/// destination so the system back returns to the list and a later port can
/// deep-link `matron://item/N` straight into it). Owns the platform bits the
/// leaf `ItemDetailView` forwards as intents: attachment picking, voice-note
/// recording, image loading through the media service, opening an
/// attachment, and the resolve menu in the top bar.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    viewModel: ItemDetailViewModel,
    media: MediaService,
    serverURL: HttpUrl,
    originLabel: suspend (String) -> String?,
    readMemory: ItemReadMemory,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val item by viewModel.item.collectAsStateWithLifecycle()
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    val pending by viewModel.pendingComments.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val loadedCount by viewModel.loadedCommentCount.collectAsStateWithLifecycle()
    var draft by remember(viewModel.itemID) { mutableStateOf(viewModel.draft) }
    var previewModel by remember { mutableStateOf<Any?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    var openingBlob by remember { mutableStateOf<String?>(null) }
    // Read once: the reader's last position for this thread.
    val startsAtBottom = remember(viewModel.itemID) { readMemory.wasAtBottom(viewModel.itemID) }

    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    // Image attachments resolve through the (authenticated) media service to
    // bytes Coil can decode; a small per-screen cache keyed by blob ref.
    val images = remember { mutableStateMapOf<String, Any?>() }
    val inFlight = remember { mutableSetOf<String>() }
    fun mediaURL(a: TrackerAttachment): String =
        serverURL.newBuilder().addPathSegment("media").addPathSegment(a.blobRef).build().toString()
    val imageAttachments = remember(item, comments) {
        (item?.attachments.orEmpty() + comments.flatMap { it.attachments }).filter { it.isImage }
    }
    LaunchedEffect(imageAttachments) {
        for (a in imageAttachments) {
            if (a.blobRef in images || !inFlight.add(a.blobRef)) continue
            launch {
                try {
                    // A miss is deliberately NOT cached: `images` doubles as
                    // the "already handled" set above, so storing `null` for a
                    // failed load would retire that blob for the life of the
                    // screen — one flaky fetch and the image never appears.
                    // Leaving the key out lets the next pass try again.
                    val bytes = nullOnFailure { media.image(mediaURL(a)) }
                    if (bytes != null) images[a.blobRef] = bytes
                } finally {
                    // Cleared whatever happened (including cancellation) so a
                    // blob is never stuck "in flight" with nothing running.
                    inFlight.remove(a.blobRef)
                }
            }
        }
    }

    val current = item
    val origin by produceState<String?>(initialValue = null, current?.originConvoID) {
        value = current?.originConvoID?.let { runCatching { originLabel(it) }.getOrNull() }
    }

    // --- Attachment picking (the composer's own wiring, trimmed) -----------
    fun attachUri(uri: Uri) {
        scope.launch {
            val picked = withContext(Dispatchers.IO) { readPicked(context, uri) }
            if (picked == null) {
                viewModel.reportError("Couldn't read that file.")
                return@launch
            }
            viewModel.submitAttachments(listOf(picked))
        }
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::attachUri) }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::attachUri) }

    // --- Voice recording (the composer's own wiring) ------------------------
    val pendingPermission = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingPermission[0]?.complete(granted); pendingPermission[0] = null
    }
    val recorder = remember {
        VoiceRecorder(
            requestPermission = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    true
                } else {
                    val deferred = CompletableDeferred<Boolean>()
                    pendingPermission[0] = deferred
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    deferred.await()
                }
            },
            makeRecorder = { file -> MediaRecorderAudioRecording(file) },
            tempDirectory = File(context.cacheDir, "voice").apply { mkdirs() },
            setKeepScreenAwake = { keepAwake ->
                context.findActivity()?.window?.let { window ->
                    if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            },
        )
    }
    val recorderState by recorder.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    fun openAttachment(a: TrackerAttachment) {
        if (a.isImage) {
            previewModel = images[a.blobRef] ?: mediaURL(a)
            return
        }
        if (openingBlob != null) return
        openingBlob = a.blobRef
        scope.launch {
            // Cleared in a `finally`: a download or a cache write that throws
            // must not leave the flag set, which would make every later
            // attachment tap on this screen a silent no-op.
            try {
                val bytes = nullOnFailure { media.image(mediaURL(a)) }
                if (bytes == null) {
                    viewModel.reportError("Couldn't download the attachment.")
                    return@launch
                }
                val file = nullOnFailure {
                    withContext(Dispatchers.IO) {
                        val dir = File(context.cacheDir, "item-attachments").apply { mkdirs() }
                        // The name comes from the server's attachment metadata,
                        // so it gets the same sanitising as a chat attachment: a
                        // `sub/file` or `../../x` would otherwise aim the write
                        // at a missing (or escaped) directory and throw.
                        val name = ChatViewModel.sanitisedAttachmentFilename(
                            a.name.ifEmpty { if (a.isAudio) "voice-note.m4a" else "attachment" },
                        )
                        File(dir, "${a.blobRef.take(12)}-$name").also { it.writeBytes(bytes) }
                    }
                }
                if (file == null) {
                    viewModel.reportError("Couldn't open the attachment.")
                    return@launch
                }
                runCatching { openAttachment(context, file) }
            } finally {
                openingBlob = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.let { "#${it.num} · ${ItemGlyph.label(it.kind)}" } ?: "Item") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (current != null) {
                        ItemResolveControl(
                            isOpen = current.state == ItemState.OPEN,
                            resolutions = viewModel.availableResolutions,
                            isBusy = isBusy,
                            onClose = { r -> scope.launch { viewModel.close(r) } },
                            onReopen = { scope.launch { viewModel.reopen() } },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            MatronTimelineBackground()
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Column(Modifier.fillMaxSize()) {
                    error?.let { message ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.dismissError() }) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        ItemDetailView(
                            model = ItemDetailModel(
                                item = current,
                                comments = comments,
                                pending = pending.map { row ->
                                    val payload = runCatching { MatronJson.decodeFromString(ItemsSync.CommentPayload.serializer(), row.payloadJson) }.getOrNull()
                                    PendingCommentModel(
                                        id = row.localID, body = payload?.body ?: "", attachmentCount = payload?.attachments?.size ?: 0,
                                        attempts = row.attempts, lastError = row.lastError,
                                    )
                                },
                                originTitle = origin,
                                availableResolutions = viewModel.availableResolutions,
                                isBusy = isBusy,
                                loadedCommentCount = loadedCount,
                            ),
                            draft = draft,
                            onDraftChange = { draft = it; viewModel.draft = it },
                            image = { a -> images[a.blobRef] },
                            onOpenAttachment = ::openAttachment,
                            onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
                            onOpenConversation = onOpenConversation,
                            onSubmit = { scope.launch { viewModel.submitComment(); draft = viewModel.draft } },
                            onAttach = { attachMenu = true },
                            onVoiceNote = {
                                scope.launch {
                                    try {
                                        recorder.start()
                                    } catch (e: VoiceRecorder.RecorderError) {
                                        viewModel.reportError(
                                            when (e) {
                                                VoiceRecorder.RecorderError.PermissionDenied -> "Microphone access is needed to record a voice note."
                                                VoiceRecorder.RecorderError.RecordFailed -> "Couldn't start recording."
                                                VoiceRecorder.RecorderError.AlreadyRecording -> "Already recording."
                                            },
                                        )
                                    }
                                }
                            },
                            startsAtBottom = startsAtBottom,
                            onBottomVisibilityChange = { atBottom -> readMemory.store(viewModel.itemID, atBottom) },
                        )
                        // The attach menu anchors at the composer's bottom-left.
                        Box(Modifier.align(Alignment.BottomStart).padding(start = 8.dp)) {
                            DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Photo or video") },
                                    leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                                    onClick = {
                                        attachMenu = false
                                        photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("File") },
                                    leadingIcon = { Icon(Icons.Outlined.InsertDriveFile, contentDescription = null) },
                                    onClick = { attachMenu = false; fileLauncher.launch("*/*") },
                                )
                            }
                        }
                        if (recorderState is VoiceRecorder.State.Recording) {
                            RecordingBar(
                                modifier = Modifier.align(Alignment.BottomCenter),
                                onCancel = { recorder.cancel() },
                                onSend = { recorder.stop()?.let { note -> scope.launch { viewModel.sendVoiceNote(note.file) } } },
                            )
                        }
                    }
                }
            }
        }
    }

    if (previewModel != null) {
        AttachmentFullscreenViewer(model = previewModel, onDismiss = { previewModel = null })
    }
}

/// Overlays the composer while a voice note records: cancel on the left,
/// send on the right — the chat composer's own recording bar.
@Composable
private fun RecordingBar(modifier: Modifier, onCancel: () -> Unit, onSend: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Delete, contentDescription = "Cancel recording") }
        Text("Recording…", style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onSend) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice note", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/// Reads a picked content [Uri] into an [OutgoingAttachment]: bytes plus the
/// provider's display name and MIME type. `null` when unreadable.
private fun readPicked(context: Context, uri: Uri): OutgoingAttachment? = runCatching {
    val resolver = context.contentResolver
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString) else null
    }?.takeIf { it.isNotBlank() } ?: "attachment"
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    OutgoingAttachment(bytes, name, mime)
}.getOrNull()

private fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
