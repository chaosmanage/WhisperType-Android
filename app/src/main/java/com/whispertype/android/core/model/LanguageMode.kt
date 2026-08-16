package com.whispertype.android.core.model

/**
 * Supported dictation language modes. Hinglish is Hindi romanized in Latin
 * script mixed naturally with English. Only Latin-script modes are supported.
 *
 * 0.8.0: the mode no longer carries a Live `systemInstruction`. The Live model
 * is raw-ASR transport only (its ASR cannot be steered by an instruction), and
 * every text decision — style level and Hinglish romanization — is made
 * afterwards by `PolishPrompts` on Groq. The old echo instruction is what made
 * MEDIUM restructure speech; it is gone.
 */
enum class LanguageMode {
    ENGLISH,
    HINGLISH,
}
