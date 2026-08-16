package chat.matron.android.features.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.chat.TimelineItem
import chat.matron.android.events.AgentChatCardState
import chat.matron.android.events.AgentChatRequest
import chat.matron.android.designsystem.ActivityIndicatorRow
import chat.matron.android.designsystem.AgentChatRequestCard
import chat.matron.android.designsystem.AskUserCard
import chat.matron.android.designsystem.AttachmentFile
import chat.matron.android.designsystem.AttachmentImage
import chat.matron.android.designsystem.DiffCard
import chat.matron.android.designsystem.LiveOutputCard
import chat.matron.android.designsystem.LiveOutputSessionStore
import chat.matron.android.designsystem.MarkdownText
import chat.matron.android.designsystem.MessageAuthorStyle
import chat.matron.android.designsystem.MessageBubble
import chat.matron.android.designsystem.SendStateIndicator
import chat.matron.android.designsystem.ToolCallCard
import chat.matron.android.designsystem.ToolStreamCard
import chat.matron.android.designsystem.sendStateGlyphFrom
import chat.matron.android.events.AskUserEvent
import chat.matron.android.models.TimelineSendState
import chat.matron.android.viewmodels.AskUserSheetViewModel
import kotlinx.coroutines.launch

/** Width cap for tool/diff/live cards — terminal output wants columns. */
private val CardMaxWidth = 480.dp

/**
 * Renders a single [TimelineItem] row. Ports Features/Chat/Rendering/
 * TimelineItemView.swift: text/image/file kinds wrap in a [MessageBubble];
 * tool/diff/live/stream cards and ambient notices dispatch to their design-system
 * composables. Resolver lambdas are `null`-tolerant for previews.
 */
@Composable
fun TimelineItemView(
    item: TimelineItem,
    resolveImage: ((String) -> ByteArray?)? = null,
    onRetry: ((String) -> Unit)? = null,
    onTapImage: ((Any) -> Unit)? = null,
    onTapFile: ((url: String, filename: String) -> Unit)? = null,
    /// Whether a file attachment's blob download is in flight — drives the
    /// chip's spinner ([ChatViewModel.isDownloadingFile]). `null` keeps
    /// previews/tests compiling (port of apple #138).
    isDownloadingFile: ((String) -> Boolean)? = null,
    askViewModel: ((String) -> AskUserSheetViewModel?)? = null,
    isPromptAnswered: ((String) -> Boolean)? = null,
    answerSummary: ((String) -> String?)? = null,
    /// Render state for an agent-chat consent card. `null` (previews, tests)
    /// renders the card read-only rather than offering buttons with nothing
    /// behind them.
    agentChatState: ((String) -> AgentChatCardState)? = null,
    /// Answers a consent card: approve or decline, for this request only.
    /// Goes to `POST /agent-chat/answer`, not into the timeline.
    onAnswerAgentChat: ((
        eventID: String,
        request: AgentChatRequest,
        approve: Boolean,
    ) -> Unit)? = null,
) {
    if (item.isOwn && item.sendState != TimelineSendState.Sent) {
        Column(horizontalAlignment = Alignment.End) {
            RenderedBody(
                item, resolveImage, onTapImage, onTapFile, isDownloadingFile, askViewModel,
                isPromptAnswered, answerSummary, agentChatState, onAnswerAgentChat,
            )
            SendStateIndicator(
                state = sendStateGlyphFrom(item.sendState),
                onRetry = onRetry?.let { handler -> { handler(item.id) } },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    } else {
        RenderedBody(
            item, resolveImage, onTapImage, onTapFile, isDownloadingFile, askViewModel,
            isPromptAnswered, answerSummary, agentChatState, onAnswerAgentChat,
        )
    }
}

@Composable
private fun RenderedBody(
    item: TimelineItem,
    resolveImage: ((String) -> ByteArray?)?,
    onTapImage: ((Any) -> Unit)?,
    onTapFile: ((url: String, filename: String) -> Unit)?,
    isDownloadingFile: ((String) -> Boolean)?,
    askViewModel: ((String) -> AskUserSheetViewModel?)?,
    isPromptAnswered: ((String) -> Boolean)?,
    answerSummary: ((String) -> String?)?,
    agentChatState: ((String) -> AgentChatCardState)?,
    onAnswerAgentChat: ((
        eventID: String,
        request: AgentChatRequest,
        approve: Boolean,
    ) -> Unit)?,
) {
    val style = if (item.isOwn) MessageAuthorStyle.Me else MessageAuthorStyle.Bot
    when (val kind = item.kind) {
        is TimelineItem.Kind.Text ->
            // copyText carries the raw markdown body — what the sender actually
            // wrote — so a copied message pastes as text, not styled spans.
            MessageBubble(style = style, timestamp = item.timestamp, copyText = kind.body) {
                MarkdownText(kind.body)
            }

        is TimelineItem.Kind.Image -> {
            val model = kind.url?.let { url -> resolveImage?.invoke(url) }
            MessageBubble(style = style, timestamp = item.timestamp) {
                AttachmentImage(
                    model = model,
                    caption = kind.caption,
                    onTap = if (model != null && onTapImage != null) ({ onTapImage(model) }) else null,
                )
            }
        }

        is TimelineItem.Kind.File -> {
            // Read inside the row body so the collected downloadingFiles flow
            // recomposes the row when the flag flips (apple #138).
            val isLoading = kind.url?.let { isDownloadingFile?.invoke(it) } ?: false
            MessageBubble(style = style, timestamp = item.timestamp) {
                AttachmentFile(
                    filename = kind.filename,
                    sizeBytes = kind.sizeBytes,
                    caption = kind.caption,
                    isLoading = isLoading,
                    onTap = if (kind.url != null && onTapFile != null) ({ onTapFile(kind.url!!, kind.filename) }) else null,
                )
            }
        }

        is TimelineItem.Kind.StateChange -> AmbientNotice(kind.text)

        is TimelineItem.Kind.ToolCall -> CappedCard { ToolCallCard(event = kind.event) }

        is TimelineItem.Kind.Diff -> CappedCard { DiffCard(event = kind.event) }

        is TimelineItem.Kind.LiveOutput -> CappedCard {
            LiveOutputCard(
                session = LiveOutputSessionStore.shared.session(kind.event),
                eventTimestamp = item.timestamp,
            )
        }

        is TimelineItem.Kind.ToolStreamLive -> CappedCard {
            ToolStreamCard(command = kind.command, text = kind.text, headTruncated = kind.headTruncated)
        }

        is TimelineItem.Kind.AskUser ->
            CappedCard(maxWidth = 360.dp) {
                AskCard(
                    eventID = kind.eventID,
                    event = kind.event,
                    askViewModel = askViewModel,
                    isPromptAnswered = isPromptAnswered,
                    answerSummary = answerSummary,
                )
            }

        is TimelineItem.Kind.AgentChatRequestCard ->
            CappedCard(maxWidth = 360.dp) {
                AgentChatRequestCard(
                    request = kind.request,
                    state = agentChatState?.invoke(kind.eventID) ?: AgentChatCardState.Expired,
                    onApprove = { onAnswerAgentChat?.invoke(kind.eventID, kind.request, true) },
                    onDeny = { onAnswerAgentChat?.invoke(kind.eventID, kind.request, false) },
                )
            }

        is TimelineItem.Kind.AskUserAnswer -> Unit // bookkeeping, never rendered

        is TimelineItem.Kind.ActivityIndicator -> ActivityIndicatorRow(label = kind.label)

        is TimelineItem.Kind.Unknown -> AmbientNotice(
            if (kind.eventType == "m.room.encrypted") "Encrypted message — waiting for key"
            else "[unsupported event: ${kind.eventType}]",
        )
    }
}

@Composable
private fun CappedCard(maxWidth: androidx.compose.ui.unit.Dp = CardMaxWidth, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(modifier = Modifier.widthIn(max = maxWidth)) { content() }
    }
}

@Composable
private fun AmbientNotice(text: String) {
    if (text.isEmpty()) return
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/**
 * Inline ask-user card. Binds the cached per-prompt [AskUserSheetViewModel] when
 * the resolvers are wired (production); otherwise renders a static, non-
 * interactive card (previews/tests).
 */
@Composable
private fun AskCard(
    eventID: String,
    event: AskUserEvent,
    askViewModel: ((String) -> AskUserSheetViewModel?)?,
    isPromptAnswered: ((String) -> Boolean)?,
    answerSummary: ((String) -> String?)?,
) {
    val vm = askViewModel?.invoke(eventID)
    if (vm == null || isPromptAnswered == null || answerSummary == null) {
        AskUserCard(
            event = event,
            isAnswered = false,
            answerSummary = null,
            textInput = "",
            onTextChange = {},
            selectedChoiceIDs = emptySet(),
            onToggleChoice = {},
            onPickChoice = {},
            onPickBoolean = {},
            isSending = false,
            isExpired = false,
            onSend = {},
        )
        return
    }
    AskUserCardHost(
        viewModel = vm,
        isAnswered = isPromptAnswered(eventID),
        answerSummary = answerSummary(eventID),
    )
}

@Composable
private fun AskUserCardHost(
    viewModel: AskUserSheetViewModel,
    isAnswered: Boolean,
    answerSummary: String?,
) {
    val scope = rememberCoroutineScope()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var text by remember { mutableStateOf(viewModel.textInput) }
    var selected by remember { mutableStateOf(viewModel.selectedChoiceIDs) }
    // Re-render the card into its expired state when the deadline passes:
    // isExpired reads the clock, so an explicit tick forces a re-read.
    var expiryTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(viewModel.promptEventID) {
        viewModel.awaitExpiry { expiryTick++ }
    }
    val isExpired = remember(expiryTick) { viewModel.isExpired }

    AskUserCard(
        event = viewModel.event,
        isAnswered = isAnswered,
        answerSummary = answerSummary,
        textInput = text,
        onTextChange = { text = it; viewModel.textInput = it },
        selectedChoiceIDs = selected,
        onToggleChoice = { id ->
            val next = if (id in selected) selected - id else selected + id
            selected = next
            viewModel.selectedChoiceIDs = next
        },
        onPickChoice = { id ->
            viewModel.selectedChoiceIDs = setOf(id)
            scope.launch { viewModel.send() }
        },
        onPickBoolean = { value ->
            viewModel.booleanAnswer = value
            scope.launch { viewModel.send() }
        },
        isSending = isSending,
        isExpired = isExpired,
        onSend = { scope.launch { viewModel.send() } },
        error = error,
    )
}

/**
 * Whether a [TimelineItem] should render at all — the port of
 * `TimelineItemView.shouldRender(_:)`. Hides state-change rows (meta-noise in a
 * bot chat) and button-response answers (pendingAsk bookkeeping).
 */
fun timelineItemShouldRender(item: TimelineItem): Boolean = when (item.kind) {
    is TimelineItem.Kind.StateChange -> false
    is TimelineItem.Kind.AskUserAnswer -> false
    else -> true
}

/**
 * Display name for a sender id — the port of `TimelineItemView.displayName(for:)`.
 * Takes the local part without the leading `@` sigil, falling back to the input
 * for genuinely malformed ids.
 */
fun timelineDisplayName(senderID: String): String {
    val withoutSigil = if (senderID.startsWith("@")) senderID.drop(1) else senderID
    return withoutSigil.split(":").firstOrNull()?.takeIf { it.isNotEmpty() } ?: senderID
}
