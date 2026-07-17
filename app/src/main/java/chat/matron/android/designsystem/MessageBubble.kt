package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
/// the bubble's bottom-right corner, sharing the last line's baseline. The
/// sender name is deliberately NOT shown — these are 1:1 chats with one bot.
@Composable
fun MessageBubble(
    style: MessageAuthorStyle,
    modifier: Modifier = Modifier,
    timestamp: Instant? = null,
    content: @Composable () -> Unit,
) {
    val colors = MatronThemeColors.current
    val alignment = if (style == MessageAuthorStyle.Me) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (style == MessageAuthorStyle.Me) colors.bubbleMe else colors.bubbleBot

    Box(
        modifier
            .padding(horizontal = 16.dp)
            // Own bubbles keep a minimum inset from the far edge so a long
            // sent message never spans the full pane on narrow windows.
            .padding(start = if (style == MessageAuthorStyle.Me) 32.dp else 0.dp),
        contentAlignment = alignment,
    ) {
        Row(
            Modifier
                .widthIn(max = MessageBubbleMetrics.maxWidth)
                .shadow(1.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(bubbleColor)
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
    }
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
