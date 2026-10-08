package chat.matron.android.designsystem

import chat.matron.android.events.DiffEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/// Pins [DiffCardAccessibility.summary] — the single source of truth the chat
/// timeline rows must reuse for their row-level content description. Ported
/// from the Swift `DiffCardAccessibilityTests`.
class DiffCardAccessibilityTest {
    private fun event(
        tool: String? = "Edit",
        newFile: Boolean = false,
        added: Int? = 2,
        removed: Int? = 1,
        hasFilename: Boolean = true,
    ): DiffEvent = DiffEvent(
        filePath = if (hasFilename) "/w/Sources/A.swift" else null,
        displayPath = null,
        viewerURL = null,
        tool = tool,
        label = null,
        diff = "",
        added = added,
        removed = removed,
        truncated = false,
        newFile = newFile,
    )

    @Test
    fun editIncludesCountsAndFilename() {
        assertEquals("Edited A.swift, 2 additions, 1 removal", DiffCardAccessibility.summary(event()))
    }

    @Test
    fun writeNewFileUsesCreatedWording() {
        assertEquals(
            "Created A.swift, 5 additions",
            DiffCardAccessibility.summary(event(tool = "Write", newFile = true, added = 5, removed = null)),
        )
    }

    @Test
    fun writeExistingFileUsesWroteWording() {
        assertEquals(
            "Wrote A.swift, 3 removals",
            DiffCardAccessibility.summary(event(tool = "Write", newFile = false, added = null, removed = 3)),
        )
    }

    @Test
    fun missingFilenameFallsBackToGenericFile() {
        assertEquals(
            "Edited file, 1 addition",
            DiffCardAccessibility.summary(event(added = 1, removed = null, hasFilename = false)),
        )
    }

    @Test
    fun singularCountsDropPluralS() {
        assertEquals(
            "Edited A.swift, 1 addition, 1 removal",
            DiffCardAccessibility.summary(event(added = 1, removed = 1)),
        )
    }

    @Test
    fun noCountsOmitsThem() {
        assertEquals("Edited A.swift", DiffCardAccessibility.summary(event(added = null, removed = null)))
    }
}
