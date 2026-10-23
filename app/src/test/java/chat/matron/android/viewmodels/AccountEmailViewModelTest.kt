package chat.matron.android.viewmodels

import chat.matron.android.journal.AccountEmail
import chat.matron.android.journal.AccountEmailProviding
import chat.matron.android.journal.JournalApiError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountEmailViewModelTest {
    private class Api(var account: Result<AccountEmail>) : AccountEmailProviding {
        val puts = mutableListOf<Pair<String, String?>>()
        var answer: Result<String?>? = null

        override suspend fun accountEmail(): AccountEmail = account.getOrThrow()
        override suspend fun setAccountEmail(password: String, email: String?): String? {
            puts += password to email
            return answer?.getOrThrow() ?: email
        }
    }

    private val set = AccountEmail("alice@example.com", resetByEmail = true)
    private val unset = AccountEmail(null, resetByEmail = true)

    private suspend fun loaded(api: Api) = AccountEmailViewModel(api).also { it.load() }

    @Test
    fun loadsTheAddress() = runBlocking {
        val vm = AccountEmailViewModel(Api(Result.success(set)))
        assertEquals(AccountEmailViewModel.State.Loading, vm.state.value)
        vm.load()
        assertEquals(AccountEmailViewModel.State.Loaded(set), vm.state.value)
    }

    @Test
    fun aJournalWithoutItHidesTheRow() = runBlocking {
        val vm = loaded(Api(Result.failure(JournalApiError.NotFound)))
        assertEquals(AccountEmailViewModel.State.Unsupported, vm.state.value)
    }

    @Test
    fun aFailedReloadKeepsWhatShows() = runBlocking {
        val api = Api(Result.success(set))
        val vm = loaded(api)
        api.account = Result.failure(JournalApiError.Transport("offline"))
        vm.load()
        assertEquals(AccountEmailViewModel.State.Loaded(set), vm.state.value)
    }

    @Test
    fun editStartsFromTheAddressAndNeedsThePassword() = runBlocking {
        val vm = loaded(Api(Result.success(set)))
        vm.edit()
        assertEquals("alice@example.com", vm.form.value!!.email)
        assertFalse(vm.form.value!!.canSave)
        assertFalse(vm.form.value!!.canRemove)
        vm.setPassword("secret-pw")
        assertTrue(vm.form.value!!.canSave)
        vm.setEmail("  ")
        assertFalse(vm.form.value!!.canSave)
        assertTrue(vm.form.value!!.canRemove)
    }

    @Test
    fun savingSetsTheAddressAndClosesTheForm() = runBlocking {
        val api = Api(Result.success(unset))
        val vm = loaded(api)
        vm.edit()
        vm.setEmail(" alice@example.com ")
        vm.setPassword("secret-pw")
        vm.save()
        assertEquals(listOf("secret-pw" to "alice@example.com"), api.puts)
        assertNull(vm.form.value)
        assertEquals(AccountEmailViewModel.State.Loaded(set), vm.state.value)
    }

    @Test
    fun removeSendsNullAndOnlyWhenSet() = runBlocking {
        val api = Api(Result.success(set))
        val vm = loaded(api)
        vm.edit()
        vm.setPassword("secret-pw")
        vm.remove()
        assertEquals(listOf("secret-pw" to null), api.puts)
        assertEquals(AccountEmailViewModel.State.Loaded(unset), vm.state.value)

        vm.edit()
        vm.setPassword("secret-pw")
        vm.remove()
        assertEquals(1, api.puts.size)
    }

    @Test
    fun aRefusalClearsOnlyThePassword() = runBlocking {
        val api = Api(Result.success(unset))
        api.answer = Result.failure(JournalApiError.BadPassword)
        val vm = loaded(api)
        vm.edit()
        vm.setEmail("alice@example.com")
        vm.setPassword("wrong-pw")
        vm.save()
        val form = vm.form.value!!
        assertEquals("That password is incorrect.", form.error)
        assertEquals("alice@example.com", form.email)
        assertEquals("", form.password)
        assertFalse(form.isSaving)
        assertEquals(AccountEmailViewModel.State.Loaded(unset), vm.state.value)
    }

    @Test
    fun cancelForgetsThePassword() = runBlocking {
        val vm = loaded(Api(Result.success(set)))
        vm.edit()
        vm.setPassword("secret-pw")
        assertFalse(vm.form.value.toString().contains("secret-pw"))
        vm.cancel()
        assertNull(vm.form.value)
    }

    @Test
    fun copy() {
        assertEquals("Enter a valid email address.", AccountEmailViewModel.errorText(JournalApiError.BadEmail))
        assertEquals("Another account already uses that address.", AccountEmailViewModel.errorText(JournalApiError.EmailTaken))
        assertEquals("That password is incorrect.", AccountEmailViewModel.errorText(JournalApiError.BadPassword))
        assertEquals("Too many attempts. Try again in 1 minute.", AccountEmailViewModel.errorText(JournalApiError.LockedOut(30)))
        assertEquals("Too many attempts. Try again in 3 minutes.", AccountEmailViewModel.errorText(JournalApiError.LockedOut(150)))
        assertEquals(
            "Couldn't save your email address: Couldn't reach the server — offline",
            AccountEmailViewModel.errorText(JournalApiError.Transport("offline")),
        )
        assertEquals("Used to reset your password if you forget it.", AccountEmailViewModel.helpText(set))
        assertEquals(
            "Used to reset your password if you forget it. This journal can't send email yet.",
            AccountEmailViewModel.helpText(set.copy(resetByEmail = false)),
        )
    }
}
