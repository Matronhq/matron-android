package chat.matron.android.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotCommandCatalogTest {
    @Test
    fun filterEmptyPrefixReturnsAll() {
        val all = BotCommandCatalog.claudeBridge
        assertEquals(all.size, BotCommandCatalog.filter(all, "").size)
    }

    @Test
    fun filterMatchesPrefixCaseInsensitive() {
        val filtered = BotCommandCatalog.filter(BotCommandCatalog.claudeBridge, "/STA")
        assertTrue(filtered.any { it.trigger == "/start" })
        assertTrue(filtered.any { it.trigger == "/status" })
        assertFalse(filtered.any { it.trigger == "/stop" })
    }

    @Test
    fun filterAcceptsBangPrefix() {
        val filtered = BotCommandCatalog.filter(BotCommandCatalog.claudeBridge, "!resu")
        assertTrue(filtered.any { it.trigger == "/resume" })
    }

    @Test
    fun filterNoMatchReturnsEmpty() {
        assertTrue(BotCommandCatalog.filter(BotCommandCatalog.claudeBridge, "/doesnotexist").isEmpty())
    }

    @Test
    fun claudeBridgeIncludesContextAndAccountCommands() {
        val triggers = BotCommandCatalog.claudeBridge.map { it.trigger }.toSet()
        for (expected in listOf("/context", "/compact", "/login", "/logout")) {
            assertTrue("catalog must include $expected", triggers.contains(expected))
        }
    }

    /// Pins the 2026-08-05 palette audit against the bridge's command set:
    /// /timer and /switch were missing entirely, and the rescue keystrokes
    /// must be the bang forms — the bridge only intercepts "!esc"/"!enter";
    /// a typed "/esc" passes through into the agent's terminal as junk.
    @Test
    fun claudeBridgeIncludesTimerSwitchAndBangRescues() {
        val triggers = BotCommandCatalog.claudeBridge.map { it.trigger }.toSet()
        for (expected in listOf("/timer", "/switch", "!esc", "!enter")) {
            assertTrue("catalog must include $expected", triggers.contains(expected))
        }
        assertFalse("slash-form esc is not a bridge command", triggers.contains("/esc"))
    }

    @Test
    fun claudeBridgeIsNonEmptyAndHasUniqueTriggers() {
        val all = BotCommandCatalog.claudeBridge
        assertFalse(all.isEmpty())
        assertEquals(all.size, all.map { it.trigger }.toSet().size)
    }
}
