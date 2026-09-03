package chat.matron.android.journal

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class JournalApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(path: String = "/", token: String? = null): JournalApi =
        JournalApi(server.url(path).toString(), token = token)

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun loginSuccessStoresToken() = runBlocking {
        server.enqueue(json(200, """{"token":"aabb","device_id":12,"user_id":3}"""))
        server.enqueue(json(200, """{"conversations":[],"seq":0}"""))
        val api = api()
        val login = api.login("dan", "pw", "mac")
        assertEquals("aabb", login.token)
        assertEquals(12L, login.deviceID)
        server.takeRequest() // login
        api.snapshot()
        assertEquals("Bearer aabb", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun loginErrors() = runBlocking {
        server.enqueue(json(403, """{"error":"bad_credentials"}"""))
        try {
            api().login("dan", "x", "mac"); fail("expected throw")
        } catch (e: JournalApiError) {
            assertEquals(JournalApiError.BadCredentials, e)
        }

        server.enqueue(json(429, """{"error":"locked_out","retry_after":60}"""))
        try {
            api().login("dan", "x", "mac"); fail("expected throw")
        } catch (e: JournalApiError) {
            assertEquals(JournalApiError.LockedOut(60), e)
        }
    }

    @Test
    fun snapshotParsesConversations() = runBlocking {
        server.enqueue(json(200, """{"conversations":[{"id":"c1","title":"T","session_state":"waiting","last_seq":9,"unread_count":2,"snippet":"s","created_at":5,"last_ts":7000}],"seq":9}"""))
        val snap = api(token = "t").snapshot()
        assertEquals(9L, snap.seq)
        assertEquals(
            listOf(ConvoSummaryDTO("c1", "T", "waiting", 9, "s", 5, 7000)),
            snap.conversations,
        )
    }

    @Test
    fun snapshotParsesParentConvoID() = runBlocking {
        server.enqueue(json(200, """{"conversations":[{"id":"p1","title":"Parent","session_state":"running","last_seq":5,"snippet":"","created_at":1,"parent_convo_id":null},{"id":"p1:sub:a1","title":"child","session_state":"done","last_seq":6,"snippet":"","created_at":2,"parent_convo_id":"p1"}],"seq":6}"""))
        val snap = api(token = "t").snapshot()
        assertNull(snap.conversations.first { it.id == "p1" }.parentConvoID)
        assertEquals("p1", snap.conversations.first { it.id == "p1:sub:a1" }.parentConvoID)
    }

    @Test
    fun snapshotToleratesMissingParentConvoID() = runBlocking {
        server.enqueue(json(200, """{"conversations":[{"id":"c1","title":"T","session_state":"running","last_seq":1,"snippet":"","created_at":0}],"seq":1}"""))
        assertNull(api(token = "t").snapshot().conversations.first().parentConvoID)
    }

    /// Ports the snapshot-parsing slice of matron-apple #131: each convo row
    /// carries its owning box, and a top-level `agents` list resolves id →
    /// name. Presence-aware (diverging from the Swift original): an ABSENT
    /// field (an older server) decodes to null so the store keeps its roster,
    /// while a PRESENT-but-empty list decodes to an empty list so revoking
    /// the last box clears stale chips.
    @Test
    fun snapshotParsesAgentDeviceIDAndAgentsList() = runBlocking {
        server.enqueue(
            json(
                200,
                """{"conversations":[{"id":"c1","title":"T","session_state":"running","last_seq":1,"snippet":"","created_at":0,"agent_device_id":7}],""" +
                    """"agents":[{"device_id":7,"name":"dev-y"},{"device_id":9,"name":"dev-z"},{"name":"no-id-skipped"}],"seq":1}""",
            )
        )
        val snap = api(token = "t").snapshot()
        assertEquals(7L, snap.conversations.first().agentDeviceID)
        assertEquals(listOf(AgentDTO(7, "dev-y", tagCharKnown = false), AgentDTO(9, "dev-z", tagCharKnown = false)), snap.agents)

        server.enqueue(json(200, """{"conversations":[{"id":"c1","title":"T","session_state":"running","last_seq":1,"snippet":"","created_at":0}],"seq":1}"""))
        val old = api(token = "t").snapshot()
        assertNull(old.conversations.first().agentDeviceID)
        assertNull("absent agents field must decode to null, not empty", old.agents)

        server.enqueue(json(200, """{"conversations":[],"agents":[],"seq":1}"""))
        assertEquals(emptyList<AgentDTO>(), api(token = "t").snapshot().agents)
    }

    /// Ports the snapshot-parsing slice of matron-apple #151: a multi-agent
    /// room row carries its `participants` membership; absent (a solo
    /// conversation or an older server) degrades to null — untouched store.
    @Test
    fun snapshotParsesParticipants() = runBlocking {
        server.enqueue(
            json(
                200,
                """{"conversations":[{"id":"room","title":"T","session_state":"waiting","last_seq":1,"snippet":"","created_at":0,"agent_device_id":7,"participants":[7,9]},""" +
                    """{"id":"solo","title":"S","session_state":"running","last_seq":1,"snippet":"","created_at":0}],"seq":1}""",
            )
        )
        val snap = api(token = "t").snapshot()
        assertEquals(listOf(7L, 9L), snap.conversations.first { it.id == "room" }.participants)
        assertNull(snap.conversations.first { it.id == "solo" }.participants)
    }

    @Test
    fun snapshotToleratesMissingLastTS() = runBlocking {
        server.enqueue(json(200, """{"conversations":[{"id":"c1","title":"T","session_state":"waiting","last_seq":9,"unread_count":2,"snippet":"s","created_at":5}],"seq":9}"""))
        assertNull(api(token = "t").snapshot().conversations.first().lastTS)
    }

    @Test
    fun messagesBuildsQueryAndParsesEvents() = runBlocking {
        server.enqueue(json(200, """{"events":[{"seq":8,"convo_id":"c1","ts":8000,"sender":"agent:a","type":"text","payload":{"body":"m8"}}]}"""))
        val events = api(token = "t").messages("c1", 9, 30)
        assertEquals(listOf(8L), events.map { it.seq })
        val query = server.takeRequest().requestUrl?.query ?: ""
        assertTrue(query.contains("before_seq=9"))
        assertTrue(query.contains("limit=30"))
    }

    @Test
    fun unauthenticatedMapsToError() = runBlocking {
        server.enqueue(json(401, """{"error":"unauthenticated"}"""))
        try {
            api().snapshot(); fail("expected throw")
        } catch (e: JournalApiError) {
            assertEquals(JournalApiError.Unauthenticated, e)
        }
    }

    @Test
    fun tokenPassedAtInitIsUsedWithoutSetToken() = runBlocking {
        server.enqueue(json(200, """{"conversations":[],"seq":0}"""))
        api(token = "t0").snapshot()
        assertEquals("Bearer t0", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun wsUrl() {
        assertEquals("wss://chat.example.com/ws",
            JournalApi("https://chat.example.com").wsUrl)
    }

    @Test
    fun messagesEscapesConvoIDSegment() = runBlocking {
        server.enqueue(json(200, """{"events":[]}"""))
        api(token = "t").messages("c 1/x", null, 10)
        val path = server.takeRequest().path ?: ""
        assertTrue(path.contains("/convo/c%201%2Fx/messages"))
    }

    @Test
    fun registerPushPostsTokenAndEnvironment() = runBlocking {
        server.enqueue(json(200, """{"ok":true}"""))
        api(token = "t").registerPush("aabbcc", JournalApi.PushEnvironment.SANDBOX)
        val req = server.takeRequest()
        assertEquals("/push/register", req.path)
        assertEquals("POST", req.method)
        val obj = parseJsonObjectOrNull(req.body.readUtf8())!!
        assertEquals("aabbcc", obj.stringOrNull("apns_token"))
        assertEquals("sandbox", obj.stringOrNull("environment"))
    }

    @Test
    fun unregisterPushSendsNullToken() = runBlocking {
        server.enqueue(json(200, """{"ok":true}"""))
        api(token = "t").unregisterPush()
        val obj = parseJsonObjectOrNull(server.takeRequest().body.readUtf8())!!
        assertTrue(obj["apns_token"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun mediaDataReturnsBytes() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("PNGDATA"))
        val data = api(token = "t").mediaData("b1")
        assertEquals("PNGDATA", String(data, Charsets.UTF_8))
    }

    @Test
    fun uploadMediaPostsRawBytesAndReturnsMediaID() = runBlocking {
        server.enqueue(json(200, """{"media_id":"m-123","size":3,"content_type":"image/png","sha256":"ab"}"""))
        val mediaID = api(token = "t").uploadMedia("PNG".toByteArray(), "image/png")
        assertEquals("m-123", mediaID)
        val req = server.takeRequest()
        assertEquals("/media", req.path)
        assertEquals("POST", req.method)
        assertEquals("image/png", req.getHeader("Content-Type"))
        assertEquals("Bearer t", req.getHeader("Authorization"))
        assertEquals("PNG", req.body.readUtf8())
    }

    @Test
    fun uploadMediaMapsErrorStatus() = runBlocking {
        server.enqueue(json(401, """{"error":"unauthenticated"}"""))
        try {
            api(token = "t").uploadMedia("x".toByteArray(), "application/octet-stream"); fail("expected throw")
        } catch (e: JournalApiError) {
            assertEquals(JournalApiError.Unauthenticated, e)
        }
    }

    @Test
    fun devicesDecodesRosterIncludingNulls() = runBlocking {
        server.enqueue(json(200, """{"devices":[{"device_id":7,"kind":"client","name":"dan-mac","created_at":1784000000000,"cursor":5123,"lag":0,"last_seen_at":1784500000000,"is_self":true,"connected":true},{"device_id":9,"kind":"agent","name":"dev-7","created_at":1784100000000,"cursor":5000,"lag":123,"last_seen_at":null,"is_self":false}]}"""))
        val devices = api(token = "t").devices()
        assertEquals(2, devices.size)
        assertEquals(
            DeviceDTO(7, "client", "dan-mac", 1_784_000_000_000, 5123, 0, 1_784_500_000_000, true, true),
            devices[0],
        )
        assertNull(devices[1].lastSeenAt)
        assertFalse(devices[1].isSelf)
        assertFalse(devices[1].connected)
        assertEquals("Bearer t", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun revokeDevicePostsToScopedPath() = runBlocking {
        server.enqueue(json(200, """{"ok":true}"""))
        api(token = "t").revokeDevice(7)
        val req = server.takeRequest()
        assertEquals("/devices/7/revoke", req.path)
        assertEquals("POST", req.method)
    }

    /// Ports the `renameDevice` slice of matron-apple #131: POST to the
    /// scoped rename path with the name as body, parsing the partial device
    /// echo (identity + new name only — callers re-fetch the roster).
    @Test
    fun renameDevicePostsAndParses() = runBlocking {
        server.enqueue(json(200, """{"ok":true,"device":{"device_id":7,"name":"dev-y"}}"""))
        val renamed = api(token = "t").renameDevice(7, "dev-y")
        val req = server.takeRequest()
        assertEquals("/devices/7/rename", req.path)
        assertEquals("POST", req.method)
        assertEquals("""{"name":"dev-y"}""", req.body.readUtf8())
        assertEquals(7L, renamed.id)
        assertEquals("dev-y", renamed.name)

        // A malformed echo (no device object) throws rather than fabricating.
        server.enqueue(json(200, """{"ok":true}"""))
        try {
            api(token = "t").renameDevice(7, "dev-y"); fail("expected throw")
        } catch (e: JournalApiError) {
            assertTrue(e is JournalApiError.Transport)
        }

        // So does an echo naming a different device than the one renamed.
        server.enqueue(json(200, """{"ok":true,"device":{"device_id":8,"name":"dev-y"}}"""))
        try {
            api(token = "t").renameDevice(7, "dev-y"); fail("expected throw")
        } catch (e: JournalApiError) {
            assertTrue(e is JournalApiError.Transport)
        }
    }

    @Test
    fun revokeDeviceMapsNotFound() = runBlocking {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        try {
            api(token = "t").revokeDevice(9); fail("expected throw")
        } catch (e: JournalApiError) {
            assertEquals(JournalApiError.NotFound, e)
        }
    }

    @Test
    fun pairPreviewSendsCodeAndDecodes() = runBlocking {
        server.enqueue(json(200, """{"requester_ip":"65.108.10.252","expires_in":412}"""))
        val preview = api(token = "t").pairPreview("KTNM3VQ8")
        assertEquals(PairPreview("65.108.10.252", 412), preview)
        val obj = parseJsonObjectOrNull(server.takeRequest().body.readUtf8())!!
        assertEquals("KTNM3VQ8", obj.stringOrNull("pair_code"))
    }

    @Test
    fun pairApproveSendsCodeAndName() = runBlocking {
        server.enqueue(json(200, """{"status":"approved"}"""))
        api(token = "t").pairApprove("KTNM3VQ8", "dev-7")
        val obj = parseJsonObjectOrNull(server.takeRequest().body.readUtf8())!!
        assertEquals("KTNM3VQ8", obj.stringOrNull("pair_code"))
        assertEquals("dev-7", obj.stringOrNull("agent_name"))
    }

    @Test
    fun pairApproveMapsConflict() = runBlocking {
        server.enqueue(json(409, """{"error":"conflict"}"""))
        try {
            api(token = "t").pairApprove("KTNM3VQ8", "dev-7"); fail("expected throw")
        } catch (e: JournalApiError) {
            assertEquals(JournalApiError.Conflict, e)
        }
    }

    @Test
    fun answerAgentSpawnPostsExactBody() = runBlocking {
        server.enqueue(json(200, """{"ok":true}"""))
        api(token = "t").answerAgentSpawn("spawn-1", AgentSpawnDecision.APPROVE)
        val req = server.takeRequest()
        assertEquals("/agent-spawn/answer", req.path)
        assertEquals("POST", req.method)
        val obj = parseJsonObjectOrNull(req.body.readUtf8())!!
        // Exact key set: no `always_allow`, no stray fields.
        assertEquals(setOf("request_id", "decision"), obj.keys)
        assertEquals("spawn-1", obj.stringOrNull("request_id"))
        assertEquals("approve", obj.stringOrNull("decision"))
    }

    @Test
    fun answerAgentSpawnSendsDenyWireValue() = runBlocking {
        server.enqueue(json(200, """{"ok":true}"""))
        api(token = "t").answerAgentSpawn("spawn-2", AgentSpawnDecision.DENY)
        val obj = parseJsonObjectOrNull(server.takeRequest().body.readUtf8())!!
        assertEquals("deny", obj.stringOrNull("decision"))
    }

    @Test
    fun answerAgentSpawnMapsConflict() = runBlocking {
        server.enqueue(json(409, """{"error":"conflict"}"""))
        try {
            api(token = "t").answerAgentSpawn("spawn-1", AgentSpawnDecision.APPROVE); fail("expected throw")
        } catch (e: JournalApiError) {
            assertEquals(JournalApiError.Conflict, e)
        }
    }

    @Test
    fun answerAgentSpawnMapsNotFound() = runBlocking {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        try {
            api(token = "t").answerAgentSpawn("spawn-1", AgentSpawnDecision.APPROVE); fail("expected throw")
        } catch (e: JournalApiError) {
            assertEquals(JournalApiError.NotFound, e)
        }
    }

    @Test
    fun serverPathPrefixIsPreservedOnRequests() = runBlocking {
        server.enqueue(json(200, """{"conversations":[],"seq":0}"""))
        JournalApi(server.url("/matron").toString(), token = "t").snapshot()
        assertEquals("/matron/snapshot", server.takeRequest().path)
    }

    @Test
    fun serverPathPrefixTrailingSlashNormalized() = runBlocking {
        server.enqueue(json(200, """{"conversations":[],"seq":0}"""))
        JournalApi(server.url("/matron/").toString(), token = "t").snapshot()
        assertEquals("/matron/snapshot", server.takeRequest().path)
    }

    @Test
    fun wsUrlKeepsPathPrefix() {
        assertEquals("wss://chat.example.com/matron/ws",
            JournalApi("https://chat.example.com/matron").wsUrl)
        assertEquals("ws://localhost:8787/ws",
            JournalApi("http://localhost:8787").wsUrl)
    }

    @Test
    fun linkStartParsesResponseAndSendsBearer() = runBlocking {
        server.enqueue(json(200, """{"link_code":"KTNM-3VQ8","expires_in":120}"""))
        val started = api(token = "tok").linkStart()
        assertEquals(LinkStart("KTNM-3VQ8", 120), started)
        val request = server.takeRequest()
        assertEquals("/link/start", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
    }

    @Test
    fun linkStatusWaitingAndClaimed() = runBlocking {
        server.enqueue(json(200, """{"status":"waiting","expires_in":90}"""))
        assertEquals(LinkStatus.Waiting(90), api(token = "tok").linkStatus())
        server.enqueue(json(200,
            """{"status":"claimed","device_name":"Pixel 9","requester_ip":"198.51.100.7","expires_in":55}"""))
        assertEquals(LinkStatus.Claimed("Pixel 9", "198.51.100.7", 55), api(token = "tok").linkStatus())
    }

    @Test
    fun linkApproveAndDenySendCode() = runBlocking {
        server.enqueue(json(200, """{"status":"approved"}"""))
        api(token = "tok").linkApprove("KTNM-3VQ8")
        assertTrue(server.takeRequest().body.readUtf8().contains(""""link_code":"KTNM-3VQ8""""))
        server.enqueue(json(200, """{"status":"denied"}"""))
        api(token = "tok").linkDeny("KTNM-3VQ8")
        assertTrue(server.takeRequest().body.readUtf8().contains(""""link_code":"KTNM-3VQ8""""))
    }

    @Test
    fun linkClaimSendsBodyUnauthenticatedAndParses() = runBlocking {
        server.enqueue(json(200, """{"status":"claimed","claim_token":"aa11","expires_in":60}"""))
        // token set but must NOT be sent: claim is the unauthenticated side
        val claim = api(token = "tok").linkClaim("KTNM-3VQ8", "Matron Android")
        assertEquals(LinkClaim("aa11", 60), claim)
        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""link_code":"KTNM-3VQ8""""))
        assertTrue(body.contains(""""device_name":"Matron Android""""))
    }

    @Test
    fun linkPollPendingDeniedApproved() = runBlocking {
        server.enqueue(json(200, """{"status":"pending"}"""))
        assertEquals(LinkPollResult.Pending, api().linkPoll("aa11"))
        server.enqueue(json(200, """{"status":"denied"}"""))
        assertEquals(LinkPollResult.Denied, api().linkPoll("aa11"))
        server.enqueue(json(200,
            """{"status":"approved","token":"bb22","device_id":42,"user_id":7,"username":"dan"}"""))
        assertEquals(LinkPollResult.Approved(LinkApproval("bb22", 42, 7, "dan")), api().linkPoll("aa11"))
    }

    @Test
    fun linkPollApprovedWithoutUsernameIsMalformed() = runBlocking {
        // username is load-bearing (it becomes UserSession.userID) — a server
        // that omits it must fail loudly, not sign in with a garbage identity.
        server.enqueue(json(200, """{"status":"approved","token":"bb22","device_id":42,"user_id":7}"""))
        try {
            api().linkPoll("aa11"); fail("expected throw")
        } catch (e: JournalApiError.Transport) { /* expected */ }
        Unit
    }

    // MARK: - journal-held tag characters (apple #158)

    @Test
    fun setDeviceTagSendsValueAndNullForClear() = runBlocking {
        server.enqueue(json(200, """{"ok":true}"""))
        api(token = "t").setDeviceTag(7, "Q")
        val set = server.takeRequest()
        assertEquals("/devices/7/tag", set.path)
        assertEquals("POST", set.method)
        assertEquals("""{"tag_char":"Q"}""", set.body.readUtf8())

        server.enqueue(json(200, """{"ok":true}"""))
        api(token = "t").setDeviceTag(7, null)
        assertEquals("""{"tag_char":null}""", server.takeRequest().body.readUtf8())
    }

    /// The tag rides `pair/approve` only when given — omitted entirely, not
    /// sent as null, so an older server sees the request it always did.
    @Test
    fun pairApproveCarriesTagOnlyWhenGiven() = runBlocking {
        server.enqueue(json(200, """{"ok":true}"""))
        api(token = "t").pairApprove("1234-5678", "dev-a", tagChar = "a")
        assertEquals("""{"pair_code":"1234-5678","agent_name":"dev-a","tag_char":"a"}""", server.takeRequest().body.readUtf8())
        server.enqueue(json(200, """{"ok":true}"""))
        api(token = "t").pairApprove("1234-5678", "dev-a")
        assertEquals("""{"pair_code":"1234-5678","agent_name":"dev-a"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun devicesParseTagChar() = runBlocking {
        server.enqueue(json(200, """{"devices":[{"device_id":1,"kind":"agent","name":"dev-y","tag_char":"Q"},{"device_id":2,"kind":"agent","name":"dev-z"}]}"""))
        val devices = api(token = "t").devices()
        assertEquals("Q", devices[0].tagChar)
        assertNull(devices[1].tagChar)
    }

    /// Snapshot `agents[]`: an absent key is a server predating tags
    /// (unknown), an explicit null is an authoritative clear.
    @Test
    fun snapshotAgentsDistinguishAbsentTagCharFromNull() = runBlocking {
        server.enqueue(json(200, """{"seq":1,"conversations":[],"agents":[{"device_id":1,"name":"a","tag_char":"Q"},{"device_id":2,"name":"b","tag_char":null},{"device_id":3,"name":"c"}]}"""))
        val agents = api(token = "t").snapshot().agents!!
        assertEquals(AgentDTO(1, "a", tagChar = "Q", tagCharKnown = true), agents[0])
        assertEquals(AgentDTO(2, "b", tagChar = null, tagCharKnown = true), agents[1])
        assertEquals(AgentDTO(3, "c", tagChar = null, tagCharKnown = false), agents[2])
    }
}
