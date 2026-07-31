package chat.matron.android.features.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.AttachmentTray
import chat.matron.android.designsystem.UploadProgressBar
import chat.matron.android.models.BotCommand
import chat.matron.android.viewmodels.ComposerDraftMemory
import chat.matron.android.viewmodels.ComposerViewModel
import chat.matron.android.viewmodels.MediaRecorderAudioRecording
import chat.matron.android.viewmodels.VoiceRecorder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * Message composer. Ports the Features/Chat/Composer Swift views: a growing text field
 * with a slash-command / recent-folder palette above it, an attachment tray, and
 * a plus (attach) / mic (record) / send accessory column.
 *
 * Platform adaptations: PhotosPicker/fileImporter → Android Photo Picker +
 * GetContent (ActivityResultContracts) copying the picked [Uri] to a temp file
 * fed to [ComposerViewModel.attachFiles]; AVAudioRecorder → [VoiceRecorder] over
 * [MediaRecorderAudioRecording] with a RECORD_AUDIO runtime request. The paste
 * hooks (ComposerPasteSupport) are handled by the text field's own clipboard.
 *
 * [ComposerViewModel.input] is a plain var and `canSend`/`showPalette`/
 * `filteredCommands`/`folderSuggestions` computed getters off it, so the field
 * mirrors the var, writes through on edit (+ `handleInputChange()`), and re-syncs
 * after a VM-driven mutation (command/folder pick).
 */
@Composable
fun ComposerView(viewModel: ComposerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val staged by viewModel.stagedAttachments.collectAsStateWithLifecycle()
    val sendError by viewModel.sendError.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()

    // Keyed to the room: without this, swapping the VM at the same call site
    // (navigating to a different room) leaves the previous room's typed text
    // sitting in the field until something else happens to overwrite it.
    var text by remember(viewModel.roomID) { mutableStateOf(viewModel.input) }
    fun syncFromVm() { text = viewModel.input }

    // --- Attachment picking -------------------------------------------------
    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) scope.launch { attachUri(context, viewModel, uri) }
    }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) scope.launch { attachUri(context, viewModel, uri) }
    }

    // --- Voice recording ----------------------------------------------------
    val pendingPermission = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> pendingPermission[0]?.complete(granted); pendingPermission[0] = null }
    val recorder = remember {
        VoiceRecorder(
            requestPermission = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
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
        )
    }
    val recorderState by recorder.state.collectAsStateWithLifecycle()

    // Restore any per-room draft on first appearance; persist on disappear.
    DisposableEffect(viewModel.roomID) {
        // ComposerViewModel instances are cached per-room (ChatVMCache) and
        // reused on revisit, so a `sendError` left undismissed from a prior
        // visit would otherwise resurface here as if it just happened.
        viewModel.dismissError()
        if (viewModel.input.isEmpty()) {
            ComposerDraftMemory.retrieve(viewModel.roomID)?.let { draft ->
                viewModel.input = draft
                syncFromVm()
            }
        }
        onDispose {
            ComposerDraftMemory.store(viewModel.roomID, viewModel.input)
            recorder.cancel()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (viewModel.showPalette) {
            SlashCommandPalette(
                commands = viewModel.filteredCommands,
                folders = viewModel.folderSuggestions,
                onSelect = { cmd -> viewModel.selectCommand(cmd); syncFromVm() },
                onSelectFolder = { folder -> viewModel.selectFolder(folder); syncFromVm() },
            )
        }

        sendError?.let { message ->
            ComposerErrorBanner(message = message, onDismiss = { viewModel.dismissError() })
        }

        // Determinate upload feedback: on a slow uplink a multi-MB screenshot
        // otherwise spends many seconds behind a bare disabled send button,
        // which reads as the app hanging.
        uploadProgress?.let { upload ->
            UploadProgressBar(label = upload.label, fraction = upload.fraction)
        }

        if (recorderState is VoiceRecorder.State.Recording) {
            RecordingBar(
                onCancel = { recorder.cancel() },
                onSend = {
                    recorder.stop()?.let { note ->
                        scope.launch { viewModel.sendVoiceNote(note.file, note.duration) }
                    }
                },
            )
        } else {
            AttachmentTray(attachments = staged, onRemove = { id -> viewModel.removeAttachment(id) })

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (ComposerViewModel.MEDIA_AVAILABLE) {
                    AttachMenu(
                        onPickPhoto = {
                            photoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onPickFile = { fileLauncher.launch("*/*") },
                    )
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { new ->
                        text = new
                        viewModel.input = new
                        viewModel.handleInputChange()
                    },
                    placeholder = { Text("Message…") },
                    maxLines = 8,
                    // iOS gives the field a `.regularMaterial` rounded-16
                    // backing; without an opaque container the timeline's
                    // cream gradient shows through the field.
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.weight(1f),
                )

                val canSend = viewModel.canSend
                if (!canSend && ComposerViewModel.MEDIA_AVAILABLE) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                try {
                                    recorder.start()
                                } catch (error: VoiceRecorder.RecorderError) {
                                    viewModel.reportAttachmentError(voiceRecorderErrorMessage(error))
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "Record voice note")
                    }
                } else {
                    IconButton(
                        onClick = { scope.launch { viewModel.send(); syncFromVm() } },
                        enabled = canSend && !isSending,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachMenu(onPickPhoto: () -> Unit, onPickFile: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Add, contentDescription = "Attach")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Photo") }, onClick = { open = false; onPickPhoto() })
            DropdownMenuItem(text = { Text("File") }, onClick = { open = false; onPickFile() })
        }
    }
}

@Composable
private fun RecordingBar(onCancel: () -> Unit, onSend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
        Text("Recording…", modifier = Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text("Cancel") }
        IconButton(onClick = onSend) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice note", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Dismissible inline banner for [ComposerViewModel.sendError]: send /
 * attachment / voice-note failures the view model records but has no
 * `matron-apple` presentation to mirror (its composer records `sendError`
 * but no shell renders it either — this is the Android-side fix).
 */
@Composable
private fun ComposerErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss error", tint = MaterialTheme.colorScheme.error)
        }
    }
}

/** User-facing copy for a [VoiceRecorder.RecorderError] thrown by [VoiceRecorder.start]. */
private fun voiceRecorderErrorMessage(error: VoiceRecorder.RecorderError): String = when (error) {
    VoiceRecorder.RecorderError.PermissionDenied -> "Microphone access is needed to record a voice note."
    VoiceRecorder.RecorderError.RecordFailed -> "Couldn't start recording."
    VoiceRecorder.RecorderError.AlreadyRecording -> "Already recording."
}

/**
 * Drop-down palette above the composer. Ports Composer/SlashCommandPalette.swift:
 * recent-folder rows when [folders] is non-empty, otherwise the filtered command
 * rows. The two modes are mutually exclusive; folders win.
 */
@Composable
fun SlashCommandPalette(
    commands: List<BotCommand>,
    folders: List<String>,
    onSelect: (BotCommand) -> Unit,
    onSelectFolder: (String) -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
            if (folders.isNotEmpty()) {
                items(folders, key = { "folder-$it" }) { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            folder,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .weight(1f)
                                .padding(0.dp),
                        )
                        TextButton(onClick = { onSelectFolder(folder) }) { Text("Use") }
                    }
                    HorizontalDivider()
                }
            } else {
                items(commands, key = { it.trigger }) { cmd ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(cmd.trigger, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            cmd.argHint?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            TextButton(onClick = { onSelect(cmd) }, modifier = Modifier.padding(0.dp)) { Text("Insert") }
                        }
                        Text(cmd.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Copies a picked content [Uri] to a temp file and stages it on the composer. */
private suspend fun attachUri(context: Context, viewModel: ComposerViewModel, uri: Uri) {
    val file = copyUriToTemp(context, uri) ?: run {
        viewModel.reportAttachmentError("Couldn't read that file.")
        return
    }
    viewModel.attachFiles(listOf(file))
}

private fun copyUriToTemp(context: Context, uri: Uri): File? = runCatching {
    val resolver = context.contentResolver
    val name = displayName(context, uri) ?: run {
        val ext = resolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        "picked-${UUID.randomUUID()}" + (ext?.let { ".$it" } ?: "")
    }
    val dir = File(context.cacheDir, "picked").apply { mkdirs() }
    val out = File(dir, "${UUID.randomUUID()}-$name")
    resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
        ?: return null
    out
}.getOrNull()

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else {
            null
        }
    }
}.getOrNull()
