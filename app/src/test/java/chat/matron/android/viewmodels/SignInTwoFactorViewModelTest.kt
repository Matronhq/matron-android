package chat.matron.android.viewmodels

import chat.matron.android.auth.AuthError
import chat.matron.android.auth.FakeAuthService
import chat.matron.android.auth.ServerCapabilities
import chat.matron.android.auth.TwoFactorEnrolled
import chat.matron.android.auth.TwoFactorSignIn
import chat.matron.android.journal.TwoFactorSetup
import chat.matron.android.models.UserSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// The two-factor steps of a password sign-in: the code step, setup at
/// sign-in, and each refusal the journal can answer with.
class SignInTwoFactorViewModelTest {

    private val session = UserSession(
        userID = "alice", deviceID = "7", homeserverURL = "https://journal.example.com/", accessToken = "tok",
    )
    private val setup = TwoFactorSetup(
        secret = "JBSWY3DPEHPK3PXP",
        otpauthURI = "otpauth://totp/Matron:alice@journal.example.com?secret=JBSWY3DPEHPK3PXP&issuer=Matron",
        issuer = "Matron",
        account = "alice@journal.example.com",
    )

    private fun fake(enrolment: Boolean = false) = FakeAuthService().apply {
        stubbedProbe = Result.success(ServerCapabilities(supportsPasswordLogin = true, supportsSSO = false))
        stubbedLogin = Result.failure(AuthError.TwoFactorRequired(ticket = "ticket-1", enrolment = enrolment))
    }

    private suspend fun signedInToCodeStep(fake: FakeAuthService): SignInViewModel {
        val vm = SignInViewModel(fake, "Matron Tests")
        vm.serverURL = "https://journal.example.com"
        vm.username = "alice"
        vm.password = "pw"
        vm.submit()
        return vm
    }

    @Test
    fun passwordThatNeedsACode_showsTheCodeStep_andPersistsNothing() = runBlocking {
        val fake = fake()
        val vm = signedInToCodeStep(fake)
        assertEquals(SignInViewModel.State.TwoFactorCode(), vm.state.value)
        assertTrue(fake.persistedSessions.isEmpty())
    }

    @Test
    fun rightCode_signsIn_andStoresTheTokenLikeLogin() = runBlocking {
        val fake = fake()
        fake.stubbedVerify += Result.success(TwoFactorSignIn(session, usedRecoveryCode = false, recoveryCodesLeft = 10))
        val vm = signedInToCodeStep(fake)
        vm.code = " 123456 "
        vm.submitCode()
        assertEquals(listOf("ticket-1" to "123456"), fake.verifyCalls)
        assertEquals(SignInViewModel.State.SignedIn(session), vm.state.value)
        assertEquals(listOf(session), fake.persistedSessions)
    }

    @Test
    fun wrongCode_staysOnTheStep_andARetryCanSucceed() = runBlocking {
        val fake = fake()
        fake.stubbedVerify += Result.failure(AuthError.WrongCode(ticketValid = true))
        fake.stubbedVerify += Result.success(TwoFactorSignIn(session, usedRecoveryCode = false, recoveryCodesLeft = 10))
        val vm = signedInToCodeStep(fake)
        vm.code = "000000"
        vm.submitCode()
        val state = vm.state.value as SignInViewModel.State.TwoFactorCode
        assertTrue(state.error!!.startsWith("Wrong code"))
        vm.code = "123456"
        vm.submitCode()
        assertEquals(SignInViewModel.State.SignedIn(session), vm.state.value)
        assertEquals(listOf("ticket-1" to "000000", "ticket-1" to "123456"), fake.verifyCalls)
    }

    @Test
    fun spentTicket_goesBackToThePassword() = runBlocking {
        val fake = fake()
        fake.stubbedVerify += Result.failure(AuthError.WrongCode(ticketValid = false))
        val vm = signedInToCodeStep(fake)
        vm.code = "000000"
        vm.submitCode()
        assertEquals(SignInViewModel.State.Error("Too many wrong codes. Sign in again."), vm.state.value)
        assertEquals("", vm.code)
        // The ticket is gone: a code submitted now goes nowhere.
        vm.submitCode()
        assertEquals(1, fake.verifyCalls.size)
    }

    @Test
    fun expiredTicket_goesBackToThePassword() = runBlocking {
        val fake = fake()
        fake.stubbedVerify += Result.failure(AuthError.TwoFactorExpired)
        val vm = signedInToCodeStep(fake)
        vm.code = "123456"
        vm.submitCode()
        assertEquals(SignInViewModel.State.Error("The sign-in timed out. Sign in again."), vm.state.value)
    }

    @Test
    fun lockedOut_showsWhenToTryAgain_onTheStep() = runBlocking {
        val fake = fake()
        fake.stubbedVerify += Result.failure(AuthError.LockedOut(retryAfterSeconds = 300))
        val vm = signedInToCodeStep(fake)
        vm.code = "123456"
        vm.submitCode()
        assertEquals(
            SignInViewModel.State.TwoFactorCode(error = "Too many attempts. Try again in 5 minutes."),
            vm.state.value,
        )
    }

    @Test
    fun recoveryCode_warnsHowManyAreLeft_beforeSigningIn() = runBlocking {
        val fake = fake()
        fake.stubbedVerify += Result.success(TwoFactorSignIn(session, usedRecoveryCode = true, recoveryCodesLeft = 2))
        val vm = signedInToCodeStep(fake)
        vm.code = "abcd-efgh-ijkl-mnop"
        vm.submitCode()
        assertEquals(SignInViewModel.State.RecoveryCodeUsed(2, session), vm.state.value)
        assertEquals("the code is spent: keep the token", listOf(session), fake.persistedSessions)
        vm.finishTwoFactor()
        assertEquals(SignInViewModel.State.SignedIn(session), vm.state.value)
    }

    @Test
    fun cancel_dropsTheTicket() = runBlocking {
        val vm = signedInToCodeStep(fake())
        vm.cancelTwoFactor()
        assertEquals(SignInViewModel.State.Idle, vm.state.value)
    }

    @Test
    fun cancel_dropsAnAnswerStillOnItsWay() = runBlocking {
        val fake = fake()
        fake.stubbedVerify += Result.success(TwoFactorSignIn(session, usedRecoveryCode = false, recoveryCodesLeft = 10))
        val vm = signedInToCodeStep(fake)
        vm.code = "123456"
        val gate = CompletableDeferred<Unit>().also { fake.gate = it }
        val job = launch { vm.submitCode() }
        yield()
        assertEquals(SignInViewModel.State.TwoFactorCode(busy = true), vm.state.value)
        vm.cancelTwoFactor()
        gate.complete(Unit)
        job.join()
        assertEquals(SignInViewModel.State.Idle, vm.state.value)
        assertTrue("a cancelled sign-in stores nothing", fake.persistedSessions.isEmpty())
    }

    @Test
    fun cancel_dropsAFailureStillOnItsWay() = runBlocking {
        val fake = fake(enrolment = true)
        fake.stubbedSetup = Result.success(setup)
        fake.stubbedEnable += Result.failure(AuthError.WrongCode(ticketValid = true))
        val vm = signedInToCodeStep(fake)
        vm.code = "000000"
        val gate = CompletableDeferred<Unit>().also { fake.gate = it }
        val job = launch { vm.confirmEnrolment() }
        yield()
        vm.cancelTwoFactor()
        gate.complete(Unit)
        job.join()
        assertEquals(SignInViewModel.State.Idle, vm.state.value)
    }

    @Test
    fun enrolmentRequired_fetchesTheSetup_thenShowsRecoveryCodesOnce() = runBlocking {
        val fake = fake(enrolment = true)
        fake.stubbedSetup = Result.success(setup)
        val codes = List(10) { "code-$it" }
        fake.stubbedEnable += Result.success(TwoFactorEnrolled(session, codes))
        val vm = signedInToCodeStep(fake)
        assertEquals(listOf("ticket-1"), fake.setupTickets)
        assertEquals(SignInViewModel.State.TwoFactorEnrol(setup), vm.state.value)

        vm.code = "123456"
        vm.confirmEnrolment()
        assertEquals(listOf("ticket-1" to "123456"), fake.enableCalls)
        assertEquals(SignInViewModel.State.RecoveryCodes(codes, session), vm.state.value)
        assertEquals("two-factor is on now: keep the token", listOf(session), fake.persistedSessions)

        // Cancel has nothing to undo once the token is issued.
        vm.cancelTwoFactor()
        assertEquals(SignInViewModel.State.RecoveryCodes(codes, session), vm.state.value)

        vm.finishTwoFactor()
        assertEquals(SignInViewModel.State.SignedIn(session), vm.state.value)
        assertEquals(listOf(session), fake.persistedSessions)
    }

    @Test
    fun enrolment_wrongCodeKeepsTheQR() = runBlocking {
        val fake = fake(enrolment = true)
        fake.stubbedSetup = Result.success(setup)
        fake.stubbedEnable += Result.failure(AuthError.WrongCode(ticketValid = true))
        val vm = signedInToCodeStep(fake)
        vm.code = "000000"
        vm.confirmEnrolment()
        val state = vm.state.value as SignInViewModel.State.TwoFactorEnrol
        assertEquals(setup, state.setup)
        assertTrue(state.error!!.startsWith("Wrong code"))
    }

    @Test
    fun enrolment_setupFailure_canBeRetried() = runBlocking {
        val fake = fake(enrolment = true)
        fake.stubbedSetup = Result.failure(AuthError.Unexpected("Couldn't reach the server."))
        val vm = signedInToCodeStep(fake)
        assertEquals(
            SignInViewModel.State.TwoFactorEnrol(setup = null, error = "Couldn't reach the server."),
            vm.state.value,
        )
        fake.stubbedSetup = Result.success(setup)
        vm.retryEnrolment()
        assertEquals(SignInViewModel.State.TwoFactorEnrol(setup), vm.state.value)
        assertEquals(2, fake.setupTickets.size)
    }

    @Test
    fun enrolment_expiredTicket_goesBackToThePassword() = runBlocking {
        val fake = fake(enrolment = true)
        fake.stubbedSetup = Result.failure(AuthError.TwoFactorExpired)
        val vm = signedInToCodeStep(fake)
        assertEquals(SignInViewModel.State.Error("The sign-in timed out. Sign in again."), vm.state.value)
    }

    @Test
    fun copy() {
        assertEquals("45 seconds", SignInViewModel.retryAfterText(45))
        assertEquals("1 minute", SignInViewModel.retryAfterText(60))
        assertEquals("2 minutes", SignInViewModel.retryAfterText(61))
        assertEquals("1 hour", SignInViewModel.retryAfterText(3600))
        assertEquals(
            "You signed in with a recovery code. You have 4 recovery codes left.",
            SignInViewModel.recoveryCodesLeftText(4),
        )
        assertTrue(SignInViewModel.recoveryCodesLeftText(0).contains("last recovery code"))
    }
}
