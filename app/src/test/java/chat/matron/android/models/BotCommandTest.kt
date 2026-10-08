package chat.matron.android.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // MARK: - Static argument suggestions (apple #161, 2026-08-10 spec phase 1)

    private fun command(trigger: String) = BotCommandCatalog.claudeBridge.first { it.trigger == trigger }

    /// Pins the two flags the 2026-08-10 audit found documented as nonexistent:
    /// the catalog said /restart took no arguments at all, so the palette
    /// denied flags the bridge accepts.
    @Test
    fun claudeBridge_restartOffersForceAndBrowser() {
        assertEquals(listOf("--force", "--browser"), command("/restart").argSuggestions.map { it.value })
    }

    /// Every session-creating command accepts the agent-picker flags.
    @Test
    fun claudeBridge_agentFlagsOnSessionCommands() {
        for (trigger in listOf("/start", "/resume", "/sessions", "/workdir")) {
            val values = command(trigger).argSuggestions.map { it.value }.toSet()
            assertTrue("$trigger must offer --claude and --codex", values.containsAll(listOf("--claude", "--codex")))
        }
    }

    @Test
    fun claudeBridge_startOffersBrowser() {
        assertTrue(command("/start").argSuggestions.any { it.value == "--browser" })
    }

    /// /switch and /mode had their values only as an argHint; the spec makes
    /// them selectable rows.
    @Test
    fun claudeBridge_switchAndModeValuesSelectable() {
        assertEquals(listOf("claude", "codex"), command("/switch").argSuggestions.map { it.value })
        assertEquals(listOf("interactive", "print"), command("/mode").argSuggestions.map { it.value })
    }

    @Test
    fun claudeBridge_timerOffersCancel() {
        assertEquals(listOf("cancel"), command("/timer").argSuggestions.map { it.value })
    }
}

/// `BotCommandCatalog.argSuggestions` — the pure resolver behind the palette's
/// argument-completion mode: given the raw composer input, which of the
/// matched command's static suggestions apply. Sibling of
/// `BotCommandCatalog.filter` and tested the same way (apple #161).
class ArgSuggestionResolutionTest {
    private fun resolve(input: String) =
        BotCommandCatalog.argSuggestions(input, BotCommandCatalog.claudeBridge).map { it.value }

    @Test fun completeCommandWithEmptyPartial_offersAll() = assertEquals(listOf("--force", "--browser"), resolve("/restart "))
    @Test fun partialFlag_filtersCaseInsensitive() = assertEquals(listOf("--browser"), resolve("/restart --B"))
    @Test fun typedFlag_isNotReoffered() = assertEquals(listOf("--browser"), resolve("/restart --force "))

    /// Mirrors the folder rule: a suggestion equal to what's already typed
    /// offers nothing, so the palette doesn't linger once the flag is complete.
    @Test fun partialIdenticalToSuggestion_offersNothing() = assertEquals(emptyList<String>(), resolve("/restart --browser"))

    /// No whitespace after the command yet — the command list, not the
    /// argument list, owns this input.
    @Test fun incompleteCommandToken_offersNothing() = assertEquals(emptyList<String>(), resolve("/restart"))

    /// "/rest" prefix-matches /restart in the COMMAND palette, but the argument
    /// resolver needs the full trigger.
    @Test fun commandPrefixIsNotACommand() = assertEquals(emptyList<String>(), resolve("/rest --f"))
    @Test fun unknownCommand_offersNothing() = assertEquals(emptyList<String>(), resolve("/doesnotexist --x"))

    @Test fun freeTextCommands_offerNothing() {
        assertEquals(emptyList<String>(), resolve("/stop "))
        assertEquals(emptyList<String>(), resolve("/compact tighten it "))
    }

    /// Value suggestions (no `--`) fill a single slot: once any argument token
    /// is down, they stop being offered — `/switch claude codex` is junk and
    /// the palette must not build it.
    @Test fun values_offeredOnlyForFirstArgument() {
        assertEquals(listOf("claude", "codex"), resolve("/switch "))
        assertEquals(emptyList<String>(), resolve("/switch claude "))
        assertEquals(emptyList<String>(), resolve("/timer cancel "))
    }

    /// Flags compose, so later slots keep offering the remaining ones.
    @Test fun flags_remainOfferedAfterEarlierFlags() = assertEquals(listOf("--browser"), resolve("/restart --force "))

    /// Mutually exclusive flags must not be offered together — the bridge
    /// refuses "/start --claude --codex" ("Choose only one agent"), so the
    /// palette must not build it from two taps. --browser is Claude-only, so a
    /// --codex line drops it too.
    @Test fun exclusiveFlags_notOfferedTogether() {
        assertEquals(listOf("--browser"), resolve("/start --claude "))
        assertEquals(emptyList<String>(), resolve("/start --codex "))
        assertEquals(emptyList<String>(), resolve("/resume --claude "))
    }

    /// The Claude-only rule must hold in both orders: a line already carrying
    /// --browser must not offer --codex (only --browser lists the conflict; the
    /// resolver checks the suggestion's own list against what's typed).
    @Test fun exclusiveFlags_browserFirst_dropsCodex() = assertEquals(listOf("--claude"), resolve("/start --browser "))

    /// Phone keyboards auto-correct a leading "--" into an em dash; the bridge
    /// normalizes leading unicode dashes before parsing, so the resolver must
    /// match — both in the partial being completed and in tokens already on
    /// the line.
    @Test fun smartDashes_normalizedLikeTheBridge() {
        assertEquals("an em-dash partial still completes the flag", listOf("--force"), resolve("/restart \u2014f"))
        assertEquals("an em-dash flag counts as typed and is not re-offered", listOf("--force"), resolve("/restart \u2014browser "))
    }

    /// The bridge's grammar is `[flags] [path]` — once the positional slot is
    /// filled the command line is complete, and the palette must not re-open
    /// over it to offer trailing flags.
    @Test fun flagsNotOffered_oncePositionalSlotFilled() {
        assertEquals(emptyList<String>(), resolve("/start ~/proj "))
        assertEquals(emptyList<String>(), resolve("/workdir ~/proj "))
    }

    @Test fun bangPrefix_resolvesLikeSlash() = assertEquals(listOf("--force", "--browser"), resolve("!restart "))
    @Test fun leadingWhitespace_isIgnored() = assertEquals(listOf("--force", "--browser"), resolve("  /restart "))
    @Test fun plainText_offersNothing() = assertEquals(emptyList<String>(), resolve("just chatting about --force"))
}

/// Session-derived argument suggestions (apple #163): the model aliases and
/// effort levels are lists the BRIDGE owns and publishes on the status frame,
/// so `/model` and `/effort` take their values from `SessionStatus` rather
/// than the catalog. Same resolver, same filtering and single-slot rules.
class SessionDerivedArgSuggestionTest {
    private val status = SessionStatus(
        modelOptions = listOf(
            SessionStatus.Option("opus", "Opus"),
            SessionStatus.Option("sonnet", "Sonnet"),
            SessionStatus.Option("opusplan", "Opus Plan"),
        ),
        effortLevels = listOf(
            SessionStatus.Option("low", "Low"),
            SessionStatus.Option("high", "High"),
            SessionStatus.Option("xhigh", "X-High"),
        ),
    )

    private fun resolve(input: String, status: SessionStatus? = null) =
        BotCommandCatalog.argSuggestions(input, BotCommandCatalog.claudeBridge) { status }.map { it.value }

    /// The catalog declares WHERE a command's values come from; it can't hold
    /// the values themselves, because they're agent-dependent.
    @Test
    fun claudeBridge_modelAndEffortDeclareSessionSources() {
        val catalog = BotCommandCatalog.claudeBridge
        assertEquals(SessionArgSource.MODEL_OPTIONS, catalog.first { it.trigger == "/model" }.sessionArgSource)
        assertEquals(SessionArgSource.EFFORT_LEVELS, catalog.first { it.trigger == "/effort" }.sessionArgSource)
        assertNull(catalog.first { it.trigger == "/switch" }.sessionArgSource)
    }

    @Test
    fun emptyArgument_offersEveryOption() {
        assertEquals(listOf("opus", "sonnet", "opusplan"), resolve("/model ", status))
        assertEquals(listOf("low", "high", "xhigh"), resolve("/effort ", status))
    }

    @Test
    fun partialFiltersByPrefix_caseInsensitively() {
        assertEquals(listOf("opus", "opusplan"), resolve("/model op", status))
        assertEquals(listOf("xhigh"), resolve("/effort X", status))
    }

    @Test fun noMatch_dismisses() = assertEquals(emptyList<String>(), resolve("/model gpt", status))

    @Test
    fun partialIdenticalToOption_offersNothing() {
        assertEquals(listOf("opusplan"), resolve("/model opus", status))
        assertEquals(emptyList<String>(), resolve("/effort high", status))
    }

    /// These are values, not flags: one fills the slot and the palette is done.
    @Test
    fun valuesFillASingleSlot() {
        assertEquals(emptyList<String>(), resolve("/model opus ", status))
        assertEquals(emptyList<String>(), resolve("/effort high ", status))
    }

    /// An older bridge never sends the lists: absent offers nothing.
    @Test
    fun absentLists_offerNothing() {
        assertEquals(emptyList<String>(), resolve("/model "))
        assertEquals(emptyList<String>(), resolve("/effort "))
        assertEquals(emptyList<String>(), resolve("/model ", SessionStatus(model = "opus")))
    }

    /// Empty means "this agent offers nothing" — same rendering, different statement.
    @Test
    fun emptyLists_offerNothing() {
        val none = SessionStatus(modelOptions = emptyList(), effortLevels = emptyList())
        assertEquals(emptyList<String>(), resolve("/model ", none))
        assertEquals(emptyList<String>(), resolve("/effort ", none))
    }

    /// The label rides through to the palette row; a value without one displays as itself.
    @Test
    fun labelsSurviveResolution() {
        val labels = BotCommandCatalog.argSuggestions("/model ", BotCommandCatalog.claudeBridge) { status }.map { it.displayLabel }
        assertEquals(listOf("Opus", "Sonnet", "Opus Plan"), labels)
        val unlabelled = SessionStatus(modelOptions = listOf(SessionStatus.Option("opus", null)))
        assertEquals(
            listOf("opus"),
            BotCommandCatalog.argSuggestions("/model ", BotCommandCatalog.claudeBridge) { unlabelled }.map { it.displayLabel },
        )
    }

    /// A session's lists belong to the command that declared them.
    @Test
    fun sessionListsDoNotLeakToOtherCommands() {
        assertEquals(listOf("claude", "codex"), resolve("/switch ", status))
        assertEquals(listOf("--force", "--browser"), resolve("/restart ", status))
    }

    @Test fun bangPrefixAndCaseResolveLikeSlash() = assertEquals(listOf("sonnet"), resolve("!MODEL son", status))

    /// A bridge that publishes the same value twice must not produce two rows
    /// (the palette keys rows by value). First occurrence wins.
    @Test
    fun duplicateOptions_collapseKeepingBridgeOrder() {
        val repeated = SessionStatus(
            modelOptions = listOf(
                SessionStatus.Option("opus", "Opus"), SessionStatus.Option("sonnet", "Sonnet"),
                SessionStatus.Option("OPUS", "Opus again"), SessionStatus.Option("sonnet", null),
            ),
        )
        assertEquals(listOf("opus", "sonnet"), resolve("/model ", repeated))
    }

    /// The status is read only once a session-derived command has matched.
    @Test
    fun statusIsReadLazily() {
        var reads = 0
        val lazy: () -> SessionStatus? = { reads++; status }
        BotCommandCatalog.argSuggestions("just chatting about /model", BotCommandCatalog.claudeBridge, lazy)
        BotCommandCatalog.argSuggestions("/restart --f", BotCommandCatalog.claudeBridge, lazy)
        assertEquals(0, reads)
        BotCommandCatalog.argSuggestions("/model ", BotCommandCatalog.claudeBridge, lazy)
        assertEquals(1, reads)
    }
}
