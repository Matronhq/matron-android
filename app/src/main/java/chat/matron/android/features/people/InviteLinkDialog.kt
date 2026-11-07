package chat.matron.android.features.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.matron.android.designsystem.InviteLinkReveal
import chat.matron.android.events.ContactInviteAsk
import chat.matron.android.journal.RevealedInviteLinks

/// A link just made, shown once: People's invite ("Invite someone on
/// another Matron") or an agent's approved ask ("Invite link request").
/// Only Done closes it, and Done drops the link for good.
@Composable
fun InviteLinkDialog(reveal: RevealedInviteLinks.Reveal, onDone: () -> Unit) {
    val invite = reveal.source == RevealedInviteLinks.Source.INVITE
    AlertDialog(
        // A stray tap outside or a back press must not lose the link
        // before the user has copied or shared it.
        onDismissRequest = {},
        title = { Text(if (invite) PeopleWording.INVITE_BUTTON else ContactInviteAsk.LABEL) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (invite) Text(PeopleWording.INVITE_BODY, style = MaterialTheme.typography.bodyMedium)
                InviteLinkReveal(link = reveal.link, note = if (invite) null else ContactInviteAsk.SHOWN_ONCE)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
    )
}
