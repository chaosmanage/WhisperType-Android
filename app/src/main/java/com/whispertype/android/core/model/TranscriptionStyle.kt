package com.whispertype.android.core.model

/** How much the Live transcription instruction should polish the output. */
enum class TranscriptionStyle {
    /** Completely raw: verbatim as spoken. */
    NONE,
    /** Light cleanup (basic punctuation/capitalization only). */
    LOW,
    /** Clean written text (proper punctuation/capitalization/grammar). */
    MEDIUM,
    /** Fully polished, well-structured text. */
    HIGH,
}
