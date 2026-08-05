package com.whispertype.android.data.settings

import com.whispertype.android.core.dictionary.DictionaryEntry
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.TranscriptionStyle
import kotlinx.coroutines.flow.Flow

/**
 * Settings consumed by working runtime consumers only (PRD FR-10). No setting
 * exists here without an implementation and a test.
 */
interface SettingsProvider {
    val speechMode: Flow<LanguageMode>
    val historyEnabled: Flow<Boolean>
    val historyRetentionDays: Flow<Int>
    val appEnabled: Flow<Boolean>
    val onboardingCompleted: Flow<Boolean>
    val modelOverride: Flow<String?>
    val autoStopSeconds: Flow<Int>
    val polishLevel: Flow<TranscriptionStyle>
    val dictionary: Flow<List<DictionaryEntry>>
    val bubbleX: Flow<Float?>
    val bubbleY: Flow<Float?>
}