package com.whispertype.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    @Test
    fun longGoogleApiKeyIsRedacted() {
        val key = "AIzaSyDfGpQwErTyUiOpAsDfGhJkLzXcVbNmQqWwEeRrTtYyUuIiOoPp"
        val result = SecretRedactor.redact(key)
        assertEquals("[REDACTED]", result)
        assertFalse(result.contains("AIza"))
    }

    @Test
    fun urlQueryKeyIsRedactedAndRestIntact() {
        val url = "https://host/ws?key=AIzaSyA-1234567890abcdefghijklmnopqrstuvwx&model=x"
        val result = SecretRedactor.redact(url)
        assertEquals("https://host/ws?key=[REDACTED]&model=x", result)
        assertFalse(result.contains("AIza"))
        assertTrue(result.contains("model=x"))
    }

    @Test
    fun authorizationBearerTokenIsRedacted() {
        val header =
            "Authorization: Bearer abcdefghijklmnopqrstuvwxyz.ABCDEFGHIJKLMNOPQRSTUVWXYZ.0123456789abcdefghijklmnopqrstuvwxyz-AB"
        val result = SecretRedactor.redact(header)
        assertEquals("Authorization: Bearer [REDACTED]", result)
        assertFalse(result.contains("abcdefghijklmnopqrstuvwxyz"))
        assertFalse(result.contains("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
    }

    @Test
    fun longBase64HexRunIsRedacted() {
        val run = "0123456789abcdef0123456789abcdef0123456789abcdef"
        assertEquals("[REDACTED]", SecretRedactor.redact(run))
    }

    @Test
    fun normalTextIsUnchanged() {
        val text = "Hello world, how are you today?"
        assertEquals(text, SecretRedactor.redact(text))
    }

    @Test
    fun nullTextYieldsEmptyString() {
        assertEquals("", SecretRedactor.redact(null))
    }
}
