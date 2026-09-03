package chat.matron.android.features.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.chat.SessionTag
import chat.matron.android.chat.TimelineItem
import chat.matron.android.designsystem.SessionTagText
import chat.matron.android.journal.AgentChatDecision
import chat.matron.android.designsystem.ActivityIndicatorRow
import chat.matron.android.designsystem.AttachmentFullscreenViewer
import chat.matron.android.designsystem.ChatSearchBar
import chat.matron.android.designsystem.ImageGallery
import kotlinx.coroutines.CancellationException
import chat.matron.android.designsystem.CompactContextBanner
import chat.matron.android.designsystem.DateSeparator
import chat.matron.android.designsystem.DateSeparatorLabel
import chat.matron.android.designsystem.EmptyChatPlaceholder
import chat.matron.android.designsystem.JumpToBottomButton
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.designsystem.PaginatingHeader
import chat.matron.android.designsystem.StopTurnButton
import chat.matron.android.designsystem.SubtaskLinkCard
import chat.matron.android.designsystem.TimelineLoadingIndicator
import chat.matron.android.designsystem.UsageMetersFormat
import chat.matron.android.designsystem.shouldShowCompactHeader
import chat.matron.android.viewmodels.ChatViewModel
import chat.matron.android.viewmodels.ComposerViewModel
import chat.matron.android.viewmodels.MediaBrowserViewModel
import chat.matron.android.viewmodels.SubChatStripViewModel
import chat.matron.android.viewmodels.TimelineRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
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
    /// Opens a spawned session's room from an agent-spawn card or its
    /// `SpawnOutcomeRow` — the nav host's `prepareConversation` → `navigate`
    /// callback (`MainActivity.openConversationCallback`).
    onOpenConversation: (String) -> Unit,
    /// Which agent box runs this session, or null when the user has fewer
    /// than two boxes. Threaded from the list's ChatSummary (same source as
    /// the row chip) so header and row can never disagree.
    boxName: String? = null,
    /// The `A:bc` tag halves, threaded from the list summary like [boxName]
    /// (see ChatSummary.sessionShort / .boxShort). Composed ahead of the
    /// title so the in-chat header matches the row.
    sessionShort: String? = null,
    boxShort: String? = null,
    /// Multi-agent room participants (ChatSummary.roomBoxNames /
    /// .roomBoxShorts, parallel arrays), threaded like the halves above so
    /// a room's header shows the same colored `A↔B` tag as its row.
    roomBoxNames: List<String> = emptyList(),
    roomBoxShorts: List<String> = emptyList(),
    /// Builds the media & links browser's VM when its sheet opens (deferred,
    /// like the iOS sheet's `.task` construction — the store queries only run
    /// for users who open the browser). `null` (previews/tests) hides the
    /// toolbar button. Port of apple #142's ChatView toolbar + sheet.
    mediaBrowser: ((CoroutineScope) -> MediaBrowserViewModel)? = null,
) {
    val error by chatVM.error.collectAsStateWithLifecycle()
    val chatSearch by chatVM.chatSearch.collectAsStateWithLifecycle()
    val chatSearchScope = rememberCoroutineScope()
    val children by stripVM.children.collectAsStateWithLifecycle()
    val runningChildren by stripVM.runningChildren.collectAsStateWithLifecycle()
    val activityLabel by chatVM.activityLabel.collectAsStateWithLifecycle()
    val isTurnRunning by chatVM.isTurnRunning.collectAsStateWithLifecycle()
    val sessionStatus by chatVM.sessionStatus.collectAsStateWithLifecycle()

    var showSessionStatus by remember { mutableStateOf(false) }
    var showMediaBrowser by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
    /// Tappable title → summaries TOC sheet (jump-to-point navigation).
    var showSummaries by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var previewModel by remember { mutableStateOf<Any?>(null) }
    val compactScope = rememberCoroutineScope()
    // The gallery a timeline tap opens: every image in the conversation,
    // oldest → newest (so swipe-left means "newer", matching the direction
    // the timeline reads), starting at the tapped one. Built from the same
    // store mapping the media browser uses, so it covers the whole
    // conversation rather than the loaded scrollback window; with no
    // browser factory, or a URL the store doesn't hold yet (an outgoing image
    // still uploading), the viewer falls back to just the tapped image
    // (apple #175).
    var previewGallery by remember { mutableStateOf<ImageGallery?>(null) }
    LaunchedEffect(previewModel) {
        val tapped = previewModel
        if (tapped == null) { previewGallery = null; return@LaunchedEffect }
        val image = tapped as? TappedImage ?: run { previewGallery = ImageGallery.single(tapped); return@LaunchedEffect }
        // The tapped image opens on the SAME frame as the tap; the full
        // gallery swaps in underneath once the store has answered (Bugbot).
        previewGallery = ImageGallery.single(image.model)
        val browser = mediaBrowser?.invoke(compactScope)
        val stored = if (browser == null || image.url == null) {
            emptyList()
        } else {
            try {
                browser.imageEntries()
            } catch (cancel: CancellationException) {
                // A superseding tap or a screen exit: this build is stale
                // and must not land at all.
                throw cancel
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val entries = stored.asReversed().map { ImageGallery.Entry(it.id.toString(), it.url, it.expired) }
        val start = entries.indexOfFirst { it.url == image.url }
        if (browser != null && start >= 0) {
            previewGallery = ImageGallery(entries, start, image.model) { url ->
                // The timeline may already hold this neighbour's bytes.
                chatVM.resolvedImage(url) ?: browser.openMedia(url)
            }
        }
    }

    ChatLifecycle(chatVM, stripVM)

    Scaffold(
        topBar = {
            TopAppBar(
                // Under the title, "box · ~/workdir" in small text — which
                // machine and folder this session lives on, readable without
                // opening the info sheet (apple #150). Box comes from the
                // list summary (same gate as the row chip); the path arrives
                // with the first session-status frame, home-abbreviated like
                // the info sheet.
                // Tappable title → summaries TOC sheet, mirroring iOS's
                // principal-item title button (apple #124).
                title = {
                    Column(
                        modifier = Modifier.clickable(
                            onClickLabel = "Show conversation summaries",
                        ) { showSummaries = true },
                    ) {
                        // `A:bc Title` (or `A↔B:bc Title` for a multi-agent
                        // room) as one styled line — same composition and
                        // fallbacks as ChatRow's titleLine (apple #152). The
                        // visible header leads with the styled tag, so the
                        // accessibility label spells the same information
                        // out — box name(s) and session short ahead of the
                        // clean title.
                        val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                        val titleText = SessionTagText.titleLine(
                            // The room marker drops only when a room tag
                            // will actually render in its place.
                            title = if (roomBoxNames.size >= 2) {
                                SessionTag.titleBesideRoomTag(chatTitle)
                            } else {
                                chatTitle
                            },
                            boxLetter = boxShort,
                            boxName = boxName,
                            sessionShort = sessionShort,
                            roomBoxNames = roomBoxNames,
                            roomBoxShorts = roomBoxShorts,
                            darkTheme = darkTheme,
                            secondary = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val a11yTitle = chatAccessibilityTitle(chatTitle, boxName, sessionShort, roomBoxNames)
                        Text(
                            titleText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { contentDescription = a11yTitle },
                        )
                        chatContextLine(boxName, sessionStatus?.workdir)?.let { context ->
                            Text(
                                context,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                // The Apple original middle-truncates (the
                                // tail of a path is the part worth keeping);
                                // TextOverflow.MiddleEllipsis needs Compose
                                // 1.8, so tail ellipsis until the BOM moves.
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // One ellipsis menu instead of a row of icon buttons —
                    // trailing icons squeezed the title down to a few
                    // characters (apple #150). Sub-chats stay a flat section
                    // shown whenever this chat has ANY children (running or
                    // finished): the running strip hides itself the moment
                    // the last subagent finishes, so without this the only
                    // way back into a finished sub-chat is its timeline card.
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Chat options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (children.isNotEmpty()) {
                            Text(
                                "Subagents",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                            children.forEach { child ->
                                DropdownMenuItem(
                                    text = { Text(child.title) },
                                    leadingIcon = {
                                        Icon(
                                            if (child.isRunning) Icons.Default.AccountTree else Icons.Default.Check,
                                            contentDescription = if (child.isRunning) "Running" else "Finished",
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onOpenChild(child.id)
                                    },
                                )
                            }
                            HorizontalDivider()
                        }
                        // Apple's photo.on.rectangle.angled toolbar button lives
                        // in the single ellipsis menu here (apple #142 + #150).
                        if (mediaBrowser != null) {
                            DropdownMenuItem(
                                text = { Text("Media, files & links") },
                                leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    showMediaBrowser = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Session Info") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                showSessionStatus = true
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        // consumeWindowInsets stops imePadding double-counting the navigation
        // bar inset Scaffold already applied; without imePadding the keyboard
        // draws over the composer on Android 15+ (edge-to-edge is enforced).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            MatronTimelineBackground()
            Column(modifier = Modifier.fillMaxSize()) {
                // In-conversation search (armed by a grouped search-result
                // tap). Field text is local, seeded from the VM's query;
                // submit re-runs the room-scoped search (apple #172).
                chatSearch?.let { searchState ->
                    var chatSearchQuery by remember(searchState.query) { mutableStateOf(searchState.query) }
                    ChatSearchBar(
                        query = chatSearchQuery,
                        onQueryChange = { chatSearchQuery = it },
                        matchCount = searchState.matchSeqs.size,
                        matchIndex = searchState.index,
                        onSubmit = { chatSearchScope.launch { chatVM.beginChatSearch(chatSearchQuery) } },
                        onOlder = { chatSearchScope.launch { chatVM.stepChatSearch(older = true) } },
                        onNewer = { chatSearchScope.launch { chatVM.stepChatSearch(older = false) } },
                        onClose = { chatVM.endChatSearch() },
                    )
                }
                if (error != null) {
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(0.dp),
                    )
                }
                if (shouldShowCompactHeader(sessionStatus?.context)) {
                    CompactContextBanner(
                        tokens = sessionStatus!!.context!!.tokens,
                        onCompact = { compactScope.launch { composerVM.sendCommand("/compact") } },
                    )
                }
                RunningSubagentStrip(runningChildren = runningChildren, onOpenChild = onOpenChild)
                TimelineList(
                    chatVM = chatVM,
                    stripVM = stripVM,
                    activityLabel = activityLabel,
                    isTurnRunning = isTurnRunning,
                    onStopTurn = { compactScope.launch { chatVM.sendCommand("!esc") } },
                    onOpenChild = onOpenChild,
                    onOpenConversation = onOpenConversation,
                    onPreviewImage = { previewModel = it },
                    modifier = Modifier.weight(1f),
                )
                ComposerView(viewModel = composerVM)
            }
        }
    }

    if (showSummaries) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { showSummaries = false }, sheetState = sheetState) {
            SummariesSheet(
                viewModel = chatVM,
                onSelect = { seq ->
                    // Dismiss first, then jump — same order as the iOS sheet
                    // (`dismiss(); Task { await viewModel.focus(seq:) }`).
                    showSummaries = false
                    compactScope.launch { chatVM.focus(seq) }
                },
            )
        }
    }
    if (showSessionStatus) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { showSessionStatus = false }, sheetState = sheetState) {
            SessionStatusSheet(viewModel = chatVM, onDismiss = { showSessionStatus = false }, boxName = boxName)
        }
    }
    if (showMediaBrowser && mediaBrowser != null) {
        // skipPartiallyExpanded: the media grid wants its full height straight
        // away (NewChatSheet precedent).
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showMediaBrowser = false }, sheetState = sheetState) {
            MediaBrowserSheet(chatVM = chatVM, viewModelFactory = mediaBrowser)
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
    previewGallery?.let { gallery ->
        // Both cleared together: the viewer must leave on the frame of the
        // dismiss, not after the effect above catches up (Bugbot).
        AttachmentFullscreenViewer(gallery = gallery, onDismiss = { previewModel = null; previewGallery = null })
    }
}

/// "box · ~/workdir" for the small line under the chat title. Either part can
/// be missing (single-box users get no box name; the workdir only arrives
/// with the first session-status frame) — show what's known, null hides the
/// line entirely. Pure so the composition is unit-testable without rendering
/// (ports matron-apple's `ChatView.contextLine`, pinned by
/// `ChatViewBindingTests.test_contextLine_composesBoxAndAbbreviatedWorkdir`).
fun chatContextLine(boxName: String?, workdir: String?): String? {
    val parts = listOfNotNull(boxName, workdir?.let(UsageMetersFormat::homeAbbreviated))
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

/// What TalkBack reads for the header title: the visible tag's meaning
/// spelled out (box names, session short), not just the clean title —
/// sighted users see the `A:bc` tag, so the label must carry it too.
/// Delegates to the shared `SessionTag.accessibilityTitle` (apple #154) so
/// the header can never drift from other tagged surfaces.
fun chatAccessibilityTitle(
    chatTitle: String,
    boxName: String?,
    sessionShort: String?,
    roomBoxNames: List<String>,
): String = SessionTag.accessibilityTitle(chatTitle, boxName, sessionShort, roomBoxNames)

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
    /// Opens a spawned session's room from an agent-spawn card or its
    /// `SpawnOutcomeRow`. Threaded straight through to [TimelineItemView] via
    /// [TimelineRowView] — shared by both [ChatScreen] and [SubChatView], so
    /// the deep link works from a sub-chat's timeline too.
    onOpenConversation: (String) -> Unit,
    onPreviewImage: (Any) -> Unit,
    modifier: Modifier = Modifier,
    // Floating stop button — supplied only by the main chat pane (sub-chat
    // viewers are read-only, matching matron-apple). Visible while the durable
    // session_state says a turn is running, with the ephemeral activity label
    // OR-ed in as a fast path in case a session_state frame is missed.
    isTurnRunning: Boolean = false,
    onStopTurn: (() -> Unit)? = null,
) {
    val rows by chatVM.windowedRows.collectAsStateWithLifecycle()
    val settledEmpty by chatVM.settledEmpty.collectAsStateWithLifecycle()
    val lastRenderableItemID by chatVM.lastRenderableItemID.collectAsStateWithLifecycle()
    val children by stripVM.children.collectAsStateWithLifecycle()
    val attachmentError by chatVM.attachmentError.collectAsStateWithLifecycle()

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

    // Your own outgoing message always returns you to the bottom, even if
    // follow-tail was disarmed when you sent it (matron-apple ChatView.swift
    // ~530: `if lastRenderableItemIsOwn, !isFollowingTail { isFollowingTail =
    // true }`). Keyed on the tail id itself, not rows.size — streaming growth
    // and backward pagination don't change the newest item's identity, so
    // they can't trigger this, only a genuinely new tail row can.
    LaunchedEffect(lastRenderableItemID) {
        if (lastRenderableItemID != null && chatVM.lastRenderableItemIsOwn) {
            followTail = true
        }
    }

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

    // Summaries TOC jump target — same consumer shape as iOS ChatView's
    // `pendingFocusID` observer (apple #124): disengage tail-follow (or the
    // pin-to-tail effect would yank the viewport straight back), widen the
    // window so the target is composed, scroll it to the top, clear. The
    // 200ms re-assert covers the widened window's layout pass, guarded on
    // still being the newest jump so a superseded jump's re-assert can't
    // yank the viewport back to its old target.
    var latestFocusTarget by remember { mutableStateOf<String?>(null) }
    val reassertScope = rememberCoroutineScope()
    LaunchedEffect(chatVM) {
        chatVM.pendingFocusID.collect { target ->
            if (target == null) return@collect
            followTail = false
            latestFocusTarget = target
            chatVM.ensureWindowContains(target)
            summaryScrollIndex(chatVM.windowedRows.value, target)?.let { listState.scrollToItem(it) }
            chatVM.clearPendingFocus()
            reassertScope.launch {
                delay(200)
                if (!followTail && latestFocusTarget == target) {
                    summaryScrollIndex(chatVM.windowedRows.value, target)?.let { listState.scrollToItem(it) }
                }
            }
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
                // Matches ChatView.swift's `VStack(spacing: 8)` between rows.
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
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
                        onOpenConversation = onOpenConversation,
                        onPreviewImage = onPreviewImage,
                        onTapFile = onTapFile,
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

        if (onStopTurn != null && (isTurnRunning || activityLabel != null)) {
            StopTurnButton(
                onClick = onStopTurn,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }

        attachmentError?.let { message ->
            AttachmentErrorBanner(
                message = message,
                onDismiss = { chatVM.dismissAttachmentError() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * Dismissible banner for [ChatViewModel.attachmentError]: a file-attachment tap
 * that failed to download/write had no user-visible feedback (a dead button) —
 * this surfaces it, playing the analogous role to [ComposerView]'s
 * `ComposerErrorBanner` (its own Surface/errorContainer styling, not a copy).
 * `internal` so [MediaBrowserSheet] shows the same banner (same package) for
 * its attachment errors instead of a dismissless copy.
 */
@Composable
internal fun AttachmentErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss error",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun TimelineRowView(
    row: TimelineRow,
    chatVM: ChatViewModel,
    children: List<chat.matron.android.chat.SubChatSummary>,
    onOpenChild: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onPreviewImage: (Any) -> Unit,
    onTapFile: (url: String, filename: String) -> Unit,
) {
    // Collected so a consent card's in-flight / answered state redraws: the
    // answer is an HTTP call with no journal event behind it, so nothing in the
    // timeline snapshot would otherwise change.
    val agentChatStates by chatVM.agentChatStates.collectAsStateWithLifecycle()
    // Collected so a file chip's spinner appears on tap and clears when the
    // open/share fires — the download flips no timeline snapshot either
    // (port of apple #138).
    val downloadingFiles by chatVM.downloadingFiles.collectAsStateWithLifecycle()
    // Collected so a 404 discovered at fetch time flips the chip/image to its
    // Expired presentation without a timeline change (port of apple #139).
    val unavailableMedia by chatVM.unavailableMedia.collectAsStateWithLifecycle()
    // Same idea for agent-spawn cards, but two sources: the durable
    // resolution (a `spawn_outcome` event arriving IS a snapshot change, but
    // the card is a different row than the outcome row it resolves) and the
    // transient in-flight/failed state (an HTTP call, no journal event).
    val spawnOutcomes by chatVM.spawnOutcomes.collectAsStateWithLifecycle()
    val agentSpawnStates by chatVM.agentSpawnStates.collectAsStateWithLifecycle()
    // A bridge queued_release lands as a hidden row, so no visible row changes;
    // collecting the memo is what recomposes the sibling queue cards' buttons
    // (apple #162; Bugbot #48).
    val releaseResolvedAnswers by chatVM.releaseResolvedAnswers.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Memoised in the VM's derived-recompute pass (apple #141) — reading the
    // flag here is a plain state read, never an O(N) timeline scan per row.
    val hasMultipleSenders by chatVM.hasMultipleSenders.collectAsStateWithLifecycle()
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
                    isDownloadingFile = { url -> url in downloadingFiles },
                    isMediaUnavailable = { url -> url in unavailableMedia },
                    askViewModel = { id -> chatVM.askViewModel(id) },
                    // Read through the view model; releaseResolvedAnswers above
                    // is what makes these recompose when a release lands.
                    isPromptAnswered = { id -> releaseResolvedAnswers.let { chatVM.isPromptAnswered(id) } },
                    answerSummary = { id -> releaseResolvedAnswers.let { chatVM.answerSummary(id) } },
                    agentChatState = { id ->
                        // Read through the view model (persisted decision wins);
                        // `agentChatStates` above is what makes this recompose.
                        agentChatStates.let { chatVM.agentChatState(id) }
                    },
                    onAnswerAgentChat = { eventID, request, approve ->
                        // Deliberately NOT wrapped in this row's coroutine
                        // scope — the view model owns the call so scrolling the
                        // card away can't cancel an answer in flight.
                        chatVM.answerAgentChat(
                            eventID = eventID,
                            request = request,
                            decision = if (approve) AgentChatDecision.APPROVE else AgentChatDecision.DENY,
                        )
                    },
                    agentSpawnState = { eventID, request ->
                        // Read through the view model (derived-outcome
                        // precedence wins); spawnOutcomes/agentSpawnStates
                        // above are what make this recompose.
                        spawnOutcomes.let { agentSpawnStates }.let { chatVM.agentSpawnState(eventID, request) }
                    },
                    onAnswerAgentSpawn = { eventID, request, decision ->
                        // Same VM-scope rationale as onAnswerAgentChat above:
                        // NOT this row's coroutine scope, so scrolling the
                        // card away can't cancel an answer in flight.
                        chatVM.answerAgentSpawn(eventID = eventID, request = request, decision = decision)
                    },
                    onOpenSpawnedRoom = onOpenConversation,
                    hasMultipleSenders = hasMultipleSenders,
                )
            }
        }
    }
}

/**
 * Index of [targetItemID]'s row in the LazyColumn [TimelineList] renders, or
 * `null` when it isn't in the window. Row i of [rows] sits at list index
 * i + 1 — index 0 is the always-present "paginating" item.
 */
internal fun summaryScrollIndex(rows: List<TimelineRow>, targetItemID: String): Int? {
    val index = rows.indexOfFirst { it is TimelineRow.Message && it.item.id == targetItemID }
    return if (index >= 0) index + 1 else null
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
