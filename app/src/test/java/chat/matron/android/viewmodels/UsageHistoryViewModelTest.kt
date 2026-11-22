package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.models.UsageAccount
import chat.matron.android.models.UsageHistory
import chat.matron.android.models.UsageMeter
import chat.matron.android.models.UsageMeterChoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageHistoryViewModelTest {
    private class Fake(var result: () -> UsageHistory) : UsageHistoryProviding {
        val weeksAsked = mutableListOf<Int>()
        override suspend fun usageHistory(weeks: Int): UsageHistory {
            weeksAsked += weeks
            return result()
        }
    }

    private fun history(vararg modelLabels: String) = UsageHistory(
        listOf(
            UsageAccount(
                "claude:login:alice@example.com", "claude", "alice@example.com", listOf("box-a"),
                listOf(UsageMeter("week_all", "Week (all models)", true, null, emptyList())) +
                    modelLabels.map { UsageMeter("id-$it", it, false, null, emptyList()) },
            ),
        ),
    )

    @Test
    fun load_readsAWeekMoreThanItShowsAndKeepsAChoiceThatIsStillThere() = runBlocking {
        val fake = Fake { history("Week (Example)") }
        val vm = UsageHistoryViewModel(fake)
        vm.load()
        assertEquals(listOf(13), fake.weeksAsked)
        assertTrue(vm.state.value.hasLoaded)
        vm.choose("Week (Example)")
        vm.load()
        assertEquals("Week (Example)", vm.state.value.choice.id)
        assertEquals(1, vm.state.value.table().rows.size)
    }

    @Test
    fun load_aMeterThatDroppedOutFallsBackToTheHeadline() = runBlocking {
        val fake = Fake { history("Week (Example)") }
        val vm = UsageHistoryViewModel(fake)
        vm.load()
        vm.choose("Week (Example)")
        fake.result = { history() }
        vm.load()
        assertEquals(UsageMeterChoice.PRIMARY_ID, vm.state.value.choiceID)
        assertEquals(listOf(UsageMeterChoice.primary), vm.state.value.choices)
    }

    @Test
    fun load_anOldJournalSaysItNeedsAnUpdate() = runBlocking {
        val vm = UsageHistoryViewModel(Fake { throw JournalApiError.NotFound })
        vm.load()
        assertEquals("This journal doesn't keep usage history yet. It needs an update.", vm.state.value.errorMessage)
        assertNull(vm.state.value.history)
    }

    @Test
    fun load_otherFailuresKeepWhatWasShown() = runBlocking {
        var fail = false
        val vm = UsageHistoryViewModel(Fake { if (fail) throw IllegalStateException("down") else history() })
        vm.load()
        fail = true
        vm.load()
        assertEquals("Couldn't load the usage history. Try again.", vm.state.value.errorMessage)
        assertEquals(history(), vm.state.value.history)
    }
}
