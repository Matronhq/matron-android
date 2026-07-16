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

    @Test
    fun claudeBridgeIsNonEmptyAndHasUniqueTriggers() {
        val all = BotCommandCatalog.claudeBridge
        assertFalse(all.isEmpty())
        assertEquals(all.size, all.map { it.trigger }.toSet().size)
    }
}
