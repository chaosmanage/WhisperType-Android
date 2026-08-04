package com.whispertype.android.settings

import com.whispertype.android.gemini.LanguageMode
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for user settings, backed by DataStore.
 * Implemented by the settings workstream; consumed by the overlay and
 * dictation engine through this interface.
 */
interface SettingsRepository {
    val speechMode: Flow<LanguageMode>
    suspend fun setSpeechMode(mode: LanguageMode)

    val dockSettings: Flow<DockSettings>
    suspend fun updateDock(transform: (DockSettings) -> DockSettings)

    val historyEnabled: Flow<Boolean>
    suspend fun setHistoryEnabled(enabled: Boolean)

    val historyRetentionDays: Flow<Int>
    suspend fun setHistoryRetentionDays(days: Int)

    /** Opt-in app-package metadata in history; off by default (plan §16). */
    val historyIncludeMetadata: Flow<Boolean>
    suspend fun setHistoryIncludeMetadata(enabled: Boolean)

    val onboardingCompleted: Flow<Boolean>
    suspend fun setOnboardingCompleted(completed: Boolean)

    /** True when an API key is stored in the Keystore-backed SecretStore. */
    val geminiKeyConfigured: Flow<Boolean>
    suspend fun refreshKeyConfigured()

    /** Gemini Live model id override; defaults to [com.whispertype.android.gemini.GeminiSessionConfig.DEFAULT_MODEL_ID]. */
    val geminiModelId: Flow<String>
    suspend fun setGeminiModelId(modelId: String)

    suspend fun isAppDisabled(packageName: String): Boolean
}
