package com.whispertype.android.platform.accessibility

/**
 * Fail-closed classifier that decides whether the currently focused editor is
 * safe to insert dictation text into (PRD FR-2 Eligibility, §16.4). Pure Kotlin
 * and fully unit-testable on a JVM host: it carries no Android runtime
 * dependency and publishes named constants for the relevant
 * `android.text.InputType` bit masks.
 *
 * [SAFE] is returned only for clearly ordinary editable text. Password / PIN /
 * payment / secure fields and any unknown flag combination fail closed to
 * [SECURE] or [UNCERTAIN] so WhisperType never writes into a protected field
 * and never writes when it cannot be confident about the field.
 */
enum class Classification {
    /** Ordinary editable text; safe to insert into. */
    SAFE,

    /** Password / PIN / secure / private field; never eligible. */
    SECURE,

    /** Could not confidently classify; never eligible (fails closed). */
    UNCERTAIN,
}

/**
 * Classifies an editor from its raw InputType mask plus independent
 * password/secure signals. Host-testable; see [SecurityClassifierTest].
 */
object SecurityClassifier {

    // ------------------------------------------------------------------
    // Named InputType bit masks (values match android.text.InputType).
    // ------------------------------------------------------------------
    const val TYPE_MASK_CLASS: Int = 0x0000_000f
    const val TYPE_MASK_VARIATION: Int = 0x0000_00f0

    const val TYPE_CLASS_TEXT: Int = 0x0000_0001
    const val TYPE_CLASS_NUMBER: Int = 0x0000_0002

    const val TYPE_TEXT_VARIATION_NORMAL: Int = 0x0000_0000
    const val TYPE_NUMBER_VARIATION_NORMAL: Int = 0x0000_0000

    const val TYPE_TEXT_VARIATION_PASSWORD: Int = 0x0000_0080
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD: Int = 0x0000_0090
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD: Int = 0x0000_00e0
    const val TYPE_NUMBER_VARIATION_PASSWORD: Int = 0x0000_0010

    /**
     * @param inputType the editor's raw InputType bit mask (0 when unknown).
     * @param password true when the node reported a password / PIN / secure
     *                  flag (e.g. `node.isPassword` or a secure window flag).
     * @param contentInvalid true when the editor content / type could not be
     *                  read reliably; forces [UNCERTAIN].
     */
    fun classify(inputType: Int, password: Boolean, contentInvalid: Boolean): Classification {
        // Any reported password / secure flag is authoritative and fail-closed.
        if (password) return Classification.SECURE
        // If we could not confidently read the type, never guess.
        if (contentInvalid) return Classification.UNCERTAIN

        val cls = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION

        if (variation == TYPE_TEXT_VARIATION_PASSWORD ||
            variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == TYPE_TEXT_VARIATION_WEB_PASSWORD
        ) {
            return Classification.SECURE
        }
        // PIN / payment / autofill number fields carry a password variation.
        if (cls == TYPE_CLASS_NUMBER && variation == TYPE_NUMBER_VARIATION_PASSWORD) {
            return Classification.SECURE
        }

        // SAFE only for clearly ordinary editable text.
        if (cls == TYPE_CLASS_TEXT && variation == TYPE_TEXT_VARIATION_NORMAL) {
            return Classification.SAFE
        }
        if (cls == TYPE_CLASS_NUMBER && variation != TYPE_NUMBER_VARIATION_PASSWORD) {
            return Classification.SAFE
        }

        // Unknown class / variation / flag combination -> cannot be confident.
        return Classification.UNCERTAIN
    }
}
