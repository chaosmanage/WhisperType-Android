package com.whispertype.android.core.model

/**
 * Supported dictation language modes. Hinglish is Hindi romanized in Latin
 * script mixed naturally with English. Only Latin-script modes are supported
 * in v1.
 */
enum class LanguageMode {
    ENGLISH,
    HINGLISH,
    ;

    /**
     * The systemInstruction that makes the Live model's *spoken reply* be the
     * dictation. The dictation source is the model's echo
     * (`outputTranscription`): native audio Live models only output AUDIO, so the
     * instruction tells the model to repeat the user's speech back verbatim (the
     * echo) and apply the selected polish level; for Hinglish it additionally
     * forces Roman/Latin script. `inputTranscription` (raw ASR) cannot be
     * influenced by the instruction and is used as the fast fallback.
     * Verified on-device via host probes (0.4.1).
     */
    fun liveInstruction(style: TranscriptionStyle = TranscriptionStyle.MEDIUM): String {
        val styleText = when (style) {
            TranscriptionStyle.NONE ->
                "Repeat the user's speech back exactly as spoken, including filler " +
                    "words like um, uh, ah. Do not add punctuation, capitalization, or " +
                    "grammar."
            TranscriptionStyle.LOW ->
                "Repeat the user's speech back and add basic sentence punctuation and " +
                    "capitalization. Keep the exact words and the natural spoken phrasing."
            TranscriptionStyle.MEDIUM ->
                "Repeat the user's speech back and lightly polish it: add proper " +
                    "punctuation and capitalization, remove frequent fillers (um, uh, ah), " +
                    "and fix obvious grammar while keeping the user's words and meaning."
            TranscriptionStyle.HIGH ->
                "Repeat the user's speech back and fully polish it: remove all filler " +
                    "words (um, uh, ah, you know, like, i mean), correct grammar, add proper " +
                    "punctuation and capitalization, and structure it into clear, " +
                    "well-formed sentences. Keep the user's meaning and as many of their " +
                    "words as possible."
        }
        val base =
            "Output ONLY the repeated text and nothing else - no greetings, no " +
                "acknowledgments, no questions, no commentary."
        return when (this) {
            ENGLISH -> "$styleText $base"
            HINGLISH -> {
                val hinglishRule =
                    "Write Hindi words in Roman (Latin) script only, never in Devanagari."
                "$styleText $hinglishRule $base"
            }
        }
    }
}
