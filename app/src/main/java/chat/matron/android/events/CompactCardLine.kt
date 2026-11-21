package chat.matron.android.events

import java.text.BreakIterator

/// The one quiet line a bulky timeline card folds to (icon, summary and,
/// once it is settled, a short status), drawn by `CompactCardRow` the way a
/// bridge notice is: tap it for the whole card. A reminder's Send now /
/// Cancel card is always folded; a consent card folds once it is answered,
/// because until then the facts it shows are what the decision rests on.
/// Ported from matron-apple's `Events/CompactCardLine.swift`.
data class CompactCardLine(
    val icon: String,
    val summary: String,
    val status: String?,
) {
    companion object {
        /// [text] on one line: runs of whitespace and line breaks become one
        /// space.
        fun oneLine(text: String): String = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

        /// A leading emoji or symbol followed by whitespace, split off
        /// ("⏰ In 10m" → "⏰", "In 10m"); a null glyph and the text untouched
        /// otherwise (a letter, digit or punctuation first, no space after
        /// it, or nothing after the space). One grapheme, so a multi-part
        /// emoji counts as one.
        fun leadingGlyph(text: String): Pair<String?, String> {
            if (text.isEmpty()) return null to text
            val first = text.codePointAt(0)
            if (Character.isWhitespace(first) || Character.isLetterOrDigit(first) || isPunctuation(first)) {
                return null to text
            }
            val breaker = BreakIterator.getCharacterInstance().apply { setText(text) }
            val end = breaker.next()
            if (end == BreakIterator.DONE || end >= text.length) return null to text
            if (!Character.isWhitespace(text.codePointAt(end))) return null to text
            val rest = text.substring(end).trimStart()
            return if (rest.isEmpty()) null to text else text.substring(0, end) to rest
        }

        private fun isPunctuation(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            -> true
            else -> false
        }

        /// The reminder line's icon when its prompt leads with none.
        const val REMINDER_ICON = "⏰"
    }
}

/// The bridge's reminder card: a single choice whose every option is one of
/// its `timer:` actions (Send now, Cancel timer).
val AskUserEvent.isReminderCard: Boolean
    get() {
        val choice = kind as? AskUserEvent.InputKind.Choice ?: return false
        return choice.options.isNotEmpty() && choice.options.all { it.value.startsWith("timer:") }
    }

/// The prompt on one line, its leading glyph as the icon.
val AskUserEvent.compactLine: CompactCardLine
    get() {
        val (glyph, rest) = CompactCardLine.leadingGlyph(prompt.trim())
        return CompactCardLine(icon = glyph ?: CompactCardLine.REMINDER_ICON, summary = CompactCardLine.oneLine(rest), status = null)
    }

/// The settled spawn request's line; `null` while it still waits for an
/// answer (or the Coordinator approved it and the session has not started
/// yet), so the full card stays up.
fun AgentSpawnRequest.compactLine(state: AgentSpawnCardState): CompactCardLine? {
    val resolved = state as? AgentSpawnCardState.Resolved ?: return null
    val (icon, status) = when (resolved.outcome.outcome) {
        "started" -> "🚀" to "Started"
        "declined" -> "🚫" to "Declined"
        "expired" -> "⌛" to "Expired"
        "approved" -> "✅" to "Approved"
        "failed" -> "❌" to "Failed"
        else -> "✨" to "Resolved"
    }
    return CompactCardLine(icon = icon, summary = CompactCardLine.oneLine("Spawn on $targetLabel: $headline"), status = status)
}

/// The answered chat request's line; `null` while it still waits for an
/// answer (or the answer is being sent, or failed to send).
fun AgentChatRequest.compactLine(state: AgentChatCardState): CompactCardLine? {
    val status = when (state) {
        AgentChatCardState.Idle, AgentChatCardState.Sending, is AgentChatCardState.Failed -> return null
        is AgentChatCardState.Answered -> (if (state.approved) "Approved" else "Declined") +
            (if (state.byCoordinator) " by the Coordinator" else "")
        AgentChatCardState.Expired -> "No longer waiting"
    }
    return CompactCardLine(icon = "👥", summary = CompactCardLine.oneLine(headline), status = status)
}
