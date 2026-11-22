package chat.matron.android.viewmodels

import chat.matron.android.journal.ItemTapResult
import chat.matron.android.journal.approvals.ApprovalSubjects
import chat.matron.android.models.TrackerLink
import chat.matron.android.journal.ItemDetail
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.approvals.PromptDigest
import chat.matron.android.journal.approvals.SignedApproval
import chat.matron.android.journal.intOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.journal.stringOrNull
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemKind
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import chat.matron.android.platform.ApprovalKeyStore
import chat.matron.android.platform.SoftwareKeyProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StepUpTapTest {
    private val journal = FakeApprovalJournal()
    private val center = ApprovalCenter(
        journal, SoftwareKeyProvider(), ApprovalKeyStore(InMemoryKeyValueStore(), "journal.example", "alice"),
        "alice", 7, "Pixel", "journal.example",
    )
    private val sync = FakeItemsSync()

    private val cached = TrackerItem(
        id = "it_1", num = 7, kind = ItemKind.QUESTION, title = "Deploy box-a?", body = "Roll out the new build?",
        originConvoID = "c1", actions = listOf("Deploy", "Wait"),
    )
    private val question = TrackerComment(
        id = "cm_1", itemID = "it_1", author = ItemAuthor.AGENT, body = "Also restart the service?",
        actions = listOf("Yes", "No"), stepUp = true, subject = mapOf("op" to "restart"),
    )

    /// The journal's side: the item as it now stands, and the taps it took.
    private inner class Api(var fresh: TrackerItem, var comments: List<TrackerComment> = emptyList()) : FakeItemsApi() {
        val taps = mutableListOf<Triple<String, String?, SignedApproval>>()
        var fetches = 0
        var readError: Throwable? = null
        var tapError: Throwable? = null
        override suspend fun item(id: String): ItemDetail {
            fetches += 1
            readError?.let { throw it }
            return ItemDetail(fresh, comments)
        }
        val keys = mutableListOf<String>()
        override suspend fun tapItem(id: String, action: String, replyTo: String?, approval: SignedApproval, idempotencyKey: String): ItemTapResult {
            keys += idempotencyKey
            tapError?.let { throw it }
            taps += Triple(action, replyTo, approval)
            return ItemTapResult(fresh.copy(chosenAction = action), question.copy(id = "cm_tap"))
        }
    }

    private suspend fun withKey() {
        center.refresh()
        center.enrol("secret", "123456")
    }

    /// Runs [send] and confirms the sheet when it comes up.
    private suspend fun TestScope.sendConfirming(api: Api, comment: TrackerComment?, label: String, confirm: Boolean = true): StepUpTap.Outcome {
        val sending = async { StepUpTap.send(cached, comment, label, api, sync, center.signer, center.accountHasKey) }
        runCurrent()
        if (center.prompt.value != null) { if (confirm) center.confirm() else center.cancel() }
        return sending.await()
    }

    @Test
    fun anOrdinaryTapOnAnAccountWithoutAKeyGoesToTheOutboxUnread() = runTest {
        val api = Api(cached)
        assertEquals(StepUpTap.Outcome.Unsigned, StepUpTap.send(cached, null, "Deploy", api, sync, center.signer, accountHasKey = false))
        assertEquals(0, api.fetches)
    }

    @Test
    fun aStepUpTapIsSignedOverTheJournalsCurrentWords() = runTest {
        withKey()
        // The cache predates the mark; the fresh read has it, and new words.
        val fresh = cached.copy(stepUp = true, subject = mapOf("box" to "box-a"), body = "Roll out build 412?")
        val api = Api(fresh)
        assertEquals(StepUpTap.Outcome.Sent, sendConfirming(api, null, "Deploy"))
        val (action, replyTo, approval) = api.taps.single()
        assertEquals("Deploy", action)
        assertEquals(null, replyTo)
        val st = parseJsonObjectOrNull(approval.statement)!!
        assertEquals("item_tap", st.stringOrNull("type"))
        assertEquals("Deploy", st.stringOrNull("action"))
        assertEquals(PromptDigest.item("Deploy box-a?", "Roll out build 412?"), st.stringOrNull("prompt_digest"))
        val item = st.objectOrNull("item")!!
        assertEquals("it_1", item.stringOrNull("id"))
        assertEquals(7, item.intOrNull("num"))
        assertEquals(JsonNull, item["reply_to"])
        assertEquals("box-a", st.objectOrNull("subject")!!.stringOrNull("box"))
        assertTrue(FakeApprovalJournal.verifies(journal.keys.single().publicKey, approval))
        // The mark the read brought is kept, and the answer refetched.
        assertTrue(sync.applied.first().stepUp)
        assertEquals(listOf("it_1"), sync.refetched)
    }

    @Test
    fun aStepUpQuestionSignsItsOwnSubjectAndWords() = runTest {
        withKey()
        val api = Api(cached, listOf(question))
        assertEquals(StepUpTap.Outcome.Sent, sendConfirming(api, question, "Yes"))
        val st = parseJsonObjectOrNull(api.taps.single().third.statement)!!
        assertEquals("cm_1", st.objectOrNull("item")!!.stringOrNull("reply_to"))
        assertEquals(PromptDigest.comment("Also restart the service?"), st.stringOrNull("prompt_digest"))
        assertEquals("restart", st.objectOrNull("subject")!!.stringOrNull("op"))
        assertEquals("cm_1", api.taps.single().second)
    }

    @Test
    fun aMarkLoweredMeanwhileMakesItAnOrdinaryTap() = runTest {
        withKey()
        val api = Api(cached.copy(stepUp = false))
        assertEquals(StepUpTap.Outcome.Unsigned, StepUpTap.send(cached.copy(stepUp = true), null, "Deploy", api, sync, center.signer, true))
        assertTrue(api.taps.isEmpty())
    }

    @Test
    fun cancellingSendsNothing() = runTest {
        withKey()
        val api = Api(cached.copy(stepUp = true))
        assertEquals(StepUpTap.Outcome.Cancelled, sendConfirming(api, null, "Deploy", confirm = false))
        assertTrue(api.taps.isEmpty())
        // The thread is read again, so backing out leaves it as it stands.
        assertEquals(listOf("it_1"), sync.refetched)
    }

    @Test
    fun aQuestionChangedWhileSigningIsSaidSo() = runTest {
        withKey()
        val api = Api(cached.copy(stepUp = true)).apply { tapError = JournalApiError.ApprovalInvalid("mismatch") }
        val outcome = sendConfirming(api, null, "Deploy")
        assertEquals(StepUpTap.Outcome.Failed("The question changed while you were approving it. Read it again, then approve."), outcome)
        assertTrue("it_1" in sync.refetched)
    }

    @Test
    fun offlineAKnownStepUpTapCannotWaitButAnOrdinaryOneIsQueued() = runTest {
        withKey()
        val api = Api(cached).apply { readError = JournalApiError.Transport("offline") }
        assertEquals(StepUpTap.Outcome.Unsigned, StepUpTap.send(cached, null, "Deploy", api, sync, center.signer, true))
        val outcome = StepUpTap.send(cached.copy(stepUp = true), null, "Deploy", api, sync, center.signer, true)
        assertTrue(outcome is StepUpTap.Outcome.Failed)
    }

    @Test
    fun aRefusedReadStillQueuesAnOrdinaryTap() = runTest {
        withKey()
        val api = Api(cached).apply { readError = JournalApiError.Http(503, "busy") }
        assertEquals(StepUpTap.Outcome.Unsigned, StepUpTap.send(cached, null, "Deploy", api, sync, center.signer, true))
        assertTrue(StepUpTap.send(cached.copy(stepUp = true), null, "Deploy", api, sync, center.signer, true) is StepUpTap.Outcome.Failed)
    }

    @Test
    fun offlineAConsentDeclineIsQueuedUnsigned() = runTest {
        withKey()
        val consent = cached.copy(stepUp = true, labels = listOf(TrackerItem.CONSENT_LABEL), actions = listOf("Approve", "Decline"))
        val api = Api(consent).apply { readError = JournalApiError.Transport("offline") }
        assertEquals(StepUpTap.Outcome.Unsigned, StepUpTap.send(consent, null, "Decline", api, sync, center.signer, true))
        assertTrue(StepUpTap.send(consent, null, "Approve", api, sync, center.signer, true) is StepUpTap.Outcome.Failed)
    }

    @Test
    fun aStepUpItemOnAnAccountWithNoKeyGoesUnsigned() = runTest {
        center.refresh()
        val api = Api(cached.copy(stepUp = true))
        assertEquals(StepUpTap.Outcome.Unsigned, StepUpTap.send(cached.copy(stepUp = true), null, "Deploy", api, sync, center.signer, false))
        assertTrue(api.taps.isEmpty())
    }

    @Test
    fun anAccountWithAKeyButNoneHereIsToldWhy() = runTest {
        journal.keys += chat.matron.android.journal.approvals.ApprovalKeyDTO("ab".repeat(32), "", "Tablet", 99)
        center.refresh()
        val api = Api(cached.copy(stepUp = true))
        val outcome = StepUpTap.send(cached, null, "Deploy", api, sync, center.signer, center.accountHasKey)
        assertTrue((outcome as StepUpTap.Outcome.Failed).message.startsWith("This needs an approval key"))
    }

    private val task = "Run the test suite and report.\nThen ```wait``` for me."
    private fun spawnAsk(taskInBody: String = task) = cached.copy(
        consentKind = "spawn", stepUp = true, actions = listOf("Approve", "Decline"), labels = listOf(TrackerItem.CONSENT_LABEL),
        title = "Approve spawn on box-a — tests",
        body = "**box-b** asks to start a new agent session on **box-a**.\n\n**Task the new session will be given, verbatim:**\n\n" +
            "````\n$taskInBody\n````\n\n**To answer:** tap **Approve** or **Decline** here.",
        links = listOf(TrackerLink("matron://consent/spawn/sp_1", "Spawn request sp_1")),
        subject = mapOf(
            "op" to "spawn", "ask" to "sp_1", "box" to "box-a", "workdir" to "/srv/app",
            "task_sha256" to ApprovalSubjects.sha256(task), "decision" to "approve",
        ),
    )

    @Test
    fun approvingAProtectedSpawnSignsAConsentStatementOverTheTaskShown() = runTest {
        withKey()
        val ask = spawnAsk()
        val api = Api(ask)
        val sending = async { StepUpTap.send(ask, null, "Approve", api, sync, center.signer, true) }
        runCurrent()
        val prompt = center.prompt.value!!
        // The task is shown in full, and it is the very text the subject hashes.
        assertEquals(task, prompt.request.detail)
        center.confirm()
        assertEquals(StepUpTap.Outcome.Sent, sending.await())
        val st = parseJsonObjectOrNull(api.taps.single().third.statement)!!
        assertEquals("consent", st.stringOrNull("type"))
        assertEquals(null, st["item"])
        assertEquals(ask.subject, st.objectOrNull("subject")!!.mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun aConsentAskWhoseTextDoesNotMatchIsNeverSigned() = runTest {
        withKey()
        // The body's task is not the one the subject hashes.
        val swapped = spawnAsk(taskInBody = "Delete everything.")
        assertTrue((StepUpTap.send(swapped, null, "Approve", Api(swapped), sync, center.signer, true) as StepUpTap.Outcome.Failed)
            .message.startsWith("The task on this request is not"))
        // A subject about another request.
        val other = spawnAsk().let { it.copy(subject = it.subject!! + ("ask" to "sp_2")) }
        assertEquals(StepUpTap.Outcome.Failed(StepUpTap.RELOAD_REQUEST), StepUpTap.send(other, null, "Approve", Api(other), sync, center.signer, true))
        // Decline is never signed.
        val api = Api(spawnAsk())
        assertEquals(StepUpTap.Outcome.Unsigned, StepUpTap.send(spawnAsk(), null, "Decline", api, sync, center.signer, true))
        assertTrue(api.taps.isEmpty())
        assertNull(center.prompt.value)
    }

    @Test
    fun aLostAnswerSaysItMayHaveBeenSentAndTheRetryReusesTheKey() = runTest {
        withKey()
        val api = Api(cached.copy(stepUp = true)).apply { tapError = JournalApiError.Transport("reset") }
        assertEquals(StepUpTap.Outcome.Failed(StepUpTap.MAY_HAVE_BEEN_SENT), sendConfirming(api, null, "Deploy"))
        assertTrue("it_1" in sync.refetched)
        api.tapError = null
        assertEquals(StepUpTap.Outcome.Sent, sendConfirming(api, null, "Deploy"))
        assertEquals(2, api.keys.size)
        assertEquals(api.keys[0], api.keys[1])
        // A later, new tap gets a new key.
        assertEquals(StepUpTap.Outcome.Sent, sendConfirming(api, null, "Deploy"))
        assertTrue(api.keys[2] != api.keys[0])
        // A server error says nothing about whether it landed: the key stays.
        api.tapError = JournalApiError.Http(503, "busy")
        assertTrue(sendConfirming(api, null, "Deploy") is StepUpTap.Outcome.Failed)
        api.tapError = null
        assertEquals(StepUpTap.Outcome.Sent, sendConfirming(api, null, "Deploy"))
        assertEquals(api.keys[3], api.keys[4])
        // A refused tap leaves no key behind: the next try is a new tap.
        api.tapError = JournalApiError.ApprovalInvalid("mismatch")
        assertTrue(sendConfirming(api, null, "Deploy") is StepUpTap.Outcome.Failed)
        api.tapError = null
        assertEquals(StepUpTap.Outcome.Sent, sendConfirming(api, null, "Deploy"))
        assertTrue(api.keys[6] != api.keys[5])
    }

    @Test
    fun verifiedTextFindsOnlyAnExactFencedBlock() {
        val body = "intro\n```\nnot it\n```\n\n`````\nline one\n````\nstill inside\n`````\nend"
        assertEquals("line one\n````\nstill inside", StepUpTap.verifiedText(body, ApprovalSubjects.sha256("line one\n````\nstill inside")))
        assertNull(StepUpTap.verifiedText(body, ApprovalSubjects.sha256("line one")))
        assertEquals("", StepUpTap.verifiedText("```\n\n```", ApprovalSubjects.sha256("")))
    }

    @Test
    fun stepUpAndSubjectAreRead() {
        val item = TrackerItem.fromJson(
            parseJsonObjectOrNull(
                """{"id":"it_1","num":7,"kind":"question","state":"open","title":"T","origin_convo_id":"c1",
                |"created_at":1,"updated_at":1,"step_up":true,"subject":{"box":"box-a"}}""".trimMargin(),
            )!!,
        )!!
        assertTrue(item.stepUp)
        assertEquals(mapOf("box" to "box-a"), item.subject)
        val comment = TrackerComment.fromJson(
            parseJsonObjectOrNull(
                """{"id":"cm_1","item_id":"it_1","author":"agent","kind":"comment","created_at":1,"body":"Q",
                |"actions":["Yes"],"step_up":true,"subject":{"op":"x"}}""".trimMargin(),
            )!!,
        )!!
        assertTrue(comment.stepUp)
        assertEquals(mapOf("op" to "x"), comment.subject)
        // A subject that is not a flat object of strings is not one.
        assertEquals(null, TrackerItem.stringMapOrNull(parseJsonObjectOrNull("""{"n":1}""")))
    }
}
