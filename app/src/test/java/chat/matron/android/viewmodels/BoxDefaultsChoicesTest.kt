package chat.matron.android.viewmodels

import chat.matron.android.journal.BoxDefaults
import chat.matron.android.viewmodels.BoxDefaultsChoices.ModelDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxDefaultsChoicesTest {
    @Test
    fun summary_namesWhatANewSessionGets() {
        assertEquals("Box default", BoxDefaultsChoices.summary(BoxDefaults()))
        // No agent: the bridge applies no box model or effort, so none shows.
        assertEquals("Box default", BoxDefaultsChoices.summary(BoxDefaults(model = "opus", effort = "high")))
        assertEquals("Claude · Opus · High", BoxDefaultsChoices.summary(BoxDefaults("claude", "opus", "high")))
        assertEquals("Claude · Your default model", BoxDefaultsChoices.summary(BoxDefaults("claude")))
        assertEquals("Codex · gpt-5.1-codex · X-High", BoxDefaultsChoices.summary(BoxDefaults("codex", "gpt-5.1-codex", "xhigh")))
        assertEquals("Codex · Codex's own model", BoxDefaultsChoices.summary(BoxDefaults("codex")))
        assertEquals("Codex · o3 · Minimal", BoxDefaultsChoices.summary(BoxDefaults("codex", "o3", "minimal")))
    }

    @Test
    fun effortChoices_followTheAgent() {
        val codex = BoxDefaultsChoices.effortChoices(BoxDefaults(agent = "codex")).map { it.value }
        assertEquals(listOf(null, "minimal", "low", "medium", "high", "xhigh"), codex)
        val claude = BoxDefaultsChoices.effortChoices(BoxDefaults(agent = "claude")).map { it.value }
        assertEquals(listOf(null, "low", "medium", "high", "xhigh", "max"), claude)
    }

    @Test
    fun choices_keepAStoredValueOutsideTheList() {
        val models = BoxDefaultsChoices.modelChoices(BoxDefaults("claude", "claude-opus-5-5")).map { it.value }
        assertEquals("claude-opus-5-5", models.last())
        assertEquals(listOf(null, "opus", "opus[1m]", "sonnet", "sonnet[1m]", "haiku", "opusplan", "fable"), models.dropLast(1))
    }

    @Test
    fun codexModel_checksTheDraftLikeTheJournal() {
        assertEquals(ModelDraft.Valid(null), BoxDefaultsChoices.codexModel("   "))
        assertEquals(ModelDraft.Valid("gpt-5.1-codex"), BoxDefaultsChoices.codexModel("  GPT-5.1-Codex "))
        assertEquals(ModelDraft.Invalid, BoxDefaultsChoices.codexModel("gpt 5"))
        assertEquals(ModelDraft.Invalid, BoxDefaultsChoices.codexModel("-gpt"))
        assertEquals(ModelDraft.Invalid, BoxDefaultsChoices.codexModel("a".repeat(65)))
        assertTrue(BoxDefaultsChoices.codexModel("o3") is ModelDraft.Valid)
        assertFalse(BoxDefaultsChoices.codexModel("o3!") is ModelDraft.Valid)
    }
}
