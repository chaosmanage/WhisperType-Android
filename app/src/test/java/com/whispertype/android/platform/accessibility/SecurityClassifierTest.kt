package com.whispertype.android.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host tests for [SecurityClassifier]. Input types are composed from **literal
 * platform hex** (verified against `android.text.InputType`, API 36) instead of
 * named constants, so a mis-declared constant upstream can never make these
 * tests pass again (Phase 3 audit fix: URI was declared 0x30 instead of 0x10).
 *
 * ```text
 * class:   text=0x1  number=0x2  phone=0x3  datetime=0x4
 * text:    normal=0x00 uri=0x10 email=0x20 subject=0x30 short=0x40
 *          long=0x50 person=0x60 postal=0x70 password=0x80 visible_pw=0x90
 *          web_edit=0xa0 filter=0xb0 phonetic=0xc0 web_email=0xd0 web_pw=0xe0
 * number:  normal=0x00 password=0x10
 * ```
 */
class SecurityClassifierTest {

    private fun classify(cls: Int, variation: Int) =
        SecurityClassifier.classify(cls or variation, password = false, contentInvalid = false)

    // ------------------------------------------------------------------
    // SAFE — ordinary editable text
    // ------------------------------------------------------------------

    @Test
    fun `plain single line text is safe`() {
        assertEquals(Classification.SAFE, classify(cls = 0x0000_0001, variation = 0x0000_0000))
    }

    @Test
    fun `browser address bar uri variation is safe`() {
        assertEquals(Classification.SAFE, classify(cls = 0x0000_0001, variation = 0x0000_0010))
    }

    @Test
    fun `every known non-password text variation is safe`() {
        val safeVariations = listOf(
            0x0000_0000, // TYPE_TEXT_VARIATION_NORMAL
            0x0000_0010, // TYPE_TEXT_VARIATION_URI
            0x0000_0020, // TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            0x0000_0030, // TYPE_TEXT_VARIATION_EMAIL_SUBJECT
            0x0000_0040, // TYPE_TEXT_VARIATION_SHORT_MESSAGE
            0x0000_0050, // TYPE_TEXT_VARIATION_LONG_MESSAGE
            0x0000_0060, // TYPE_TEXT_VARIATION_PERSON_NAME
            0x0000_0070, // TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            0x0000_00a0, // TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
            0x0000_00b0, // TYPE_TEXT_VARIATION_FILTER
            0x0000_00c0, // TYPE_TEXT_VARIATION_PHONETIC
            0x0000_00d0, // TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        )
        safeVariations.forEach { variation ->
            assertEquals("variation 0x%02x".format(variation), Classification.SAFE, classify(0x0000_0001, variation))
        }
    }

    @Test
    fun `phonetic name field is not secure`() {
        assertEquals(Classification.SAFE, classify(cls = 0x0000_0001, variation = 0x0000_00c0))
    }

    @Test
    fun `web edit text and filter variations are safe`() {
        assertEquals(Classification.SAFE, classify(cls = 0x0000_0001, variation = 0x0000_00a0))
        assertEquals(Classification.SAFE, classify(cls = 0x0000_0001, variation = 0x0000_00b0))
    }

    @Test
    fun `plain number field without password variation is safe`() {
        assertEquals(Classification.SAFE, classify(cls = 0x0000_0002, variation = 0x0000_0000))
    }

    @Test
    fun `phone class is safe ordinary input`() {
        assertEquals(Classification.SAFE, classify(cls = 0x0000_0003, variation = 0x0000_0000))
    }

    @Test
    fun `datetime class is safe ordinary input`() {
        assertEquals(Classification.SAFE, classify(cls = 0x0000_0004, variation = 0x0000_0020))
    }

    @Test
    fun `text flags do not break classification`() {
        val type = 0x0000_0001 or 0x0000_8000 // TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_AUTO_CORRECT
        assertEquals(
            Classification.SAFE,
            SecurityClassifier.classify(type, password = false, contentInvalid = false),
        )
    }

    @Test
    fun `zero input type with no other signal is safe for custom editors`() {
        // §2.3: many custom / web editors report inputType == 0; that alone must
        // not fail closed.
        assertEquals(
            Classification.SAFE,
            SecurityClassifier.classify(0, password = false, contentInvalid = false),
        )
    }

    // ------------------------------------------------------------------
    // SECURE
    // ------------------------------------------------------------------

    @Test
    fun `text password variation is secure`() {
        assertEquals(Classification.SECURE, classify(cls = 0x0000_0001, variation = 0x0000_0080))
    }

    @Test
    fun `visible password variation is secure`() {
        assertEquals(Classification.SECURE, classify(cls = 0x0000_0001, variation = 0x0000_0090))
    }

    @Test
    fun `web password variation is secure`() {
        assertEquals(Classification.SECURE, classify(cls = 0x0000_0001, variation = 0x0000_00e0))
    }

    @Test
    fun `number password variation pin is secure`() {
        assertEquals(Classification.SECURE, classify(cls = 0x0000_0002, variation = 0x0000_0010))
    }

    @Test
    fun `reported password flag forces secure even for plain type`() {
        val type = 0x0000_0001 // TYPE_CLASS_TEXT, normal variation
        assertEquals(
            Classification.SECURE,
            SecurityClassifier.classify(type, password = true, contentInvalid = false),
        )
    }

    // ------------------------------------------------------------------
    // UNCERTAIN — fail closed on unknown combinations
    // ------------------------------------------------------------------

    @Test
    fun `content invalid forces uncertain even for text`() {
        val type = 0x0000_0001 // TYPE_CLASS_TEXT, normal variation
        assertEquals(
            Classification.UNCERTAIN,
            SecurityClassifier.classify(type, password = false, contentInvalid = true),
        )
    }

    @Test
    fun `unknown text variation flag combination is uncertain`() {
        assertEquals(Classification.UNCERTAIN, classify(cls = 0x0000_0001, variation = 0x0000_00f0))
    }

    @Test
    fun `unknown input class is uncertain`() {
        assertEquals(Classification.UNCERTAIN, classify(cls = 0x0000_0005, variation = 0x0000_0000))
    }
}
