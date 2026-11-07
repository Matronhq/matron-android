package chat.matron.android.features.people

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import chat.matron.android.designsystem.InviteLinkReveal
import chat.matron.android.events.ContactInviteAsk

/// The link an Approve tap on an agent's invite ask brought back, shown once.
/// Done drops it for good.
@Composable
fun InviteLinkDialog(link: String, onDone: () -> Unit) {
    AlertDialog(
        // Only Done closes it: a stray tap outside must not lose the link
        // before the user has copied or shared it.
        onDismissRequest = {},
        title = { Text(ContactInviteAsk.LABEL) },
        text = { InviteLinkReveal(link = link, note = ContactInviteAsk.SHOWN_ONCE) },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
    )
}
