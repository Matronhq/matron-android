package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.viewmodels.ApprovalCenter

/// The confirm sheet for a signed approval: what is being approved, the
/// button, and every line of the subject exactly as it will be signed, above
/// the button that asks for the biometric. Pure: plain values and lambdas, so
/// it renders in tests without a key. Ported from matron-apple's
/// `ApprovalConfirmView`.
object ApprovalConfirm {
    const val TITLE = "Approve with your key"

    data class Model(
        /// The item's number as text ("#7"), for a tap.
        val reference: String? = null,
        /// The item's title, or the account action.
        val headline: String,
        /// The button being pressed, for a tap.
        val button: String? = null,
        /// The question's words, shown under the headline.
        val detail: String? = null,
        /// The subject's lines, key and value, in the order they are signed.
        val subject: List<Pair<String, String>>,
        /// "fingerprint" or "biometrics".
        val method: String,
        /// The fingerprint of the key that signs.
        val fingerprint: String,
        /// This device's key is re-attached first: the system asks twice.
        val attachesFirst: Boolean = false,
        val isSigning: Boolean = false,
        val error: String? = null,
    ) {
        val confirmLabel: String get() = if (isSigning) "Waiting for your $method…" else "Confirm with your $method"

        companion object {
            fun of(prompt: ApprovalCenter.Prompt, isSigning: Boolean, error: String?): Model {
                val request = prompt.request
                return Model(
                    reference = request.reference, headline = request.headline, button = request.button,
                    detail = request.detail, subject = request.subjectLines, method = prompt.method,
                    fingerprint = prompt.fingerprint, attachesFirst = prompt.attachesFirst,
                    isSigning = isSigning, error = error,
                )
            }
        }
    }

    const val EMPTY_SUBJECT = "Nothing beyond the item and the button above."
    fun attachNote(method: String) =
        "This device signed in again since its key was added, so the key is re-attached first. Your $method is asked for twice."
}

@Composable
fun ApprovalConfirmView(model: ApprovalConfirm.Model, onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val panel = MaterialTheme.colorScheme.surfaceVariant
    // Its own surface, so its text takes the theme's colours wherever it is hosted.
    Surface(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().testTag("approval.confirm")) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(ApprovalConfirm.TITLE, style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.reference?.let {
                        Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(model.headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                model.button?.let { button ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.testTag("approval.button"),
                    ) {
                        Text("Button", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            button,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            model.detail?.takeIf { it.isNotEmpty() }?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(panel, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                        .testTag("approval.detail"),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What you are signing", style = MaterialTheme.typography.titleSmall)
                if (model.subject.isEmpty()) {
                    Text(ApprovalConfirm.EMPTY_SUBJECT, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(panel, RoundedCornerShape(10.dp))
                            .testTag("approval.subject"),
                    ) {
                        model.subject.forEachIndexed { index, (key, value) ->
                            if (index > 0) HorizontalDivider()
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    value.ifEmpty { "(empty)" },
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                                )
                            }
                        }
                    }
                }
            }
            if (model.attachesFirst) Note(ApprovalConfirm.attachNote(model.method), Icons.Filled.Sync)
            model.error?.let { Note(it, Icons.Filled.Warning, MaterialTheme.colorScheme.error, Modifier.testTag("approval.error")) }
        }
        HorizontalDivider()
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onConfirm,
                enabled = !model.isSigning,
                modifier = Modifier.fillMaxWidth().testTag("approval.confirmButton"),
            ) {
                if (model.isSigning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(model.confirmLabel, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Signed by this device's key ${model.fingerprint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel, enabled = !model.isSigning, modifier = Modifier.testTag("approval.cancelButton")) {
                    Text("Cancel")
                }
            }
        }
    }
    }
}

@Composable
private fun Note(text: String, icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/// Shows the confirm sheet whenever [center] has a request waiting, over
/// whatever is on screen (a dialog window, so it also covers another dialog
/// or sheet). Only the sheet's own Cancel, or back, ends it: a stray tap
/// outside must not be read as an answer either way.
@Composable
fun ApprovalConfirmHost(center: ApprovalCenter) {
    val prompt by center.prompt.collectAsStateWithLifecycle()
    val isSigning by center.isSigning.collectAsStateWithLifecycle()
    val error by center.promptError.collectAsStateWithLifecycle()
    val current = prompt ?: return
    Dialog(
        onDismissRequest = { if (!isSigning) center.cancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            ApprovalConfirmView(
                model = ApprovalConfirm.Model.of(current, isSigning, error),
                onConfirm = center::confirm,
                onCancel = center::cancel,
                modifier = Modifier.safeDrawingPadding(),
            )
        }
    }
}
