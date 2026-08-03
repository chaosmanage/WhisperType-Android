package com.whispertype.android.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for [SecureFieldClassifier]. Raw Ints mirror the
 * android.text.InputType constants so no Android framework code runs on the JVM.
 */
class SecureFieldClassificationTest {

    @Test
    fun textPasswordVariationIsSecure() {
        assertTrue(SecureFieldClassifier.isSecure(TYPE_TEXT_VARIATION_PASSWORD, isFlagSecure = false))
        assertTrue(SecureFieldClassifier.isSecure(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD, false))
    }

    @Test
    fun visiblePasswordVariationIsSecure() {
        assertTrue(SecureFieldClassifier.isSecure(TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, false))
        assertTrue(SecureFieldClassifier.isSecure(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, false))
    }

    @Test
    fun webPasswordVariationIsSecure() {
        assertTrue(SecureFieldClassifier.isSecure(TYPE_TEXT_VARIATION_WEB_PASSWORD, false))
        assertTrue(SecureFieldClassifier.isSecure(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_WEB_PASSWORD, false))
    }

    @Test
    fun numberPasswordVariationIsSecure() {
        assertTrue(SecureFieldClassifier.isSecure(TYPE_NUMBER_VARIATION_PASSWORD, false))
        assertTrue(SecureFieldClassifier.isSecure(TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD, false))
    }

    @Test
    fun numberPinVariationIsSecure() {
        assertTrue(SecureFieldClassifier.isSecure(TYPE_NUMBER_VARIATION_PIN, false))
        assertTrue(SecureFieldClassifier.isSecure(TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PIN, false))
    }

    @Test
    fun plainTextIsNotSecure() {
        assertFalse(SecureFieldClassifier.isSecure(TYPE_CLASS_TEXT, false))
        assertFalse(SecureFieldClassifier.isSecure(0, false))
    }

    @Test
    fun multilineTextIsNotSecure() {
        assertFalse(SecureFieldClassifier.isSecure(TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_MULTI_LINE, false))
    }

    @Test
    fun emailAddressIsNotSecure() {
        assertFalse(SecureFieldClassifier.isSecure(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_EMAIL_ADDRESS, false))
    }

    @Test
    fun webPlainTextIsNotSecure() {
        assertFalse(SecureFieldClassifier.isSecure(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_WEB_EDIT_TEXT, false))
    }

    @Test
    fun flagSecureOverridesNonSecureInputType() {
        assertTrue(SecureFieldClassifier.isSecure(TYPE_CLASS_TEXT, isFlagSecure = true))
        assertTrue(SecureFieldClassifier.isSecure(TYPE_CLASS_NUMBER or TYPE_TEXT_FLAG_MULTI_LINE, true))
    }

    @Test
    fun maskedVariationBitsMatchWithoutClassBits() {
        val masked = TYPE_TEXT_VARIATION_PASSWORD and TYPE_MASK_VARIATION
        assertTrue(SecureFieldClassifier.isSecure(TYPE_CLASS_TEXT or masked, false))
        val maskedNumberPin = TYPE_NUMBER_VARIATION_PIN and TYPE_MASK_VARIATION
        assertTrue(SecureFieldClassifier.isSecure(TYPE_CLASS_NUMBER or maskedNumberPin, false))
    }

    private companion object {
        const val TYPE_CLASS_TEXT = 0x00000001
        const val TYPE_CLASS_NUMBER = 0x00000002
        const val TYPE_MASK_VARIATION = 0x00000FF0
        const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
        const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
        const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x00000020
        const val TYPE_TEXT_VARIATION_WEB_EDIT_TEXT = 0x000000A0
        const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000E0
        const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010
        const val TYPE_NUMBER_VARIATION_PIN = 0x00000020
        const val TYPE_TEXT_FLAG_MULTI_LINE = 0x00020000
    }
}
