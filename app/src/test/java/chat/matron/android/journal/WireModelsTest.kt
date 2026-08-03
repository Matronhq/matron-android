package chat.matron.android.journal

import chat.matron.android.models.SessionStatus
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireModelsTest {
    private fun encodedObject(op: ClientOp): JsonObject =
        parseJsonObjectOrNull(op.encoded())!!

    @Test
    fun decodeJournalFrame() {
        val text = """{"kind":"journal","seq":43,"convo_id":"c-abc","ts":1752200000000,"sender":"user:dan","type":"text","payload":{"body":"hi"}}"""
        val frame = ServerFrame.decode(text)
        assertTrue(frame is ServerFrame.Journal)
        val event = (frame as ServerFrame.Journal).event
        assertEquals(43L, event.seq)
        assertEquals("c-abc", event.convoID)
        assertEquals("user:dan", event.sender)
        assertEquals("text", event.type)
        assertEquals(Instant.ofEpochMilli(1_752_200_000_000), event.ts)
        assertEquals("hi", event.payload.stringOrNull("body"))
    }

    @Test
    fun decodeControlAndEphemeralFrames() {
        val hello = ServerFrame.decode("""{"kind":"control","op":"hello_ok","seq":42}""")
        assertEquals(ServerFrame.HelloOK(42), hello)

        val error = ServerFrame.decode("""{"kind":"control","op":"error","code":"forbidden","ref":"send"}""")
        assertTrue(error is ServerFrame.Error)
        error as ServerFrame.Error
        assertEquals("forbidden", error.code)
        assertEquals("send", error.ref)

        assertEquals(ServerFrame.SnapshotRequired, ServerFrame.decode("""{"kind":"control","op":"snapshot_required"}"""))

        val ephemeral = ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","message_ref":"m7","replace_text":"progress 3"}""")
        assertTrue(ephemeral is ServerFrame.Ephemeral)
        val update = (ephemeral as ServerFrame.Ephemeral).update
        assertEquals("m7", update.messageRef)
        assertEquals(EphemeralUpdate.Change.Replace("progress 3"), update.change)
    }

    @Test
    fun decodeActivityEphemeralFrames() {
        val thinking = ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","activity":{"state":"thinking"}}""")
        assertTrue(thinking is ServerFrame.Activity)
        (thinking as ServerFrame.Activity).update.let {
            assertEquals("c1", it.convoID)
            assertEquals(ActivityUpdate.State.THINKING, it.state)
            assertNull(it.detail)
        }

        val tool = ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","activity":{"state":"tool","detail":"Bash"}}""")
        (tool as ServerFrame.Activity).update.let {
            assertEquals(ActivityUpdate.State.TOOL, it.state)
            assertEquals("Bash", it.detail)
        }

        val idle = ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","activity":{"state":"idle"}}""")
        assertEquals(ActivityUpdate.State.IDLE, (idle as ServerFrame.Activity).update.state)

        assertNull(ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","activity":{"state":"dancing"}}"""))
    }

    @Test
    fun decodeToolStreamAppendFrame() {
        val frame = ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","message_ref":"tu1","tool_stream":{"event":"append","offset":7,"chunk":"hello\n"}}""")
        assertEquals(
            ServerFrame.ToolStream(ToolStreamUpdate("c1", "tu1", ToolStreamUpdate.Event.Append(7, "hello\n"))),
            frame,
        )
    }

    @Test
    fun decodeToolStreamSyncFrame() {
        val frame = ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","message_ref":"tu1","tool_stream":{"event":"sync","meta":{"tool":"Bash","command":"make"},"offset":0,"content":"$ make\n","head_truncated":false}}""")
        assertEquals(
            ServerFrame.ToolStream(ToolStreamUpdate("c1", "tu1",
                ToolStreamUpdate.Event.Sync("Bash", "make", 0, "$ make\n", false))),
            frame,
        )
    }

    @Test
    fun decodeToolStreamSyncWithoutMetaAndTruncatedHead() {
        val frame = ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","message_ref":"tu1","tool_stream":{"event":"sync","offset":512,"content":"tail","head_truncated":true}}""")
        assertEquals(
            ServerFrame.ToolStream(ToolStreamUpdate("c1", "tu1",
                ToolStreamUpdate.Event.Sync(null, null, 512, "tail", true))),
            frame,
        )
    }

    @Test
    fun decodeToolStreamEndFrame() {
        val frame = ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","message_ref":"tu1","tool_stream":{"event":"end","reason":"stale"}}""")
        assertEquals(
            ServerFrame.ToolStream(ToolStreamUpdate("c1", "tu1", ToolStreamUpdate.Event.End("stale"))),
            frame,
        )
    }

    @Test
    fun decodeToolStreamUnknownEventSkipsFrame() {
        assertNull(ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","message_ref":"tu1","tool_stream":{"event":"wat"}}"""))
    }

    @Test
    fun toolStreamFrameDoesNotDecodeAsEmptyTextEphemeral() {
        val frame = ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","message_ref":"tu1","tool_stream":{"event":"append","offset":0,"chunk":"x"}}""")
        assertFalse(frame is ServerFrame.Ephemeral)
    }

    @Test
    fun streamEphemeralStillRequiresMessageRef() {
        assertNull(ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","text":"hi"}"""))
    }

    @Test
    fun decodeGarbageReturnsNull() {
        assertNull(ServerFrame.decode("not json"))
        assertNull(ServerFrame.decode("""{"kind":"journal","seq":"nope"}"""))
    }

    @Test
    fun encodeClientOps() {
        val hello = encodedObject(ClientOp.Hello("t", 5))
        assertEquals("hello", hello.stringOrNull("op"))
        assertEquals(5L, hello.longOrNull("cursor"))

        val send = encodedObject(ClientOp.Send("c1", "hi", "L1"))
        assertEquals("send", send.stringOrNull("op"))
        assertEquals("text", send.stringOrNull("type"))
        assertEquals("hi", send.objectOrNull("payload")?.stringOrNull("body"))
        assertEquals("L1", send.stringOrNull("local_id"))

        val media = encodedObject(ClientOp.SendMedia("c1", MediaKind.IMAGE, "b9", "cat.png", "image/png", 42, null, "L2"))
        assertEquals("send", media.stringOrNull("op"))
        assertEquals("image", media.stringOrNull("type"))
        assertEquals("b9", media.stringOrNull("blob_ref"))
        assertEquals("L2", media.stringOrNull("local_id"))
        val mediaPayload = media.objectOrNull("payload")
        assertEquals("b9", mediaPayload?.stringOrNull("blob_ref"))
        assertEquals("cat.png", mediaPayload?.stringOrNull("name"))
        assertEquals("image/png", mediaPayload?.stringOrNull("content_type"))
        assertEquals(42, mediaPayload?.intOrNull("size"))
        assertNull(mediaPayload?.stringOrNull("caption"))

        val reply = encodedObject(ClientOp.PromptReply("c1", 40, "yes", null))
        assertEquals(40L, reply.longOrNull("target_seq"))
        assertEquals("yes", reply.stringOrNull("choice"))
        assertTrue(reply["text"] is kotlinx.serialization.json.JsonNull)

        val viewingNil = encodedObject(ClientOp.Viewing(null))
        assertTrue(viewingNil["convo_id"] is kotlinx.serialization.json.JsonNull)

        val ack = encodedObject(ClientOp.Ack(42))
        assertEquals(42L, ack.longOrNull("cursor"))

        val marker = encodedObject(ClientOp.ReadMarker("c1", 40))
        assertEquals("read_marker", marker.stringOrNull("op"))
        assertEquals(40L, marker.longOrNull("up_to_seq"))
    }

    @Test
    fun encodeSendMediaCarriesCaptionInsideThePayload() {
        val media = encodedObject(ClientOp.SendMedia("c1", MediaKind.IMAGE, "b9", "cat.png", "image/png", 42,
            "what breed is this?", "L2"))
        assertEquals("what breed is this?", media.objectOrNull("payload")?.stringOrNull("caption"))
    }

    @Test
    fun encodeSendMediaTreatsEmptyCaptionAsAbsent() {
        val media = encodedObject(ClientOp.SendMedia("c1", MediaKind.IMAGE, "b9", "cat.png", "image/png", 42, "", "L2"))
        assertNull(media.objectOrNull("payload")?.stringOrNull("caption"))
    }

    @Test
    fun decodeSessionStatusEphemeralFrame() {
        val text = """{"kind":"ephemeral","convo_id":"c1","status":{"model":"claude-fable-5","email":"dan@example.com","context":{"tokens":265000,"window":1000000,"pct":27},"limits":[{"label":"Week (Fable)","percent":80,"resets":"Jul 12, 6:59pm (UTC)","resets_at":"2026-07-12T18:59:00.000Z"}]}}"""
        val frame = ServerFrame.decode(text)
        assertTrue(frame is ServerFrame.SessionStatusFrame)
        val update = (frame as ServerFrame.SessionStatusFrame).update
        assertEquals("c1", update.convoID)
        assertEquals("claude-fable-5", update.model)
        assertEquals("dan@example.com", update.email)
        assertEquals(SessionStatus.Context(265_000, 1_000_000, 27), update.context)
        assertEquals(
            listOf(SessionStatus.Limit("Week (Fable)", 80, "Jul 12, 6:59pm (UTC)",
                Instant.parse("2026-07-12T18:59:00.000Z"))),
            update.limits,
        )
        assertNull(update.taskRef)
    }

    @Test
    fun decodeSessionStatusCarriesTaskRefForChild() {
        val text = """{"kind":"ephemeral","convo_id":"p1:sub:a1","status":{"model":"claude-fable-5","task_ref":"toolu_abc123"}}"""
        val frame = ServerFrame.decode(text) as ServerFrame.SessionStatusFrame
        assertEquals("p1:sub:a1", frame.update.convoID)
        assertEquals("claude-fable-5", frame.update.model)
        assertEquals("toolu_abc123", frame.update.taskRef)
    }

    @Test
    fun decodeSessionStatusPartialAndMalformed() {
        val partial = ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","status":{"context":{"tokens":5000,"window":200000,"pct":3}}}""")
                as ServerFrame.SessionStatusFrame
        assertNull(partial.update.model)
        assertNull(partial.update.limits)
        assertNull(partial.update.email)
        assertEquals(5000, partial.update.context?.tokens)

        val badDate = ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","status":{"limits":[{"label":"Session","percent":39,"resets":"soon","resets_at":"not-a-date"}]}}""")
                as ServerFrame.SessionStatusFrame
        assertEquals("soon", badDate.update.limits?.first()?.resets)
        assertNull(badDate.update.limits?.first()?.resetsAt)

        val noPct = ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","status":{"model":"m","context":{"tokens":5000}}}""")
                as ServerFrame.SessionStatusFrame
        assertNull(noPct.update.context)
        assertEquals("m", noPct.update.model)

        val mixed = ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","status":{"limits":[{"percent":5},{"label":"Session","percent":39}]}}""")
                as ServerFrame.SessionStatusFrame
        assertEquals(listOf("Session"), mixed.update.limits?.map { it.label })

        assertTrue(ServerFrame.decode("""{"kind":"ephemeral","convo_id":"c1","message_ref":"m7","text":"hi"}""")
                is ServerFrame.Ephemeral)
    }

    @Test
    fun decodeRPCResponseFrames() {
        val ok = ServerFrame.decode("""{"kind":"rpc","response":{"request_id":"r1","agent_device_id":9,"ok":true,"result":{"convo_id":"c-new"}}}""")
                as ServerFrame.RpcResponse
        assertEquals("r1", ok.response.requestID)
        assertEquals(9L, ok.response.agentDeviceID)
        val okOutcome = ok.response.outcome as RPCResponse.Outcome.Success
        assertEquals("c-new", (okOutcome.result as JsonObject).stringOrNull("convo_id"))

        val fail = ServerFrame.decode("""{"kind":"rpc","response":{"request_id":"r2","agent_device_id":9,"ok":false,"error":{"code":"bad_workdir","detail":"/nope"}}}""")
                as ServerFrame.RpcResponse
        val failOutcome = fail.response.outcome as RPCResponse.Outcome.Failure
        assertEquals("bad_workdir", failOutcome.code)
        assertEquals("/nope", failOutcome.detail)

        val bare = ServerFrame.decode("""{"kind":"rpc","response":{"request_id":"r3","agent_device_id":9,"ok":false}}""")
                as ServerFrame.RpcResponse
        val bareOutcome = bare.response.outcome as RPCResponse.Outcome.Failure
        assertNull(bareOutcome.code)

        assertNull(ServerFrame.decode("""{"kind":"rpc","request":{"request_id":"r1","from_device_id":7,"method":"start","params":null}}"""))
        assertNull(ServerFrame.decode("""{"kind":"rpc","response":{"agent_device_id":9,"ok":true}}"""))
        assertNull(ServerFrame.decode("""{"kind":"rpc","response":{"request_id":"r1","agent_device_id":9}}"""))
    }

    @Test
    fun decodeControlErrorCarriesRequestIDAndDetail() {
        val err = ServerFrame.decode("""{"kind":"control","op":"error","code":"not_ready","ref":"agent_request","request_id":"r9","detail":"mid-replay"}""")
                as ServerFrame.Error
        assertEquals("not_ready", err.code)
        assertEquals("agent_request", err.ref)
        assertEquals("r9", err.requestID)
        assertEquals("mid-replay", err.detail)

        val noRid = ServerFrame.decode("""{"kind":"control","op":"error","code":"forbidden","ref":"send"}""")
                as ServerFrame.Error
        assertNull(noRid.requestID)
    }

    @Test
    fun encodeAgentRequestOp() {
        val params = """{"workdir":"~/dev","browser":true}"""
        val op = ClientOp.AgentRequest("r1", 9, "start", params)
        val obj = encodedObject(op)
        assertEquals("agent_request", obj.stringOrNull("op"))
        assertEquals("r1", obj.stringOrNull("request_id"))
        assertEquals(9L, obj.longOrNull("agent_device_id"))
        assertEquals("start", obj.stringOrNull("method"))
        val sent = obj.objectOrNull("params")
        assertEquals("~/dev", sent?.stringOrNull("workdir"))
        assertEquals(true, sent?.boolOrNull("browser"))

        val broken = ClientOp.AgentRequest("r2", 9, "recent_folders", "junk")
        val brokenObj = encodedObject(broken)
        assertEquals(true, brokenObj.objectOrNull("params")?.isEmpty())
    }

    @Test
    fun encodeSendMediaFileKindUsesFileWireString() {
        val media = encodedObject(ClientOp.SendMedia("c1", MediaKind.FILE, "b9", "report.pdf", "application/pdf",
            42, null, "L2"))
        assertEquals("file", media.stringOrNull("type"))
    }

    @Test
    fun sessionStateWireRoundTrip() {
        assertEquals(SessionState.Running, SessionState.fromWire("running"))
        assertEquals(SessionState.Done, SessionState.fromWire("done"))
        assertEquals("running", SessionState.Running.wire)
        assertEquals("done", SessionState.Done.wire)

        // Unknown wire values must round-trip unchanged rather than being
        // coerced or dropped.
        val other = SessionState.fromWire("waiting")
        assertEquals(SessionState.Other("waiting"), other)
        assertEquals("waiting", other.wire)
    }

    // MARK: workdir + vitals decode (matron-apple #90 port)

    @Test
    fun decodeStatusWorkdirAndVitals() {
        val text = """{"kind":"ephemeral","convo_id":"c1","status":{"workdir":"/Users/dan/Dev/matron-apple","vitals":{"cpu_pct":12,"ram_pct":63}}}"""
        val update = (ServerFrame.decode(text) as ServerFrame.SessionStatusFrame).update
        assertEquals("/Users/dan/Dev/matron-apple", update.workdir)
        assertEquals(SessionStatus.Vitals(cpuPct = 12, ramPct = 63), update.vitals)
    }

    @Test
    fun decodeStatusVitalsWithOnlyRam() {
        // CPU needs two sampler ticks after a bridge boot — the first frames
        // carry RAM alone and must still decode.
        val text = """{"kind":"ephemeral","convo_id":"c1","status":{"vitals":{"ram_pct":41}}}"""
        val update = (ServerFrame.decode(text) as ServerFrame.SessionStatusFrame).update
        assertEquals(SessionStatus.Vitals(cpuPct = null, ramPct = 41), update.vitals)
    }

    @Test
    fun decodeStatusEmptyVitalsDegradesToNull() {
        // An object carrying neither number degrades to null so the merge
        // keeps the last good sample instead of blanking it.
        val text = """{"kind":"ephemeral","convo_id":"c1","status":{"model":"m","vitals":{}}}"""
        val update = (ServerFrame.decode(text) as ServerFrame.SessionStatusFrame).update
        assertEquals("m", update.model)
        assertNull(update.vitals)
    }
}
