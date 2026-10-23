package chat.matron.android.journal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TwoFactorDecodingTest {
    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun setupNeedsASecretAndAnOtpauthURI() {
        assertNull(TwoFactorDecoding.setup(obj("""{"secret":"ABC","otpauth_uri":"https://example.com"}""")))
        assertNull(TwoFactorDecoding.setup(obj("""{"otpauth_uri":"otpauth://totp/x?secret=ABC"}""")))
    }

    @Test
    fun enrolmentNeedsRecoveryCodes() {
        assertNull(TwoFactorDecoding.enrolment(obj("""{"token":"t","device_id":1,"user_id":2,"recovery_codes":[]}""")))
        assertNull(TwoFactorDecoding.enrolment(obj("""{"token":"t","device_id":1,"user_id":2}""")))
    }

    @Test
    fun verifiedDefaultsToNoRecoveryCode() {
        val v = TwoFactorDecoding.verified(obj("""{"token":"t","device_id":1,"user_id":2}"""))!!
        assertEquals(false, v.usedRecoveryCode)
        assertNull(v.recoveryCodesLeft)
    }

    @Test
    fun statusNeedsEnabled() {
        assertNull(TwoFactorDecoding.status(obj("""{"required":true}""")))
        assertEquals(
            TwoFactorStatus(enabled = false, recoveryCodesLeft = null, required = false, available = true),
            TwoFactorDecoding.status(obj("""{"enabled":false}""")),
        )
    }

    @Test
    fun enabledNeedsRecoveryCodes_andDefaultsSignedOutToNone() {
        assertNull(TwoFactorDecoding.enabled(obj("""{"recovery_codes":[],"signed_out":1}""")))
        assertEquals(
            TwoFactorEnabled(listOf("aaaa-bbbb-cccc-dddd"), signedOut = 0),
            TwoFactorDecoding.enabled(obj("""{"recovery_codes":["aaaa-bbbb-cccc-dddd"]}""")),
        )
    }

    @Test
    fun shownAsNamesTheIssuerAndAccount() {
        val setup = TwoFactorSetup("ABC", "otpauth://totp/x", issuer = "Matron", account = "alice@example.com")
        assertEquals("It shows up as Matron (alice@example.com).", TwoFactorDecoding.shownAs(setup))
        assertEquals("It shows up as Matron.", TwoFactorDecoding.shownAs(setup.copy(account = null)))
        assertNull(TwoFactorDecoding.shownAs(setup.copy(issuer = "", account = null)))
    }

    @Test
    fun groupsTheSetupKeyInFours() {
        assertEquals("JBSW Y3DP EHPK 3PXP", TwoFactorDecoding.groupedSecret("JBSWY3DPEHPK3PXP"))
    }
}
