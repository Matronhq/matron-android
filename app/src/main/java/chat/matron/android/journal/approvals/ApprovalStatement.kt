package chat.matron.android.journal.approvals

import java.security.MessageDigest
import java.util.Base64

// Approval keys (step-up): the statement a device signs, and the encodings
// around it. Pure: no keystore, no network, no UI. The journal's protocol
// ("Approval keys (step-up)") is the contract: ES256 (ECDSA P-256 over
// SHA-256), the public key as base64url of the 65-byte uncompressed point,
// the key id as the lowercase hex SHA-256 of those 65 bytes, the signature as
// base64url of the ASN.1 DER signature, and the signed bytes as the UTF-8 of
// the statement's RFC 8785 canonical string. The journal's own test vectors
// pin every byte of what is built here (ApprovalStatementTest).

/// RFC 8785 canonical JSON for what a statement can hold: strings, integers,
/// `null` and objects. No whitespace, object keys sorted by UTF-16 code
/// units, strings written as ECMAScript's `JSON.stringify` writes them.
object CanonicalJson {
    sealed interface Value {
        data class Str(val value: String) : Value
        data class Int(val value: Long) : Value
        data object Null : Value
        data class Obj(val members: Map<String, Value>) : Value
    }

    /// A string or key the journal refuses in a statement: a Unicode
    /// noncharacter, or a lone surrogate.
    class NotCanonical : Exception("not_canonical")

    fun string(value: Value): String = StringBuilder().also { write(value, it) }.toString()

    /// Keys in the order they are signed: by UTF-16 code units, which is
    /// exactly how Kotlin compares strings.
    fun sortedKeys(keys: Collection<String>): List<String> = keys.sorted()

    private fun write(value: Value, out: StringBuilder) {
        when (value) {
            Value.Null -> out.append("null")
            is Value.Int -> out.append(value.value)
            is Value.Str -> writeString(value.value, out)
            is Value.Obj -> {
                out.append('{')
                var first = true
                for (key in sortedKeys(value.members.keys)) {
                    if (!first) out.append(',')
                    first = false
                    writeString(key, out)
                    out.append(':')
                    write(value.members.getValue(key), out)
                }
                out.append('}')
            }
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isSurrogate(c)) {
                // Only a well-formed pair is a character; a lone half is refused.
                if (!Character.isHighSurrogate(c) || i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) {
                    throw NotCanonical()
                }
                val cp = Character.toCodePoint(c, s[i + 1])
                if ((cp and 0xFFFE) == 0xFFFE) throw NotCanonical()
                out.append(c).append(s[i + 1])
                i += 2
                continue
            }
            val v = c.code
            if (v in 0xFDD0..0xFDEF || (v and 0xFFFE) == 0xFFFE) throw NotCanonical()
            when (v) {
                0x22 -> out.append("\\\"")
                0x5C -> out.append("\\\\")
                0x08 -> out.append("\\b")
                0x0C -> out.append("\\f")
                0x0A -> out.append("\\n")
                0x0D -> out.append("\\r")
                0x09 -> out.append("\\t")
                in 0 until 0x20 -> out.append("\\u%04x".format(v))
                else -> out.append(c)
            }
            i += 1
        }
        out.append('"')
    }
}

/// What an action is about: a flat object of strings, shown to the user line
/// by line and signed exactly as shown.
typealias ApprovalSubject = Map<String, String>

/// One statement, before it is serialised and signed.
data class ApprovalStatement(
    val kind: Kind,
    /// The journal's host, as `GET /approval-keys` gives it.
    val journal: String,
    /// The account's name, as signed in with.
    val user: String,
    val keyID: String,
    val nonce: String,
    /// When the device signed, in ms, by its own clock.
    val signedAt: Long,
    val subject: ApprovalSubject,
) {
    sealed interface Kind {
        val wireType: String

        /// A button on an item ([replyTo] null) or on a follow-up question
        /// ([replyTo] the asking comment's id).
        data class ItemTap(
            val itemID: String,
            val num: Int,
            val replyTo: String?,
            val action: String,
            val promptDigest: String,
        ) : Kind {
            override val wireType: String get() = "item_tap"
        }

        /// An ask aimed at a protected box: spawn, chat invite, transfer.
        data object Consent : Kind {
            override val wireType: String get() = "consent"
        }

        /// An account action, or a request to a box.
        data object Account : Kind {
            override val wireType: String get() = "account"
        }
    }

    /// The string that is signed, sent, stored and forwarded, byte for byte.
    /// Throws [CanonicalJson.NotCanonical] for text the journal refuses.
    fun canonicalString(): String {
        val members = linkedMapOf<String, CanonicalJson.Value>(
            "v" to CanonicalJson.Value.Int(1),
            "type" to CanonicalJson.Value.Str(kind.wireType),
            "journal" to CanonicalJson.Value.Str(journal),
            "user" to CanonicalJson.Value.Str(user),
            "key_id" to CanonicalJson.Value.Str(keyID),
            "nonce" to CanonicalJson.Value.Str(nonce),
            "signed_at" to CanonicalJson.Value.Int(signedAt),
            "subject" to CanonicalJson.Value.Obj(subject.mapValues { CanonicalJson.Value.Str(it.value) }),
        )
        if (kind is Kind.ItemTap) {
            members["item"] = CanonicalJson.Value.Obj(
                mapOf(
                    "id" to CanonicalJson.Value.Str(kind.itemID),
                    "num" to CanonicalJson.Value.Int(kind.num.toLong()),
                    "reply_to" to (kind.replyTo?.let { CanonicalJson.Value.Str(it) } ?: CanonicalJson.Value.Null),
                ),
            )
            members["action"] = CanonicalJson.Value.Str(kind.action)
            members["prompt_digest"] = CanonicalJson.Value.Str(kind.promptDigest)
        }
        return CanonicalJson.string(CanonicalJson.Value.Obj(members))
    }

    /// Signs the canonical string with [key]. [reason] is what the system's
    /// biometric prompt says.
    suspend fun signed(key: ApprovalSigningKey, reason: String): SignedApproval {
        val statement = canonicalString()
        val der = key.signature(statement.toByteArray(Charsets.UTF_8), reason)
        return SignedApproval(statement, ApprovalKeyEncoding.base64URL(der))
    }
}

/// `approval` as every guarded route takes it: exactly these two strings.
data class SignedApproval(val statement: String, val signature: String)

/// The hash of the words the user was shown, so the question cannot be
/// swapped after the signature. Over the stored text, exactly as the journal
/// returns it to a client that keeps inline attachment refs (this app sends
/// `X-Matron-Item-Inline: attachments` on every request).
object PromptDigest {
    /// A tap on the item's own buttons.
    fun item(title: String, body: String): String =
        digest(mapOf("body" to CanonicalJson.Value.Str(body), "title" to CanonicalJson.Value.Str(title)))

    /// A tap on a follow-up question's buttons.
    fun comment(body: String): String = digest(mapOf("body" to CanonicalJson.Value.Str(body)))

    private fun digest(members: Map<String, CanonicalJson.Value>): String {
        // A body the journal stored can hold a noncharacter; the statement
        // cannot be signed then, and an empty digest never matches.
        val text = try {
            CanonicalJson.string(CanonicalJson.Value.Obj(members))
        } catch (_: CanonicalJson.NotCanonical) {
            return ""
        }
        return ApprovalSubjects.sha256(text)
    }
}

/// Encodings of keys and signatures on the wire.
object ApprovalKeyEncoding {
    /// Base64url without padding.
    fun base64URL(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    fun data(base64URL: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(base64URL) }.getOrNull()

    /// Lowercase hex SHA-256 of the 65-byte uncompressed point.
    fun keyID(publicKey: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(publicKey))

    /// The short form shown on both devices and in the journal's notices: the
    /// first 8 characters of the key id.
    fun fingerprint(keyID: String): String = keyID.take(8)

    internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/// The subjects the protocol defines that this app builds. A subject the
/// journal publishes (on a step-up item or comment) is signed as published
/// and never rebuilt here; these are for account actions.
object ApprovalSubjects {
    const val MAX_KEYS = 12
    const val MAX_LENGTH = 200

    fun sha256(text: String): String =
        ApprovalKeyEncoding.hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    /// Whether the journal would take [subject] in a statement: at most 12
    /// keys, each key and value at most 200 UTF-16 units.
    fun isValid(subject: ApprovalSubject): Boolean =
        subject.size <= MAX_KEYS && subject.all { it.key.length <= MAX_LENGTH && it.value.length <= MAX_LENGTH }

    /// The subject's lines in the order they are signed.
    fun lines(subject: ApprovalSubject): List<Pair<String, String>> =
        CanonicalJson.sortedKeys(subject.keys).map { it to subject.getValue(it) }

    fun enrolKey(keyID: String): ApprovalSubject = mapOf("op" to "enrol_key", "key_id" to keyID)
    fun revokeKey(keyID: String): ApprovalSubject = mapOf("op" to "revoke_key", "key_id" to keyID)
    fun attachKey(keyID: String): ApprovalSubject = mapOf("op" to "attach_key", "key_id" to keyID)

    /// Unlocks the account, from a device the lock froze. [since] (the lock's
    /// start, epoch ms, in decimal) ties the statement to this lock.
    fun clearPanic(since: Long): ApprovalSubject = mapOf("op" to "clear_panic", "since" to since.toString())
}

/// One thing the user is asked to approve with their device's key: what will
/// be signed, and the words the confirm sheet shows with it.
data class ApprovalRequest(
    /// What kind of statement this becomes. A tap carries its item, button
    /// and prompt digest here.
    val kind: ApprovalStatement.Kind,
    /// Signed exactly as given, and shown line by line.
    val subject: ApprovalSubject,
    /// What is being approved, in a few words: the item's title, or the
    /// account action.
    val headline: String,
    /// The item's number as text ("#7"), for a tap.
    val reference: String? = null,
    /// The button being pressed, for a tap.
    val button: String? = null,
    /// The question's words, shown under the headline.
    val detail: String? = null,
) {
    /// The subject's lines, in the order they are signed.
    val subjectLines: List<Pair<String, String>> get() = ApprovalSubjects.lines(subject)
}

/// Signs a request, after showing it to the user. Returns null when the
/// account has no approval key (the request then goes unsigned, as it always
/// did). Throws [ApprovalKeyFailure.Cancelled] when the user backs out, and
/// an [ApprovalError] when the account has a key but this device cannot sign.
typealias ApprovalSigner = suspend (ApprovalRequest) -> SignedApproval?

/// A device's approval key: a P-256 key whose private half signs statements.
/// The app holds it in the Android Keystore, behind a strong biometric.
interface ApprovalSigningKey {
    /// The 65-byte uncompressed point, `0x04 ‖ X ‖ Y`.
    val publicKey: ByteArray

    /// The ASN.1 DER ECDSA signature over SHA-256 of [data]. [reason] is what
    /// the system's biometric prompt says. Throws
    /// [ApprovalKeyFailure.Cancelled] when the user backs out.
    suspend fun signature(data: ByteArray, reason: String): ByteArray

    val keyID: String get() = ApprovalKeyEncoding.keyID(publicKey)
    val fingerprint: String get() = ApprovalKeyEncoding.fingerprint(keyID)
}

/// Why this device could not produce an approval the account needs.
sealed class ApprovalError(message: String) : Exception(message) {
    /// The account has an approval key, this device has none.
    data object NoKeyOnThisDevice : ApprovalError(
        "This needs an approval key and this device doesn't have one. Approve it on a device that does, " +
            "or set one up in Settings ▸ Approval keys.",
    )

    /// This device's key was removed from the account.
    data object KeyRevoked : ApprovalError(
        "This device's approval key has been removed from the account. Set up a new key in Settings ▸ Approval keys.",
    )

    /// The journal has approval keys switched off (no public URL).
    data object Disabled : ApprovalError("Approval keys are switched off on this server.")

    /// What the user was shown does not match what would be signed.
    data class SubjectMismatch(val detail: String) : ApprovalError(detail)
}

/// Why a device's key could not sign.
sealed class ApprovalKeyFailure(message: String) : Exception(message) {
    /// The user cancelled the biometric prompt.
    data object Cancelled : ApprovalKeyFailure("Cancelled.")

    /// The biometric did not recognise the user, or is locked out.
    data class BiometryFailed(val detail: String) :
        ApprovalKeyFailure(detail.ifEmpty { "Your fingerprint or face was not recognised." })

    /// The key no longer works: the device's fingerprints or face changed
    /// since it was made, which destroys it by design.
    data object Invalidated : ApprovalKeyFailure(
        "This device's approval key no longer works because its fingerprint or face unlock changed. Set up a new key.",
    )

    /// This device cannot hold an approval key (no strong biometric set up).
    data object Unavailable : ApprovalKeyFailure("This device can't hold an approval key.")

    data class Other(val detail: String) : ApprovalKeyFailure(detail)
}
