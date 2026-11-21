package chat.matron.android.features.approvals

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import chat.matron.android.MainActivity
import chat.matron.android.designsystem.ApprovalConfirm
import chat.matron.android.designsystem.ApprovalConfirmView
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.features.settings.ApprovalKeysScreen
import chat.matron.android.journal.approvals.ApprovalKeyDTO
import chat.matron.android.journal.approvals.ApprovalKeyEncoding
import chat.matron.android.journal.approvals.ApprovalKeyRequestDTO
import chat.matron.android.journal.approvals.ApprovalKeysProviding
import chat.matron.android.journal.approvals.ApprovalKeysState
import chat.matron.android.journal.approvals.ApprovalNonce
import chat.matron.android.journal.approvals.SignedApproval
import chat.matron.android.platform.ApprovalKeyStore
import chat.matron.android.platform.SoftwareKeyProvider
import chat.matron.android.viewmodels.ApprovalCenter
import chat.matron.android.viewmodels.ApprovalKeysScreenModel
import chat.matron.android.viewmodels.SharedPreferencesKeyValueStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Settings ▸ Approval keys and the confirm sheet, drawn from fixtures; no server, account or key required. */
@RunWith(AndroidJUnit4::class)
class ApprovalKeysScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), 5_000)) { "Missing $selector" }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(500)
        val directory = File(context.getExternalFilesDir(null), "approval-keys").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    /// The account's keys as a journal would list them.
    private class Fixture(var state: ApprovalKeysState) : ApprovalKeysProviding {
        override suspend fun approvalKeys() = state
        override suspend fun approvalNonce() = ApprovalNonce("n", Instant.now())
        override suspend fun enrolApprovalKey(publicKey: String, name: String, password: String, code: String) = error("fixture")
        override suspend fun requestApprovalKey(publicKey: String, name: String) = error("fixture")
        override suspend fun approveApprovalKeyRequest(id: String, approval: SignedApproval) = error("fixture")
        override suspend fun revokeApprovalKey(keyID: String, approval: SignedApproval) = Unit
        override suspend fun revokeApprovalKey(keyID: String, password: String, code: String) = Unit
        override suspend fun attachApprovalKey(approval: SignedApproval) = error("fixture")
    }

    private val created = Instant.parse("2026-11-12T10:00:00Z")

    private fun model(build: (thisDevice: String) -> ApprovalKeysState, holdsKey: Boolean): ApprovalKeysScreenModel {
        val prefs = context.getSharedPreferences("approval-keys-screen-test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        val store = ApprovalKeyStore(SharedPreferencesKeyValueStore(prefs), "journal.example", "alice")
        val keys = SoftwareKeyProvider()
        val mine = keys.create()
        if (holdsKey) store.save(mine)
        val fixture = Fixture(build(mine.publicKey))
        val center = ApprovalCenter(fixture, keys, store, "alice", 7, "Pixel 8", "journal.example")
        runBlocking { center.refresh() }
        return ApprovalKeysScreenModel(center)
    }

    private fun key(publicKey: String, name: String, device: Long?, via: ApprovalKeyDTO.EnrolledVia, revoked: Instant? = null) =
        ApprovalKeyDTO(
            ApprovalKeyEncoding.keyID(ApprovalKeyEncoding.data(publicKey)!!), publicKey, name, device, via, created, revoked,
        )

    private fun otherKey(seed: Int): String =
        ApprovalKeyEncoding.base64URL(SoftwareKeyProvider().create().publicKeyBytes).also { check(seed >= 0) }

    private fun show(appearance: MatronAppearance, content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) { content() }
                    }
                }
            }
        }

    @Test
    fun noKeyYet() {
        val model = model({ ApprovalKeysState(true, "journal.example", emptyList()) }, holdsKey = false)
        show(MatronAppearance.Light) { ApprovalKeysScreen(model, onBack = {}, poll = false) }.use {
            node(By.text("No approval key yet"))
            node(By.text("Set Up with Password and Code"))
            screenshot("01-no-key-yet")
        }
    }

    @Test
    fun readyWithARequestWaiting() {
        val tablet = otherKey(1)
        val old = otherKey(2)
        val model = model({ mine ->
            ApprovalKeysState(
                true, "journal.example",
                listOf(
                    key(mine, "Pixel 8", 7, ApprovalKeyDTO.EnrolledVia.PASSWORD),
                    key(tablet, "Tablet", null, ApprovalKeyDTO.EnrolledVia.DEVICE),
                    key(old, "Old phone", null, ApprovalKeyDTO.EnrolledVia.PASSWORD, revoked = Instant.parse("2026-11-13T09:00:00Z")),
                ),
                listOf(ApprovalKeyRequestDTO("kr_1", ApprovalKeyEncoding.keyID(ApprovalKeyEncoding.data(otherKey(3))!!), "Laptop", created)),
            )
        }, holdsKey = true)
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance) { ApprovalKeysScreen(model, onBack = {}, poll = false) }.use {
                node(By.text("This device can approve"))
                node(By.text("Waiting to be added"))
                node(By.text("Approve…"))
                screenshot("02-ready-${appearance.rawValue}")
            }
        }
    }

    @Test
    fun anotherDeviceHasTheKey() {
        val tablet = otherKey(1)
        val model = model({ ApprovalKeysState(true, "journal.example", listOf(key(tablet, "Tablet", 9, ApprovalKeyDTO.EnrolledVia.PASSWORD))) }, holdsKey = false)
        show(MatronAppearance.Light) { ApprovalKeysScreen(model, onBack = {}, poll = false) }.use {
            node(By.text("This device has no key"))
            node(By.text("Ask a Device That Has a Key"))
            screenshot("03-no-device-key")
        }
    }

    private val tapModel = ApprovalConfirm.Model(
        reference = "#7", headline = "Deploy the new build to box-a?", button = "Deploy",
        detail = "Roll out build 412 to box-a. The service restarts and is down for about a minute.",
        subject = listOf("box" to "box-a", "build" to "412", "op" to "deploy"),
        method = "fingerprint", fingerprint = "7734f909",
    )

    @Test
    fun confirmSheetForAStepUpTap() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance) { ApprovalConfirmView(tapModel, onConfirm = {}, onCancel = {}) }.use {
                node(By.text("Confirm with your fingerprint"))
                node(By.text("box-a"))
                screenshot("04-confirm-tap-${appearance.rawValue}")
            }
        }
    }

    @Test
    fun confirmSheetReattachingWithAnError() {
        val model = tapModel.copy(attachesFirst = true, error = "Your fingerprint was not recognised.")
        show(MatronAppearance.Light) { ApprovalConfirmView(model, onConfirm = {}, onCancel = {}) }.use {
            node(By.text("Your fingerprint was not recognised."))
            screenshot("05-confirm-attach-error")
        }
    }
}
