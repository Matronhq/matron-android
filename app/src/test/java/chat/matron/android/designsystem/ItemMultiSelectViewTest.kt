package chat.matron.android.designsystem

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.ItemState
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Multi-select questions on an item's page: a tick box per label and one
/// Send for the ticked ones; one-tap questions keep their buttons.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ItemMultiSelectViewTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val labels = listOf("Docs", "Billing", "Search")

    private fun question(chosen: List<String> = emptyList(), state: ItemState = ItemState.OPEN) = TrackerItem(
        "it_1", 7, ItemKind.QUESTION, state = state,
        resolution = if (state == ItemState.CLOSED) ItemResolution.ANSWERED else null,
        title = "Which areas should the release notes cover?", originConvoID = "c1", createdAt = now,
        actions = labels, multiSelect = true, chosenActions = chosen, chosenAction = chosen.firstOrNull(),
    )

    private fun show(item: TrackerItem, comments: List<TrackerComment> = emptyList(), onSend: (List<String>) -> Unit = {}, onTap: (String) -> Unit = {}) {
        compose.setContent {
            MatronTheme(appearance = MatronAppearance.Light) {
                Surface {
                    ItemDetailView(
                        model = ItemDetailModel(
                            item = item, comments = comments, pending = emptyList(),
                            context = ItemContext(null, null, null), availableResolutions = emptyList(), isBusy = false,
                            loadedCommentCount = comments.size,
                        ),
                        draft = "", onDraftChange = {}, image = { null }, onOpenAttachment = {}, onOpenLink = {},
                        onOpenConversation = {}, onSubmit = {}, onAttach = {}, onVoiceNote = {}, now = now,
                        onSendItemSelection = onSend, onTapItemAction = onTap,
                    )
                }
            }
        }
    }

    @Test
    fun sendIsDisabledUntilSomethingIsTickedAndSendsTheTickedLabelsInOrder() {
        val sent = mutableListOf<List<String>>()
        show(question(), onSend = { sent += it })
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithText("Docs").performClick()
        compose.onNodeWithText("Send").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(listOf("Docs", "Search")), sent) }
    }

    @Test
    fun theAnswerGivenIsTickedAndCanBeChanged() {
        val sent = mutableListOf<List<String>>()
        show(question(chosen = listOf("Billing")), onSend = { sent += it })
        compose.onNodeWithText("Billing").assertIsOn()
        compose.onNodeWithText("Docs").assertIsOff()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("Docs").performClick()
        compose.onNodeWithText("Send").performClick()
        compose.runOnIdle { assertEquals(listOf(listOf("Docs", "Billing")), sent) }
    }

    @Test
    fun aClosedItemShowsItsAnswerWithoutSend() {
        show(question(chosen = listOf("Docs", "Search"), state = ItemState.CLOSED))
        compose.onNodeWithText("Docs").assertIsOn()
        compose.onNodeWithText("Send").assertDoesNotExist()
    }

    @Test
    fun ticksLeftUnsentAreNotShownOnceTheItemCloses() {
        var item by androidx.compose.runtime.mutableStateOf(question(chosen = listOf("Billing")))
        compose.setContent {
            MatronTheme(appearance = MatronAppearance.Light) {
                Surface {
                    ItemDetailView(
                        model = ItemDetailModel(
                            item = item, comments = emptyList(), pending = emptyList(),
                            context = ItemContext(null, null, null), availableResolutions = emptyList(), isBusy = false,
                            loadedCommentCount = 0,
                        ),
                        draft = "", onDraftChange = {}, image = { null }, onOpenAttachment = {}, onOpenLink = {},
                        onOpenConversation = {}, onSubmit = {}, onAttach = {}, onVoiceNote = {}, now = now,
                    )
                }
            }
        }
        compose.onNodeWithText("Docs").performClick()
        compose.onNodeWithText("Docs").assertIsOn()
        compose.runOnIdle { item = item.copy(state = ItemState.CLOSED, resolution = ItemResolution.ANSWERED) }
        compose.onNodeWithText("Docs").assertIsOff()
        compose.onNodeWithText("Billing").assertIsOn()
    }

    @Test
    fun aOneTapQuestionKeepsItsButtons() {
        val tapped = mutableListOf<String>()
        show(question().copy(multiSelect = false, actions = listOf("Go", "Wait")), onTap = { tapped += it })
        compose.onNodeWithText("Send").assertDoesNotExist()
        compose.onNodeWithText("Go").performClick()
        compose.runOnIdle { assertEquals(listOf("Go"), tapped) }
    }

    @Test
    fun anAnswerCommentListsEveryLabel() {
        val answer = TrackerComment(
            "ic_a", "it_1", ItemAuthor.USER, body = "Docs, Search", createdAt = now.minusSeconds(60),
            action = "Docs", selected = listOf("Docs", "Search"),
        )
        show(question(chosen = listOf("Docs", "Search")), comments = listOf(answer))
        compose.onNodeWithText("Picked: Docs, Search").assertExists()
        compose.onNodeWithText("Docs, Search").assertDoesNotExist()
    }

    @Test
    fun helpers() {
        val asking = TrackerComment("ic_q", "it_1", ItemAuthor.AGENT, body = "?", actions = labels, multiSelect = true)
        assertEquals(emptyList<String>(), commentChosenActions(asking, emptyMap()))
        assertEquals(listOf("Docs"), commentChosenActions(asking.copy(chosenActions = listOf("Docs", "Gone")), emptyMap()))
        assertEquals("a queued answer is the newer one", listOf("Search"),
            commentChosenActions(asking.copy(chosenActions = listOf("Docs")), mapOf("ic_q" to listOf("Search"))))
        assertEquals("falls back to chosen_action", listOf("Billing"), commentChosenActions(asking.copy(chosenAction = "Billing"), emptyMap()))
        assertEquals(listOf("Billing"), itemChosenActions(question(listOf("Billing")), null))
        assertEquals(listOf("Docs"), itemChosenActions(question(listOf("Billing")), listOf("Docs")))
        assertFalse(multiSelectCanSend(emptySet(), emptyList()))
        assertTrue(multiSelectCanSend(setOf("Docs"), emptyList()))
        assertFalse("the answer already given", multiSelectCanSend(setOf("Docs", "Search"), listOf("Search", "Docs")))
        assertEquals("Picked: Docs, Search", itemSelectionLine(asking.copy(selected = listOf("Docs", "Search"))))
        assertNull("a one-tap tap keeps its plain body", itemSelectionLine(asking.copy(action = "Docs")))
    }
}
