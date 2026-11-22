package chat.matron.android.journal.approvals

import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.journal.stringOrNull
import chat.matron.android.platform.SoftwareApprovalKey
import java.io.File
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/// The statement's bytes, against the server's published test vectors: the
/// canonical strings built here must be the very strings the server's key
/// signed (so its signatures verify over them), and a signature made here
/// must verify the way the server verifies (ES256, DER, over the UTF-8 of
/// the canonical string).
class ApprovalStatementTest {
    private val vectors: JsonObject = parseJsonObjectOrNull(
        javaClass.classLoader!!.getResource("approvals/approval-vectors.json")!!.readText(),
    )!!

    private val publicKeyB64 = vectors.stringOrNull("public_key")!!
    private val keyID = vectors.stringOrNull("key_id")!!
    private val nonce = "q83vEjRWeJq83vEjRWeJq83vEjRWeJq83vEjRWeJq8k"

    private fun vector(name: String): Pair<String, String> {
        val v = vectors.objectOrNull("statements")!!.objectOrNull(name)!!
        return v.stringOrNull("statement")!! to v.stringOrNull("signature")!!
    }

    private fun statement(kind: ApprovalStatement.Kind, subject: ApprovalSubject) = ApprovalStatement(
        kind = kind, journal = "journal.example", user = "alice", keyID = keyID, nonce = nonce,
        signedAt = 1_790_000_000_000, subject = subject,
    )

    /// The server's check: ES256 over the statement's UTF-8, DER signature.
    private fun verifies(publicKey: ByteArray, statement: String, signatureB64: String): Boolean {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initVerify(ecPublicKey(publicKey))
        signature.update(statement.toByteArray(Charsets.UTF_8))
        return runCatching { signature.verify(ApprovalKeyEncoding.data(signatureB64)) }.getOrDefault(false)
    }

    private fun ecPublicKey(raw: ByteArray): PublicKey {
        require(raw.size == 65 && raw[0] == 0x04.toByte())
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
    }

    @Test
    fun keyIdIsTheSha256OfTheUncompressedPoint() {
        val raw = ApprovalKeyEncoding.data(publicKeyB64)!!
        assertEquals(65, raw.size)
        assertEquals(keyID, ApprovalKeyEncoding.keyID(raw))
        assertEquals("7734f909", ApprovalKeyEncoding.fingerprint(keyID))
        assertEquals(publicKeyB64, ApprovalKeyEncoding.base64URL(raw))
    }

    @Test
    fun promptDigestsMatchTheServers() {
        val item = vectors.objectOrNull("prompt_digest_example")!!
        val input = item.objectOrNull("input")!!
        assertEquals(
            item.stringOrNull("digest"),
            PromptDigest.item(input.stringOrNull("title")!!, input.stringOrNull("body")!!),
        )
        val reply = vectors.objectOrNull("reply_prompt_example")!!
        assertEquals(reply.stringOrNull("digest"), PromptDigest.comment(reply.objectOrNull("input")!!.stringOrNull("body")!!))
    }

    @Test
    fun itemTapIsByteForByteTheVectorAndItsSignatureVerifies() {
        val (expected, signature) = vector("item_tap")
        val built = statement(
            ApprovalStatement.Kind.ItemTap(
                itemID = "it_abc123", num = 7, replyTo = null, action = "Approve",
                promptDigest = PromptDigest.item("Deploy box-a", "Roll out the new build?"),
            ),
            mapOf("box" to "box-a"),
        ).canonicalString()
        assertEquals(expected, built)
        assertTrue(verifies(ApprovalKeyEncoding.data(publicKeyB64)!!, built, signature))
    }

    @Test
    fun followUpTapIsByteForByteTheVector() {
        val (expected, signature) = vector("item_tap_reply")
        val built = statement(
            ApprovalStatement.Kind.ItemTap(
                itemID = "it_abc123", num = 7, replyTo = "cm_def456", action = "Yes",
                promptDigest = PromptDigest.comment("Also restart the service?"),
            ),
            mapOf("box" to "box-a"),
        ).canonicalString()
        assertEquals(expected, built)
        assertTrue(verifies(ApprovalKeyEncoding.data(publicKeyB64)!!, built, signature))
    }

    @Test
    fun accountStatementIsByteForByteTheVector() {
        val (expected, signature) = vector("account")
        val built = statement(ApprovalStatement.Kind.Account, mapOf("op" to "link_device", "code" to "BCDF2345")).canonicalString()
        assertEquals(expected, built)
        assertTrue(verifies(ApprovalKeyEncoding.data(publicKeyB64)!!, built, signature))
        // One byte off, and the server's signature no longer covers it.
        assertFalse(verifies(ApprovalKeyEncoding.data(publicKeyB64)!!, built.replace("alice", "alicf"), signature))
    }

    @Test
    fun aSignatureMadeHereVerifiesLikeTheServersDoes() = runBlocking {
        val key = SoftwareApprovalKey.generate()
        val signed = ApprovalStatement(
            kind = ApprovalStatement.Kind.Account, journal = "journal.example", user = "alice", keyID = key.keyID,
            nonce = nonce, signedAt = 1_790_000_000_000, subject = ApprovalSubjects.clearPanic(1_789_000_000_000),
        ).signed(key, "Unlock")
        // DER: a SEQUENCE of two INTEGERs.
        val der = ApprovalKeyEncoding.data(signed.signature)!!
        assertEquals(0x30, der[0].toInt() and 0xFF)
        assertTrue(verifies(key.publicKey, signed.statement, signed.signature))
        // For a cross-check against the server's own verify function in node
        // (see the pull request): the key, the statement and the signature.
        val out = File(System.getProperty("java.io.tmpdir"), "approval-crosscheck.json")
        out.writeText(
            buildJsonObject {
                put("public_key", ApprovalKeyEncoding.base64URL(key.publicKey))
                put("statement", signed.statement)
                put("signature", signed.signature)
            }.toString(),
        )
    }

    @Test
    fun stringsAreWrittenAsJsonStringifyWritesThem() {
        val s = CanonicalJson.string(
            CanonicalJson.Value.Obj(
                mapOf("q" to CanonicalJson.Value.Str("a\"b\\c\n\t\u0001\u001f\u007f é😀")),
            ),
        )
        assertEquals("{\"q\":\"a\\\"b\\\\c\\n\\t\\u0001\\u001f\u007f é😀\"}", s)
    }

    @Test
    fun keysSortByUtf16CodeUnits() {
        // U+FF61 sorts after U+1F600's high surrogate (D83D) in UTF-16, the
        // reverse of code point order.
        val s = CanonicalJson.string(
            CanonicalJson.Value.Obj(
                mapOf(
                    "｡" to CanonicalJson.Value.Int(1), "😀" to CanonicalJson.Value.Int(2),
                    "b" to CanonicalJson.Value.Int(3), "B" to CanonicalJson.Value.Int(4), "10" to CanonicalJson.Value.Int(5),
                    "9" to CanonicalJson.Value.Int(6),
                ),
            ),
        )
        assertEquals("{\"10\":5,\"9\":6,\"B\":4,\"b\":3,\"😀\":2,\"｡\":1}", s)
    }

    @Test
    fun noncharactersAndLoneSurrogatesAreRefused() {
        for (bad in listOf("﷐", "￾", "🿿", "x\uD800", "\uDC00y")) {
            assertThrows(CanonicalJson.NotCanonical::class.java) {
                CanonicalJson.string(CanonicalJson.Value.Str(bad))
            }
        }
        // A body the server stored with one cannot be signed: the digest is empty.
        assertEquals("", PromptDigest.comment("﷐"))
    }

    @Test
    fun subjectLinesComeInSignedOrder() {
        assertEquals(
            listOf("key_id" to "k", "op" to "enrol_key"),
            ApprovalRequest(ApprovalStatement.Kind.Account, ApprovalSubjects.enrolKey("k"), "Add").subjectLines,
        )
        assertEquals(mapOf("op" to "clear_panic", "since" to "1789000000000"), ApprovalSubjects.clearPanic(1_789_000_000_000))
    }
}
