package com.whispertype.android.accessibility

import android.text.InputType

/**
 * Pure classifier for secure input fields.
 *
 * A field is secure when its hosting window is flag-secure or its input type
 * carries a password/PIN variation. Matching compares both the class and the
 * variation bits ([InputType.TYPE_MASK_CLASS], [InputType.TYPE_MASK_VARIATION] =
 * 0x00000ff0) so full input types (class + variation) match their bare variation
 * constants, and the shared variation nibble does not collide across classes
 * (e.g. text email 0x21 vs numeric PIN 0x22). A bare variation constant (no
 * class bits) matches any class. The numeric PIN variation is
 * compared as the literal 0x00000020 because the platform removed
 * `InputType.TYPE_NUMBER_VARIATION_PIN` from the SDK (API 34); the value matches
 * the historical constant from API 26-33.
 *
 * Pure functions only — no Android runtime state is read, so unit tests may pass
 * raw [Int]s directly.
 */
object SecureFieldClassifier {

    /**
     * Returns true when the input type or the window flag marks the field as secure.
     */
    fun isSecure(inputType: Int, isFlagSecure: Boolean): Boolean {
        if (isFlagSecure) return true
        return matches(inputType, InputType.TYPE_NUMBER_VARIATION_PASSWORD, InputType.TYPE_CLASS_NUMBER) ||
            matches(inputType, InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_CLASS_TEXT) ||
            matches(inputType, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_CLASS_TEXT) ||
            matches(inputType, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_CLASS_TEXT) ||
            matches(inputType, NUMBER_VARIATION_PIN, InputType.TYPE_CLASS_NUMBER)
    }

    private const val NUMBER_VARIATION_PIN = 0x00000020

    private fun matches(inputType: Int, variation: Int, variationClass: Int): Boolean {
        if (inputType == variation) return true
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != 0 && inputClass != variationClass) return false
        return (inputType and InputType.TYPE_MASK_VARIATION) == (variation and InputType.TYPE_MASK_VARIATION)
    }
}
