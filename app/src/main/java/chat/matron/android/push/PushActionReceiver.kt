package chat.matron.android.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import chat.matron.android.MatronApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/// A button tapped on an item's notification: answers the item without
/// opening the app, as the item's own button would (the label as both the
/// action and the comment). The notification goes once the journal takes
/// the answer; if it does not, a notification says the answer is
/// unconfirmed and a tap on it opens the item.
class PushActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val answer = PushNotificationActions.answer(intent) ?: return
        val tag = intent.getStringExtra(PushNotificationActions.Key.TAG) ?: return
        val title = intent.getStringExtra(PushNotificationActions.Key.TITLE).orEmpty()
        val deps = (context.applicationContext as MatronApplication).dependencies
        val notifier = PushNotifier(context)
        val pending = goAsync()
        val started = deps.pushGeneration
        scope.launch {
            try {
                // A broadcast has about ten seconds before the system gives up on it.
                val sent = withTimeoutOrNull(8_000) { deps.answerItem(answer) } == true
                // Signed out meanwhile: that account's notifications are gone
                // and must stay gone.
                if (deps.pushGeneration != started) return@launch
                notifier.cancel(tag)
                if (!sent) notifier.showUnconfirmed(answer, tag, title, intent.getStringExtra(PushNotificationActions.Key.CONVO_ID))
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
