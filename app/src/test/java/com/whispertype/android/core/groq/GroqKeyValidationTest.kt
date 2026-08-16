package com.whispertype.android.core.groq

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.7.0: Groq key shape validation (never touches the network). */
class GroqKeyValidationTest {

    @Test
    fun `a well-formed gsk key is valid`() {
        assertTrue(GroqKeyValidation.looksValid("gsk_0123456789abcdefghijklmnopqrstuv"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertTrue(GroqKeyValidation.looksValid("  gsk_0123456789abcdefghijklmnopqrstuv  "))
    }

    @Test
    fun `a missing prefix is invalid`() {
        assertFalse(GroqKeyValidation.looksValid("sk_0123456789abcdefghijklmnopqrstuv"))
    }

    @Test
    fun `an empty key is invalid`() {
        assertFalse(GroqKeyValidation.looksValid(""))
        assertFalse(GroqKeyValidation.looksValid("   "))
    }

    @Test
    fun `a too-short key is invalid`() {
        assertFalse(GroqKeyValidation.looksValid("gsk_short"))
    }

    @Test
    fun `a too-long key is invalid`() {
        assertFalse(GroqKeyValidation.looksValid("gsk_" + "a".repeat(160)))
    }

    @Test
    fun `keys with illegal characters are invalid`() {
        assertFalse(GroqKeyValidation.looksValid("gsk_0123456789abcdefghijklmnopqrstuv!"))
        assertFalse(GroqKeyValidation.looksValid("gsk_0123456789abcdefghijklmnopqrst\tuv"))
    }

    @Test
    fun `dashes and underscores in the token body are accepted`() {
        assertTrue(GroqKeyValidation.looksValid("gsk_0123456789abcdefghijklmnopqrstuv_abc-def.gh="))
    }
}