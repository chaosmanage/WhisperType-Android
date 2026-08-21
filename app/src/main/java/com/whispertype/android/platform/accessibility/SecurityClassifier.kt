package com.whispertype.android.platform.accessibility

import android.text.InputType

/**
 * Fail-closed classifier that decides whether the currently focused editor is
 * safe to insert dictation text into (PRD FR-2 Eligibility, §16.4). The
 * relevant `android.text.InputType` constants are referenced directly from the
 * platform class (their `static final int` values are compile-time inlined, so
 * this stays host-testable; see [SecurityClassifierTest] which pins the literal
 * hex values so drift cannot recur).
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

    /**
     * Text variations that are secrets (never eligible). Number PIN fields are
     * handled separately via [InputType.TYPE_NUMBER_VARIATION_PASSWORD].
     */
    private val SECURE_TEXT_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )

    /**
     * Ordinary text variations that are eligible: plain, email (incl. web),
     * subject, short/long message, person name, postal address, phonetic name,
     * list filter, web edit text, and URI (browser address bars).
     */
    private val SAFE_TEXT_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_NORMAL,
        InputType.TYPE_TEXT_VARIATION_URI,
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
        InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
        InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
        InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
        InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
        InputType.TYPE_TEXT_VARIATION_FILTER,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_PHONETIC,
    )

    /**
     * @param inputType the editor's raw InputType bit mask (0 when unknown —
     *                  many custom / web editors report 0; that alone is no
     *                  longer treated as uncertain, §2.3).
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

        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        if (variation in SECURE_TEXT_VARIATIONS) return Classification.SECURE
        // PIN / payment / autofill number fields carry a password variation.
        if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            return Classification.SECURE
        }

        // Ordinary text classes and their safe variations are eligible.
        if (cls == InputType.TYPE_CLASS_TEXT && variation in SAFE_TEXT_VARIATIONS) {
            return Classification.SAFE
        }
        if (cls == InputType.TYPE_CLASS_NUMBER && variation != InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            return Classification.SAFE
        }
        // Phone / datetime classes are ordinary, non-secret input.
        if (cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) {
            return Classification.SAFE
        }
        // inputType == 0 (unknown) on a confirmed editable node: treat as ordinary
        // text (§2.3) rather than rejecting.
        if (inputType == 0) return Classification.SAFE

        // Unknown class / variation / flag combination -> cannot be confident.
        return Classification.UNCERTAIN
    }
}
