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
     * forces Roman/Latin script and forbids translating English speech (English
     * stays English, only Hindi words are romanized). `inputTranscription` (raw
     * ASR) cannot be influenced by the instruction and is used as the fast
     * fallback.
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
                "Repeat the user's speech back and polish it into clean, natural written " +
                    "text: add proper punctuation and capitalization, remove fillers (um, uh, ah), " +
                    "fix grammar and awkward phrasing, and make it read well. You may rephrase " +
                    "and lightly reorder to improve clarity, as long as you keep the user's " +
                    "meaning and every point they made."
            TranscriptionStyle.HIGH ->
                "Repeat the user's speech back and fully refine it into polished, " +
                    "well-structured prose. You are free to completely rewrite, reorder sentences " +
                    "and lines, and choose different words as needed - carry the user's message " +
                    "across and preserve every point they made. Add structure where it helps: " +
                    "bullet points, numbered items, or clear paragraphs, and write complete, " +
                    "well-formed sentences. The final output should read like carefully edited, " +
                    "professional writing."
        }
        val base =
            "Output ONLY the repeated text and nothing else - no greetings, no " +
                "acknowledgments, no questions, no commentary."
        // 0.4.2 reliability: long dictations must never be condensed. The model is
        // prone to summarizing or truncating long input, so this clause is explicit
        // and repeated to maximize verbatim coverage.
        val verbatim =
            "CRITICAL: repeat the ENTIRE speech verbatim, every single word, from the " +
                "first to the last. NEVER summarize, NEVER shorten, NEVER omit the end of " +
                "what the user said, NEVER stop early, even when the user speaks for a " +
                "very long time. Your output must contain every word the user said."
        val content =
            "CRITICAL: preserve every idea and every point from the user's speech - " +
                "never omit, drop, or summarize away any part of the message, even for long " +
                "dictations - but you are free to rephrase, reorder, and restructure the text " +
                "as needed for polish."
        val tail = if (style == TranscriptionStyle.NONE || style == TranscriptionStyle.LOW) verbatim else content
        return when (this) {
            ENGLISH -> "$styleText $base $tail"
            HINGLISH -> {
                val hinglishRule =
                    "CRITICAL SCRIPT RULE: the output MUST be entirely in Latin (Roman) script. " +
                        "Never use Devanagari (Hindi) script - never output a single Devanagari " +
                        "character. Even though the user is speaking in Hindi, render every Hindi " +
                        "word in Latin letters as it sounds, exactly as if the user were speaking " +
                        "in Latin script (for example write 'main theek hoon', never 'मैं ठीक हूँ'). " +
                        "CRITICAL LANGUAGE RULE: NEVER translate the user's speech into a different " +
                        "language. If the user speaks English, output it in English exactly as " +
                        "spoken (with the polish style above applied) - never convert English words " +
                        "into Hindi or Hinglish. Only the Hindi words the user actually says are " +
                        "written in Latin letters. These rules apply to the whole output with no " +
                        "exceptions."
                "$styleText $hinglishRule $base $tail"
            }
        }
    }
}
