package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/// ~24dp per spec — small enough to sit beside a bubble without competing
/// with it, big enough for two initials to stay legible.
val SENDER_AVATAR_DIAMETER = 24.dp

/**
 * Small tinted-circle avatar for a non-own message, shown beside its bubble
 * in rooms with ≥2 distinct senders (agent-chat rooms like "dan-mac ↔
 * dev-2") — see `ChatViewModel.hasMultipleSenders`. 1:1 chats never
 * construct this; every non-own bubble there already reads as "the bot", so
 * a per-message avatar would just be noise. Ports MatronShared/Sources/
 * DesignSystem/SenderAvatar.swift (apple #141).
 *
 * Colour comes from [BoxChipColors.tint] — the same deterministic name→hue
 * mapping the box chip uses — so a sender's avatar matches its box's colour
 * in the chat list. No avatar images: initials + colour only (spec,
 * 2026-08-13).
 *
 * Initials foreground comes from [BoxChipColors.contrastingForeground], not
 * a hardcoded white — the raw (full-opacity) fill spans light hues
 * (green/orange/cyan/mint) where white text falls well short of WCAG AA at
 * this font size; see that function's doc for why it picks per-hue rather
 * than reusing the chip's pale-fill `textTint`.
 */
@Composable
fun SenderAvatar(name: String, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(SENDER_AVATAR_DIAMETER)
            .background(BoxChipColors.tint(name), CircleShape)
            // In a multi-agent room the avatar is the only visible sender cue
            // and MessageBubble sets no sender label of its own, so hiding it
            // (Apple's `.accessibilityHidden(true)`) would leave TalkBack
            // reading message text with no who-sent-it. Announce the sender
            // here instead; the initials text underneath is merged away.
            .semantics(mergeDescendants = true) { contentDescription = "From $name" },
    ) {
        Text(
            text = senderAvatarInitials(name),
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = BoxChipColors.contrastingForeground(name),
        )
    }
}

/**
 * First letter of each name segment, uppercased, capped at two characters —
 * digits count as a segment (`dev-2` → "D2", so `dev-2`/`dev-3`/`dev-6`
 * stay visually distinct). Segments split on the separators box/agent names
 * actually use (`-`, `_`, space, `.`); a single-segment name (`mavis`)
 * yields a single initial rather than padding to two. Pure and top-level so
 * it's unit-testable without composing the view. Ports
 * `SenderAvatar.initials(for:)` (apple #141), byte for byte on the fixtures.
 *
 * The two-character cap is applied AFTER uppercasing, not before:
 * `uppercase()` can EXPAND a character (German `ß` → `"SS"`), so capping the
 * letter count pre-uppercase doesn't bound the final string length — `"ß-a"`
 * would uppercase its two capped letters ("ß", "a") to three displayed
 * characters ("SSA") despite the `take(2)` above (CodeRabbit, apple #141).
 *
 * Deviation: "first letter" and the cap are code-point-based (surrogate-pair
 * safe) where Swift's `Character`/`prefix` are grapheme-based — identical on
 * every fixture Apple pins; only multi-code-point grapheme clusters (ZWJ
 * emoji) could differ.
 */
fun senderAvatarInitials(name: String): String {
    val segments = name.split('-', '_', '.', ' ').filter { it.isNotEmpty() }
    val letters = segments.take(2).joinToString("") { segment ->
        String(Character.toChars(segment.codePointAt(0)))
    }
    val upper = letters.uppercase()
    val capped = upper.offsetByCodePoints(0, minOf(2, upper.codePointCount(0, upper.length)))
    return upper.substring(0, capped)
}
