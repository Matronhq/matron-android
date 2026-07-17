package chat.matron.android.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight

/// Stateful ANSI → [AnnotatedString] converter for the live-output pane.
/// Mirrors matron-web's `ansiToReact` and the Swift `AnsiSGRParser`: SGR
/// colour/bold codes are applied, every other escape sequence (cursor
/// movement, OSC titles, …) is stripped. Stateful on two axes so it can be fed
/// streaming chunks:
///   * SGR state (current colour/bold) carries across chunks;
///   * a chunk may end mid-escape-sequence — the tail is buffered and
///     prepended to the next chunk instead of leaking `ESC[3` fragments into
///     the output.
///
/// A `struct` in Swift; a mutable class here (the append loop mutates in place
/// and is not shared across threads).
class AnsiSGRParser {
    private var pendingEscape = ""
    private var bold = false
    private var foreground: Color? = null

    /// Converts one streamed chunk, applying carried-over SGR state and
    /// buffering any trailing partial escape sequence for the next call.
    fun append(chunk: String): AnnotatedString {
        val builder = AnnotatedString.Builder()
        val plain = StringBuilder()
        val text = pendingEscape + chunk
        pendingEscape = ""
        var index = 0

        fun flushPlain() {
            if (plain.isEmpty()) return
            val style = when {
                foreground != null && bold -> SpanStyle(color = foreground!!, fontWeight = FontWeight.Bold)
                foreground != null -> SpanStyle(color = foreground!!)
                bold -> SpanStyle(fontWeight = FontWeight.Bold)
                else -> null
            }
            if (style != null) {
                builder.pushStyle(style)
                builder.append(plain.toString())
                builder.pop()
            } else {
                builder.append(plain.toString())
            }
            plain.setLength(0)
        }

        while (index < text.length) {
            val char = text[index]
            if (char != '\u001B') {
                plain.append(char)
                index++
                continue
            }
            // At an ESC: emit what's accumulated under the CURRENT attributes
            // before any SGR change mutates them.
            flushPlain()
            // If the rest of the chunk can't tell us what kind of sequence this
            // is yet, buffer it for the next chunk.
            val kindIndex = index + 1
            if (kindIndex >= text.length) {
                pendingEscape = text.substring(index)
                break
            }
            when (text[kindIndex]) {
                '[' -> { // CSI … final byte 0x40–0x7E
                    var scan = kindIndex + 1
                    val params = StringBuilder()
                    var terminated = false
                    while (scan < text.length) {
                        val c = text[scan]
                        val code = c.code
                        if (code in 0x40..0x7E) {
                            if (c == 'm') applySGR(params.toString()) // others stripped
                            index = scan + 1
                            terminated = true
                            break
                        }
                        params.append(c)
                        scan++
                    }
                    if (!terminated) {
                        pendingEscape = text.substring(index)
                        index = text.length
                    }
                }
                ']' -> { // OSC … terminated by BEL or ESC \
                    var scan = kindIndex + 1
                    var terminated = false
                    while (scan < text.length) {
                        if (text[scan] == '\u0007') {
                            index = scan + 1
                            terminated = true
                            break
                        }
                        if (text[scan] == '\u001B' && scan + 1 < text.length && text[scan + 1] == '\\') {
                            index = scan + 2
                            terminated = true
                            break
                        }
                        scan++
                    }
                    if (!terminated) {
                        // Unterminated OSC could buffer unboundedly on hostile
                        // input — cap what we carry and otherwise drop it.
                        val tail = text.substring(index)
                        pendingEscape = if (tail.length <= 512) tail else ""
                        index = text.length
                    }
                }
                else -> {
                    // Two-byte escape (ESC + one char) — strip both.
                    index = kindIndex + 1
                }
            }
        }
        flushPlain()
        return builder.toAnnotatedString()
    }

    private fun applySGR(params: String) {
        val codes = params.split(";").map { it.toIntOrNull() ?: 0 }
        val list = if (params.isEmpty()) listOf(0) else codes
        var i = 0
        while (i < list.size) {
            when (val code = list[i]) {
                0 -> { bold = false; foreground = null }
                1 -> bold = true
                22 -> bold = false
                in 30..37 -> foreground = palette[code - 30]
                in 90..97 -> foreground = palette[code - 90 + 8]
                39 -> foreground = null
                38 -> {
                    // 38;5;n (256-color) or 38;2;r;g;b (truecolor).
                    if (i + 2 < list.size && list[i + 1] == 5) {
                        foreground = color256(list[i + 2])
                        i += 2
                    } else if (i + 4 < list.size && list[i + 1] == 2) {
                        foreground = Color(
                            red = list[i + 2] / 255f,
                            green = list[i + 3] / 255f,
                            blue = list[i + 4] / 255f,
                        )
                        i += 4
                    }
                }
                else -> Unit // backgrounds, underline, etc. — stripped
            }
            i++
        }
    }

    companion object {
        /// Terminal palette tuned for the pane's pinned dark background.
        /// Indexes 0–7 normal, 8–15 bright (SGR 30–37 / 90–97).
        val palette: List<Color> = listOf(
            Color(0.20f, 0.20f, 0.20f), // black
            Color(0.90f, 0.35f, 0.35f), // red
            Color(0.45f, 0.82f, 0.45f), // green
            Color(0.88f, 0.79f, 0.36f), // yellow
            Color(0.42f, 0.63f, 0.94f), // blue
            Color(0.80f, 0.52f, 0.86f), // magenta
            Color(0.40f, 0.80f, 0.83f), // cyan
            Color(0.86f, 0.86f, 0.86f), // white
            Color(0.45f, 0.45f, 0.45f),
            Color(1.00f, 0.47f, 0.47f),
            Color(0.56f, 0.94f, 0.56f),
            Color(0.98f, 0.91f, 0.50f),
            Color(0.55f, 0.73f, 1.00f),
            Color(0.92f, 0.64f, 0.98f),
            Color(0.52f, 0.93f, 0.96f),
            Color(0.98f, 0.98f, 0.98f),
        )

        fun color256(n: Int): Color? = when (n) {
            in 0..15 -> palette[n]
            in 16..231 -> {
                val v = n - 16
                val r = v / 36
                val g = (v % 36) / 6
                val b = v % 6
                fun scale(c: Int): Float = if (c == 0) 0f else ((c * 40 + 55) / 255f)
                Color(red = scale(r), green = scale(g), blue = scale(b))
            }
            in 232..255 -> {
                val gray = ((n - 232) * 10 + 8) / 255f
                Color(red = gray, green = gray, blue = gray)
            }
            else -> null
        }
    }
}
