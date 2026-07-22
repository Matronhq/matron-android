package chat.matron.android.marketing

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/// Marketing/Play Store screenshot harness — NOT a correctness test. The
/// Android analogue of matron-apple's `MarketingScreenshots.swift`.
///
/// Drives the real app over UiAutomator against a seeded local matron-journal
/// (see `tools/screenshots.sh`: server on host port 9810, reached from the
/// device as 127.0.0.1:9810 via `adb reverse`, demo account, scripted
/// conversations) and writes full-resolution PNGs to the app's external files
/// dir under `screenshots/`, which the driver script pulls out. Skips itself
/// when the rig isn't reachable so a stray `connectedAndroidTest` run is
/// unaffected.
@RunWith(AndroidJUnit4::class)
class MarketingScreenshots {

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private val outputDir: File by lazy {
        File(targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    }

    companion object {
        private const val SERVER_URL = "http://127.0.0.1:9810"
        private const val USERNAME = "demo"
        private const val PASSWORD = "demo-screenshots"
        private const val SYNC_TIMEOUT = 30_000L
        private const val UI_TIMEOUT = 10_000L
    }

    @Before
    fun requireRig() {
        // TCP connect (not HTTP) — success proves `adb reverse` + server are up.
        val reachable = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", 9810), 2_000) }
        }.isSuccess
        assumeTrue("screenshot rig not running (127.0.0.1:9810 via adb reverse)", reachable)
    }

    private fun save(name: String) {
        Thread.sleep(2_000) // scroll/badge/session-header settle, matching the iOS rig
        device.takeScreenshot(File(outputDir, "$name.png"))
    }

    private fun launchApp() {
        val intent = targetContext.packageManager
            .getLaunchIntentForPackage(targetContext.packageName)!!
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
        targetContext.startActivity(intent)
        check(device.wait(Until.hasObject(By.pkg(targetContext.packageName)), UI_TIMEOUT)) {
            "app never came to the foreground"
        }
    }

    /// Compose text fields are found by their floating label, then written via
    /// the focused node — By.focused is the only handle Compose reliably
    /// exposes for setText across empty and filled states.
    private fun typeInto(label: String, value: String) {
        val field = device.wait(Until.findObject(By.text(label)), UI_TIMEOUT)
            ?: error("field with label '$label' not found")
        field.click()
        val focused = device.wait(Until.findObject(By.focused(true)), UI_TIMEOUT)
            ?: error("nothing took focus after tapping '$label'")
        focused.text = value
    }

    private fun signInIfNeeded() {
        // Already signed in from a previous run — the seeded list shows directly.
        if (device.wait(Until.hasObject(By.text("Fix the flaky upload test")), 5_000)) return

        check(device.wait(Until.hasObject(By.text("Sign in to Matron")), UI_TIMEOUT)) {
            "neither the chat list nor the sign-in screen appeared"
        }
        typeInto("Homeserver URL", SERVER_URL)
        typeInto("Username", USERNAME)
        typeInto("Password", PASSWORD)
        device.pressBack() // dismiss the keyboard so the Sign in button is tappable
        // Exact-match By.text: hits the button label, not the "Sign in to
        // Matron" title. The Compose Button's clickable node is the label's
        // parent, so click the label itself (dispatches at its center).
        requireObject(By.text("Sign in")).click()

        awaitVisible(
            By.text("Fix the flaky upload test"),
            "seeded conversations never synced after sign-in",
            SYNC_TIMEOUT,
        )
    }

    private fun requireObject(selector: androidx.test.uiautomator.BySelector): UiObject2 =
        device.wait(Until.findObject(selector), UI_TIMEOUT)
            ?: run {
                // Leave evidence for the driver script before failing.
                device.takeScreenshot(File(outputDir, "debug-failure.png"))
                error("UI object not found: $selector")
            }

    /// `check(device.wait(hasObject))` with a debug screenshot on timeout.
    private fun awaitVisible(
        selector: androidx.test.uiautomator.BySelector,
        what: String,
        timeout: Long = UI_TIMEOUT,
    ) {
        if (!device.wait(Until.hasObject(selector), timeout)) {
            device.takeScreenshot(File(outputDir, "debug-failure.png"))
            error(what)
        }
    }

    private fun openChat(title: String) {
        requireObject(By.text(title)).click()
    }

    private fun backToList() {
        requireObject(By.desc("Back")).click()
        awaitVisible(By.desc("New chat"), "never returned to the chat list")
    }

    @Test
    fun captureScreenshots() {
        launchApp()
        signInIfNeeded()

        // 01 — chat list with the seeded conversations + unread badge.
        save("android-01-chat-list")

        // 02 — hero chat: tool cards, diff, and the ask-user prompt.
        openChat("Fix the flaky upload test")
        awaitVisible(By.text("Push the fix and open a PR?"), "hero chat never rendered its prompt card")
        save("android-02-agent-chat")

        // 03 — session status sheet: model, context gauge, usage bars.
        requireObject(By.desc("Session status")).click()
        awaitVisible(By.text("claude-fable-5"), "status sheet never showed the model")
        save("android-03-session-status")
        device.pressBack() // close the sheet
        backToList()

        // 04 — diff-card chat.
        openChat("Dark mode for settings screen")
        awaitVisible(By.textContains("SettingsView.swift"), "dark-mode chat never rendered its diff card")
        save("android-04-diff-chat")
        backToList()

        // 05 — parent chat with a running subagent (the sub-chat strip).
        openChat("Refactor auth middleware")
        awaitVisible(By.textContains("Explore: auth call sites"), "sub-chat strip never appeared")
        save("android-05-subchat")
        backToList()

        // 06/07 — New Chat flow; needs the rig's responder.mjs (two connected
        // agents answering recent_folders).
        requireObject(By.desc("New chat")).click()
        awaitVisible(By.text("homelab"), "agent picker never listed homelab")
        save("android-06-new-chat-agents")
        requireObject(By.text("mac-studio")).click()
        awaitVisible(By.text("~/dev/api-server"), "recent_folders answer never rendered")
        save("android-07-new-chat-folders")
    }
}
