package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/// Visual style of a message bubble. Bots render left-aligned on a white
/// bubble; "me" renders right-aligned on a light-cyan bubble — matron-web's
/// bubble layout, sitting on the cream timeline gradient.
enum class MessageAuthorStyle { Bot, Me }

/// Shared bubble metrics. A readable cap on a single bubble's width: on a wide
/// pane an uncapped bubble stretches message lines past comfortable reading
/// length. Rows still span the full width — received bubbles anchor left, own
/// bubbles right, like WhatsApp — only the bubble itself stops growing.
object MessageBubbleMetrics {
    val maxWidth = 760.dp
}

/// Layout primitive for a single message in the chat timeline. Wraps any
/// [content] (`MarkdownText`, `AttachmentImage`, `AttachmentFile`, …) and
/// applies the bubble chrome appropriate to the author.
///
/// [timestamp], when supplied, renders as a subtle light-grey time tucked into
/// the bubble's bottom-right corner, sharing the last line's baseline.
///
/// [sender], when supplied, renders as a leading, bottom-aligned
/// [SenderAvatar] outside the bubble chrome (apple #141). `null` (the
/// default) keeps the pre-avatar layout — zero change for every existing
/// call site. Callers pass it only in rooms with ≥2 distinct non-own senders
/// (`ChatViewModel.hasMultipleSenders`); own messages never pass one, and
/// it's ignored for [MessageAuthorStyle.Me] bubbles regardless — see
/// [messageBubbleShowsAvatar].
///
/// [copyText], when supplied, arms a long-press context menu offering a single
/// Copy action that puts the raw text on the clipboard — the port of the Apple
/// apps' Copy-only message context menu (matron-apple #92). Same long-press +
/// menu shape as the chat-list rows' Mute/Leave menu. Rows without natural
/// text (tool cards, diffs) pass nothing and present no menu at all.
@Composable
fun MessageBubble(
    style: MessageAuthorStyle,
    modifier: Modifier = Modifier,
    timestamp: Instant? = null,
    sender: String? = null,
    copyText: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = MatronThemeColors.current
    val alignment = if (style == MessageAuthorStyle.Me) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (style == MessageAuthorStyle.Me) colors.bubbleMe else colors.bubbleBot
    var copyMenuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current

    // The bubble itself — chrome + content + timestamp + copy menu. Factored
    // out so both the avatar'd and plain paths share one definition (Apple's
    // `bubbleChrome`: "layout stays in one place"). The inner Box exists to
    // anchor the DropdownMenu to the bubble itself, not the full-width outer
    // row.
    val bubbleChrome: @Composable () -> Unit = {
        Box {
            Row(
                Modifier
                    .widthIn(max = MessageBubbleMetrics.maxWidth)
                    .shadow(1.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(bubbleColor)
                    // pointerInput, not combinedClickable: a plain tap on a
                    // message has no action, so it should get no ripple.
                    .then(
                        if (copyText != null) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures(onLongPress = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    copyMenuOpen = true
                                })
                            }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                // Drop the time onto the baseline of the message's last line so a
                // short message reads inline ("Hi  12:15") and a multi-line one
                // tucks the time at the bottom-right.
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                content()
                if (timestamp != null) {
                    Text(
                        text = timeFormatter.format(timestamp.atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
            if (copyText != null) {
                DropdownMenu(expanded = copyMenuOpen, onDismissRequest = { copyMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        onClick = {
                            copyMenuOpen = false
                            clipboard.setText(AnnotatedString(copyText))
                        },
                    )
                }
            }
        }
    }

    Box(
        modifier
            .padding(horizontal = 16.dp)
            // Own bubbles keep a minimum inset from the far edge so a long
            // sent message never spans the full pane on narrow windows.
            .padding(start = if (style == MessageAuthorStyle.Me) 32.dp else 0.dp),
        contentAlignment = alignment,
    ) {
        val avatarSender = sender?.takeIf { messageBubbleShowsAvatar(style, sender) }
        if (avatarSender != null) {
            // Bottom-aligned like Apple's `.lastTextBaseline`-adjacent
            // HStack(alignment: .bottom, spacing: 6): the circle sits at the
            // foot of the bubble, beside the last line.
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SenderAvatar(avatarSender)
                bubbleChrome()
            }
        } else {
            bubbleChrome()
        }
    }
}

/**
 * Whether a bubble renders a leading [SenderAvatar]: [sender] only ever
 * renders for [MessageAuthorStyle.Bot] — own messages never get an avatar
 * even if a caller passed one by mistake (spec, 2026-08-13; Apple pins this
 * in `MessageBubbleSnapshotTests.test_botBubble_withSender`, ported here as
 * a logic test since the `nil`/`Me` path must be layout-identical to before
 * the parameter existed).
 */
fun messageBubbleShowsAvatar(style: MessageAuthorStyle, sender: String?): Boolean =
    sender != null && style == MessageAuthorStyle.Bot

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
