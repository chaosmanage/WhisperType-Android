package com.whispertype.android

import android.app.Application
import com.whispertype.android.dictation.DictationBridge
import com.whispertype.android.dictation.DictationCoordinator
import com.whispertype.android.history.HistoryRepository
import com.whispertype.android.security.SecretStore
import com.whispertype.android.security.SensitiveClipboard
import com.whispertype.android.settings.SettingsRepository
import com.whispertype.android.settings.SettingsRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application-scoped wiring (Implementation Plan §4): creates the shared
 * SecretStore, SettingsRepository, HistoryRepository, and the single
 * DictationBridge consumed by the accessibility service and overlay.
 */
class WhisperTypeApplication : Application() {

    lateinit var secretStore: SecretStore
        private set

    lateinit var sensitiveClipboard: SensitiveClipboard
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var historyRepository: HistoryRepository
        private set

    /** Injected by lead integration; consumed by the accessibility service and overlay. */
    var dictationBridge: DictationBridge? = null
        private set

    @Volatile
    private var historyEnabledCache = false

    @Volatile
    private var historyRetentionCache = 30

    override fun onCreate() {
        super.onCreate()
        instance = this
        secretStore = SecretStore(this)
        sensitiveClipboard = SensitiveClipboard(this)
        settingsRepository = SettingsRepositoryImpl(this, secretStore)
        historyRepository = HistoryRepository(
            this,
            enabledProvider = { historyEnabledCache },
            retentionProvider = { historyRetentionCache },
            includeMetadataProvider = { true },
        )
        dictationBridge = DictationCoordinator(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch { settingsRepository.historyEnabled.collect { historyEnabledCache = it } }
        scope.launch { settingsRepository.historyRetentionDays.collect { historyRetentionCache = it } }
    }

    companion object {
        @Volatile
        lateinit var instance: WhisperTypeApplication
    }
}
