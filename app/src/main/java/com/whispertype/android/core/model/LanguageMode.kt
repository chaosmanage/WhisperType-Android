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
     * Optional setup instruction biasing the Live model's transcription.
     * NOTE: the Live API rejects a languageCode field on inputAudioTranscription
     * ("unknown name language code") and the voice model cannot post-process
     * text, so language and polishing bias is done via the systemInstruction.
     */
    fun liveInstruction(style: TranscriptionStyle = TranscriptionStyle.MEDIUM): String? {
        val styleText = when (style) {
            TranscriptionStyle.NONE ->
                "Transcribe the user's speech verbatim, exactly as spoken. Do not add " +
                    "or change punctuation, capitalization, grammar, or wording. Output " +
                    "only the words spoken."
            TranscriptionStyle.LOW ->
                "Transcribe the user's speech with light cleanup: add basic sentence " +
                    "punctuation and capitalization, but keep the exact words and natural " +
                    "spoken phrasing."
            TranscriptionStyle.MEDIUM ->
                "Transcribe the user's speech into clean written text: proper punctuation, " +
                    "capitalization, and standard grammar, while keeping the user's words " +
                    "and meaning."
            TranscriptionStyle.HIGH ->
                "Transcribe the user's speech into polished, well-structured written text: " +
                    "correct grammar, proper punctuation and capitalization, clear sentence " +
                    "structure, and logical organization, with paragraphs and lists where " +
                    "appropriate. Keep the user's meaning and words wherever possible."
        }
        return when (this) {
            ENGLISH -> if (style == TranscriptionStyle.NONE) null else styleText
            HINGLISH -> {
                val hinglishRule =
                    "Write Hindi words in Latin script (romanized Hindi / Hinglish), " +
                        "never in Devanagari script. Keep English words and phrases " +
                        "exactly as spoken."
                "$styleText $hinglishRule Output only the transcription, nothing else."
            }
        }
    }
}
