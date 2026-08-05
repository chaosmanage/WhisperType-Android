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
     * ("unknown name language code"), so the Hinglish Latin-script bias is done
     * via the systemInstruction instead of an ASR language code.
     */
    fun liveInstruction(): String? = when (this) {
        ENGLISH -> null
        HINGLISH ->
            "Transcribe the user's speech exactly as spoken. Write Hindi words " +
                "in Latin script (romanized Hindi / Hinglish), never in Devanagari " +
                "script. Keep English words and phrases exactly as spoken. Output " +
                "only the transcription, nothing else."
    }
}