package chat.matron.android.features.items

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.chat.MediaService
import chat.matron.android.designsystem.AttachmentFullscreenViewer
import chat.matron.android.designsystem.MarkdownPreview
import chat.matron.android.designsystem.MarkdownPreviewState
import chat.matron.android.designsystem.MarkdownPreviewTarget
import chat.matron.android.features.chat.MarkdownPreviewSheet
import chat.matron.android.designsystem.ItemDetailModel
import chat.matron.android.designsystem.ItemDetailView
import chat.matron.android.designsystem.ItemGlyph
import chat.matron.android.designsystem.ItemResolveControl
import chat.matron.android.designsystem.itemContext
import chat.matron.android.designsystem.TrackerItemLinkHost
import chat.matron.android.designsystem.TrackerItemLinkOutcome
import chat.matron.android.designsystem.rememberMessageLinkOpener
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.designsystem.PendingCommentModel
import chat.matron.android.features.chat.copyUriToTemp
import chat.matron.android.features.chat.openAttachment
import chat.matron.android.journal.ItemsSync
import chat.matron.android.journal.MatronJson
import chat.matron.android.journal.MissionsStoreReading
import chat.matron.android.models.ItemState
import chat.matron.android.models.Mission
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.viewmodels.ChatViewModel
import chat.matron.android.viewmodels.ItemDetailViewModel
import chat.matron.android.viewmodels.ItemReadMemory
import chat.matron.android.viewmodels.MediaRecorderAudioRecording
import chat.matron.android.viewmodels.VoiceRecorder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/// The image blobs a load pass must start: everything the screen hasn't
/// resolved yet, each blob once however many attachments carry it.
///
/// Deliberately NOT filtered by an "in flight" set. This effect restarts when
/// the attachment list changes, and Compose cancels the previous pass without
/// waiting for it, so a blob that pass left behind is a load that is STOPPING,
/// not one that will arrive — skipping it left the image neither loaded nor
/// queued, with nothing to start it until the list changed again (Bugbot,
/// round 2). Restarting a load that was about to be cancelled costs one
/// request; never starting it costs the image.
internal fun imageBlobsToLoad(attachments: List<TrackerAttachment>, loaded: Set<String>): List<TrackerAttachment> {
    val started = mutableSetOf<String>()
    return attachments.filter { it.blobRef !in loaded && started.add(it.blobRef) }
}

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
    /// Resolves a `[#12](matron://item/12)` link tapped inside this item's
    /// body or a comment (`AppDependencies.trackerItemLinkOutcome`).
    /// `null` (previews/tests) leaves item links inert — never
    /// handed to the OS either way.
    resolveItemLink: (suspend (Int) -> TrackerItemLinkOutcome)? = null,
    /// Opens ANOTHER tracker item from such a link by PUSHING it onto the
    /// same stack this screen sits on, so Back returns to the item the link
    /// was tapped in.
    onOpenItem: ((String) -> Unit)? = null,
    /// The conversation this detail is shown INSIDE (a chat tab's chat or its
    /// tasks page beneath it), `null` elsewhere: the context block hides the
    /// owner row when it would only point back at that conversation.
    currentConvoID: String? = null,
    /// The cached mission row, for the context block's mission label.
    /// `null` (previews/tests) draws the mission row with its `Mission #N`
    /// fallback.
    missions: MissionsStoreReading? = null,
    /// Kicks `GET /missions/:id` for the item's mission when this device has
    /// no row for it yet, so the mission row gets its name.
    refreshMission: (suspend (String) -> Unit)? = null,
    /// Opens a mission page by id (the context block's mission row).
    onOpenMission: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val item by viewModel.item.collectAsStateWithLifecycle()
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    val pending by viewModel.pendingComments.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val staged by viewModel.stagedAttachments.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val loadedCount by viewModel.loadedCommentCount.collectAsStateWithLifecycle()
    var draft by remember(viewModel.itemID) { mutableStateOf(viewModel.draft) }
    // A send that fails restores the draft on the view model, possibly after
    // this screen was left and reopened: follow it. Typing writes the same
    // value back, so this only fires on a restore.
    val vmDraft by viewModel.draftFlow.collectAsStateWithLifecycle()
    LaunchedEffect(vmDraft) { if (vmDraft != draft) draft = vmDraft }
    var previewModel by remember { mutableStateOf<Any?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    var openingBlob by remember { mutableStateOf<String?>(null) }
    val markdownPreview = remember { MarkdownPreviewState() }
    var markdownAttachment by remember { mutableStateOf<TrackerAttachment?>(null) }
    // Read once: the reader's last position for this thread.
    val startsAtBottom = remember(viewModel.itemID) { readMemory.wasAtBottom(viewModel.itemID) }

    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    // Image attachments resolve through the (authenticated) media service to
    // bytes Coil can decode; a small per-screen cache keyed by blob ref.
    // Inline images (`![caption](attachment:ref)`) are entries of these same
    // attachment lists, so this one pass loads them as well as trailing ones.
    val images = remember { mutableStateMapOf<String, Any>() }
    fun mediaURL(a: TrackerAttachment): String =
        serverURL.newBuilder().addPathSegment("media").addPathSegment(a.blobRef).build().toString()
    val imageAttachments = remember(item, comments) {
        (item?.attachments.orEmpty() + comments.flatMap { it.attachments }).filter { it.isImage }
    }
    LaunchedEffect(imageAttachments) {
        for (a in imageBlobsToLoad(imageAttachments, images.keys)) {
            launch {
                // A miss is deliberately NOT cached: `images` doubles as the
                // "already resolved" set, so storing `null` for a failed load
                // would retire that blob for the life of the screen — one
                // flaky fetch and the image never appears. Leaving the key
                // out lets the next pass try again.
                val bytes = nullOnFailure { media.image(mediaURL(a)) }
                if (bytes != null) images[a.blobRef] = bytes
            }
        }
    }

    val current = item
    // The local box + title label; `null` while loading or when this device
    // has no titled row for it — `itemContext` then falls back to the
    // journal's title, then to a generic label.
    val originID = current?.originConvoID
    val origin by produceState<String?>(initialValue = null, originID) {
        value = originID?.takeIf { it.isNotEmpty() }?.let { nullOnFailure { originLabel(it) } }
    }
    val missionID = current?.missionID
    val mission by remember(missions, missionID) {
        if (missions == null || missionID == null) flowOf<Mission?>(null) else missions.missionFlow(missionID)
    }.collectAsStateWithLifecycle(initialValue = null)
    // Once per opening (and again if the item moves to another mission),
    // only when the mission is not cached here: its normal refresh fills the
    // row. A failure leaves the `Mission #N` fallback in place.
    LaunchedEffect(missions, missionID) {
        val id = missionID ?: return@LaunchedEffect
        val store = missions ?: return@LaunchedEffect
        val refresh = refreshMission ?: return@LaunchedEffect
        if (nullOnFailure { store.missionFlow(id).first() } == null) nullOnFailure { refresh(id) }
    }

    // --- Attachment picking (the chat composer's wiring) -------------------
    // A pick is staged into the composer's tray; it goes with the next send.
    fun attachUri(uri: Uri) {
        scope.launch {
            val picked = withContext(Dispatchers.IO) { copyUriToTemp(context, uri) }
            if (picked == null) {
                viewModel.reportError("Couldn't read that file.")
                return@launch
            }
            try {
                viewModel.attachFiles(listOf(picked))
            } finally {
                // The tray holds its own staged copy.
                picked.delete()
            }
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

    fun downloadAttachment(a: TrackerAttachment) {
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
                        // Under matron-attachments/: the only cache subtree the
                        // FileProvider shares (file_paths.xml) — anywhere else
                        // getUriForFile throws and the tap does nothing.
                        val dir = File(File(context.cacheDir, "matron-attachments"), "items").apply { mkdirs() }
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

    fun openAttachment(a: TrackerAttachment) {
        if (a.isImage) {
            previewModel = images[a.blobRef] ?: mediaURL(a)
            return
        }
        // Markdown (≤ 2 MB) opens in the in-app preview; its Download
        // button comes back here through [downloadAttachment].
        if (MarkdownPreview.isPreviewableMarkdown(a)) {
            markdownPreview.open(MarkdownPreviewTarget(mediaURL(a), a.name.ifEmpty { "document.md" }))
            markdownAttachment = a
            return
        }
        downloadAttachment(a)
    }

    // Item links inside the body / comments / link chips (apple
    // #208) — one install for this whole screen, shadowing the
    // chat's host so a link pushes onto THIS stack. A link to the item
    // already on screen is a no-op rather than a second identical push.
    TrackerItemLinkHost(
        resolve = { num ->
            val resolve = resolveItemLink ?: return@TrackerItemLinkHost TrackerItemLinkOutcome.Ignore
            val outcome = resolve(num)
            if (outcome is TrackerItemLinkOutcome.Open && outcome.itemID == viewModel.itemID) TrackerItemLinkOutcome.Ignore else outcome
        },
        open = { id -> onOpenItem?.invoke(id) },
    ) { _ ->
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
                                    context = itemContext(current, currentConvoID, origin, mission),
                                    availableResolutions = viewModel.availableResolutions,
                                    isBusy = isBusy,
                                    loadedCommentCount = loadedCount,
                                    pendingTaps = ItemDetailViewModel.pendingTaps(pending),
                                    pendingItemTap = ItemDetailViewModel.pendingItemTap(pending),
                                ),
                                draft = draft,
                                onDraftChange = { draft = it; viewModel.draft = it },
                                image = { a -> images[a.blobRef] },
                                onOpenAttachment = ::openAttachment,
                                // Through the shared policy, so an item link in a link chip opens
                                    // in-app and no matron:// URL is ever handed to the OS.
                                    onOpenLink = rememberMessageLinkOpener(),
                                onOpenConversation = onOpenConversation,
                                onOpenMission = { id -> onOpenMission?.invoke(id) },
                                attachments = staged,
                                onRemoveAttachment = { id -> viewModel.removeAttachment(id) },
                                // The field clears as the send starts; a failed send
                                // puts the draft back (the VM leaves anything typed
                                // meanwhile alone), so re-read it once it settles.
                                onSubmit = {
                                    draft = ""
                                    scope.launch {
                                        try { viewModel.submitComment() } finally { draft = viewModel.draft }
                                    }
                                },
                                onTapAction = { comment, label -> scope.launch { viewModel.tapAction(comment, label) } },
                                onTapItemAction = { label -> scope.launch { viewModel.tapItemAction(label) } },
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
                                    // One comment: the draft, the recording, then the tray.
                                    onSend = {
                                        recorder.stop()?.let { note ->
                                            draft = ""
                                            scope.launch {
                                                try { viewModel.sendVoiceNote(note.file) } finally { draft = viewModel.draft }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        // Inside the link host, so a `matron://item` link in the previewed
        // file pushes onto THIS item's stack like a link in the body does.
        markdownPreview.target?.let { target ->
            MarkdownPreviewSheet(
                target = target,
                fetch = { media.fetchOutcome(target.key) },
                onDownload = { markdownAttachment?.let(::downloadAttachment) },
                onDismiss = { markdownPreview.close(); markdownAttachment = null },
            )
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

private fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
