package chat.matron.android.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import chat.matron.android.chat.FakeMediaService
import chat.matron.android.chat.FakeTimelineService
import chat.matron.android.events.GrantCardState
import chat.matron.android.events.GrantDecision
import chat.matron.android.events.KeepLevel
import chat.matron.android.events.PersonGrantRequest
import chat.matron.android.journal.GrantAnswering
import chat.matron.android.viewmodels.ChatViewModel
import chat.matron.android.viewmodels.InMemoryKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// The "Keep contributing" switch on the owner's hand-over card, and the
/// matching line on the recipient's.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonGrantCardKeepLevelTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val out = PersonGrantRequest(
        kind = PersonGrantRequest.Kind.HANDOVER,
        direction = PersonGrantRequest.Direction.OUT,
        grantId = "gr_1",
        level = "owner",
        ownerName = "alice",
        granteeName = "bob",
        subjectTitle = "Checkout redesign",
    )
    private val incoming = out.copy(direction = PersonGrantRequest.Direction.IN)

    private class Recorder : GrantAnswering {
        val keepLevels = mutableListOf<KeepLevel?>()
        override suspend fun answerGrant(grantId: String, decision: GrantDecision, excludeMedia: List<String>, keepLevel: KeepLevel?) {
            keepLevels.add(keepLevel)
        }
    }

    private fun vm(grants: GrantAnswering = Recorder()) = ChatViewModel(
        "!r:s", FakeTimelineService(), FakeMediaService(),
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), InMemoryKeyValueStore(), grants = grants,
    )

    @Test
    fun switchStartsFromThePayloadAndTheHintFollowsIt() {
        val model = vm()
        compose.setContent {
            MaterialTheme {
                val levels by model.grantKeepLevels.collectAsState()
                PersonGrantCard(
                    request = out.copy(keepLevel = KeepLevel.CONTRIBUTE),
                    state = GrantCardState.Idle,
                    onApprove = {}, onDecline = {},
                    keepLevel = levels.let { model.grantKeepLevel("10", out.copy(keepLevel = KeepLevel.CONTRIBUTE)) },
                    onKeepLevelChange = { model.setGrantKeepLevel("10", it) },
                )
            }
        }
        compose.onNodeWithText("Keep contributing after the hand-over").assertIsOn()
        compose.onNodeWithText("You can keep adding milestones, items and comments").assertExists()
        compose.onNodeWithText("Keep contributing after the hand-over").performClick()
        compose.onNodeWithText("Keep contributing after the hand-over").assertIsOff()
        compose.onNodeWithText("You keep read access to their copy").assertExists()
        compose.onNodeWithText("You can keep adding milestones, items and comments").assertDoesNotExist()
    }

    @Test
    fun switchSurvivesTheRowBeingRebuilt() {
        val model = vm()
        val shown = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                val levels by model.grantKeepLevels.collectAsState()
                if (shown.value) {
                    PersonGrantCard(
                        request = out,
                        state = GrantCardState.Idle,
                        onApprove = {}, onDecline = {},
                        keepLevel = levels.let { model.grantKeepLevel("10", out) },
                        onKeepLevelChange = { model.setGrantKeepLevel("10", it) },
                    )
                }
            }
        }
        compose.onNodeWithText("Keep contributing after the hand-over").assertIsOff().performClick()
        compose.onNodeWithText("Keep contributing after the hand-over").assertIsOn()
        // The row leaves composition (scrolled away, rebuilt) and comes back.
        compose.runOnIdle { shown.value = false }
        compose.onNodeWithText("Keep contributing after the hand-over").assertDoesNotExist()
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithText("Keep contributing after the hand-over").assertIsOn()
        compose.onNodeWithText("You can keep adding milestones, items and comments").assertExists()
    }

    @Test
    fun approveSendsTheSwitchValue() {
        val answerer = Recorder()
        val model = vm(answerer)
        compose.setContent {
            MaterialTheme {
                val levels by model.grantKeepLevels.collectAsState()
                PersonGrantCard(
                    request = out,
                    state = GrantCardState.Idle,
                    onApprove = { excluded -> model.answerGrant("10", out, GrantDecision.APPROVE, excluded) },
                    onDecline = {},
                    keepLevel = levels.let { model.grantKeepLevel("10", out) },
                    onKeepLevelChange = { model.setGrantKeepLevel("10", it) },
                )
            }
        }
        compose.onNodeWithText("Keep contributing after the hand-over").performClick()
        compose.onNodeWithText("Approve").performClick()
        compose.waitUntil(3_000) { answerer.keepLevels.isNotEmpty() }
        assertEquals(listOf<KeepLevel?>(KeepLevel.CONTRIBUTE), answerer.keepLevels)
    }

    @Test
    fun recipientCardSaysTheOwnerStaysOnAsAContributor() {
        compose.setContent {
            MaterialTheme {
                PersonGrantCard(
                    request = incoming.copy(keepLevel = KeepLevel.CONTRIBUTE),
                    state = GrantCardState.Idle, onApprove = {}, onDecline = {},
                )
            }
        }
        compose.onNodeWithText("alice stays on as a contributor; you can end that any time.").assertExists()
        compose.onNodeWithText("Keep contributing after the hand-over").assertDoesNotExist()
    }

    @Test
    fun recipientCardSaysTheOwnerKeepsReadAccess() {
        compose.setContent {
            MaterialTheme {
                PersonGrantCard(request = incoming, state = GrantCardState.Idle, onApprove = {}, onDecline = {})
            }
        }
        compose.onNodeWithText("alice keeps read access.").assertExists()
        compose.onNodeWithText("Keep contributing after the hand-over").assertDoesNotExist()
    }
}
