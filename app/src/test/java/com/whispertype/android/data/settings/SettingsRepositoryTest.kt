package com.whispertype.android.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.whispertype.android.core.model.LanguageMode
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for [SettingsRepository] backed by an on-disk preferences DataStore
 * (no Android Context required, so these run without a device). The repo's
 * primary constructor takes a [androidx.datastore.core.DataStore] directly,
 * which is exactly the seam these tests exercise.
 */
class SettingsRepositoryTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun newRepository(): SettingsRepository {
        val dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tmp.root, "settings.preferences_pb") },
            )
        return SettingsRepository(dataStore)
    }

    @Test
    fun `defaults are English - history off - 30 day retention - app on - onboarding pending`() =
        runTest {
            val repo = newRepository()

            assertEquals(LanguageMode.ENGLISH, repo.speechMode.first())
            assertFalse(repo.historyEnabled.first())
            assertEquals(SettingsRepository.DEFAULT_RETENTION_DAYS, repo.historyRetentionDays.first())
            assertTrue(repo.appEnabled.first())
            assertFalse(repo.onboardingCompleted.first())
            assertEquals(null, repo.modelOverride.first())
        }

    @Test
    fun `model override defaults to null and setter updates it`() = runTest {
        val repo = newRepository()

        assertEquals(null, repo.modelOverride.first())

        repo.setModelOverride("gemini-3.1-flash-live-preview")
        assertEquals("gemini-3.1-flash-live-preview", repo.modelOverride.first())

        repo.setModelOverride(null)
        assertEquals(null, repo.modelOverride.first())
    }

    @Test
    fun `blank model override is stored as unset`() = runTest {
        val repo = newRepository()

        repo.setModelOverride("   ")
        assertEquals(null, repo.modelOverride.first())
    }

    @Test
    fun `history is disabled by default`() = runTest {
        val repo = newRepository()

        assertFalse(repo.historyEnabled.first())
    }

    @Test
    fun `setters update the exposed flows`() = runTest {
        val repo = newRepository()

        repo.setSpeechMode(LanguageMode.HINGLISH)
        repo.setHistoryEnabled(true)
        repo.setHistoryRetentionDays(7)
        repo.setAppEnabled(false)
        repo.setOnboardingCompleted(true)

        assertEquals(LanguageMode.HINGLISH, repo.speechMode.first())
        assertTrue(repo.historyEnabled.first())
        assertEquals(7, repo.historyRetentionDays.first())
        assertFalse(repo.appEnabled.first())
        assertTrue(repo.onboardingCompleted.first())
    }

    @Test
    fun `unknown stored speech mode falls back to English`() = runTest {
        val dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tmp.root, "corrupt-mode.preferences_pb") },
            )
        val repo = SettingsRepository(dataStore)

        repo.setSpeechMode(LanguageMode.ENGLISH)
        // Write an unknown value directly to simulate a store that predates
        // removing/modifying a mode and would otherwise be a parse risk.
        dataStore.edit { it[stringPreferencesKey("speech_mode")] = "KANNADA" }

        assertEquals(LanguageMode.ENGLISH, repo.speechMode.first())
    }
}