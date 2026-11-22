package chat.matron.android.features.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.designsystem.AnimatedImages
import chat.matron.android.designsystem.AppLockShield
import chat.matron.android.designsystem.MatronThemeColors
import coil.compose.AsyncImage

/// Everything the share screen draws, read off [ShareViewModel] by the
/// activity, so the screen can also be drawn from fixtures.
data class ShareScreenState(
    val phase: ShareViewModel.Phase,
    val files: List<SharedFile> = emptyList(),
    val message: String = "",
    val query: String = "",
    /// The conversations the search leaves, in picker order.
    val targets: List<ShareTarget> = emptyList(),
    val hasAnyTargets: Boolean = targets.isNotEmpty(),
    val isLoadingTargets: Boolean = false,
    val destination: ShareViewModel.Destination? = null,
    val offersNewConversation: Boolean = true,
    val canSend: Boolean = false,
    val canRemoveFiles: Boolean = true,
    val errorMessage: String? = null,
) {
    val isSending: Boolean get() = phase is ShareViewModel.Phase.Sending || phase == ShareViewModel.Phase.Sent

    companion object {
        fun of(model: ShareViewModel, phase: ShareViewModel.Phase) = ShareScreenState(
            phase = phase,
            files = model.files.value,
            message = model.message.value,
            query = model.query.value,
            targets = model.visibleTargets,
            hasAnyTargets = model.targets.value.isNotEmpty(),
            isLoadingTargets = model.isLoadingTargets.value,
            destination = model.destination.value,
            offersNewConversation = model.offersNewConversation,
            canSend = model.canSend,
            canRemoveFiles = model.canRemoveFiles,
            errorMessage = model.errorMessage.value,
        )
    }
}

/// The share screen: what is being sent, an optional message, and the
/// conversation it goes to. Ported from matron-apple's `ShareView`.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    state: ShareScreenState,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    onMessageChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onSelectNewConversation: () -> Unit,
    onRemoveFile: (String) -> Unit,
    onOpenApp: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send to Matron") },
                navigationIcon = {
                    TextButton(onClick = onCancel, enabled = !state.isSending) { Text("Cancel") }
                },
                actions = {
                    if (state.phase != ShareViewModel.Phase.SignedOut) {
                        TextButton(onClick = onSend, enabled = state.canSend, modifier = Modifier.testTag("share.send")) {
                            Text("Send", fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
            )
        },
        bottomBar = { StatusBar(state.phase) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when {
                state.phase == ShareViewModel.Phase.SignedOut -> SignedOutContent(onOpenApp)
                state.phase == ShareViewModel.Phase.Loading && state.files.isEmpty() && state.targets.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> ShareForm(
                    state = state,
                    onMessageChange = onMessageChange,
                    onQueryChange = onQueryChange,
                    onSelect = onSelect,
                    onSelectNewConversation = onSelectNewConversation,
                    onRemoveFile = onRemoveFile,
                )
            }
        }
    }
}

/// The app lock is on: nothing of the share or the account is shown until
/// the user unlocks. The main app's own lock shield, under the share
/// screen's bar so the share can still be cancelled.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareLockedScreen(
    isAuthenticating: Boolean,
    errorMessage: String?,
    onUnlock: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send to Matron") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("Cancel") } },
            )
        },
    ) { padding ->
        AppLockShield(
            isAuthenticating = isAuthenticating,
            errorMessage = errorMessage,
            onUnlock = onUnlock,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun SignedOutContent(onOpenApp: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.AccountCircle,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Sign in to Matron first", style = MaterialTheme.typography.titleMedium)
        Text(
            "Open Matron and sign in, then share again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenApp) { Text("Open Matron") }
    }
}

@Composable
private fun ShareForm(
    state: ShareScreenState,
    onMessageChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onSelectNewConversation: () -> Unit,
    onRemoveFile: (String) -> Unit,
) {
    val enabled = !state.isSending
    val selectedID = (state.destination as? ShareViewModel.Destination.Conversation)?.id
    val isNew = state.destination == ShareViewModel.Destination.NewConversation
    LazyColumn(Modifier.fillMaxSize()) {
        state.errorMessage?.let { error ->
            item(key = "error") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(state.files, key = { "file-${it.id}" }) { file ->
            SharedFileRow(file, canRemove = enabled && state.canRemoveFiles) { onRemoveFile(file.id) }
        }
        item(key = "message") {
            OutlinedTextField(
                value = state.message,
                onValueChange = onMessageChange,
                placeholder = { Text("Add a message") },
                enabled = enabled,
                minLines = 1,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("share.message"),
            )
        }
        item(key = "header") {
            Text(
                "Send to",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        item(key = "search") {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search conversations") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("share.search"),
            )
        }
        when {
            !state.hasAnyTargets && state.isLoadingTargets -> item(key = "loading") {
                Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Loading conversations…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.targets.isEmpty() && !state.offersNewConversation -> item(key = "empty") {
                Text(
                    if (state.hasAnyTargets) "No conversations match." else "No conversations yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        // The Coordinator leads, a new conversation comes next, then
        // everything else by how recently it was used.
        val coordinator = state.targets.filter { it.isCoordinator }
        items(coordinator, key = { "target-${it.id}" }) { target ->
            TargetRow(target, selected = target.id == selectedID, enabled = enabled) { onSelect(target.id) }
        }
        if (state.offersNewConversation) {
            item(key = "new") {
                SelectableRow(selected = isNew, enabled = enabled, tag = "share.newConversation", onClick = onSelectNewConversation) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("New conversation")
                        if (isNew) {
                            Text(
                                "Choose a box and folder when you send",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        items(state.targets.filterNot { it.isCoordinator }, key = { "target-${it.id}" }) { target ->
            TargetRow(target, selected = target.id == selectedID, enabled = enabled) { onSelect(target.id) }
        }
    }
}

@Composable
private fun TargetRow(target: ShareTarget, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    SelectableRow(selected = selected, enabled = enabled, tag = "share.target", onClick = onClick) {
        if (target.isCoordinator) {
            Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(target.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            target.shownDetail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SelectableRow(
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag(tag)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        } else {
            Spacer(Modifier.size(24.dp))
        }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun SharedFileRow(file: SharedFile, canRemove: Boolean, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("share.file"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MatronThemeColors.current.codeBg),
            contentAlignment = Alignment.Center,
        ) {
            if (file.isImage) {
                AsyncImage(
                    model = AnimatedImages.still(LocalContext.current, file.file),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(Icons.Filled.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(file.filename, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(file.formattedSize, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (canRemove) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Cancel,
                    contentDescription = "Remove ${file.filename}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/// Progress while sending, and the confirmation after.
@Composable
private fun StatusBar(phase: ShareViewModel.Phase) {
    when (phase) {
        is ShareViewModel.Phase.Sending -> Surface(tonalElevation = 3.dp) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).testTag("share.progress"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    phase.progress.statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(progress = { phase.progress.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
        ShareViewModel.Phase.Sent -> Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).testTag("share.sent"),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MatronThemeColors.current.accent)
                Spacer(Modifier.size(8.dp))
                Text("Sent", style = MaterialTheme.typography.titleMedium)
            }
        }
        else -> Unit
    }
}
