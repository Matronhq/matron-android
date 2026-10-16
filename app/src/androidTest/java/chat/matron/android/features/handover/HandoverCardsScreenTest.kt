package chat.matron.android.features.handover

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import chat.matron.android.MainActivity
import chat.matron.android.designsystem.ItemContext
import chat.matron.android.designsystem.ItemDetailModel
import chat.matron.android.designsystem.ItemDetailView
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.designsystem.MissionDetailModel
import chat.matron.android.designsystem.MissionDetailView
import chat.matron.android.designsystem.MissionNotice
import chat.matron.android.designsystem.PersonGrantCard
import chat.matron.android.events.GrantCardState
import chat.matron.android.events.MissionMarkerEvent
import chat.matron.android.events.PersonGrantRequest
import chat.matron.android.models.Milestone
import chat.matron.android.models.Mission
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Hand-over and share cards and contributor attribution, drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class HandoverCardsScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 5_000L

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing $selector" }

    // Optional review artifacts, containing only the fixtures below.
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(500)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "handover").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private val brief = """
        **Where things stand.** The cart and the address step are done and tested.

        **Next.** Wire the payment step to the sandbox provider, then run the end-to-end checklist.

        **Gotchas.** The sandbox rejects amounts under 50 cents, and the checklist expects a clean test account.
    """.trimIndent()

    private val handoverOut = PersonGrantRequest.parse(json(
        """{"kind":"mission_handover","direction":"out","grant_id":"gr_1","level":"owner",
           "owner":{"user_id":"u_a","name":"alice"},"grantee":{"name":"bob","address":"bob"},
           "subject":{"kind":"mission","id":"ms_1","num":7,"title":"Checkout redesign"},
           "brief":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(brief))},
           "preview":{"missions":1,"milestones":4,"items":6,"comments":12,"attachments":[
             {"media_id":"md_1","name":"checkout-spec.pdf","mime":"application/pdf","size":1258291,"item_num":3},
             {"media_id":"md_2","name":"old-mockups.zip","mime":"application/zip","size":8912896,"item_num":4},
             {"media_id":"md_3","name":"payment-flow.png","mime":"image/png","size":245760,"item_num":5}]},
           "from_device_id":3,"from_name":"box-a","from_convo_id":"c1"}""",
    ))!!

    private val handoverIn = PersonGrantRequest.parse(json(
        """{"kind":"mission_handover","direction":"in","grant_id":"gr_1","level":"owner",
           "owner":{"user_id":"u_a","name":"alice"},"grantee":{"name":"bob","address":"bob"},
           "subject":{"kind":"mission","title":"Checkout redesign"},
           "brief":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(brief))},
           "preview":{"missions":1,"milestones":4,"items":6,"comments":12,"attachments":2}}""",
    ))!!

    private val shareIn = PersonGrantRequest.parse(json(
        """{"kind":"mission_share","direction":"in","grant_id":"gr_2","level":"contribute",
           "owner":{"user_id":"u_a","name":"alice"},"grantee":{"name":"bob"},
           "mission":{"id":"ms_2","num":9,"title":"Onboarding emails"},
           "preview":{"milestones":2,"items":3,"comments":5,"attachments":1}}""",
    ))!!

    private fun show(appearance: MatronAppearance, content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding(),
                        ) { content() }
                    }
                }
            }
        }

    @Test
    fun ownersHandoverCardWithOneFileUnticked() {
        show(MatronAppearance.Light) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PersonGrantCard(
                    request = handoverOut,
                    state = GrantCardState.Idle,
                    onApprove = {}, onDecline = {},
                    modifier = Modifier.widthIn(max = 360.dp),
                    initiallyExcluded = listOf("md_2"),
                )
            }
        }.use {
            node(By.text("Hand over a mission"))
            node(By.text("Your agent asks to hand Checkout redesign to bob."))
            node(By.text("1 mission · 4 milestones · 6 items · 12 comments · 3 files"))
            node(By.text("Approve"))
            screenshot("handover-out")
        }
    }

    @Test
    fun recipientsHandoverCardAndAContributeShare() {
        show(MatronAppearance.Light) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PersonGrantCard(
                    request = handoverIn, state = GrantCardState.Idle, onApprove = {}, onDecline = {},
                    modifier = Modifier.widthIn(max = 360.dp),
                )
                PersonGrantCard(
                    request = shareIn, state = GrantCardState.Answered(true), onApprove = {}, onDecline = {},
                    modifier = Modifier.widthIn(max = 360.dp),
                )
            }
        }.use {
            node(By.text("Mission hand-over"))
            node(By.text("alice wants to hand you Checkout redesign."))
            node(By.text("Accept"))
            node(By.text("Can contribute"))
            screenshot("handover-in")
        }
    }

    private val now = Instant.parse("2026-10-16T12:00:00Z")

    @Test
    fun missionPageWithContributorAttribution() {
        val mission = Mission(
            id = "ms_2", num = 9, title = "Onboarding emails", body = "Rewrite the welcome series.",
            originConvoID = "c1", createdAt = now, updatedAt = now,
            status = "Second email drafted; waiting on the subject-line test.", statusContributor = "bob",
        )
        val milestones = listOf(
            Milestone(
                id = "ml_2", missionID = "ms_2", num = 14, kind = chat.matron.android.models.MilestoneKind.PROGRESS,
                title = "Second email drafted", body = "Draft is in the shared folder.", convoID = "c2", seq = 2,
                createdAt = now.minusSeconds(600),
                contributor = chat.matron.android.models.Contributor("bob", chat.matron.android.models.Contributor.Via.USER),
            ),
            Milestone(
                id = "ml_1", missionID = "ms_2", num = 11, kind = chat.matron.android.models.MilestoneKind.PROGRESS,
                title = "Welcome email rewritten", convoID = "c1", seq = 1, createdAt = now.minusSeconds(7200),
            ),
        )
        val item = TrackerItem(
            id = "it_1", num = 15, kind = chat.matron.android.models.ItemKind.QUESTION, title = "Which subject line wins?",
            body = "Two variants are ready for the test.", originConvoID = "", createdAt = now, updatedAt = now,
            awaiting = chat.matron.android.models.ItemAwaiting.USER, missionID = "ms_2", missionNum = 9,
            contributor = chat.matron.android.models.Contributor("bob", chat.matron.android.models.Contributor.Via.AGENT),
        )
        val closed = MissionMarkerEvent(
            missionID = "ms_1", num = 7, title = "Checkout redesign",
            action = MissionMarkerEvent.Action.CLOSED, handedOverTo = "bob",
        )
        show(MatronAppearance.Light) {
            MissionNotice(closed, onOpen = null, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            MissionDetailView(
                model = MissionDetailModel.from(
                    mission = mission, milestones = milestones, sessionTags = emptyMap(), openItems = listOf(item),
                    conversations = emptyList(), showOnlyUserInput = false, closeSummary = "", isBusy = false,
                ),
                onToggleUserInputOnly = {}, onOpenMilestone = {}, onOpenItem = {}, onOpenConversation = {},
                onEditCloseSummary = {}, onClose = {}, onRefresh = {},
            )
        }.use {
            node(By.text("Status by bob"))
            node(By.text("by bob"))
            node(By.text("by bob's agent"))
            node(By.textContains("Handed to bob"))
            screenshot("attribution-mission")
        }
    }

    @Test
    fun itemThreadWithContributorAndHandoverAttribution() {
        val item = TrackerItem(
            id = "it_1", num = 15, kind = chat.matron.android.models.ItemKind.TASK, title = "Draft the second email",
            body = "Keep it under 120 words.", originConvoID = "", createdAt = now.minusSeconds(7200), updatedAt = now,
            contributor = chat.matron.android.models.Contributor("bob", chat.matron.android.models.Contributor.Via.USER),
        )
        val comments = listOf(
            TrackerComment(
                id = "cm_1", itemID = "it_1", author = chat.matron.android.models.ItemAuthor.AGENT,
                body = "Earlier notes from the previous owner: the tone guide is in the brief.",
                createdAt = now.minusSeconds(5400), attribution = "alice's agent",
            ),
            TrackerComment(
                id = "cm_2", itemID = "it_1", author = chat.matron.android.models.ItemAuthor.AGENT,
                body = "Draft attached to the mission folder; 112 words.", createdAt = now.minusSeconds(1800),
                contributor = chat.matron.android.models.Contributor("bob", chat.matron.android.models.Contributor.Via.AGENT),
                attribution = "bob's agent",
            ),
            TrackerComment(
                id = "cm_3", itemID = "it_1", author = chat.matron.android.models.ItemAuthor.AGENT,
                body = "Looks good to me.", createdAt = now.minusSeconds(600),
                contributor = chat.matron.android.models.Contributor("bob", chat.matron.android.models.Contributor.Via.USER),
                attribution = "bob",
            ),
        )
        show(MatronAppearance.Light) {
            ItemDetailView(
                model = ItemDetailModel(
                    item = item, comments = comments, pending = emptyList(),
                    context = ItemContext(missionID = "ms_2", missionLabel = "#9 Onboarding emails", owner = null),
                    availableResolutions = emptyList(), isBusy = false, loadedCommentCount = comments.size,
                ),
                draft = "", onDraftChange = {}, image = { null }, onOpenAttachment = {}, onOpenLink = {},
                onOpenConversation = {}, onSubmit = {}, onAttach = {}, onVoiceNote = {}, now = now,
            )
        }.use {
            node(By.text("by alice's agent"))
            node(By.text("by bob's agent"))
            screenshot("attribution-item")
        }
    }
}
