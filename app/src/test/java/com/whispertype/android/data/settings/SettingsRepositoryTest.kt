package com.whispertype.android.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.whispertype.android.core.dictionary.DictionaryEntry
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.TranscriptionStyle
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

    @Test
    fun `auto stop seconds default to 60 and setter round-trips`() = runTest {
        val repo = newRepository()

        assertEquals(SettingsRepository.DEFAULT_AUTO_STOP_SECONDS, repo.autoStopSeconds.first())

        repo.setAutoStopSeconds(15)
        assertEquals(15, repo.autoStopSeconds.first())
    }

    @Test
    fun `polish level defaults to medium and setter round-trips`() = runTest {
        val repo = newRepository()

        assertEquals(SettingsRepository.DEFAULT_POLISH_LEVEL, repo.polishLevel.first())

        repo.setPolishLevel(TranscriptionStyle.NONE)
        assertEquals(TranscriptionStyle.NONE, repo.polishLevel.first())

        repo.setPolishLevel(TranscriptionStyle.HIGH)
        assertEquals(TranscriptionStyle.HIGH, repo.polishLevel.first())
    }

    @Test
    fun `unknown stored polish level maps to medium`() = runTest {
        val dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tmp.root, "corrupt-style.preferences_pb") },
            )
        val repo = SettingsRepository(dataStore)

        repo.setPolishLevel(TranscriptionStyle.MEDIUM)
        dataStore.edit { it[stringPreferencesKey("polish_level")] = "ULTRA" }

        assertEquals(TranscriptionStyle.MEDIUM, repo.polishLevel.first())
    }

    @Test
    fun `dictionary defaults to empty`() = runTest {
        val repo = newRepository()

        assertEquals(emptyList<DictionaryEntry>(), repo.dictionary.first())
    }

    @Test
    fun `dictionary add remove and clear round-trip`() = runTest {
        val repo = newRepository()

        repo.addDictionaryEntry(DictionaryEntry("teh", "the"))
        repo.addDictionaryEntry(DictionaryEntry("recieve", "receive"))
        assertEquals(
            listOf(DictionaryEntry("teh", "the"), DictionaryEntry("recieve", "receive")),
            repo.dictionary.first(),
        )

        repo.removeDictionaryEntry("teh")
        assertEquals(listOf(DictionaryEntry("recieve", "receive")), repo.dictionary.first())

        repo.clearDictionary()
        assertEquals(emptyList<DictionaryEntry>(), repo.dictionary.first())
    }

    @Test
    fun `adding a duplicate match replaces the existing entry`() = runTest {
        val repo = newRepository()

        repo.addDictionaryEntry(DictionaryEntry("teh", "the"))
        repo.addDictionaryEntry(DictionaryEntry("teh", "thee"))

        assertEquals(listOf(DictionaryEntry("teh", "thee")), repo.dictionary.first())
    }

    @Test
    fun `malformed dictionary json decodes to empty list`() = runTest {
        val dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tmp.root, "corrupt-dictionary.preferences_pb") },
            )
        val repo = SettingsRepository(dataStore)

        dataStore.edit { it[stringPreferencesKey("dictionary")] = "{ not valid json" }

        assertEquals(emptyList<DictionaryEntry>(), repo.dictionary.first())
    }

    @Test
    fun `bubble position defaults to null`() = runTest {
        val repo = newRepository()

        assertEquals(null, repo.bubbleX.first())
        assertEquals(null, repo.bubbleY.first())
    }

    @Test
    fun `bubble position setter round-trips`() = runTest {
        val repo = newRepository()

        repo.setBubblePosition(x = 0.25f, y = 0.75f)
        assertEquals(0.25f, repo.bubbleX.first()!!, 0f)
        assertEquals(0.75f, repo.bubbleY.first()!!, 0f)

        repo.setBubblePosition(x = null, y = null)
        assertEquals(null, repo.bubbleX.first())
        assertEquals(null, repo.bubbleY.first())
    }

    @Test
    fun `reset bubble position clears both axes`() = runTest {
        val repo = newRepository()

        repo.setBubblePosition(x = 0.5f, y = 0.5f)
        repo.resetBubblePosition()

        assertEquals(null, repo.bubbleX.first())
        assertEquals(null, repo.bubbleY.first())
    }
}