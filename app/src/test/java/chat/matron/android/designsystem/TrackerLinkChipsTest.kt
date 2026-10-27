package chat.matron.android.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import chat.matron.android.journal.NumberKind
import chat.matron.android.journal.TrackerLinkInfo
import chat.matron.android.journal.TrackerLinkTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The chip after a tracker link in a message body: what it says in each
/// case, and how it is spliced into the parsed text without moving the
/// links' tap targets.
class TrackerLinkChipsTest {

    private val found = { kind: NumberKind, title: String -> TrackerLinkInfo.Found(kind, title) }

    @Test
    fun aBareLinkShowsTheKindAndTitle() {
        val chip = TrackerLinkChips.chipFor(found(NumberKind.ITEM, "Ship the release notes"), 12, "#12")
        assertEquals(TrackerLinkChip("item · Ship the release notes", mismatch = false), chip)
    }

    @Test
    fun aLinkThatCarriesTheRightTitleShowsOnlyTheKind() {
        val chip = TrackerLinkChips.chipFor(found(NumberKind.MISSION, "Ship the release notes"), 12, "#12 Ship the release…")
        assertEquals(TrackerLinkChip("mission", mismatch = false), chip)
    }

    @Test
    fun aLinkNamingAnotherTitleOrNumberIsFlagged() {
        val other = TrackerLinkChips.chipFor(found(NumberKind.ITEM, "Late orders after the cut-off"), 12, "#12 Merge: printer export")
        assertEquals(TrackerLinkChip("item · Late orders after the cut-off", mismatch = true), other)
        val wrongNumber = TrackerLinkChips.chipFor(found(NumberKind.ITEM, "Merge: printer export"), 12, "#13 Merge: printer export")
        assertTrue(wrongNumber!!.mismatch)
    }

    @Test
    fun aMissingNumberIsFlaggedAndAFailedLookupShowsNothing() {
        assertEquals(TrackerLinkChip("not found", mismatch = true), TrackerLinkChips.chipFor(TrackerLinkInfo.Missing, 99, "#99"))
        assertNull(TrackerLinkChips.chipFor(TrackerLinkInfo.Failed, 12, "#12"))
    }

    @Test
    fun aOneWordLinkOrABareUrlIsNotAClaimAboutTheTarget() {
        assertFalse(TrackerLinkChips.chipFor(found(NumberKind.ITEM, "Ship it"), 12, "here")!!.mismatch)
        assertFalse(TrackerLinkChips.chipFor(found(NumberKind.ITEM, "Ship it"), 12, "")!!.mismatch)
    }

    private fun body(): AnnotatedString = buildAnnotatedString {
        append("See ")
        val a = length
        append("#12")
        addStringAnnotation("URL", "https://chat.example/u/alice/12", a, length)
        append(" and ")
        val b = length
        append("matron://item/6")
        addStringAnnotation("URL", "matron://item/6", b, length)
        append(" or ")
        val c = length
        append("a PR")
        addStringAnnotation("URL", "https://github.com/acme/x/pull/1", c, length)
        append(".")
    }

    private val target = { url: String ->
        when (url) {
            "https://chat.example/u/alice/12" -> TrackerLinkTarget("alice", 12)
            "matron://item/6" -> TrackerLinkTarget("alice", 6)
            else -> null
        }
    }

    @Test
    fun linksListsTrackerLinksWithTheirTextAndABareUrlAsNoText() {
        val links = TrackerLinkChips.links(body(), target)
        assertEquals(listOf(TrackerLinkTarget("alice", 12), TrackerLinkTarget("alice", 6)), links.map { it.target })
        assertEquals(listOf("#12", ""), links.map { it.text })
    }

    @Test
    fun decorateAppendsEachChipAfterItsLinkAndKeepsTheLinksTappable() {
        val style = TrackerLinkChipStyle(SpanStyle(color = Color.Gray), SpanStyle(color = Color.Red))
        val chips = mapOf(
            TrackerLinkTarget("alice", 12) to TrackerLinkChip("item · Ship it", mismatch = false),
            TrackerLinkTarget("alice", 6) to TrackerLinkChip("not found", mismatch = true),
        )
        val out = TrackerLinkChips.decorate(body(), target, style) { chips[it.target] }

        assertTrue(out.text.startsWith("See #12"))
        assertTrue(out.text.contains("item · Ship it"))
        assertTrue(out.text.contains("⚠ not found"))
        assertTrue(out.text.endsWith(" or a PR."))
        val urls = out.getStringAnnotations("URL", 0, out.length)
        assertEquals(3, urls.size)
        for (u in urls) {
            val label = out.text.substring(u.start, u.end)
            assertTrue(label, label == "#12" || label == "matron://item/6" || label == "a PR")
        }
        // The chips carry their style; nothing else picked it up.
        val styled = out.spanStyles.filter { it.item.color == Color.Red || it.item.color == Color.Gray }
            .map { out.text.substring(it.start, it.end).trim() }
        assertEquals(listOf("item · Ship it", "⚠ not found"), styled)
    }

    @Test
    fun decorateWithNoChipsLeavesTheTextAlone() {
        val text = body()
        assertEquals(text, TrackerLinkChips.decorate(text, target, TrackerLinkChipStyle(SpanStyle(), SpanStyle())) { null })
    }
}
