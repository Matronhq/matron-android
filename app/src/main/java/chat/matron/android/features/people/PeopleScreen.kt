package chat.matron.android.features.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.journal.Contact
import chat.matron.android.journal.ContactInvite
import chat.matron.android.journal.ContactsDates
import chat.matron.android.journal.ContactsWording
import chat.matron.android.journal.InvitePreview
import chat.matron.android.viewmodels.AcceptCardState
import chat.matron.android.viewmodels.InviteSheetState
import chat.matron.android.viewmodels.PeopleViewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/// Wording shared with the web and Apple apps.
object PeopleWording {
    const val TITLE = "People"
    const val EMPTY = "No contacts yet."
    const val INVITE_BUTTON = "Invite someone on another Matron"
    const val OPEN_BUTTON = "Open an invite link"
    const val PENDING = "Waiting for them to accept"
    const val KEY_CHANGED = "Their journal's key has changed. Nothing is shared until they send you a new invite."
    const val OPEN_INVITES = "Open invites"
    const val INVITE_BODY = "Send this link to them yourself, over any chat app or email. It is shown only now, works once and lasts 7 days. " +
        "Nobody becomes a contact until they open it and accept."
    const val ACCEPT_TITLE = "Contact invite"
    const val COMPARE_NOTE = "Compare this fingerprint with them another way, in person or on a call, before you accept."
    const val ACCEPT_KEY_CHANGED = "Their journal's key has changed since you were last in touch. Compare the fingerprint before you accept."
}

/// Settings → People: contacts (with their full address on another
/// journal), open invites, and the two ways to make a contact on another
/// Matron — make an invite link, or open one.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(viewModel: PeopleViewModel, onBack: () -> Unit) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val invites by viewModel.invites.collectAsStateWithLifecycle()
    val hasLoaded by viewModel.hasLoaded.collectAsStateWithLifecycle()
    val error by viewModel.errorMessage.collectAsStateWithLifecycle()
    val withdrawing by viewModel.withdrawing.collectAsStateWithLifecycle()
    val inviteSheet by viewModel.inviteSheet.collectAsStateWithLifecycle()
    val acceptCard by viewModel.acceptCard.collectAsStateWithLifecycle()
    var pasting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(PeopleWording.TITLE) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            }
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { viewModel.makeInvite() }, modifier = Modifier.fillMaxWidth()) {
                        Text(PeopleWording.INVITE_BUTTON)
                    }
                    OutlinedButton(onClick = { pasting = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(PeopleWording.OPEN_BUTTON)
                    }
                }
            }
            if (!hasLoaded) {
                if (error == null) item { Hint("Loading…") }
            } else if (contacts.isEmpty()) {
                item { Hint(PeopleWording.EMPTY) }
            } else {
                items(contacts, key = { it.id }) { contact ->
                    ContactRow(contact)
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                }
            }
            if (invites.isNotEmpty()) {
                item { SectionHeader(PeopleWording.OPEN_INVITES) }
                items(invites, key = { "inv:" + it.id }) { invite ->
                    InviteRow(invite, busy = invite.id in withdrawing, onWithdraw = { viewModel.withdraw(invite) })
                }
            }
        }
    }

    if (pasting) {
        PasteLinkDialog(
            onOpen = { text ->
                pasting = false
                viewModel.openLink(text)
            },
            onDismiss = { pasting = false },
        )
    }

    inviteSheet?.let { state ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { viewModel.closeInviteSheet() }, sheetState = sheetState) {
            InviteSheet(state, onDone = { viewModel.closeInviteSheet() })
        }
    }

    acceptCard?.let { state ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { viewModel.closeAcceptCard() }, sheetState = sheetState) {
            AcceptCard(
                state = state,
                onAccept = { viewModel.accept() },
                onCancel = { viewModel.closeAcceptCard() },
            )
        }
    }
}

@Composable
private fun ContactRow(contact: Contact) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(contact.name, style = MaterialTheme.typography.bodyLarge)
        contact.addressLine?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (contact.isPendingOut) Caption(PeopleWording.PENDING)
        if (contact.keyChanged) Caption(PeopleWording.KEY_CHANGED, error = true)
        contact.unreachableSince?.let { Caption(ContactsDates.unreachable(it)) }
    }
}

@Composable
private fun InviteRow(invite: ContactInvite, busy: Boolean, onWithdraw: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(ContactsDates.inviteRow(invite), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (busy) CircularProgressIndicator(Modifier.size(18.dp))
        else TextButton(onClick = onWithdraw) { Text("Withdraw") }
    }
}

@Composable
private fun PasteLinkDialog(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(PeopleWording.OPEN_BUTTON) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Paste the link they sent you") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                // The Play-services code scanner the app already uses for
                // sign-in: no camera permission. Where it is unavailable
                // the paste field stays.
                OutlinedButton(
                    onClick = {
                        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                        GmsBarcodeScanning.getClient(context, options).startScan()
                            .addOnSuccessListener { barcode -> barcode.rawValue?.let(onOpen) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan QR code") }
            }
        },
        confirmButton = { TextButton(onClick = { onOpen(text) }, enabled = text.isNotBlank()) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun InviteSheet(state: InviteSheetState, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(PeopleWording.INVITE_BUTTON, style = MaterialTheme.typography.titleLarge)
        when (state) {
            InviteSheetState.Making -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            is InviteSheetState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text("Done") }
    }
}

@Composable
private fun AcceptCard(state: AcceptCardState, onAccept: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(PeopleWording.ACCEPT_TITLE, style = MaterialTheme.typography.titleLarge)
        when (state) {
            AcceptCardState.Opening -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            is AcceptCardState.Refused -> {
                Text(state.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
            AcceptCardState.Sent -> {
                Text(ContactsWording.ACCEPT_SENT, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
            is AcceptCardState.Preview -> PreviewBody(state, onAccept, onCancel)
        }
    }
}

@Composable
private fun PreviewBody(state: AcceptCardState.Preview, onAccept: () -> Unit, onCancel: () -> Unit) {
    val preview: InvitePreview = state.preview
    Text(preview.headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    Detail("From", preview.address)
    Detail("Journal", preview.journal)
    Detail("Fingerprint", preview.fingerprint, monospace = true)
    Text(PeopleWording.COMPARE_NOTE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (preview.keyChanged) Text(PeopleWording.ACCEPT_KEY_CHANGED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    if (preview.alreadyContacts) Text(ContactsWording.ALREADY_CONTACTS, style = MaterialTheme.typography.bodyMedium)
    if (preview.blocked) Text(ContactsWording.BLOCKED, style = MaterialTheme.typography.bodyMedium)
    state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.accepting) CircularProgressIndicator(Modifier.size(18.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
        if (!preview.blocked) Button(onClick = onAccept, enabled = !state.accepting) { Text("Accept") }
    }
}

@Composable
private fun Detail(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (monospace) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun Caption(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
