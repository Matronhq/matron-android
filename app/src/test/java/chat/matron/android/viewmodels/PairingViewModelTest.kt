package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.PairPreview
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `PairingViewModelTests`. Near-zero debounce/poll
/// intervals + a controllable clock; the VM's background tasks run on the same
/// runBlocking event loop (single cooperative thread — MainActor-like), and each
/// test cancels its VM scope on the way out so a still-polling claim loop can't
/// hang runBlocking.
class PairingViewModelTest {

    private fun makeVM(
        fake: FakeDevicesProvider,
        scope: CoroutineScope,
        existingNames: List<String> = emptyList(),
        now: () -> Instant = { Instant.now() },
    ) = PairingViewModel(
        api = fake,
        existingNames = existingNames,
        scope = scope,
        now = now,
        pollInterval = 1.milliseconds,
        previewDebounce = 1.milliseconds,
    )

    private fun preview(ip: String, expiresIn: Int) = Result.success(PairPreview(ip, expiresIn))

    @Test
    fun codeInput_autoFormatsForDisplay() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val vm = makeVM(FakeDevicesProvider(), scope)
            vm.codeInput = "ktnm3vq8"
            assertEquals("KTNM-3VQ8", vm.codeInput)
            vm.codeInput = "ktn"
            assertEquals("KTN", vm.codeInput)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun plausibleCode_triggersPreview_andPhaseCarriesIP() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDevicesProvider()
            fake.previewResult = preview("65.108.10.252", 412)
            val vm = makeVM(fake, scope)
            vm.codeInput = "ktnm-3vq8"
            waitUntil { vm.phase.value == PairingViewModel.Phase.Preview("65.108.10.252") }
            assertEquals(PairingViewModel.Phase.Preview("65.108.10.252"), vm.phase.value)
            assertEquals("KTNM3VQ8", fake.previewedCodes.last())
            assertNotNull(vm.expiresAt.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun implausibleCode_neverPreviews() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDevicesProvider()
            val vm = makeVM(fake, scope)
            vm.codeInput = "ktn"
            delay(50)
            assertTrue(fake.previewedCodes.isEmpty())
            assertEquals(PairingViewModel.Phase.EnterCode, vm.phase.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun preview404_showsSpecCopy() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDevicesProvider()
            fake.previewResult = Result.failure(JournalApiError.NotFound)
            val vm = makeVM(fake, scope)
            vm.codeInput = "ktnm-3vq8"
            waitUntil { vm.errorMessage.value != null }
            assertEquals(
                "Code not recognized or expired. Get a fresh code from the box and try again.",
                vm.errorMessage.value,
            )
            assertEquals(PairingViewModel.Phase.EnterCode, vm.phase.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun duplicateName_warnsButDoesNotBlock() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val vm = makeVM(FakeDevicesProvider(), scope, existingNames = listOf("dev-7", "dan-mac"))
            vm.agentName = "dev-7"
            assertEquals("You already have an agent called dev-7", vm.duplicateNameWarning.value)
            vm.agentName = "dev-8"
            assertNull(vm.duplicateNameWarning.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun approve_conflict_showsSpecCopy() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDevicesProvider()
            fake.previewResult = preview("1.2.3.4", 600)
            fake.approveError = JournalApiError.Conflict
            val vm = makeVM(fake, scope)
            vm.codeInput = "ktnm-3vq8"
            waitUntil { vm.phase.value != PairingViewModel.Phase.EnterCode }
            vm.agentName = "dev-7"
            vm.approve()
            assertEquals("This code was already approved.", vm.errorMessage.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun approve_thenClaimDetectedByIDSnapshot_notName() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDevicesProvider()
            fake.previewResult = preview("1.2.3.4", 600)
            val preexisting = device(3, kind = "agent", name = "dev-7", createdAt = 10)
            fake.rosters = mutableListOf(
                listOf(preexisting),
                listOf(preexisting),
                listOf(preexisting, device(9, kind = "agent", name = "dev-7", createdAt = 99)),
            )
            val vm = makeVM(fake, scope)
            vm.codeInput = "ktnm-3vq8"
            waitUntil { vm.phase.value != PairingViewModel.Phase.EnterCode }
            vm.agentName = "dev-7"
            vm.approve()
            waitUntil { vm.phase.value == PairingViewModel.Phase.Success("dev-7") }
            assertEquals(PairingViewModel.Phase.Success("dev-7"), vm.phase.value)
            assertEquals(1, fake.approvals.size)
            assertEquals("KTNM3VQ8", fake.approvals[0].first)
            assertTrue(fake.devicesCalls >= 3)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun waitForClaim_ttlExpiry_showsSpecCopy() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDevicesProvider()
            fake.previewResult = preview("1.2.3.4", 600)
            fake.rosters = mutableListOf(emptyList())
            var currentDate = Instant.ofEpochSecond(1_000)
            val vm = makeVM(fake, scope, now = { currentDate })
            vm.codeInput = "ktnm-3vq8"
            waitUntil { vm.phase.value != PairingViewModel.Phase.EnterCode }
            vm.agentName = "dev-7"
            currentDate = Instant.ofEpochSecond(1_000 + 601)
            vm.approve()
            waitUntil { vm.errorMessage.value != null }
            assertEquals(
                "The box never collected its token. Start again with a fresh code.",
                vm.errorMessage.value,
            )
            assertEquals(PairingViewModel.Phase.EnterCode, vm.phase.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun staleEditDuringApprove_cannotStompWaitState() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDevicesProvider()
            fake.previewResult = preview("1.2.3.4", 600)
            fake.rosters = mutableListOf(emptyList())
            val vm = makeVM(fake, scope)
            vm.codeInput = "ktnm-3vq8"
            waitUntil { vm.phase.value == PairingViewModel.Phase.Preview("1.2.3.4") }
            vm.agentName = "dev-7"
            fake.holdApprove = true
            fake.holdPreview = true
            val approving = launch { vm.approve() }
            waitUntil { fake.approvals.size == 1 }
            assertEquals(1, fake.approvals.size)
            vm.codeInput = "BCDF-GHJK"
            waitUntil { fake.previewedCodes.size == 2 }
            assertEquals(2, fake.previewedCodes.size)
            fake.releaseApprove()
            approving.join()
            assertEquals(PairingViewModel.Phase.WaitingForClaim, vm.phase.value)
            fake.releasePreview()
            delay(100)
            assertEquals(PairingViewModel.Phase.WaitingForClaim, vm.phase.value)
            assertNull(vm.errorMessage.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun approve_secondTapWhileInFlight_isIgnored() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDevicesProvider()
            fake.previewResult = preview("1.2.3.4", 600)
            fake.rosters = mutableListOf(emptyList())
            fake.approveDelay = 100.milliseconds
            val vm = makeVM(fake, scope)
            vm.codeInput = "ktnm-3vq8"
            waitUntil { vm.phase.value == PairingViewModel.Phase.Preview("1.2.3.4") }
            vm.agentName = "dev-7"
            val first = launch { vm.approve() }
            delay(20)
            vm.approve() // impatient second tap while the first is in flight
            first.join()
            assertEquals(1, fake.approvals.size)
            assertEquals(PairingViewModel.Phase.WaitingForClaim, vm.phase.value)
            assertNull(vm.errorMessage.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun cancelWaiting_stopsPolling() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDevicesProvider()
            fake.previewResult = preview("1.2.3.4", 600)
            fake.rosters = mutableListOf(emptyList())
            val vm = makeVM(fake, scope)
            vm.codeInput = "ktnm-3vq8"
            waitUntil { vm.phase.value != PairingViewModel.Phase.EnterCode }
            vm.agentName = "dev-7"
            vm.approve()
            assertEquals(PairingViewModel.Phase.WaitingForClaim, vm.phase.value)
            vm.cancelWaiting()
            delay(30)
            val callsAfterCancel = fake.devicesCalls
            delay(50)
            assertEquals(callsAfterCancel, fake.devicesCalls)
        } finally {
            scope.cancel()
        }
        Unit
    }

    // MARK: - tag at pairing (apple #158)

    @Test
    fun approve_sendsTheSievedTagOrOmitsIt() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDevicesProvider()
            fake.previewResult = preview("1.2.3.4", 600)
            val vm = makeVM(fake, scope)
            vm.codeInput = "ktnm-3vq8"
            waitUntil { vm.phase.value != PairingViewModel.Phase.EnterCode }
            vm.agentName = "dev-a"
            vm.tagChar = " a1 "
            vm.approve()
            assertEquals(listOf("a"), fake.approvalTags)

            val bare = FakeDevicesProvider()
            bare.previewResult = preview("1.2.3.4", 600)
            val vm2 = makeVM(bare, scope)
            vm2.codeInput = "ktnm-3vq8"
            waitUntil { vm2.phase.value != PairingViewModel.Phase.EnterCode }
            vm2.agentName = "dev-b"
            vm2.approve()
            assertEquals(listOf<String?>(null), bare.approvalTags)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun tagChar_warnsOnADuplicateAcrossTheRoster() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val vm = PairingViewModel(api = FakeDevicesProvider(), existingNames = emptyList(), scope = scope, existingTags = listOf("Q"))
            vm.tagChar = "q"
            assertNotNull(vm.duplicateTagWarning.value)
            vm.tagChar = "Z"
            assertNull(vm.duplicateTagWarning.value)
        } finally {
            scope.cancel()
        }
        Unit
    }
}
