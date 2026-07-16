package chat.matron.android.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/// Ported from matron-apple's `ServerURLValidatorTests`. The Apple validator
/// returns a `URL`; the Kotlin one returns the normalised string, so these
/// assert on that string (equivalent to Swift's `url.absoluteString`).
class ServerURLValidatorTest {
    private fun expectError(raw: String, expected: ServerURLValidator.ValidationError) {
        try {
            ServerURLValidator.normalize(raw)
            fail("expected $expected for \"$raw\"")
        } catch (e: ServerURLValidator.ValidationError) {
            assertEquals(expected, e)
        }
    }

    @Test fun validatesSimpleHTTPS() =
        assertEquals("https://matrix.example.com", ServerURLValidator.normalize("https://matrix.example.com"))

    @Test fun addsHTTPSWhenMissingScheme() =
        assertEquals("https://matrix.example.com", ServerURLValidator.normalize("matrix.example.com"))

    @Test fun stripsTrailingSlash() =
        assertEquals("https://matrix.example.com", ServerURLValidator.normalize("https://matrix.example.com/"))

    @Test fun rejectsHTTP() =
        expectError("http://matrix.example.com", ServerURLValidator.ValidationError.InsecureScheme)

    @Test fun allowsHTTPLocalhost() =
        assertEquals("http://localhost:6167", ServerURLValidator.normalize("http://localhost:6167"))

    @Test fun allowsHTTP127() =
        assertEquals("http://127.0.0.1:6167", ServerURLValidator.normalize("http://127.0.0.1:6167"))

    @Test fun rejectsHTTPNonLocalhost() =
        expectError("http://192.168.1.5:6167", ServerURLValidator.ValidationError.InsecureScheme)

    @Test fun rejectsEmptyString() =
        expectError("", ServerURLValidator.ValidationError.Empty)

    @Test fun rejectsWhitespaceOnly() =
        expectError("   ", ServerURLValidator.ValidationError.Empty)

    @Test fun rejectsInvalidHost() =
        expectError("https:///", ServerURLValidator.ValidationError.NoHost)

    @Test fun trimsWhitespace() =
        assertEquals("https://matrix.example.com", ServerURLValidator.normalize("  matrix.example.com  "))

    @Test fun explicitHTTPAllowedForLoopbackOnly() {
        assertEquals("http://127.0.0.1:9810", ServerURLValidator.normalize("http://127.0.0.1:9810"))
        assertEquals("http", ServerURLValidator.normalize("http://localhost:9810").substringBefore("://"))
        assertEquals("http", ServerURLValidator.normalize("http://[::1]:9810").substringBefore("://"))
        expectError("http://chat.example.com", ServerURLValidator.ValidationError.InsecureScheme)
    }
}
