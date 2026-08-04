package com.whispertype.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.whispertype.android.gemini.LanguageMode
import com.whispertype.android.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "whispertype_settings")

private val SPEECH_MODE: Preferences.Key<String> = stringPreferencesKey("speech_mode")
private val DOCK_POSITION: Preferences.Key<String> = stringPreferencesKey("dock_position")
private val DOCK_SIZE: Preferences.Key<String> = stringPreferencesKey("dock_size")
private val DOCK_OVERLAP: Preferences.Key<String> = stringPreferencesKey("dock_overlap")
private val DOCK_OPACITY: Preferences.Key<Float> = floatPreferencesKey("dock_opacity")
private val DOCK_THEME: Preferences.Key<String> = stringPreferencesKey("dock_theme")
private val DOCK_ACCENT: Preferences.Key<Long> = longPreferencesKey("dock_accent")
private val DOCK_HAPTICS: Preferences.Key<Boolean> = booleanPreferencesKey("dock_haptics")
private val DOCK_SOUNDS: Preferences.Key<Boolean> = booleanPreferencesKey("dock_sounds")
private val DOCK_SHOW_ELAPSED: Preferences.Key<Boolean> = booleanPreferencesKey("dock_show_elapsed")
private val DOCK_CANCEL_SIDE: Preferences.Key<String> = stringPreferencesKey("dock_cancel_side")
private val DOCK_DISABLED_APPS: Preferences.Key<Set<String>> = stringSetPreferencesKey("dock_disabled_apps")
private val HISTORY_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("history_enabled")
private val HISTORY_RETENTION_DAYS: Preferences.Key<Int> = intPreferencesKey("history_retention_days")
private val HISTORY_INCLUDE_METADATA: Preferences.Key<Boolean> = booleanPreferencesKey("history_include_metadata")
private val ONBOARDING_COMPLETED: Preferences.Key<Boolean> = booleanPreferencesKey("onboarding_completed")
private val GEMINI_MODEL_ID: Preferences.Key<String> = stringPreferencesKey("gemini_model_id")

private fun String.toSpeechMode(): LanguageMode =
    LanguageMode.entries.firstOrNull { it.name == this } ?: LanguageMode.ENGLISH

private fun String.toDockPosition(): DockPosition =
    DockPosition.entries.firstOrNull { it.name == this } ?: DockPosition.CENTER

private fun String.toDockSize(): DockSize =
    DockSize.entries.firstOrNull { it.name == this } ?: DockSize.STANDARD

private fun String.toDockOverlap(): DockOverlap =
    DockOverlap.entries.firstOrNull { it.name == this } ?: DockOverlap.MOSTLY_OVER_KEYBOARD

private fun String.toThemeMode(): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == this } ?: ThemeMode.SYSTEM

private fun String.toCancelSide(): CancelSide =
    CancelSide.entries.firstOrNull { it.name == this } ?: CancelSide.RIGHT

/**
 * DataStore-backed [SettingsRepository] implementation.
 */
class SettingsRepositoryImpl(
    private val context: Context,
    private val secretStore: SecretStore,
) : SettingsRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyConfigured = MutableStateFlow(false)

    init {
        scope.launch {
            keyConfigured.value = secretStore.getApiKey().getOrNull() != null
        }
    }

    override val geminiKeyConfigured: Flow<Boolean> = keyConfigured

    override val speechMode: Flow<LanguageMode> =
        context.settingsDataStore.data.map { preferences ->
            preferences[SPEECH_MODE]?.toSpeechMode() ?: LanguageMode.ENGLISH
        }

    override suspend fun setSpeechMode(mode: LanguageMode) {
        edit { this[SPEECH_MODE] = mode.name }
    }

    override val dockSettings: Flow<DockSettings> =
        context.settingsDataStore.data.map { preferences ->
            DockSettings(
                position = preferences[DOCK_POSITION]?.toDockPosition() ?: DockPosition.CENTER,
                size = preferences[DOCK_SIZE]?.toDockSize() ?: DockSize.STANDARD,
                overlapMode = preferences[DOCK_OVERLAP]?.toDockOverlap() ?: DockOverlap.MOSTLY_OVER_KEYBOARD,
                opacity = preferences[DOCK_OPACITY] ?: 1f,
                themeMode = preferences[DOCK_THEME]?.toThemeMode() ?: ThemeMode.SYSTEM,
                accentColorArgb = preferences[DOCK_ACCENT] ?: 0xFF7C6CFF,
                hapticsEnabled = preferences[DOCK_HAPTICS] ?: true,
                soundsEnabled = preferences[DOCK_SOUNDS] ?: false,
                showElapsedTime = preferences[DOCK_SHOW_ELAPSED] ?: false,
                cancelSide = preferences[DOCK_CANCEL_SIDE]?.toCancelSide() ?: CancelSide.RIGHT,
                disabledApps = preferences[DOCK_DISABLED_APPS] ?: emptySet(),
            )
        }

    override suspend fun updateDock(transform: (DockSettings) -> DockSettings) {
        val current = dockSettings.first()
        val next = transform(current)
        edit {
            this[DOCK_POSITION] = next.position.name
            this[DOCK_SIZE] = next.size.name
            this[DOCK_OVERLAP] = next.overlapMode.name
            this[DOCK_OPACITY] = next.opacity
            this[DOCK_THEME] = next.themeMode.name
            this[DOCK_ACCENT] = next.accentColorArgb
            this[DOCK_HAPTICS] = next.hapticsEnabled
            this[DOCK_SOUNDS] = next.soundsEnabled
            this[DOCK_SHOW_ELAPSED] = next.showElapsedTime
            this[DOCK_CANCEL_SIDE] = next.cancelSide.name
            this[DOCK_DISABLED_APPS] = next.disabledApps
        }
    }

    override val historyEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { preferences -> preferences[HISTORY_ENABLED] ?: false }

    override suspend fun setHistoryEnabled(enabled: Boolean) {
        edit { this[HISTORY_ENABLED] = enabled }
    }

    override val historyRetentionDays: Flow<Int> =
        context.settingsDataStore.data.map { preferences -> preferences[HISTORY_RETENTION_DAYS] ?: 30 }

    override suspend fun setHistoryRetentionDays(days: Int) {
        edit { this[HISTORY_RETENTION_DAYS] = days }
    }

    override val historyIncludeMetadata: Flow<Boolean> =
        context.settingsDataStore.data.map { preferences -> preferences[HISTORY_INCLUDE_METADATA] ?: false }

    override suspend fun setHistoryIncludeMetadata(enabled: Boolean) {
        edit { this[HISTORY_INCLUDE_METADATA] = enabled }
    }

    override val onboardingCompleted: Flow<Boolean> =
        context.settingsDataStore.data.map { preferences -> preferences[ONBOARDING_COMPLETED] ?: false }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        edit { this[ONBOARDING_COMPLETED] = completed }
    }

    override val geminiModelId: Flow<String> =
        context.settingsDataStore.data.map { preferences ->
            preferences[GEMINI_MODEL_ID]?.takeIf { it.isNotBlank() }
                ?: com.whispertype.android.gemini.GeminiSessionConfig.DEFAULT_MODEL_ID
        }

    override suspend fun setGeminiModelId(modelId: String) {
        edit { this[GEMINI_MODEL_ID] = modelId.trim() }
    }

    override suspend fun refreshKeyConfigured() {
        keyConfigured.value = secretStore.getApiKey().getOrNull() != null
    }

    override suspend fun isAppDisabled(packageName: String): Boolean =
        dockSettings.first().disabledApps.contains(packageName)

    private suspend fun edit(block: MutablePreferences.() -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
