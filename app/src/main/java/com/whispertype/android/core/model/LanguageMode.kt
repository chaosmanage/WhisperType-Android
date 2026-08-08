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
                "Repeat the user's speech back and refine it into exceptional, " +
                    "publication-grade writing. Go well beyond clean copy: cut every filler, " +
                    "hesitation, redundancy, and false start; tighten wordy phrasing; choose the " +
                    "most precise and well-chosen words; vary sentence length and structure for " +
                    "rhythm; and use sophisticated punctuation (em dashes, colons, semicolons) " +
                    "where it clarifies. Restructure freely - reorganize ideas into the clearest " +
                    "logical flow, split or merge sentences, and add bullet points, numbered " +
                    "items, or paragraphs where they improve readability. The result should read " +
                    "like a professional editor spent time on it: polished, elegant, and " +
                    "effortless - while still carrying the user's message across and preserving " +
                    "every point they made."
            TranscriptionStyle.HIGH ->
                "Repeat the user's speech back and transform it into masterfully crafted, " +
                    "publication-grade prose - writing a professional editor would publish. " +
                    "Eliminate every trace of spoken language: fillers, hesitations, false " +
                    "starts, repetition, and rambling. Condense each wordy phrase to its most " +
                    "elegant, economical form and choose words that are precise, vivid, and " +
                    "memorable. Craft varied sentence rhythms and deploy sophisticated " +
                    "punctuation (em dashes, colons, semicolons) deliberately. Reimagine the " +
                    "structure - reorganize ideas into the most logical, compelling order and use " +
                    "headings, bullet points, numbered lists, or paragraphs wherever they sharpen " +
                    "clarity. Elevate the tone to confident, articulate, assured writing. The " +
                    "final output should read like carefully edited, award-quality prose - while " +
                    "still carrying the user's message across and preserving every point they made."
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
                "as needed for polish. Never change, invent, or drop facts, names, numbers, " +
                "dates, or quoted phrases the user actually said - polish the wording, never " +
                "the substance. Keep your output close to the user's length: do not drastically " +
                "shorten a long dictation."
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
