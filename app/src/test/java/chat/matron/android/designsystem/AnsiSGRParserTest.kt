package chat.matron.android.designsystem

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
        val out = AnsiSGRParser().append("${esc}[2Aup${esc}]0;titleosc${esc}Mtwo")
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
}
