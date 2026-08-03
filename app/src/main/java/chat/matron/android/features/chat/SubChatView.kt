package chat.matron.android.features.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.AttachmentFullscreenViewer
import chat.matron.android.designsystem.ContextGaugeLabel
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.viewmodels.ChatViewModel
import chat.matron.android.viewmodels.SubChatStripViewModel

/**
 * Read-only viewer for a subagent child conversation. Ports `SubChatView`: the
 * shared [TimelineList] with NO composer under a mini-header carrying the child's
 * title, model, context gauge, running/finished state, and a sibling switcher.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubChatView(
    chatVM: ChatViewModel,
    stripVM: SubChatStripViewModel,
    childID: String,
    fallbackTitle: String,
    onBack: () -> Unit,
    onSwitchTo: (String) -> Unit,
) {
    val children by stripVM.children.collectAsStateWithLifecycle()
    val sessionStatus by chatVM.sessionStatus.collectAsStateWithLifecycle()
    val currentChild = children.firstOrNull { it.id == childID }
    val title = currentChild?.title ?: fallbackTitle
    val isRunning = currentChild?.isRunning ?: true

    var previewModel by remember { mutableStateOf<Any?>(null) }
    var showSwitcher by remember { mutableStateOf(false) }

    DisposableEffect(chatVM, stripVM) {
        val chatGeneration = chatVM.observationGeneration + 1
        stripVM.start()
        val stripGeneration = stripVM.observationGeneration
        onDispose {
            chatVM.stop(chatGeneration)
            stripVM.stop(stripGeneration)
        }
    }
    LaunchedEffect(chatVM) {
        chatVM.start()
        chatVM.paginateBackward()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (children.size > 1) {
                        IconButton(onClick = { showSwitcher = true }) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "Switch subagent")
                        }
                        DropdownMenu(expanded = showSwitcher, onDismissRequest = { showSwitcher = false }) {
                            children.forEach { sibling ->
                                DropdownMenuItem(
                                    text = { Text((if (sibling.id == childID) "✓ " else "") + sibling.title) },
                                    enabled = sibling.id != childID,
                                    onClick = { showSwitcher = false; onSwitchTo(sibling.id) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            MatronTimelineBackground()
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isRunning) CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            sessionStatus?.model?.takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                if (isRunning) "Running" else "Finished",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    sessionStatus?.context?.let { ContextGaugeLabel(context = it) }
                }
                HorizontalDivider()
                TimelineList(
                    chatVM = chatVM,
                    stripVM = stripVM,
                    activityLabel = null,
                    onOpenChild = onSwitchTo,
                    onPreviewImage = { previewModel = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    previewModel?.let { model ->
        AttachmentFullscreenViewer(model = model, onDismiss = { previewModel = null })
    }
}
