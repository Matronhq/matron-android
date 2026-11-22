package chat.matron.android.push

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import chat.matron.android.MainActivity
import chat.matron.android.R

/// What one notification shows, once its box (if any) is opened.
data class PushDisplay(
    val title: String,
    val subtitle: String?,
    val body: String,
    val convoID: String,
    val seq: Long?,
    val kind: String,
    val itemID: String?,
    val actions: List<String>,
    val replyTo: String?,
    val badge: Int?,
) {
    /// One notification per item, else one per conversation: a second
    /// question in a chat does not replace the first, and a follow-up on
    /// the same item replaces what it follows (the journal's collapse rule).
    val tag: String get() = itemID?.let { "item:$it" } ?: "convo:$convoID"

    companion object {
        /// The fixed text for a kind, as the push relay words it for iOS.
        fun fallbackBody(kind: String): String = when (kind) {
            "attention" -> "Your agent needs you"
            "done" -> "Your agent finished its turn"
            else -> "New activity from your agent"
        }

        /// The alert as it will show: the opened box when there is one,
        /// else the text sent in the clear, else the fixed text.
        fun of(alert: PushMessage.Alert, content: PushContent?): PushDisplay =
            if (content != null) {
                PushDisplay(
                    title = content.title.ifEmpty { "Matron" },
                    subtitle = content.subtitle,
                    body = content.body,
                    convoID = content.convoID,
                    seq = content.seq ?: alert.seq,
                    kind = content.kind.ifEmpty { alert.kind },
                    itemID = content.item?.id ?: alert.itemID,
                    actions = content.item?.actions.orEmpty(),
                    replyTo = content.item?.replyTo,
                    badge = alert.badge,
                )
            } else {
                // With a box that could not be opened, the clear text is
                // ignored even if present: the journal never sends both.
                val clear = alert.box == null && !alert.title.isNullOrEmpty()
                PushDisplay(
                    title = if (clear) alert.title!! else "Matron",
                    subtitle = null,
                    body = if (clear) alert.body.orEmpty() else fallbackBody(alert.kind),
                    convoID = alert.convoID,
                    seq = alert.seq,
                    kind = alert.kind,
                    itemID = alert.itemID,
                    actions = emptyList(),
                    replyTo = null,
                    badge = alert.badge,
                )
            }
    }
}

/// Builds and posts Matron's notifications: a channel per kind, a tap that
/// opens the chat or the item, and the item's buttons.
class PushNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        for (channel in Channel.entries) {
            if (system.getNotificationChannel(channel.id) != null) continue
            system.createNotificationChannel(
                NotificationChannel(channel.id, channel.title, channel.importance).apply {
                    description = channel.description
                    setShowBadge(true)
                },
            )
        }
    }

    /// Posts [display], replacing any notification with the same tag.
    /// Returns false when the app may not post (permission off).
    @SuppressLint("MissingPermission") // checked: areNotificationsEnabled
    fun show(display: PushDisplay): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        ensureChannels()
        val channel = Channel.forKind(display.kind)
        val builder = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(display.title)
            .setContentText(display.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(display.body))
            .setSubText(display.subtitle)
            .setPriority(channel.priority)
            .setCategory(if (display.kind == "activity") NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(display.kind == "activity")
            .setContentIntent(openIntent(display))
            .addExtras(Bundle().apply { putString(PushNotificationActions.Key.CONVO_ID, display.convoID) })
        display.badge?.let { builder.setNumber(it) }
        val itemID = display.itemID
        if (itemID != null) {
            display.actions.forEachIndexed { index, label ->
                val intent = PushNotificationActions.fill(
                    Intent(context, PushActionReceiver::class.java),
                    itemID = itemID, labels = display.actions, index = index,
                    replyTo = display.replyTo, tag = display.tag, title = display.title, convoID = display.convoID,
                )
                val pending = PendingIntent.getBroadcast(
                    context, requestCode(display.tag, index + 1), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                // Answering is a decision: never from someone else's lock
                // screen (iOS asks for authentication on the same buttons).
                builder.addAction(
                    NotificationCompat.Action.Builder(0, label, pending)
                        .setAuthenticationRequired(true)
                        .build(),
                )
            }
        }
        return runCatching { manager.notify(display.tag, NOTIFICATION_ID, builder.build()) }.isSuccess
    }

    /// Takes a conversation's notifications off the shade: it was read on
    /// another device.
    fun cancelConversation(convoID: String) {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        for (n in runCatching { system.activeNotifications }.getOrNull().orEmpty()) {
            val convo = n.notification.extras?.getString(PushNotificationActions.Key.CONVO_ID)
            if (convo == convoID && n.id == NOTIFICATION_ID) manager.cancel(n.tag, n.id)
        }
    }

    fun cancel(tag: String) = manager.cancel(tag, NOTIFICATION_ID)

    /// Sign-out: the previous account's notifications go.
    fun cancelAll() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        for (n in runCatching { system.activeNotifications }.getOrNull().orEmpty()) {
            if (n.id == NOTIFICATION_ID) manager.cancel(n.tag, n.id)
        }
    }

    /// An answer the journal did not confirm must not vanish with the
    /// notification it was tapped on. A failure does not say whether it
    /// arrived, so this says only that it is unconfirmed; it carries no
    /// buttons, and a tap opens the item.
    @SuppressLint("MissingPermission")
    fun showUnconfirmed(answer: PushNotificationActions.Answer, tag: String, title: String, convoID: String?) {
        if (!manager.areNotificationsEnabled()) return
        ensureChannels()
        val display = PushDisplay(
            title = title, subtitle = "Not confirmed: ${answer.label}",
            body = "Your answer may not have reached the journal. Open the item to check before answering again.",
            convoID = convoID.orEmpty(), seq = null, kind = "attention", itemID = answer.itemID,
            actions = emptyList(), replyTo = null, badge = null,
        )
        val builder = NotificationCompat.Builder(context, Channel.ATTENTION.id)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(display.title)
            .setSubText(display.subtitle)
            .setContentText(display.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(display.body))
            .setAutoCancel(true)
            .setContentIntent(openIntent(display))
        runCatching { manager.notify(tag, NOTIFICATION_ID, builder.build()) }
    }

    private fun openIntent(display: PushDisplay): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(PushTap.ACTION_OPEN)
            // The app's task if it has one, its MainActivity handed the
            // intent rather than a second one stacked (see VoiceRecordingService).
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(PushNotificationActions.Key.CONVO_ID, display.convoID)
            .apply {
                display.itemID?.let { putExtra(PushNotificationActions.Key.ITEM_ID, it) }
                display.seq?.let { putExtra(PushNotificationActions.Key.SEQ, it) }
            }
        return PendingIntent.getActivity(
            context, requestCode(display.tag, 0), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /// Distinct per notification and button, so one notification's intents
    /// never overwrite another's.
    private fun requestCode(tag: String, slot: Int): Int = tag.hashCode() * 31 + slot

    enum class Channel(val id: String, val title: String, val description: String, val importance: Int, val priority: Int) {
        ATTENTION("matron.attention", "Needs you", "Questions, permission prompts and items waiting for you.",
            NotificationManager.IMPORTANCE_HIGH, NotificationCompat.PRIORITY_HIGH),
        DONE("matron.done", "Finished turns", "A session finished its turn or stopped.",
            NotificationManager.IMPORTANCE_DEFAULT, NotificationCompat.PRIORITY_DEFAULT),
        ACTIVITY("matron.activity", "Activity", "Routine activity from your agents, batched.",
            NotificationManager.IMPORTANCE_LOW, NotificationCompat.PRIORITY_LOW);

        companion object {
            fun forKind(kind: String): Channel = when (kind) {
                "attention" -> ATTENTION
                "done" -> DONE
                else -> ACTIVITY
            }
        }
    }

    companion object {
        const val NOTIFICATION_ID = 0x6d61 // one id; notifications are told apart by tag
    }
}
