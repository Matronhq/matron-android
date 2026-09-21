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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.ItemState
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
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
    /// Maximum width of the thread column — roughly 70–75 characters per line.
    val measure: Dp = 640.dp

    /// Vertical gap between thread rows (header, body card, comments).
    val threadSpacing: Dp = 18.dp

    /// Inner padding of a body/comment card.
    val cardPadding: Dp = 14.dp
}

/// A comment queued locally (offline outbox / in-flight send) that hasn't
/// landed in the thread yet.
data class PendingCommentModel(
    val id: String,
    val body: String,
    val attachmentCount: Int,
    val attempts: Int,
    val lastError: String?,
)

/// Everything the detail view renders, supplied by the host from
/// `ItemDetailViewModel` (the Swift `ItemDetailView.Model`).
data class ItemDetailModel(
    val item: TrackerItem,
    val comments: List<TrackerComment>,
    val pending: List<PendingCommentModel>,
    val originTitle: String?,
    val availableResolutions: List<ItemResolution>,
    val isBusy: Boolean,
    /// The comment count of the loaded thread, `null` until the opening
    /// refetch has completed (`ItemDetailViewModel.loadedCommentCount`).
    val loadedCommentCount: Int? = null,
)

/// The state pill's four values: Needs you / Closed · resolution / With the
/// agent / Open.
fun itemStatusText(item: TrackerItem): String = when {
    item.needsUser -> "Needs you"
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
    now: Instant = Instant.now(),
    /// Whether the reader had previously scrolled to the bottom of this
    /// item's thread (`ItemReadMemory.wasAtBottom`, read once by the host).
    startsAtBottom: Boolean = false,
    /// Reports whether the thread's bottom is currently visible, so the host
    /// can persist it for next time.
    onBottomVisibilityChange: ((Boolean) -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
) {
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
    // optional body card, divider, comments, pending rows, anchor.
    val hasMeta = item.labels.isNotEmpty() || item.links.isNotEmpty()
    val hasBody = item.body.isNotEmpty() || item.attachments.isNotEmpty()
    val lastIndex = 1 + (if (hasMeta) 1 else 0) + (if (hasBody) 1 else 0) + 1 + rowCount

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
    LaunchedEffect(atBottom) { onBottomVisibilityChange?.invoke(atBottom) }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().wrapContentWidth().widthIn(max = ItemTypography.measure),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(ItemTypography.threadSpacing),
            ) {
                item(key = "header") { Header(model, onOpenConversation) }
                if (hasMeta) item(key = "meta") { Meta(item, onOpenLink) }
                if (hasBody) {
                    item(key = "body") {
                        ItemCard(mine = item.createdBy == ItemAuthor.USER) {
                            AuthorCaption(item.createdBy, item.createdAt, now)
                            if (item.body.isNotEmpty()) MarkdownText(item.body, onLinkClick = onOpenLink)
                            Attachments(item.attachments, image, onOpenAttachment)
                        }
                    }
                }
                item(key = "divider") { HorizontalDivider() }
                items(model.comments.size, key = { "c-${model.comments[it].id}" }) { i ->
                    CommentView(model.comments[i], now, image, onOpenAttachment, onOpenLink)
                }
                items(model.pending.size, key = { "p-${model.pending[it].id}" }) { i -> PendingView(model.pending[i]) }
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
            )
        }
    }
}

@Composable
private fun Header(model: ItemDetailModel, onOpenConversation: (String) -> Unit) {
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
        SelectableMessageText(item.title, textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
        val origin = model.originTitle
        if (origin != null) {
            Row(
                Modifier.clickable { onOpenConversation(item.originConvoID) },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(origin, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
private fun AuthorCaption(author: ItemAuthor, date: Instant, now: Instant) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (author == ItemAuthor.USER) "You" else "Agent", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text("· ${itemRelativeDate(date, now)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/// Renders a list of attachments with the shared chat-timeline primitives so
/// items match the conversation surface. Audio gets a bespoke transcript
/// treatment — neither shared primitive covers audio playback yet.
@Composable
private fun Attachments(list: List<TrackerAttachment>, image: (TrackerAttachment) -> Any?, onOpen: (TrackerAttachment) -> Unit) {
    list.forEach { a ->
        when {
            a.isImage -> AttachmentImage(model = image(a), onTap = { onOpen(a) })
            a.isAudio -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.clickable { onOpen(a) },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Voice note", style = MaterialTheme.typography.bodyMedium)
                }
                val transcript = a.transcript
                when {
                    !transcript.isNullOrEmpty() -> Text(transcript, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    a.transcriptionFailed -> Text("Couldn’t transcribe — tap to listen", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text("Transcribing…", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> AttachmentFile(filename = a.name, sizeBytes = a.size, onTap = { onOpen(a) })
        }
    }
}

@Composable
private fun CommentView(c: TrackerComment, now: Instant, image: (TrackerAttachment) -> Any?, onOpen: (TrackerAttachment) -> Unit, onOpenLink: (String) -> Unit) {
    if (c.kind == TrackerComment.Kind.STATUS) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemStatusLine(c)?.let { line ->
                Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (c.body.isNotEmpty()) {
                Text(c.body, style = MaterialTheme.typography.labelMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    } else {
        ItemCard(mine = c.author == ItemAuthor.USER) {
            AuthorCaption(c.author, c.createdAt, now)
            if (c.body.isNotEmpty()) MarkdownText(c.body, onLinkClick = onOpenLink)
            Attachments(c.attachments, image, onOpen)
        }
    }
}

@Composable
private fun PendingView(p: PendingCommentModel) {
    ItemCard(mine = true, alpha = 0.85f) {
        Text("You", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        if (p.body.isNotEmpty()) Text(p.body, style = MaterialTheme.typography.bodyLarge)
        if (p.attachmentCount > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp))
                Text("${p.attachmentCount} attachment${if (p.attachmentCount == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium)
            }
        }
        SendStateIndicator(pendingCommentSendState(p.attempts, p.lastError))
    }
}

/// The thread's card chrome — the chat bubble surfaces on a rounded
/// rectangle with the bubble shadow — shared by the body card, the comment
/// cards and the pending rows so they read as one thread.
@Composable
private fun ItemCard(mine: Boolean, alpha: Float = 1f, content: @Composable () -> Unit) {
    val colors = MatronThemeColors.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = (if (mine) colors.bubbleMe else colors.bubbleBot).copy(alpha = alpha),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(ItemTypography.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}
