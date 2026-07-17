package chat.matron.android.storage

import java.util.UUID

/// Errors surfaced by [KeychainProbe.run]. Distinct cases let the bootstrap
/// path tell apart the failure modes the probe guards against. Ported from
/// matron-apple's `KeychainProbeError`.
sealed class KeychainProbeError(message: String) : Exception(message) {
    /// The initial write failed — on Android this is the AndroidKeyStore /
    /// EncryptedSharedPreferences setup being broken on this device.
    data class SetFailed(val underlying: Throwable) :
        KeychainProbeError("Keychain probe set failed: ${underlying.message}")

    /// The read after a successful write failed.
    data class GetFailed(val underlying: Throwable) :
        KeychainProbeError("Keychain probe get failed: ${underlying.message}")

    /// The write succeeded and the read succeeded, but the bytes came back
    /// wrong — a shadowing/collision that means the secure store can't be
    /// trusted.
    data class RoundTripMismatch(val expected: String, val got: String?) :
        KeychainProbeError("Keychain probe round-trip mismatch: expected $expected, got ${got ?: "null"}")

    /// Cleanup after a successful round-trip failed.
    data class DeleteFailed(val underlying: Throwable) :
        KeychainProbeError("Keychain probe delete failed: ${underlying.message}")
}

/// Setup-time probe that round-trips a value through a [SessionStore] to assert
/// secure storage is wired up correctly before the app depends on it. Always
/// attempts the final delete (even on success) so it leaves no residue. Ported
/// from matron-apple's `KeychainProbe`.
object KeychainProbe {
    const val probeKey = "matron.keychain-probe.v1"

    fun run(keychain: SessionStore) {
        val expected = "matron-probe-${UUID.randomUUID()}"

        try {
            keychain.set(expected, probeKey)
        } catch (e: Throwable) {
            throw KeychainProbeError.SetFailed(e)
        }

        val actual: String?
        try {
            actual = keychain.get(probeKey)
        } catch (e: Throwable) {
            runCatching { keychain.delete(probeKey) }
            throw KeychainProbeError.GetFailed(e)
        }

        if (actual != expected) {
            runCatching { keychain.delete(probeKey) }
            throw KeychainProbeError.RoundTripMismatch(expected, actual)
        }

        try {
            keychain.delete(probeKey)
        } catch (e: Throwable) {
            throw KeychainProbeError.DeleteFailed(e)
        }
    }
}
