package chat.matron.android.features.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.chat.TimelineItem
import chat.matron.android.designsystem.ActivityIndicatorRow
import chat.matron.android.designsystem.AttachmentFullscreenViewer
import chat.matron.android.designsystem.DateSeparator
import chat.matron.android.designsystem.DateSeparatorLabel
import chat.matron.android.designsystem.EmptyChatPlaceholder
import chat.matron.android.designsystem.JumpToBottomButton
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.designsystem.PaginatingHeader
import chat.matron.android.designsystem.SubtaskLinkCard
import chat.matron.android.designsystem.TimelineLoadingIndicator
import chat.matron.android.viewmodels.ChatViewModel
import chat.matron.android.viewmodels.ComposerViewModel
import chat.matron.android.viewmodels.SubChatStripViewModel
import chat.matron.android.viewmodels.TimelineRow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Full chat screen. Ports Features/Chat/ChatView.swift: the timeline (windowed
 * rows via [TimelineList]) above a [ComposerView], a running-subagent strip, a
 * session-status sheet, and a subagent switcher. The elaborate iOS scroll-
 * anchoring machinery has no Compose analogue — a [LazyListState] keeps the tail
 * pinned and drives near-top backward pagination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatVM: ChatViewModel,
    composerVM: ComposerViewModel,
    stripVM: SubChatStripViewModel,
    chatTitle: String,
    onBack: () -> Unit,
    onOpenChild: (String) -> Unit,
) {
    val error by chatVM.error.collectAsStateWithLifecycle()
    val children by stripVM.children.collectAsStateWithLifecycle()
    val runningChildren by stripVM.runningChildren.collectAsStateWithLifecycle()
    val activityLabel by chatVM.activityLabel.collectAsStateWithLifecycle()

    var showSessionStatus by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
    var sourceItem by remember { mutableStateOf<TimelineItem?>(null) }
    var previewModel by remember { mutableStateOf<Any?>(null) }

    ChatLifecycle(chatVM, stripVM)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chatTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (children.isNotEmpty()) {
                        IconButton(onClick = { showSwitcher = true }) {
                            Icon(Icons.Default.AccountTree, contentDescription = "Subagents")
                        }
                    }
                    IconButton(onClick = { showSessionStatus = true }) {
                        Icon(Icons.Default.Info, contentDescription = "Session status")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            MatronTimelineBackground()
            Column(modifier = Modifier.fillMaxSize()) {
                if (error != null) {
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(0.dp),
                    )
                }
                RunningSubagentStrip(runningChildren = runningChildren, onOpenChild = onOpenChild)
                TimelineList(
                    chatVM = chatVM,
                    stripVM = stripVM,
                    activityLabel = activityLabel,
                    onOpenChild = onOpenChild,
                    onPreviewImage = { previewModel = it },
                    onShowSource = { sourceItem = it },
                    modifier = Modifier.weight(1f),
                )
                ComposerView(viewModel = composerVM)
            }
        }
    }

    if (showSessionStatus) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { showSessionStatus = false }, sheetState = sheetState) {
            SessionStatusSheet(viewModel = chatVM, onDismiss = { showSessionStatus = false })
        }
    }
    if (showSwitcher) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { showSwitcher = false }, sheetState = sheetState) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Subagents", style = MaterialTheme.typography.titleMedium)
                children.forEach { child ->
                    Text(
                        text = (if (child.isRunning) "● " else "✓ ") + child.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showSwitcher = false
                                onOpenChild(child.id)
                            }
                            .padding(vertical = 12.dp),
                    )
                    androidx.compose.material3.HorizontalDivider()
                }
            }
        }
    }
    sourceItem?.let { item ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { sourceItem = null }, sheetState = sheetState) {
            EventSourceSheet(item = item)
        }
    }
    previewModel?.let { model ->
        AttachmentFullscreenViewer(model = model, onDismiss = { previewModel = null })
    }
}

/**
 * Shared timeline list used by both [ChatScreen] and the sub-chat viewer. Renders
 * [ChatViewModel.windowedRows], keeps the tail pinned while the user is at the
 * bottom, exposes a jump-to-latest button otherwise, and drives near-top backward
 * pagination + history-window growth.
 */
@Composable
fun TimelineList(
    chatVM: ChatViewModel,
    stripVM: SubChatStripViewModel,
    activityLabel: String?,
    onOpenChild: (String) -> Unit,
    onPreviewImage: (Any) -> Unit,
    onShowSource: (TimelineItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows by chatVM.windowedRows.collectAsStateWithLifecycle()
    val settledEmpty by chatVM.settledEmpty.collectAsStateWithLifecycle()
    val lastRenderableItemID by chatVM.lastRenderableItemID.collectAsStateWithLifecycle()
    val children by stripVM.children.collectAsStateWithLifecycle()

    // Tap on a file attachment: download → cache dir → hand to the system
    // (iOS: writeTempFile → QuickLook). Lives here rather than at the call
    // sites so the read-only SubChatView gets the behaviour too.
    val context = LocalContext.current
    val fileTapScope = rememberCoroutineScope()
    val onTapFile: (String, String) -> Unit = { url, filename ->
        fileTapScope.launch {
            chatVM.writeTempFile(url, filename, context.cacheDir)
                ?.let { openAttachment(context, it) }
        }
    }

    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 1
        }
    }
    var followTail by remember { mutableStateOf(true) }
    var paginating by remember { mutableStateOf(false) }

    // A user scroll away from the bottom drops follow-tail; returning re-arms it.
    LaunchedEffect(atBottom) { followTail = atBottom }

    // Keep the tail pinned while following: a new tail id, a row-count change, or
    // the jump button re-arming scrolls to the true last item. Index 0 is the
    // always-present "paginating" item, so row i sits at index i + 1; the
    // optional activity footer sits one further past the last row.
    LaunchedEffect(followTail, lastRenderableItemID, rows.size, activityLabel) {
        if (followTail && rows.isNotEmpty()) {
            val lastIndex = rows.size + if (activityLabel != null) 1 else 0
            listState.scrollToItem(lastIndex)
        }
    }

    // Near-top → grow the history window and paginate backward over HTTP.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { first ->
                if (first <= 2 && rows.isNotEmpty() && !paginating) {
                    paginating = true
                    chatVM.extendHistoryWindow()
                    chatVM.paginateBackward()
                    paginating = false
                }
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            settledEmpty -> EmptyChatPlaceholder(
                botName = "",
                modifier = Modifier.fillMaxSize(),
            )
            rows.isEmpty() -> TimelineLoadingIndicator(modifier = Modifier.align(Alignment.Center))
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                item(key = "paginating") {
                    if (paginating) PaginatingHeader()
                }
                items(rows, key = { it.id }) { row ->
                    TimelineRowView(
                        row = row,
                        chatVM = chatVM,
                        children = children,
                        onOpenChild = onOpenChild,
                        onPreviewImage = onPreviewImage,
                        onTapFile = onTapFile,
                        onShowSource = onShowSource,
                    )
                }
                if (activityLabel != null) {
                    item(key = "activity-footer") { ActivityIndicatorRow(label = activityLabel) }
                }
            }
        }

        if (!followTail) {
            JumpToBottomButton(
                onClick = { followTail = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun TimelineRowView(
    row: TimelineRow,
    chatVM: ChatViewModel,
    children: List<chat.matron.android.chat.SubChatSummary>,
    onOpenChild: (String) -> Unit,
    onPreviewImage: (Any) -> Unit,
    onTapFile: (url: String, filename: String) -> Unit,
    onShowSource: (TimelineItem) -> Unit,
) {
    when (row) {
        is TimelineRow.Separator -> DateSeparator(label = DateSeparatorLabel.format(row.date))
        is TimelineRow.Message -> {
            val item = row.item
            val child = subtaskChild(item, children)
            if (child != null) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    SubtaskLinkCard(
                        title = child.title,
                        isRunning = child.isRunning,
                        onClick = { onOpenChild(child.id) },
                    )
                }
            } else {
                TimelineItemView(
                    item = item,
                    resolveImage = { url -> chatVM.image(url) },
                    onRetry = { id -> chatVM.retrySend(id) },
                    onTapImage = { model -> onPreviewImage(model) },
                    onTapFile = onTapFile,
                    askViewModel = { id -> chatVM.askViewModel(id) },
                    isPromptAnswered = { id -> chatVM.isPromptAnswered(id) },
                    answerSummary = { id -> chatVM.answerSummary(id) },
                )
            }
        }
    }
}

/**
 * Resolves the sub-chat a bridge "🔀 Subtask: …" indicator message refers to, or
 * `null` when [item] isn't such an indicator. Port of `TimelineListContent
 * .subtaskChild(for:)`.
 */
private fun subtaskChild(
    item: TimelineItem,
    children: List<chat.matron.android.chat.SubChatSummary>,
): chat.matron.android.chat.SubChatSummary? {
    val kind = item.kind as? TimelineItem.Kind.Text ?: return null
    if (item.isOwn) return null
    val description = SubChatStripViewModel.subtaskDescription(fromMessageBody = kind.body) ?: return null
    return SubChatStripViewModel.resolveSubtaskTarget(description = description, among = children)
}

/** Drives the chat/strip VM lifecycle: start on enter, generation-guarded stop on exit. */
@Composable
private fun ChatLifecycle(chatVM: ChatViewModel, stripVM: SubChatStripViewModel) {
    DisposableEffect(chatVM, stripVM) {
        val chatGeneration = chatVM.observationGeneration + 1
        stripVM.start()
        val stripGeneration = stripVM.observationGeneration
        onDispose {
            chatVM.stop(chatGeneration)
            stripVM.stop(stripGeneration)
        }
    }
    // start()/paginateBackward()/markAsRead() sequenced on enter.
    LaunchedEffect(chatVM) {
        chatVM.start()
        chatVM.paginateBackward()
        chatVM.markAsRead()
    }
}

/**
 * Sticky horizontal strip of a parent chat's RUNNING subagents. Ports
 * `RunningSubagentStrip`. Renders nothing when none are running.
 */
@Composable
fun RunningSubagentStrip(
    runningChildren: List<chat.matron.android.chat.SubChatSummary>,
    onOpenChild: (String) -> Unit,
) {
    if (runningChildren.isEmpty()) return
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        items(runningChildren, key = { it.id }) { child ->
            androidx.compose.material3.AssistChip(
                onClick = { onOpenChild(child.id) },
                label = { Text(child.title, maxLines = 1) },
            )
        }
    }
}
