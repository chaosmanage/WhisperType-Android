package com.whispertype.android.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.whispertype.android.core.model.LanguageMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

/**
 * [SettingsProvider] backed by DataStore preferences. Primary constructor takes
 * the [DataStore] directly so the behavior is unit-testable on the JVM with an
 * in-memory/preference DataStore; the Context constructor wires the real,
 * app-scoped singleton store.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) : SettingsProvider {

    constructor(context: Context) : this(context.settingsDataStore)

    private object Keys {
        val speechMode = stringPreferencesKey("speech_mode")
        val historyEnabled = booleanPreferencesKey("history_enabled")
        val historyRetentionDays = intPreferencesKey("history_retention_days")
        val appEnabled = booleanPreferencesKey("app_enabled")
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val modelOverride = stringPreferencesKey("model_override")
    }

    override val speechMode: Flow<LanguageMode> =
        dataStore.data.map { prefs ->
            val stored = prefs[Keys.speechMode]
            LanguageMode.entries.firstOrNull { it.name == stored } ?: LanguageMode.ENGLISH
        }

    override val historyEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.historyEnabled] ?: false }

    override val historyRetentionDays: Flow<Int> =
        dataStore.data.map { it[Keys.historyRetentionDays] ?: DEFAULT_RETENTION_DAYS }

    override val appEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.appEnabled] ?: true }

    override val onboardingCompleted: Flow<Boolean> =
        dataStore.data.map { it[Keys.onboardingCompleted] ?: false }

    override val modelOverride: Flow<String?> =
        dataStore.data.map { it[Keys.modelOverride] }

    suspend fun setSpeechMode(mode: LanguageMode) {
        dataStore.edit { it[Keys.speechMode] = mode.name }
    }

    suspend fun setHistoryEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.historyEnabled] = enabled }
    }

    suspend fun setHistoryRetentionDays(days: Int) {
        dataStore.edit { it[Keys.historyRetentionDays] = days }
    }

    suspend fun setAppEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.appEnabled] = enabled }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.onboardingCompleted] = completed }
    }

    suspend fun setModelOverride(model: String?) {
        dataStore.edit {
            if (model.isNullOrBlank()) it.remove(Keys.modelOverride) else it[Keys.modelOverride] = model.trim()
        }
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 30
    }
}