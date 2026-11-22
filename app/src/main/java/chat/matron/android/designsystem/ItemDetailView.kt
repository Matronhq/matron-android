package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Link
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import chat.matron.android.events.ItemSpawnConsent
import chat.matron.android.models.InlineSegment
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.ItemState
import chat.matron.android.models.ItemWorkingIndicator
import chat.matron.android.models.QueuedReplyState
import chat.matron.android.models.StagedAttachment
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import chat.matron.android.models.splitInlineAttachments
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/// Type and measure for the tracker item reading surface — one place for
/// the numbers so the body card, the comment cards, the pending rows and the
/// composer cannot drift apart. The item thread is a reading surface, not a
/// chat: a filed question is often several paragraphs, so it gets a capped,
/// centred column instead of stretching across a wide screen. Ported from
/// matron-apple's `ItemTypography`.
object ItemTypography {
    /// ×1.18 over the chat body (`bodyLarge`, 16sp ⇒ ≈19sp) — the same step
    /// iOS takes (17pt ⇒ ≈20pt). Chat bodies stay at the system size by
    /// decision; the item thread is the one surface that reads a step above.
    const val bodyScale: Float = 1.18f

    /// Extra leading between wrapped lines, in sp, on top of the scaled line
    /// height.
    const val lineSpacing: Float = 3f

    /// Gap after each markdown paragraph inside a body, in dp — a real
    /// paragraph break, not just a wrapped line, so multi-paragraph items
    /// read as prose rather than a wall.
    const val paragraphSpacing: Float = 14f

    /// Maximum width of the thread column — roughly 70–75 characters per line.
    val measure: Dp = 640.dp

    /// The item reading style for markdown bodies AND the plain text beside
    /// them (pending replies, voice transcripts), so the two never drift.
    @Composable
    fun bodyStyle(): TextStyle = itemBodyStyle(MaterialTheme.typography.bodyLarge)

    /// Vertical gap between thread rows (header, body card, comments).
    val threadSpacing: Dp = 18.dp

    /// Inner padding of a body/comment card.
    val cardPadding: Dp = 14.dp
}

/// [base] (the chat body, `bodyLarge`) scaled to the item reading size with
/// the thread's leading: font size × [ItemTypography.bodyScale], line height
/// scaled the same and then given [ItemTypography.lineSpacing] more. Pure so
/// `ItemTypographyTest` can pin that it really is larger than the chat body.
fun itemBodyStyle(base: TextStyle): TextStyle {
    val size = if (base.fontSize.isSpecified) base.fontSize * ItemTypography.bodyScale else 19.sp
    val lineHeight = if (base.lineHeight.isSp) {
        (base.lineHeight.value * ItemTypography.bodyScale + ItemTypography.lineSpacing).sp
    } else {
        (size.value * 1.5f + ItemTypography.lineSpacing).sp
    }
    return base.copy(fontSize = size, lineHeight = lineHeight)
}

/// The line under a voice note's "Voice note" row: the transcript when there
/// is one; otherwise the journal's transcription state — "Transcribing…"
/// while the job runs (or nobody has taken it), and "Couldn’t transcribe —
/// tap to listen" once it failed, instead of transcribing forever (apple
/// #227). `second` is whether the line is a status (italic, muted) rather
/// than words.
fun itemVoiceNoteCaption(attachment: TrackerAttachment): Pair<String, Boolean> {
    val transcript = attachment.transcript
    return when {
        !transcript.isNullOrEmpty() -> transcript to false
        attachment.transcriptionFailed -> "Couldn’t transcribe — tap to listen" to true
        else -> "Transcribing…" to true
    }
}

/// A comment queued locally (offline outbox / in-flight send) that hasn't
/// landed in the thread yet. [rejectedReason] is set when the journal refused
/// it: the row then says why and offers Retry and Discard.
data class PendingCommentModel(
    val id: String,
    val body: String,
    val attachmentCount: Int,
    val attempts: Int,
    val lastError: String?,
    val rejectedReason: String? = null,
)

/// Everything the detail view renders, supplied by the host from
/// `ItemDetailViewModel` (the Swift `ItemDetailView.Model`).
data class ItemDetailModel(
    val item: TrackerItem,
    val comments: List<TrackerComment>,
    val pending: List<PendingCommentModel>,
    /// The mission and owner conversation shown under the "#N · Kind" line
    /// ([itemContext]).
    val context: ItemContext,
    val availableResolutions: List<ItemResolution>,
    val isBusy: Boolean,
    /// The comment count of the loaded thread, `null` until the opening
    /// refetch has completed (`ItemDetailViewModel.loadedCommentCount`).
    val loadedCommentCount: Int? = null,
    /// Taps on a comment's button that are still in the outbox, as
    /// asking-comment id → label: drawn as chosen before the journal
    /// confirms (`ItemDetailViewModel.pendingTaps`).
    val pendingTaps: Map<String, String> = emptyMap(),
    /// A tap on the item's OWN buttons still in the outbox (no `reply_to`) —
    /// drawn as chosen before the journal confirms
    /// (`ItemDetailViewModel.pendingItemTap`).
    val pendingItemTap: String? = null,
    /// The indicator at the foot of the thread while the agent that owns the
    /// item is busy (`ItemDetailViewModel.workingIndicator`); `null` draws
    /// none.
    val working: ItemWorkingIndicator? = null,
    /// Conversation rows this device can open from a comment's caption.
    val openableConvoIDs: Set<String> = emptySet(),
    /// The spawn ask a consent item mirrors (`ItemDetailViewModel.spawnConsent`),
    /// drawn as its live card near the top of the thread.
    val spawnConsent: ItemSpawnConsent? = null,
    /// The user's replies the agent hasn't got yet, by comment id
    /// (`ItemDetailViewModel.queuedReplies`): each draws a delivery line.
    val queuedReplies: Map<String, QueuedReplyState> = emptyMap(),
    /// Queued replies whose card can be edited from the thread.
    val editableQueuedReplies: Set<String> = emptySet(),
    /// The queued reply being edited in the reply box, if any.
    val editingQueuedReplyID: String? = null,
    /// Edit's hold is still on its way to the agent's session.
    val editHoldPending: Boolean = false,
    /// The cancelled reply the reply box holds an edit of, if any.
    val replacingCommentID: String? = null,
) {
    /// The live spawn card is drawn: it carries the request in full, so the
    /// item's body (the same request as plain text, written for places that
    /// can't draw the card) and its own Approve / Decline would only repeat it.
    val drawsSpawnCard: Boolean get() = spawnConsent?.request != null
}

/// Whether the item's body card is drawn: not while the live spawn card
/// stands in for it.
fun itemDrawsBody(model: ItemDetailModel): Boolean =
    (model.item.body.isNotEmpty() || model.item.attachments.isNotEmpty()) && !model.drawsSpawnCard

/// The placeholder's sentence while a spawn consent item's card has not
/// reached this device. When the item carries its own Approve and Decline,
/// those answer it exactly as the card would, and it says so. A closed item's
/// request no longer waits for an answer, so nothing offers to approve it.
fun itemSpawnCardMissingText(answersAbove: Boolean, isOpen: Boolean = true): String = when {
    !isOpen -> "The request card isn't on this device. This request is no longer waiting for an answer."
    answersAbove -> "The request card hasn't reached this device yet. Approve or Decline above answers it as the card would."
    else -> "The request card hasn't reached this device yet. " +
        "It can be approved once it arrives, or from the conversation it was asked in."
}

/// What a queued reply's buttons do, by comment id. A `null` handler draws no
/// button (a view model with nothing to answer the card with).
data class ItemReplyDeliveryActions(
    val sendNow: ((String) -> Unit)? = null,
    val cancel: ((String) -> Unit)? = null,
    val edit: ((String) -> Unit)? = null,
    val cancelEdit: (() -> Unit)? = null,
    val editAndResend: ((String) -> Unit)? = null,
)

/// The state pill's four values: Needs you / Closed · resolution / With the
/// agent / Open.
fun itemStatusText(item: TrackerItem): String = when {
    item.needsUser && item.kind == ItemKind.NOTICE -> "To read"
    item.needsUser -> "Needs you"
    item.kind == ItemKind.NOTICE && item.state == ItemState.CLOSED && item.resolution == ItemResolution.DONE -> "Seen"
    item.state == ItemState.CLOSED -> "Closed" + (item.resolution?.let { " · ${ItemGlyph.label(it)}" } ?: "")
    item.awaiting == ItemAwaiting.AGENT -> "With the agent"
    else -> "Open"
}

/// The centred line a `status` comment renders, derived from `statusTo`
/// (never the raw body). "Reopened" only fires on an actual closed→open
/// transition; other non-closing changes describe the awaiting change, and a
/// status row that changed neither renders no line — `null` so the caller
/// can skip it instead of showing a blank line above a body it renders
/// separately.
fun itemStatusLine(comment: TrackerComment): String? {
    val who = if (comment.author == ItemAuthor.USER) "You" else "Agent"
    val to = comment.statusTo ?: return "$who updated the item"
    if (to.state == ItemState.CLOSED) {
        return "$who closed this" + (to.resolution?.let { " as ${ItemGlyph.label(it).lowercase()}" } ?: "")
    }
    if (comment.statusFrom?.state == ItemState.CLOSED && to.state == ItemState.OPEN) return "$who reopened this"
    val toAwaiting = to.awaiting
    if (toAwaiting != null && toAwaiting != comment.statusFrom?.awaiting) {
        return if (toAwaiting == ItemAwaiting.AGENT) "Now with the agent" else "Needs you"
    }
    return null
}

/// What a `status` comment renders: the small centred transition [line]
/// (null when nothing renderable changed) and, beneath it, the closing or
/// reopening [note] the journal stores as the comment's body — rendered as
/// an ordinary comment card so it reads at full size with tappable links.
/// A blank body has no card.
data class ItemStatusRow(val line: String?, val note: String?)

fun itemStatusRow(comment: TrackerComment): ItemStatusRow =
    ItemStatusRow(itemStatusLine(comment), comment.body.takeIf { it.isNotBlank() })

/// Every image the thread shows, in the order it shows them: the item's
/// own, then each comment's, oldest first; within a card, the images placed
/// in its text first, in reading order, then the ones listed after it
/// ([splitInlineAttachments]). What the fullscreen viewer swipes through
/// from a tapped image. A status change with no note draws no card, so its
/// attachments are not on screen and are left out. An image shown more than
/// once (the same blob attached again later) is listed once, where it first
/// appears: a tap on any copy of it then has one place in the gallery,
/// rather than the counter and neighbours of whichever copy came first.
fun itemThreadImages(item: TrackerItem?, comments: List<TrackerComment>): List<TrackerAttachment> {
    fun shown(body: String, attachments: List<TrackerAttachment>): List<TrackerAttachment> {
        val split = splitInlineAttachments(body, attachments)
        val inline = split.segments.mapNotNull { (it as? InlineSegment.Attachment)?.attachment }
        return (inline + split.trailing).filter { it.isImage }
    }
    val cards = buildList {
        if (item != null) add(item.body to item.attachments)
        for (c in comments) {
            val body = if (c.kind == TrackerComment.Kind.STATUS) itemStatusRow(c).note ?: continue else c.body
            add(body to c.attachments)
        }
    }
    return cards.flatMap { (body, attachments) -> shown(body, attachments) }.distinctBy { it.blobRef }
}

/// Maps outbox progress to the shared send glyph: a fresh comment that hasn't
/// attempted a send yet reads as "Sending…"; once at least one attempt has
/// been made without an error it's genuinely waiting on connectivity
/// ("Queued"); any recorded error wins and shows the retry affordance.
fun pendingCommentSendState(attempts: Int, lastError: String?): SendStateGlyph = when {
    lastError != null -> SendStateGlyph.Failed(lastError)
    attempts > 0 -> SendStateGlyph.Queued
    else -> SendStateGlyph.Sending
}

/// Relative caption for a comment's timestamp ("5 min ago"), computed against
/// [now] (not the ambient clock) so tests are deterministic. Falls back to an
/// absolute short date once the comment is more than 7 days older than
/// [now] — "3 mo. ago" reads worse than an actual date at that range.
/// [zone] and [locale] default to the reader's, and are parameters for the
/// same reason [now] is: the absolute fallback's month name is locale-bound,
/// so a test that asserts one has to say which locale it means.
fun itemRelativeDate(
    date: Instant,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val elapsed = Duration.between(date, now)
    if (elapsed > Duration.ofDays(7)) {
        return DateTimeFormatter.ofPattern("d MMM yyyy", locale).withZone(zone).format(date)
    }
    val seconds = elapsed.seconds
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} hr ago"
        else -> "${seconds / 86_400} day${if (seconds / 86_400 == 1L) "" else "s"} ago"
    }
}

/// Whether the jump-to-bottom button is offered: only after the initial
/// placement has run (so it can't flash during the opening scroll), only when
/// the thread overflows its viewport, and only while the reader is away from
/// the bottom.
fun itemThreadShowsJumpToBottom(placed: Boolean, scrollable: Boolean, atBottom: Boolean): Boolean =
    placed && scrollable && !atBottom

/// The one-time opening placement for an item: scroll to the tail first for a
/// reader who left the thread at its bottom, and only then report the
/// placement through [onPlaced]. The order matters — [itemThreadShowsJumpToBottom]
/// is gated on that flag precisely so the button can't appear during the
/// opening scroll, and a long thread reopened at its tail measures as "not at
/// bottom" until the scroll lands, so flagging the placement first flashes the
/// button at the top of the thread for a frame. A reader who isn't starting at
/// the bottom has nothing to scroll and is placed straight away (staying at
/// the top still arms follow-tail).
suspend fun itemThreadPlaceInitially(
    startsAtBottom: Boolean,
    scrollToBottom: suspend () -> Unit,
    onPlaced: () -> Unit,
) {
    if (startsAtBottom) scrollToBottom()
    onPlaced()
}

/// The follow-tail decision for a thread that just grew. Only re-pins when
/// the growth started from a thread that was already loaded — [oldCount] at
/// or above [loadedCount] — so the opening refetch of an unread item is never
/// mistaken for a new reply. Also requires the initial placement to have run,
/// the reader at the bottom, and real growth. A [startsAtBottom] reader is
/// exempt from the load gate: they asked for the tail.
fun itemThreadShouldFollowTail(
    loadedCount: Int?,
    startsAtBottom: Boolean,
    placed: Boolean,
    atBottom: Boolean,
    oldCount: Int,
    newCount: Int,
): Boolean {
    if (!placed || !atBottom || newCount <= oldCount) return false
    if (startsAtBottom) return true
    if (loadedCount == null) return false
    return oldCount >= loadedCount
}

/// Full detail surface for a single tracker item: header, labels/links, the
/// body card, the comment thread (with in-flight pending comments), and the
/// reply composer. The resolve/reopen control lives in the host's top bar.
/// A pure leaf — the host supplies everything through [model] and the
/// callbacks. Ported from matron-apple's `ItemDetailView`.
///
/// [image] resolves an image attachment to a Coil model (bytes/URL) or
/// `null` while loading; [onOpenAttachment] opens any attachment
/// (image viewer, file, voice note) — the host decides how.
/// Whether the working indicator appearing re-pins the viewport to the
/// bottom: for a reader settled there, as a new reply does. An indicator
/// that arrives with the opening load is part of that load, and must not
/// drag an unread thread to its end: [wasLoaded] is whether the thread had
/// loaded BEFORE the indicator appeared.
fun itemThreadShouldFollowWorking(
    appeared: Boolean, startsAtBottom: Boolean, placed: Boolean, atBottom: Boolean, wasLoaded: Boolean,
): Boolean = appeared && placed && atBottom && (startsAtBottom || wasLoaded)

@Composable
fun ItemDetailView(
    model: ItemDetailModel,
    draft: String,
    onDraftChange: (String) -> Unit,
    image: (TrackerAttachment) -> Any?,
    onOpenAttachment: (TrackerAttachment) -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onSubmit: () -> Unit,
    onAttach: () -> Unit,
    onVoiceNote: () -> Unit,
    modifier: Modifier = Modifier,
    /// The composer's tray: picked attachments waiting to go with the reply.
    attachments: List<StagedAttachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    /// The composer's voice-note recorder: while it records, the composer's
    /// send is the one send and [onSubmit] must carry the note.
    voice: ItemCommentVoice = ItemCommentVoice.IDLE,
    onCancelRecording: () -> Unit = {},
    microphoneMenu: (@Composable () -> Unit)? = null,
    /// A tap on one of a comment's one-tap answer buttons.
    onTapAction: (TrackerComment, String) -> Unit = { _, _ -> },
    /// A tap on one of the item's own buttons (a notice's Seen).
    onTapItemAction: (String) -> Unit = {},
    /// Opens a mission page by id: the context block's mission row.
    onOpenMission: (String) -> Unit = {},
    /// Retry / Discard under a reply the journal refused, by its local id.
    onRetryPending: (String) -> Unit = {},
    onDiscardPending: (String) -> Unit = {},
    /// Approve (`true`) / Decline on a spawn consent item's card; `null`
    /// draws the card's state without wiring its buttons to anything.
    onAnswerSpawn: ((Boolean) -> Unit)? = null,
    /// Opens the session a started spawn runs in.
    onOpenSpawnedRoom: (String) -> Unit = onOpenConversation,
    /// Send now / Cancel / Edit under a reply the agent hasn't got yet.
    replyDelivery: ItemReplyDeliveryActions = ItemReplyDeliveryActions(),
    now: Instant = Instant.now(),
    /// Whether the reader had previously scrolled to the bottom of this
    /// item's thread (`ItemReadMemory.wasAtBottom`, read once by the host).
    startsAtBottom: Boolean = false,
    /// Reports whether the thread's bottom is currently visible, so the host
    /// can persist it for next time.
    onBottomVisibilityChange: ((Boolean) -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    /// Copied files or images pasted into the reply field
    /// ([AttachmentPasteTarget]). `null` keeps text-only paste.
    onPasteAttachments: ((List<android.net.Uri>) -> Unit)? = null,
    /// The file chips' Save button, and whether an attachment is being
    /// fetched for one. `null` draws the chips without it.
    onSaveAttachment: ((TrackerAttachment) -> Unit)? = null,
    isSavingAttachment: (TrackerAttachment) -> Boolean = { false },
) = CompositionLocalProvider(LocalItemAttachmentSave provides ItemAttachmentSave(onSaveAttachment, isSavingAttachment)) {
    val item = model.item
    val rowCount = model.comments.size + model.pending.size
    var placed by remember(item.id) { mutableStateOf(false) }
    val atBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 8
        }
    }
    val scrollable by remember(listState) { derivedStateOf { listState.canScrollForward || listState.canScrollBackward } }
    // The bottom anchor's index, from the model rather than the layout (which
    // reports 0 rows before the first measure): header, optional meta,
    // optional body card, divider, comments, pending rows, the working
    // indicator, anchor.
    val hasMeta = item.labels.isNotEmpty() || item.links.isNotEmpty()
    val hasBody = itemDrawsBody(model)
    val hasSpawn = model.spawnConsent != null
    val lastIndex = 1 + (if (hasMeta) 1 else 0) + (if (hasSpawn) 1 else 0) + (if (hasBody) 1 else 0) + 1 + rowCount +
        (if (model.working != null) 1 else 0)

    LaunchedEffect(item.id) {
        itemThreadPlaceInitially(startsAtBottom, { listState.scrollToItem(lastIndex) }) { placed = true }
    }
    var previousCount by remember(item.id) { mutableStateOf(rowCount) }
    LaunchedEffect(rowCount) {
        val old = previousCount
        previousCount = rowCount
        if (itemThreadShouldFollowTail(model.loadedCommentCount, startsAtBottom, placed, atBottom, old, rowCount)) {
            listState.scrollToItem(lastIndex)
        }
    }
    // The working indicator arriving is a row at the tail too: a reader
    // settled there keeps it in view.
    val showsWorking = model.working != null
    val loaded = model.loadedCommentCount != null
    var previousWorking by remember(item.id) { mutableStateOf(showsWorking to loaded) }
    LaunchedEffect(showsWorking, loaded) {
        val (showed, wasLoaded) = previousWorking
        previousWorking = showsWorking to loaded
        if (itemThreadShouldFollowWorking(showsWorking && !showed, startsAtBottom, placed, atBottom, wasLoaded)) {
            listState.scrollToItem(lastIndex)
        }
    }
    LaunchedEffect(atBottom) { onBottomVisibilityChange?.invoke(atBottom) }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().wrapContentWidth().widthIn(max = ItemTypography.measure),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(ItemTypography.threadSpacing),
            ) {
                item(key = "header") { Header(model, onOpenConversation, onOpenMission, onTapItemAction) }
                if (hasMeta) item(key = "meta") { Meta(item, onOpenLink) }
                model.spawnConsent?.let { consent ->
                    // Between the meta row and the body, so the answer sits
                    // near the top of the thread.
                    item(key = "spawn") {
                        SpawnConsentCard(
                            consent,
                            answersAbove = item.actions.isNotEmpty() && item.state == ItemState.OPEN,
                            isOpen = item.state == ItemState.OPEN,
                            onAnswer = onAnswerSpawn,
                            onOpen = onOpenSpawnedRoom,
                        )
                    }
                }
                if (hasBody) {
                    item(key = "body") {
                        ItemCard(mine = item.createdBy == ItemAuthor.USER, copyText = itemCardCopyText(item.body, item.attachments)) {
                            AuthorCaption(item.attributionText ?: if (item.createdBy == ItemAuthor.USER) "You" else "Agent", item.createdAt, now)
                            ItemContent(item.body, item.attachments, image, onOpenAttachment, onOpenLink)
                        }
                    }
                }
                item(key = "divider") { HorizontalDivider() }
                items(model.comments.size, key = { "c-${model.comments[it].id}" }) { i ->
                    val c = model.comments[i]
                    CommentView(
                        c, now, image, onOpenAttachment, onOpenLink,
                        chosen = commentChosenAction(c, model.pendingTaps),
                        actionsEnabled = model.item.state == ItemState.OPEN,
                        onTapAction = { label -> onTapAction(c, label) },
                        openableConvoIDs = model.openableConvoIDs,
                        onOpenConversation = onOpenConversation,
                        delivery = model.queuedReplies[c.id]?.takeIf { c.author == ItemAuthor.USER && c.kind == TrackerComment.Kind.COMMENT }
                            ?.let { state -> { DeliveryLine(state, c, model, replyDelivery) } },
                    )
                }
                items(model.pending.size, key = { "p-${model.pending[it].id}" }) { i ->
                    val p = model.pending[i]
                    PendingView(p, onRetry = { onRetryPending(p.id) }, onDiscard = { onDiscardPending(p.id) })
                }
                model.working?.let { working ->
                    // The chat's own indicator, flush with the cards above it.
                    item(key = "working") {
                        ActivityIndicatorRow(working.label, horizontalPadding = 0.dp)
                    }
                }
                item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
            }
            if (itemThreadShowsJumpToBottom(placed, scrollable, atBottom)) {
                val scope = rememberCoroutineScope()
                JumpToBottomButton(
                    onClick = { scope.launch { listState.animateScrollToItem(lastIndex) } },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
        HorizontalDivider()
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ItemCommentComposer(
                draft = draft, onDraftChange = onDraftChange, isBusy = model.isBusy,
                onSubmit = onSubmit, onAttach = onAttach, onVoiceNote = onVoiceNote,
                attachments = attachments, onRemoveAttachment = onRemoveAttachment,
                voice = voice, onCancelRecording = onCancelRecording, microphoneMenu = microphoneMenu,
                onPasteAttachments = onPasteAttachments,
            )
        }
    }
}

@Composable
private fun Header(
    model: ItemDetailModel,
    onOpenConversation: (String) -> Unit,
    onOpenMission: (String) -> Unit,
    onTapItemAction: (String) -> Unit,
) {
    val item = model.item
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ItemGlyph.icon(item.kind), contentDescription = null, tint = ItemGlyph.tint(item.kind), modifier = Modifier.size(18.dp))
            Text(
                "#${item.num} · ${ItemGlyph.label(item.kind)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            val pillColor = if (item.needsUser) MatronOrange else MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                itemStatusText(item),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(pillColor.copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        // Which mission this item belongs to and the conversation that owns
        // it, directly under the "#N · Kind" line.
        ItemContextBlock(model.context, onOpenMission = onOpenMission, onOpenConversation = onOpenConversation)
        SelectableMessageText(item.title, textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
        // The item's own buttons sit under its title, the same buttons a
        // comment's question draws: a notice's Seen, an agent's Go.
        // A drawn spawn card answers on its own buttons; the item's Approve
        // and Decline (the same answer on the journal) would only repeat them.
        if (!model.drawsSpawnCard) {
            ActionButtons(
                item.actions, itemChosenAction(item, model.pendingItemTap),
                enabled = item.state == ItemState.OPEN, onTap = onTapItemAction,
            )
        }
    }
}

@Composable
private fun Meta(item: TrackerItem, onOpenLink: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (item.labels.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.labels.forEach { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        item.links.forEach { link ->
            Row(
                Modifier.clickable { onOpenLink(link.url) },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Text(link.title ?: link.url, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/// "You · 5 min ago" / "Agent · 3 Sept 2026" above a card's body.
@Composable
internal fun AuthorCaption(
    name: String,
    date: Instant,
    now: Instant,
    conversation: TrackerComment.AuthorConversation? = null,
    openableConvoIDs: Set<String> = emptySet(),
    onOpenConversation: (String) -> Unit = {},
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        conversation?.let { convo ->
            val canOpen = convo.id in openableConvoIDs
            Text(
                "· ${convo.title}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canOpen) 1f else 0.65f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                // Weighted children are measured after the box and date:
                // only the conversation spends the remaining width.
                modifier = Modifier.weight(1f, fill = false)
                    .then(if (canOpen) Modifier.clickable(role = Role.Button, onClickLabel = "Open conversation") {
                        onOpenConversation(convo.id)
                    } else Modifier)
                    .semantics {
                        contentDescription = "Conversation: ${convo.title}"
                        if (!canOpen) stateDescription = "Not on this device yet"
                    },
            )
        }
        Text("· ${itemRelativeDate(date, now)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/// Renders a list of attachments with the shared chat-timeline primitives so
/// items match the conversation surface. Audio gets a bespoke transcript
/// treatment — neither shared primitive covers audio playback yet.
/// The thread's file-chip Save, read by every attachment row however deep
/// in the body or a comment it sits.
private class ItemAttachmentSave(
    val save: ((TrackerAttachment) -> Unit)?,
    val isSaving: (TrackerAttachment) -> Boolean,
)

private val LocalItemAttachmentSave = compositionLocalOf { ItemAttachmentSave(null) { false } }

@Composable
private fun Attachments(list: List<TrackerAttachment>, image: (TrackerAttachment) -> Any?, onOpen: (TrackerAttachment) -> Unit) {
    list.forEach { a ->
        when {
            a.isImage -> AttachmentImage(model = image(a), filename = a.name, sizeBytes = a.size.takeIf { it > 0 }, onTap = { onOpen(a) })
            a.isAudio -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.clickable { onOpen(a) },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Voice note", style = MaterialTheme.typography.bodyMedium)
                }
                val (caption, isStatus) = itemVoiceNoteCaption(a)
                if (isStatus) {
                    Text(caption, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // Words beside a markdown body: the same reading size.
                    Text(caption, style = ItemTypography.bodyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                val save = LocalItemAttachmentSave.current
                AttachmentFile(
                    filename = a.name,
                    sizeBytes = a.size.takeIf { it > 0 },
                    isLoading = save.isSaving(a),
                    onTap = { onOpen(a) },
                    onSave = save.save?.let { { it(a) } },
                )
            }
        }
    }
}

/// Which of a comment's buttons to draw as chosen: a tap still in the outbox
/// first (it is the newer answer), else the journal's `chosen_action`. A label
/// the comment no longer offers marks nothing.
fun commentChosenAction(comment: TrackerComment, pendingTaps: Map<String, String>): String? =
    (pendingTaps[comment.id] ?: comment.chosenAction)?.takeIf { it in comment.actions }

/// Which of the item's own buttons to draw as chosen — [commentChosenAction]
/// one level up: a tap still in the outbox first, else `chosen_action`.
fun itemChosenAction(item: TrackerItem, pendingItemTap: String?): String? =
    (pendingItemTap ?: item.chosenAction)?.takeIf { it in item.actions }

/// The one-tap answers under the comment that asks, or under the item's
/// title for the item's own buttons. The chosen one is filled;
/// while the item is open another can still be tapped (the journal keeps the
/// latest). On a closed item the row is a record: nothing is tappable.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionButtons(actions: List<String>, chosen: String?, enabled: Boolean, onTap: (String) -> Unit) {
    if (actions.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (label in actions) {
            val isChosen = label == chosen
            val description = if (isChosen) "Chosen" else "Not chosen"
            if (isChosen) {
                Button(onClick = { onTap(label) }, enabled = enabled, modifier = Modifier.semantics { stateDescription = description }) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                OutlinedButton(onClick = { onTap(label) }, enabled = enabled, modifier = Modifier.semantics { stateDescription = description }) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun CommentView(
    c: TrackerComment,
    now: Instant,
    image: (TrackerAttachment) -> Any?,
    onOpen: (TrackerAttachment) -> Unit,
    onOpenLink: (String) -> Unit,
    chosen: String? = null,
    actionsEnabled: Boolean = false,
    onTapAction: (String) -> Unit = {},
    openableConvoIDs: Set<String> = emptySet(),
    onOpenConversation: (String) -> Unit = {},
    delivery: (@Composable () -> Unit)? = null,
) {
    if (c.kind == TrackerComment.Kind.STATUS) {
        val row = itemStatusRow(c)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            row.line?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            row.note?.let { note ->
                ItemCard(mine = c.author == ItemAuthor.USER, copyText = itemCardCopyText(note, c.attachments)) {
                    AuthorCaption(c.attributionText ?: c.authorName, c.createdAt, now, c.authorConversation, openableConvoIDs, onOpenConversation)
                    ItemContent(note, c.attachments, image, onOpen, onOpenLink)
                }
            }
        }
    } else {
        ItemCard(mine = c.author == ItemAuthor.USER, copyText = itemCardCopyText(c.body, c.attachments)) {
            AuthorCaption(c.attributionText ?: c.authorName, c.createdAt, now, c.authorConversation, openableConvoIDs, onOpenConversation)
            ItemContent(c.body, c.attachments, image, onOpen, onOpenLink)
            ActionButtons(c.actions, chosen, actionsEnabled, onTapAction)
            delivery?.invoke()
        }
    }
}

/// The delivery line under one of the user's replies, with the buttons that
/// apply to it. Edit and Edit and resend only on a text-only reply: the reply
/// box can't take back an uploaded attachment, and resending half a reply
/// would read as the whole of it. One edit at a time: the reply box holds one.
@Composable
private fun DeliveryLine(state: QueuedReplyState, c: TrackerComment, model: ItemDetailModel, actions: ItemReplyDeliveryActions) {
    val textOnly = c.attachments.isEmpty() && c.body.isNotBlank()
    val isEditing = model.editingQueuedReplyID == c.id
    ItemReplyDeliveryLine(
        state = state,
        onSendNow = actions.sendNow?.let { send -> { send(c.id) } },
        onCancel = actions.cancel?.let { cancel -> { cancel(c.id) } },
        onEdit = actions.edit?.takeIf {
            textOnly && c.id in model.editableQueuedReplies && model.editingQueuedReplyID == null && model.replacingCommentID == null
        }?.let { edit -> { edit(c.id) } },
        // Not while the box holds an edit of a queued reply.
        onEditAndResend = actions.editAndResend?.takeIf { textOnly && model.editingQueuedReplyID == null }
            ?.let { resend -> { resend(c.id) } },
        isEditing = isEditing,
        // Only once the hold has landed: a release sent first could be
        // overtaken by it.
        onCancelEdit = actions.cancelEdit?.takeIf { isEditing && !model.editHoldPending },
        holdPending = isEditing && model.editHoldPending,
    )
}

@Composable
private fun PendingView(p: PendingCommentModel, onRetry: () -> Unit, onDiscard: () -> Unit) {
    ItemCard(mine = true, alpha = 0.85f) {
        Text("You", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        if (p.body.isNotEmpty()) Text(p.body, style = ItemTypography.bodyStyle())
        if (p.attachmentCount > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp))
                Text("${p.attachmentCount} attachment${if (p.attachmentCount == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium)
            }
        }
        val refused = p.rejectedReason
        if (refused != null) RefusedReplyFooter(refused, onRetry, onDiscard)
        else SendStateIndicator(pendingCommentSendState(p.attempts, p.lastError))
    }
}

/// Under a reply the journal refused: why, and the only two ways on — send
/// it again, or let it go. Nothing retries it on its own.
@Composable
private fun RefusedReplyFooter(reason: String, onRetry: () -> Unit, onDiscard: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Error, contentDescription = null, modifier = Modifier.size(12.dp), tint = MatronRed)
            Text("Not sent: $reason", style = MaterialTheme.typography.labelSmall, color = MatronRed)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRetry) { Text("Retry") }
            TextButton(onClick = onDiscard) { Text("Discard") }
        }
    }
}

/// A body (the item's or a comment's) with its own attachments: the text in
/// reading order with each `![caption](attachment:ref)` attachment shown in
/// place ([splitInlineAttachments]), then the attachments no ref placed.
/// Text segments go through [ItemBody] and attachments through
/// [Attachments], so an inline image is the same view, size and
/// tap-to-full-screen as a trailing one.
@Composable
private fun ItemContent(
    body: String,
    attachments: List<TrackerAttachment>,
    image: (TrackerAttachment) -> Any?,
    onOpen: (TrackerAttachment) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val split = remember(body, attachments) { splitInlineAttachments(body, attachments) }
    split.segments.forEach { segment ->
        when (segment) {
            is InlineSegment.Text -> ItemBody(segment.markdown, onOpenLink)
            is InlineSegment.Attachment -> Attachments(listOf(segment.attachment), image, onOpen)
        }
    }
    Attachments(split.trailing, image, onOpen)
}

/// A markdown body at the item reading scale with the thread's leading and
/// paragraph gap — one call for the item body and every comment.
@Composable
private fun ItemBody(markdown: String, onOpenLink: (String) -> Unit) {
    MarkdownText(
        markdown,
        textStyle = ItemTypography.bodyStyle(),
        onLinkClick = onOpenLink,
        paragraphSpacing = ItemTypography.paragraphSpacing,
    )
}

/// What a card's long-press Copy copies: the body's markdown as written,
/// without its inline attachment refs, each text part trimmed and the parts
/// a blank line apart. `null` (no Copy) for a card with no text, such as
/// one that only carries attachments.
fun itemCardCopyText(body: String, attachments: List<TrackerAttachment>): String? =
    splitInlineAttachments(body, attachments).segments
        .mapNotNull { (it as? InlineSegment.Text)?.markdown?.trim()?.takeIf(String::isNotEmpty) }
        .joinToString("\n\n")
        .ifEmpty { null }

/// The thread's card chrome — the chat bubble surfaces on a rounded
/// rectangle with the bubble shadow — shared by the body card, the comment
/// cards and the pending rows so they read as one thread.
///
/// With [copyText], a long-press beside the card's words (the space past a
/// short line, between paragraphs, its padding or its caption) offers Copy
/// for the whole text, as a chat bubble does ([LongPressCopy]). The words
/// themselves keep their own taps (links); code blocks keep their own copy
/// button.
@Composable
private fun ItemCard(mine: Boolean, alpha: Float = 1f, copyText: String? = null, content: @Composable () -> Unit) {
    val colors = MatronThemeColors.current
    val copy = rememberLongPressCopy(copyText)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = (if (mine) colors.bubbleMe else colors.bubbleBot).copy(alpha = alpha),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            Column(
                Modifier.fillMaxWidth().then(copy.modifier).padding(ItemTypography.cardPadding),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { content() }
            copy.Menu()
        }
    }
}

/// The spawn consent card for a consent item: the live card when its own
/// event is on this device, so what is approved is byte-for-byte what the
/// conversation's card shows. Without it there is nothing to approve: the id
/// in the item's link is agent-written and could name an ask the user has
/// never seen, so the placeholder says the card hasn't arrived and offers no
/// buttons.
@Composable
private fun SpawnConsentCard(
    consent: ItemSpawnConsent,
    answersAbove: Boolean,
    isOpen: Boolean,
    onAnswer: ((Boolean) -> Unit)?,
    onOpen: (String) -> Unit,
) {
    val request = consent.request
    if (request != null) {
        AgentSpawnRequestCard(
            request = request,
            state = consent.state,
            onApprove = { onAnswer?.invoke(true) },
            onDeny = { onAnswer?.invoke(false) },
            onOpen = onOpen,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MatronThemeColors.current.bubbleBot,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Agent spawn request", style = MaterialTheme.typography.titleSmall)
            Text(
                itemSpawnCardMissingText(answersAbove, isOpen),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
