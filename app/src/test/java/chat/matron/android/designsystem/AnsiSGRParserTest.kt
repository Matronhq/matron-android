package chat.matron.android.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/// Plain-logic tests for the ANSI → [androidx.compose.ui.text.AnnotatedString]
/// converter — parser state carried across streamed chunks and SGR colour/bold
/// handling. Ported from the Swift `AnsiSGRParserTests` (in
/// `LiveOutputLogicTests`), with `AttributedString` run assertions adapted to
/// Compose span-style checks.
class AnsiSGRParserTest {
    private val esc = ""

    @Test
    fun plainTextPassesThrough() {
        val out = AnsiSGRParser().append("hello world\n")
        assertEquals("hello world\n", out.text)
    }

    @Test
    fun colorAndReset() {
        val out = AnsiSGRParser().append("${esc}[31mred${esc}[0m plain")
        assertEquals("red plain", out.text)
        // "red" carries the red palette colour; " plain" carries no span.
        val redStyle = out.spanStyles.single { it.start == 0 && it.end == 3 }
        assertEquals(AnsiSGRParser.palette[1], redStyle.item.color)
        assertNull(out.spanStyles.firstOrNull { it.start >= 3 })
    }

    @Test
    fun stateCarriesAcrossChunks() {
        val parser = AnsiSGRParser()
        parser.append("${esc}[32m")
        val out = parser.append("green")
        assertEquals("green", out.text)
        assertEquals(AnsiSGRParser.palette[2], out.spanStyles.first().item.color)
    }

    @Test
    fun escapeSplitAcrossChunksDoesNotLeak() {
        val parser = AnsiSGRParser()
        // Chunk boundary mid-sequence: "ESC[3" ... "1mred".
        val first = parser.append("a${esc}[3")
        assertEquals("a", first.text)
        val second = parser.append("1mred")
        assertEquals("red", second.text)
        assertEquals(AnsiSGRParser.palette[1], second.spanStyles.first().item.color)
    }

    @Test
    fun nonSGRSequencesAreStripped() {
        // Cursor-up CSI, OSC window title, and a two-byte escape.
        val out = AnsiSGRParser().append("${esc}[2Aup${esc}]0;title\u0007osc${esc}Mtwo")
        assertEquals("uposctwo", out.text)
    }

    @Test
    fun boldOnOff() {
        val out = AnsiSGRParser().append("${esc}[1mbold${esc}[22mnormal")
        assertEquals("boldnormal", out.text)
        val boldStyle = out.spanStyles.single { it.start == 0 && it.end == 4 }
        assertEquals(FontWeight.Bold, boldStyle.item.fontWeight)
        // "normal" (indexes 4..10) carries no span.
        assertNull(out.spanStyles.firstOrNull { it.start >= 4 })
    }

    @Test
    fun color256Mapping() {
        assertEquals(AnsiSGRParser.palette[1], AnsiSGRParser.color256(1))
        assertNotNull(AnsiSGRParser.color256(120)) // cube
        assertNotNull(AnsiSGRParser.color256(240)) // grayscale
        assertNull(AnsiSGRParser.color256(300))
    }

    @Test
    fun color256DeliveredViaSGRSplitAcrossChunks() {
        // "ESC[38;5;" ... "196mred" — the CSI params split mid-parameter-list.
        val parser = AnsiSGRParser()
        val first = parser.append("${esc}[38;5;")
        assertEquals("", first.text)
        val second = parser.append("196mred")
        assertEquals("red", second.text)
        assertEquals(AnsiSGRParser.color256(196), second.spanStyles.first().item.color)
    }

    @Test
    fun truecolorDeliveredViaSGRSplitAcrossChunks() {
        // "ESC[38;2;10;" ... "20;30mrgb" — split between truecolor components.
        val parser = AnsiSGRParser()
        val first = parser.append("${esc}[38;2;10;")
        assertEquals("", first.text)
        val second = parser.append("20;30mrgb")
        assertEquals("rgb", second.text)
        val expected = Color(red = 10 / 255f, green = 20 / 255f, blue = 30 / 255f)
        assertEquals(expected, second.spanStyles.first().item.color)
    }

    @Test
    fun brightColorsMapToUpperPaletteHalf() {
        val low = AnsiSGRParser().append("${esc}[90mx")
        assertEquals(AnsiSGRParser.palette[8], low.spanStyles.first().item.color)
        val mid = AnsiSGRParser().append("${esc}[93mx")
        assertEquals(AnsiSGRParser.palette[11], mid.spanStyles.first().item.color)
        val high = AnsiSGRParser().append("${esc}[97mx")
        assertEquals(AnsiSGRParser.palette[15], high.spanStyles.first().item.color)
    }

    @Test
    fun sgr39ResetsForegroundButPreservesBold() {
        val out = AnsiSGRParser().append("${esc}[1;31mboldred${esc}[39mstillbold")
        assertEquals("boldredstillbold", out.text)
        // "boldred" (0..7): bold + red, the combined bold+color span.
        val first = out.spanStyles.single { it.start == 0 && it.end == 7 }
        assertEquals(AnsiSGRParser.palette[1], first.item.color)
        assertEquals(FontWeight.Bold, first.item.fontWeight)
        // "stillbold" (7..16): 39 dropped the color but left bold untouched.
        // A SpanStyle always carries a Color (Unspecified when none was set) —
        // it's a non-null value type, not an absent/null color.
        val second = out.spanStyles.single { it.start == 7 && it.end == 16 }
        assertEquals(Color.Unspecified, second.item.color)
        assertEquals(FontWeight.Bold, second.item.fontWeight)
    }

    @Test
    fun oscTerminatedByEscBackslash() {
        val out = AnsiSGRParser().append("${esc}]0;title${esc}\\afterosc")
        assertEquals("afterosc", out.text)
    }

    @Test
    fun oscTerminatedByEscBackslashSplitRightBetweenTheTwoBytes() {
        val parser = AnsiSGRParser()
        val first = parser.append("${esc}]0;title${esc}")
        assertEquals("", first.text)
        val second = parser.append("\\after")
        assertEquals("after", second.text)
    }

    @Test
    fun unterminatedOscAtCapIsBufferedAndTerminatesOnNextChunk() {
        val parser = AnsiSGRParser()
        val prefix = "${esc}]0;"
        val filler = "x".repeat(512 - prefix.length) // total tail length == 512, at the cap.
        val out1 = parser.append(prefix + filler)
        assertEquals("", out1.text)
        val out2 = parser.append("\u0007after")
        assertEquals("at-cap tail must stay buffered and the BEL still terminates it", "after", out2.text)
    }

    @Test
    fun unterminatedOscOverCapIsDroppedAndNextChunkParsesFresh() {
        val parser = AnsiSGRParser()
        val prefix = "${esc}]0;"
        val filler = "x".repeat(513 - prefix.length) // total tail length == 513, one over the cap.
        val out1 = parser.append(prefix + filler)
        assertEquals("", out1.text)
        val out2 = parser.append("\u0007after")
        // Cap exceeded: pendingEscape was dropped rather than buffered, so the
        // next append() sees a fresh chunk and the BEL is no longer consumed as
        // an OSC terminator — it leaks through as an ordinary (control) char.
        assertEquals("\u0007after", out2.text)
    }

    @Test
    fun massivelyOversizedUnterminatedOscStillDropsCleanly() {
        val parser = AnsiSGRParser()
        val out1 = parser.append("${esc}]0;" + "x".repeat(5000))
        assertEquals("", out1.text)
        val out2 = parser.append("\u0007after")
        assertEquals("\u0007after", out2.text)
    }
}
