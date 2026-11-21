package chat.matron.android.features.chat

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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
import chat.matron.android.chat.JournalTimelineMapper
import chat.matron.android.chat.TimelineItem
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalEventType
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Bridge notices between agent replies, mapped from fixture journal events; no server or account required. */
@RunWith(AndroidJUnit4::class)
class SystemNoticeRowsScreenTest {
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
        val directory = File(context.getExternalFilesDir(null), "system-notices").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private val base = Instant.parse("2026-09-21T09:40:00Z")

    private fun event(seq: Long, payload: JsonObject) =
        JournalEvent(seq, "c1", base.plusSeconds(seq * 60), "agent:box-a", JournalEventType.TEXT, payload)

    private fun notice(seq: Long, kind: String, icon: String, summary: String, body: String) = event(seq, buildJsonObject {
        put("body", body)
        putJsonObject("notice") { put("kind", kind); put("icon", icon); put("summary", summary) }
    })

    private val events = listOf(
        event(1, buildJsonObject { put("body", "The checkout tests pass on every device size. I'll start on the payment step next.") }),
        notice(
            2, "consent", "🔐", "bob's agent asks to chat about the payment step",
            "bob's agent asks to start a chat with this session.\n\nTopic: payment step\n\n" +
                "Call agent_chat_accept with the room id to join, or agent_chat_refuse to decline.",
        ),
        notice(3, "routine", "⏰", "Routine fired: morning sweep", "Morning sweep: check every open mission and refresh its status."),
        notice(4, "room", "💬", "Message from box-b in the payment room", "box-b: the sandbox keys are in place, go ahead."),
        event(5, buildJsonObject { put("body", "Thanks, starting on the payment step now.") }),
    )

    private val items: List<TimelineItem> =
        events.mapNotNull { JournalTimelineMapper.timelineItem(it, "user:alice", "https://j.example.com".toHttpUrl()) }

    private fun show(appearance: MatronAppearance) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Box(Modifier.fillMaxSize().systemBarsPadding()) {
                            MatronTimelineBackground()
                            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (item in items) TimelineItemView(item = item, onOpenSpawnedRoom = {})
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun collapsedThenExpanded() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance).use {
                val consent = node(By.desc("bob's agent asks to chat about the payment step"))
                node(By.text("Routine fired: morning sweep"))
                assertNull(device.findObject(By.textContains("agent_chat_accept")))
                screenshot("notices-collapsed-${appearance.rawValue}")
                consent.click()
                node(By.textContains("agent_chat_accept"))
                screenshot("notices-expanded-${appearance.rawValue}")
            }
        }
    }
}
