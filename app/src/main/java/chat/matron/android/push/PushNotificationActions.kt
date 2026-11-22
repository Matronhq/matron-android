package chat.matron.android.push

import android.content.Intent

/// The buttons on an item's notification, and what a tap on one means.
/// Port of matron-apple's `PushNotificationActions`.
///
/// iOS registers a notification category per list of labels; Android puts
/// the buttons on the notification itself, so there is no registry. Each
/// button's intent carries the item, the comment its buttons belong to, the
/// whole list of labels the notification showed and the position tapped,
/// so the answer sent is always the label the user saw.
object PushNotificationActions {
    const val ACTION_ANSWER = "chat.matron.android.push.ANSWER"

    /// Extras on a notification's tap and button intents.
    object Key {
        const val CONVO_ID = "matron.push.convo_id"
        const val SEQ = "matron.push.seq"
        const val ITEM_ID = "matron.push.item_id"
        const val ITEM_ACTIONS = "matron.push.item_actions"
        const val ITEM_REPLY_TO = "matron.push.item_reply_to"
        const val ACTION_INDEX = "matron.push.action_index"
        const val TAG = "matron.push.tag"
        const val TITLE = "matron.push.title"
    }

    data class Answer(val itemID: String, val label: String, val replyTo: String?)

    /// Fills a button's intent.
    fun fill(intent: Intent, itemID: String, labels: List<String>, index: Int, replyTo: String?, tag: String, title: String, convoID: String): Intent =
        intent.setAction(ACTION_ANSWER)
            .putExtra(Key.CONVO_ID, convoID)
            .putExtra(Key.ITEM_ID, itemID)
            .putExtra(Key.ITEM_ACTIONS, ArrayList(labels))
            .putExtra(Key.ACTION_INDEX, index)
            .putExtra(Key.TAG, tag)
            .putExtra(Key.TITLE, title)
            .apply { if (replyTo != null) putExtra(Key.ITEM_REPLY_TO, replyTo) }

    /// What a tapped button means, or null when the intent names no button
    /// on an item.
    fun answer(intent: Intent): Answer? {
        if (intent.action != ACTION_ANSWER) return null
        val itemID = intent.getStringExtra(Key.ITEM_ID)?.takeIf { it.isNotEmpty() } ?: return null
        val labels = intent.getStringArrayListExtra(Key.ITEM_ACTIONS) ?: return null
        val index = intent.getIntExtra(Key.ACTION_INDEX, -1)
        val label = labels.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return null
        return Answer(itemID, label, intent.getStringExtra(Key.ITEM_REPLY_TO)?.takeIf { it.isNotEmpty() })
    }
}

/// A tapped notification: which chat, which item, and the event it showed.
data class PushTap(val convoID: String, val itemID: String?, val seq: Long?) {
    companion object {
        const val ACTION_OPEN = "chat.matron.android.push.OPEN"

        fun from(intent: Intent?): PushTap? {
            if (intent?.action != ACTION_OPEN) return null
            val convoID = intent.getStringExtra(PushNotificationActions.Key.CONVO_ID)?.takeIf { it.isNotEmpty() } ?: return null
            return PushTap(
                convoID = convoID,
                itemID = intent.getStringExtra(PushNotificationActions.Key.ITEM_ID)?.takeIf { it.isNotEmpty() },
                seq = intent.getLongExtra(PushNotificationActions.Key.SEQ, 0L).takeIf { it > 0 },
            )
        }
    }
}

/// Notification taps waiting for the signed-in shell, which opens the chat
/// or item. Process-wide, like iOS's `NotificationDelegate.shared`: the tap
/// arrives in the activity before (or without) any signed-in screen. Last
/// tap wins.
object PushTaps {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<PushTap?>(null)

    fun record(intent: android.content.Intent?) {
        PushTap.from(intent)?.let { pending.value = it }
    }

    /// Sign-out: a tap from the last account must not open in the next.
    fun clear() {
        pending.value = null
    }
}
