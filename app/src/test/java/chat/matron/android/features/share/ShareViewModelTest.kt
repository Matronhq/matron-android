package chat.matron.android.features.share

import android.net.Uri
import chat.matron.android.models.UserSession
import chat.matron.android.viewmodels.waitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareViewModelTest {
    private val session = UserSession(userID = "alice", deviceID = "d1", homeserverURL = "https://example.com", accessToken = "t")
    private val photo = Uri.parse("content://com.example.photos/1")
    private val big = Uri.parse("content://com.example.photos/2")

    private val list = MutableStateFlow(
        listOf(
            ShareTarget("coord", "Coordinator", isCoordinator = true),
            ShareTarget("c1", "Release notes", detail = "box-a"),
            ShareTarget("c2", "Menu redesign", detail = "box-b"),
        ),
    )
    private val transport = FakeShareTransport()

    private inner class Env(val signedIn: Boolean = true) : ShareEnvironment {
        var loads = 0
        override suspend fun restoreSession(): UserSession? = session.takeIf { signedIn }
        override fun targets(session: UserSession): Flow<List<ShareTarget>> = list
        override suspend fun load(uri: Uri): SharedFile =
            if (uri == big) throw ShareLoadError.TooLarge("movie.mp4") else sharedFile(if (loads++ == 0) "photo.png" else "photo-$loads.png")
        override fun transport(session: UserSession): ShareTransport = transport
    }

    private fun content(text: String? = null, uris: List<Uri> = emptyList(), convo: String? = null) =
        ShareIntentContent(text, uris, convo)

    private fun test(
        content: ShareIntentContent,
        env: ShareEnvironment = Env(),
        body: suspend CoroutineScope.(ShareViewModel) -> Unit,
    ) = runBlocking {
        val job = Job(coroutineContext[Job])
        val model = ShareViewModel(content, env, CoroutineScope(coroutineContext + job))
        model.start()
        try {
            body(model)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun signedOutSaysSoAndReadsNothing() = test(content(uris = listOf(photo)), Env(signedIn = false)) { model ->
        waitUntil { model.phase.value == ShareViewModel.Phase.SignedOut }
        assertEquals(ShareViewModel.Phase.SignedOut, model.phase.value)
        assertTrue(model.files.value.isEmpty())
    }

    @Test
    fun theCoordinatorIsPickedAndTheTextBecomesTheMessage() = test(content(text = "https://example.com/a")) { model ->
        waitUntil { model.phase.value == ShareViewModel.Phase.Ready }
        assertEquals(ShareViewModel.Destination.Conversation("coord"), model.destination.value)
        assertEquals("https://example.com/a", model.message.value)
        assertFalse(model.isLoadingTargets.value)
        assertTrue(model.canSend)
    }

    @Test
    fun aDirectShareTargetIsPicked() = test(content(text = "hi", convo = "c2")) { model ->
        waitUntil { model.phase.value == ShareViewModel.Phase.Ready }
        assertEquals(ShareViewModel.Destination.Conversation("c2"), model.destination.value)
    }

    @Test
    fun anItemTooLargeIsReportedAndTheRestKept() = test(content(uris = listOf(photo, big))) { model ->
        waitUntil { model.phase.value == ShareViewModel.Phase.Ready }
        assertEquals(listOf("photo.png"), model.files.value.map { it.filename })
        assertEquals("movie.mp4 is too large to send. The limit is 100.0 MB.", model.errorMessage.value)
        assertTrue(model.canSend)
    }

    @Test
    fun nothingToSendCannotBeSent() = test(content()) { model ->
        waitUntil { model.phase.value == ShareViewModel.Phase.Ready }
        assertFalse(model.canSend)
        model.setMessage("   ")
        assertFalse(model.canSend)
        model.setMessage("A note")
        assertTrue(model.canSend)
    }

    @Test
    fun searchFiltersAndHidesTheNewConversationRow() = test(content(text = "x")) { model ->
        waitUntil { model.phase.value == ShareViewModel.Phase.Ready }
        assertTrue(model.offersNewConversation)
        model.setQuery("menu")
        assertEquals(listOf("c2"), model.visibleTargets.map { it.id })
        assertFalse(model.offersNewConversation)
        model.selectNewConversation()
        assertTrue(model.offersNewConversation)
    }

    @Test
    fun sendsIntoThePickedConversation() = test(content(text = "Note", uris = listOf(photo))) { model ->
        waitUntil { model.phase.value == ShareViewModel.Phase.Ready }
        model.select("c1")
        model.send()
        waitUntil { model.phase.value == ShareViewModel.Phase.Sent }
        assertEquals(ShareViewModel.Phase.Sent, model.phase.value)
        assertEquals(listOf(FakeShareTransport.Sent("c1", "photo.png", "Note", null)), transport.sent)
        assertFalse(model.files.value.single().file.exists())
    }

    @Test
    fun aFailedSendStaysOpenWithTheError() = test(content(text = "Note")) { model ->
        waitUntil { model.phase.value == ShareViewModel.Phase.Ready }
        transport.connectError = ShareConnectError()
        model.send()
        waitUntil { model.errorMessage.value != null }
        assertEquals(ShareViewModel.Phase.Ready, model.phase.value)
        assertEquals("Couldn't reach Matron's server. Check your connection and try again.", model.errorMessage.value)
        transport.connectError = null
        model.send()
        waitUntil { model.phase.value == ShareViewModel.Phase.Sent }
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun aNewConversationIsChosenOnSendAndTheShareGoesThere() = test(content(text = "Start here")) { model ->
        waitUntil { model.phase.value == ShareViewModel.Phase.Ready }
        model.selectNewConversation()
        model.send()
        waitUntil { model.isChoosingNewConversation.value }
        assertTrue(model.isChoosingNewConversation.value)
        assertEquals(1, transport.connects)
        assertTrue(transport.sent.isEmpty())
        model.newConversationStarted("fresh")
        waitUntil { model.phase.value == ShareViewModel.Phase.Sent }
        assertFalse(model.isChoosingNewConversation.value)
        assertEquals(listOf(FakeShareTransport.Sent("fresh", null, "Start here", null)), transport.sent)
        assertEquals(ShareViewModel.Destination.Conversation("fresh"), model.destination.value)
        assertEquals("fresh", model.targets.value.first { !it.isCoordinator }.id)
        // A retry goes into the one already started, never a second.
        assertFalse(model.offersNewConversation)
    }

    @Test
    fun cancellingTheChooserKeepsTheShare() = test(content(text = "x")) { model ->
        waitUntil { model.phase.value == ShareViewModel.Phase.Ready }
        model.selectNewConversation()
        model.send()
        waitUntil { model.isChoosingNewConversation.value }
        model.newConversationCancelled()
        assertFalse(model.isChoosingNewConversation.value)
        assertEquals(ShareViewModel.Phase.Ready, model.phase.value)
        assertEquals(ShareViewModel.Destination.NewConversation, model.destination.value)
    }

    @Test
    fun filesCanBeRemovedUntilPartOfTheShareHasGone() = test(content(uris = listOf(photo, photo))) { model ->
        waitUntil { model.phase.value == ShareViewModel.Phase.Ready }
        assertTrue(model.canRemoveFiles)
        val first = model.files.value.first()
        model.removeFile(first.id)
        assertFalse(first.file.exists())
        assertNull(model.files.value.firstOrNull { it.id == first.id })
        assertEquals(1, model.files.value.size)
    }

    @Test
    fun closingDeletesTheCopiesAndReleasesTheConnection() = test(content(uris = listOf(photo))) { model ->
        waitUntil { model.phase.value == ShareViewModel.Phase.Ready }
        model.select("c1")
        model.send()
        waitUntil { model.phase.value == ShareViewModel.Phase.Sent }
        model.close()
        assertEquals(1, transport.releases)
    }
}
