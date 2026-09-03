package chat.matron.android.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/// Builds the styled `A:bc ` run that leads a chat title: the box letter in
/// the box's chip hue (so the eye can match rows to machines by color at
/// the START of the scan), the `:bc` session short in a secondary color.
/// Returned as an [AnnotatedString] so callers concatenate it with the title
/// and the whole line truncates as one — a separate composable would
/// ellipsize the title while the tag kept its own layout box. Ported from
/// matron-apple's `SessionTagText` (apple #152); pure functions rather than
/// SwiftUI `Text` concatenation so the composition is unit-testable.
///
/// Either half may be missing: single-box users have no letter (same gate
/// as `BoxChip`), seed titles and pre-#224 conversations have no session
/// short. `null` when there is nothing to show at all.
object SessionTagText {

    fun run(
        boxLetter: String?,
        boxName: String?,
        sessionShort: String?,
        darkTheme: Boolean,
        secondary: Color,
    ): AnnotatedString? {
        if (boxLetter == null && sessionShort == null) return null
        // The letter carries the box's chip hue — same derivation as the
        // BoxChip capsule, so tags and chips can never disagree on color.
        val tint = boxName?.let { BoxChipColors.textTint(it, darkTheme) } ?: secondary
        return buildAnnotatedString {
            boxLetter?.let {
                withStyle(SpanStyle(color = tint, fontWeight = FontWeight.SemiBold)) { append(it) }
            }
            sessionShort?.let {
                withStyle(SpanStyle(color = secondary)) {
                    append(if (boxLetter != null) ":$it" else it)
                }
            }
        }
    }

    /// The multi-agent room variant: one letter per participating box, each
    /// in its own box's hue — `A↔B` for a pair, `A,B,C` beyond — then the
    /// 2-char room short in secondary, same as the single-box tag.
    /// [letters] and [names] are parallel arrays (`ChatSummary.roomBoxShorts`
    /// / `roomBoxNames`): letters are the glyphs, names carry the hue.
    /// `null` unless at least two boxes arrive — the gates upstream mean a
    /// non-room, a local room, or a single-box user all fall through to
    /// [run].
    fun room(
        letters: List<String>,
        names: List<String>,
        sessionShort: String?,
        darkTheme: Boolean,
        secondary: Color,
    ): AnnotatedString? {
        if (letters.size < 2 || letters.size != names.size) return null
        val separator = if (letters.size == 2) "↔" else ","
        return buildAnnotatedString {
            letters.zip(names).forEachIndexed { index, (letter, name) ->
                if (index > 0) withStyle(SpanStyle(color = secondary)) { append(separator) }
                withStyle(
                    SpanStyle(
                        color = BoxChipColors.textTint(name, darkTheme),
                        fontWeight = FontWeight.SemiBold,
                    )
                ) { append(letter) }
            }
            sessionShort?.let { withStyle(SpanStyle(color = secondary)) { append(":$it") } }
        }
    }

    /// The full title line: room tag first, single-box tag second, bare
    /// title last — one composition shared by every place a tagged title
    /// renders (list rows, chat headers, search results), so the fallback
    /// order can't drift between them. [title] arrives ready to sit beside
    /// whatever tag renders (callers drop the room marker only when they
    /// pass ≥2 room participants — `SessionTag.titleBesideRoomTag`).
    fun titleLine(
        title: String,
        boxLetter: String?,
        boxName: String?,
        sessionShort: String?,
        roomBoxNames: List<String> = emptyList(),
        roomBoxShorts: List<String> = emptyList(),
        darkTheme: Boolean,
        secondary: Color,
    ): AnnotatedString {
        val tag = room(roomBoxShorts, roomBoxNames, sessionShort, darkTheme, secondary)
            ?: run(boxLetter, boxName, sessionShort, darkTheme, secondary)
            ?: return AnnotatedString(title)
        return tag + AnnotatedString(" $title")
    }
}
