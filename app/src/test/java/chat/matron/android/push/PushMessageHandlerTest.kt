package chat.matron.android.push

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/// From an FCM data map to what the shade shows: the sealed box opened
/// with this phone's key, the channel for its kind, the item's buttons, the
/// tap's destination, and reads elsewhere clearing a conversation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushMessageHandlerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val system = context.getSystemService(NotificationManager::class.java)
    private val recipient = MessageDigest.getInstance("SHA-256").digest("fixture recipient".toByteArray())
    private var signedIn = true
    private val handler = PushMessageHandler(PushNotifier(context), privateKey = { recipient }, signedIn = { signedIn })

    // Sealed by the journal (see PushBoxTest): an item comment with buttons.
    private val itemBox =
        "G4aKnRf2vZxh3BsOM5i4sssxih3f_a0As-OjTNPknmJPu9gBrj5b7QD3P1YAJT659tWiky9qnXoh6rqKW86qHL-ILobGFEVCuz5SzkCJqJa1OqTFFCMSuEeCPQaJWJcBLtSP5sp0qHOifVkhJhyymioYjjiWX6pKPZzGNNCbolTNXht58EP4jSG77-JHUt8pr_5K1fC1_evbMiBDmfhWaMGQw6zVgnGHm51nV93GfRuiFzKdB5q5JROGQ3_tetsnsYzaJvjRF0rAr9m5-ey0PiFvjti741GA8usQN65AG6Zc023j0k4SZs2z9av14mVksiZcv-_TwAJ7PpzCFeZJ7_NAU12_SMF0svE-TJrkpQu-C70LuC4jq1pjkS03OFOD-4yCQ6IAo_JvwC4HQPj6juVKB15HYu8YTf_4GVwdhSifxNdT-QrDduh-cDHppWvcqgU5bvOBz6VPd5-qMztYi_PKf_JwIFePugOvN5m38XEQZ0YVlcbTPndgRbNCeJErnZ3jga2_wAKzotI_Ms8O9FVQDQ-Al1AKYrcLKXKSpeuNl2YSshb56VvmtZSYx3iE5-JIUsvE5mHVBELS9oDsj5nrZqEsIS8zJl-FgvBTZTaFZ2qxgwXRcTvIB6pLnqFzdvVhYSHbucwNFGzXobans61FJmKFPgBvhPFFVwePXJ4GjUOhw0O3bSCTgM0CpWV02GNHJwapD5uh2WpIGVSBx0js78_VlIcyYlbK1x3fOow"

    @Before
    fun grant() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted() = shadowOf(system).activeNotifications.toList()

    @Test
    fun aSealedItemShowsItsTextButtonsAndOpensTheItem() {
        val outcome = handler.handle(mapOf("type" to "alert", "convo_id" to "c-9", "seq" to "314", "kind" to "attention", "item_id" to "it_77", "box" to itemBox, "badge" to "4"))
        assertEquals(PushMessageHandler.Outcome.SHOWN, outcome)
        val sbn = posted().single()
        assertEquals("item:it_77", sbn.tag)
        val n = sbn.notification
        assertEquals(PushNotifier.Channel.ATTENTION.id, n.channelId)
        assertEquals("Deploy to box-a? ✅", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals("Tests are green — ship café build?", n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertEquals("Question · Release train", n.extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT).toString())
        assertEquals(4, n.number)
        assertEquals(listOf("Ship", "Hold", "Ask bob"), n.actions.map { it.title.toString() })

        val tap = shadowOf(n.contentIntent).savedIntent
        assertEquals(PushTap("c-9", "it_77", 314), PushTap.from(tap))

        val button = shadowOf(n.actions[2].actionIntent).savedIntent
        assertEquals(PushNotificationActions.Answer("it_77", "Ask bob", "cm_5"), PushNotificationActions.answer(button))
        assertEquals("item:it_77", button.getStringExtra(PushNotificationActions.Key.TAG))
    }

    @Test
    fun withoutTheKeyTheFixedTextShows() {
        val keyless = PushMessageHandler(PushNotifier(context), privateKey = { null }, signedIn = { true })
        keyless.handle(mapOf("type" to "alert", "convo_id" to "c-9", "kind" to "attention", "box" to itemBox))
        val n = posted().single().notification
        assertEquals("Matron", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals("Your agent needs you", n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertEquals(0, n.actions?.size ?: 0)
    }

    @Test
    fun kindsGoToTheirChannelsAndAChatKeepsOneNotification() {
        handler.handle(mapOf("type" to "alert", "convo_id" to "c1", "kind" to "activity", "title" to "Inbox", "body" to "one"))
        handler.handle(mapOf("type" to "alert", "convo_id" to "c1", "kind" to "activity", "title" to "Inbox", "body" to "two"))
        handler.handle(mapOf("type" to "alert", "convo_id" to "c2", "kind" to "done", "title" to "Docs", "body" to "finished"))
        val byTag = posted().associateBy { it.tag }
        assertEquals(setOf("convo:c1", "convo:c2"), byTag.keys)
        assertEquals("two", byTag["convo:c1"]!!.notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertEquals(PushNotifier.Channel.ACTIVITY.id, byTag["convo:c1"]!!.notification.channelId)
        assertEquals(PushNotifier.Channel.DONE.id, byTag["convo:c2"]!!.notification.channelId)
        assertEquals(3, system.notificationChannels.size)
    }

    @Test
    fun aReadElsewhereClearsThatConversationOnly() {
        handler.handle(mapOf("type" to "alert", "convo_id" to "c-9", "kind" to "attention", "item_id" to "it_77", "box" to itemBox))
        handler.handle(mapOf("type" to "alert", "convo_id" to "c1", "kind" to "done", "title" to "Inbox", "body" to "done"))
        assertEquals(PushMessageHandler.Outcome.CLEARED, handler.handle(mapOf("type" to "read", "convo_id" to "c-9", "badge" to "0")))
        assertEquals(listOf("convo:c1"), posted().map { it.tag })
    }

    @Test
    fun nothingShowsWhenSignedOutOrNotificationsAreOff() {
        signedIn = false
        assertEquals(PushMessageHandler.Outcome.IGNORED, handler.handle(mapOf("type" to "alert", "convo_id" to "c1", "kind" to "done", "title" to "t", "body" to "b")))
        signedIn = true
        shadowOf(system).setNotificationsEnabled(false)
        assertEquals(PushMessageHandler.Outcome.NOT_SHOWN, handler.handle(mapOf("type" to "alert", "convo_id" to "c1", "kind" to "done", "title" to "t", "body" to "b")))
        assertEquals(emptyList<Any>(), posted())
        assertEquals(PushMessageHandler.Outcome.IGNORED, handler.handle(mapOf("from" to "elsewhere")))
    }

    @Test
    fun anUnconfirmedAnswerSaysSoWithoutButtons() {
        PushNotifier(context).showUnconfirmed(PushNotificationActions.Answer("it_77", "Ship", null), "item:it_77", "Deploy?", "c-9")
        val n = posted().single().notification
        assertEquals("Not confirmed: Ship", n.extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT).toString())
        assertEquals(0, n.actions?.size ?: 0)
        assertEquals(PushTap("c-9", "it_77", null), PushTap.from(shadowOf(n.contentIntent).savedIntent))
        // A read of the chat elsewhere takes the notice away too.
        handler.handle(mapOf("type" to "read", "convo_id" to "c-9"))
        assertEquals(emptyList<Any>(), posted())
    }

    @Test
    fun aSignOutWhileAMessageIsHandledPostsNothing() {
        var generation = 0L
        val racing = PushMessageHandler(
            PushNotifier(context),
            // The sign-out lands between the session check and the post.
            privateKey = { generation += 1; recipient },
            signedIn = { true },
            generation = { generation },
        )
        assertEquals(
            PushMessageHandler.Outcome.NOT_SHOWN,
            racing.handle(mapOf("type" to "alert", "convo_id" to "c-9", "kind" to "attention", "box" to itemBox)),
        )
        assertEquals(emptyList<Any>(), posted())
    }

    @Test
    fun endingTheSessionClearsTheShadeAndBlocksLaterPosts() {
        var generation = 0L
        val notifier = PushNotifier(context)
        val h = PushMessageHandler(notifier, privateKey = { recipient }, signedIn = { true }, generation = { generation })
        h.handle(mapOf("type" to "alert", "convo_id" to "c1", "kind" to "done", "title" to "t", "body" to "b"))
        notifier.endSession { generation += 1 }
        assertEquals(emptyList<Any>(), posted())
    }

    @Test
    fun eachNotificationAndButtonHasItsOwnIntent() {
        handler.handle(mapOf("type" to "alert", "convo_id" to "c-9", "kind" to "attention", "item_id" to "it_77", "box" to itemBox))
        handler.handle(mapOf("type" to "alert", "convo_id" to "c1", "kind" to "done", "title" to "t", "body" to "b"))
        val intents = posted().flatMap { sbn ->
            listOf(shadowOf(sbn.notification.contentIntent).savedIntent) +
                sbn.notification.actions.orEmpty().map { shadowOf(it.actionIntent).savedIntent }
        }
        assertEquals(5, intents.map { it.data.toString() }.toSet().size)
    }

    @Test
    fun aWaitingTapSurvivesProcessDeath() {
        PushTaps.pending.value = PushTap("c-9", "it_77", 314)
        val saved = android.os.Bundle().also(PushTaps::save)
        PushTaps.clear()
        PushTaps.restore(saved)
        assertEquals(PushTap("c-9", "it_77", 314), PushTaps.pending.value)
        PushTaps.clear()
        PushTaps.restore(android.os.Bundle())
        assertNull(PushTaps.pending.value)
    }

    @Test
    @Config(sdk = [30])
    fun noAnswerButtonsWhereAndroidCannotRequireAnUnlock() {
        handler.handle(mapOf("type" to "alert", "convo_id" to "c-9", "kind" to "attention", "item_id" to "it_77", "box" to itemBox))
        val n = posted().single().notification
        assertEquals(0, n.actions?.size ?: 0)
        assertEquals(PushTap("c-9", "it_77", 314), PushTap.from(shadowOf(n.contentIntent).savedIntent))
    }

    @Test
    fun anIntentThatIsNotAButtonIsNoAnswer() {
        assertNull(PushNotificationActions.answer(Intent("other")))
        val outOfRange = PushNotificationActions.fill(Intent(), "it_1", listOf("A"), 3, null, "item:it_1", "t", "c1")
        assertNull(PushNotificationActions.answer(outOfRange))
        assertNull(PushTap.from(Intent(PushTap.ACTION_OPEN)))
    }
}
