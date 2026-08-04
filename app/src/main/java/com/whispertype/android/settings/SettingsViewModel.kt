package com.whispertype.android.settings

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whispertype.android.WhisperTypeApplication
import com.whispertype.android.diagnostics.DiagnosticsExporter
import com.whispertype.android.gemini.LanguageMode
import com.whispertype.android.security.SecretStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface KeySaveState {
    data object Idle : KeySaveState
    data object Saving : KeySaveState
    data object Saved : KeySaveState
    data class Failed(val message: String) : KeySaveState
}

sealed interface TestResult {
    data object Idle : TestResult
    data object Testing : TestResult
    data object Success : TestResult
    data class Failed(val message: String) : TestResult
}

/**
 * ViewModel exposing settings state and actions for the settings screens.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val secretStore = SecretStore(application)

    private val repo: SettingsRepository =
        runCatching { WhisperTypeApplication.instance.settingsRepository }.getOrNull()
            ?: SettingsRepositoryImpl(application, secretStore)

    /** Clears all stored history via the application-scoped [HistoryRepository]. */
    var clearHistory: suspend () -> Unit = {
        runCatching { WhisperTypeApplication.instance.historyRepository }.getOrNull()?.clearAll()
    }

    val speechMode: StateFlow<LanguageMode> =
        repo.speechMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LanguageMode.ENGLISH)

    val dockSettings: StateFlow<DockSettings> =
        repo.dockSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DockSettings())

    val historyEnabled: StateFlow<Boolean> =
        repo.historyEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val historyRetentionDays: StateFlow<Int> =
        repo.historyRetentionDays.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)

    /** Opt-in app-package metadata in history; off by default (plan §16). */
    val historyIncludeMetadata: StateFlow<Boolean> =
        repo.historyIncludeMetadata.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val onboardingCompleted: StateFlow<Boolean> =
        repo.onboardingCompleted.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val geminiKeyConfigured: StateFlow<Boolean> =
        repo.geminiKeyConfigured.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val geminiModelId: StateFlow<String> =
        repo.geminiModelId.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            com.whispertype.android.gemini.GeminiSessionConfig.DEFAULT_MODEL_ID,
        )

    private val _accessibilityEnabled = MutableStateFlow(false)
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled

    private val _micPermissionGranted = MutableStateFlow(
        ContextCompat.checkSelfPermission(application, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED,
    )
    val micPermissionGranted: StateFlow<Boolean> = _micPermissionGranted

    private val _notificationPermissionGranted = MutableStateFlow(
        NotificationManagerCompat.from(application).areNotificationsEnabled(),
    )
    val notificationPermissionGranted: StateFlow<Boolean> = _notificationPermissionGranted

    private val _saveResult = MutableStateFlow<KeySaveState>(KeySaveState.Idle)
    val saveResult: StateFlow<KeySaveState> = _saveResult

    private val _testResult = MutableStateFlow<TestResult>(TestResult.Idle)
    val testResult: StateFlow<TestResult> = _testResult

    fun setSpeechMode(mode: LanguageMode) {
        viewModelScope.launch { repo.setSpeechMode(mode) }
    }

    fun updateDock(transform: (DockSettings) -> DockSettings) {
        viewModelScope.launch { repo.updateDock(transform) }
    }

    fun setHistoryEnabled(enabled: Boolean) {
        viewModelScope.launch { repo.setHistoryEnabled(enabled) }
    }

    fun setRetentionDays(days: Int) {
        viewModelScope.launch { repo.setHistoryRetentionDays(days) }
    }

    fun setHistoryIncludeMetadata(enabled: Boolean) {
        viewModelScope.launch { repo.setHistoryIncludeMetadata(enabled) }
    }

    fun completeOnboarding() {
        viewModelScope.launch { repo.setOnboardingCompleted(true) }
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            _saveResult.value = KeySaveState.Idle
            val result = secretStore.saveApiKey(key)
            _saveResult.value =
                if (result.isSuccess) KeySaveState.Saved else KeySaveState.Failed(result.exceptionOrNull()?.message ?: "error")
            if (result.isSuccess) {
                repo.refreshKeyConfigured()
            }
        }
    }

    fun deleteApiKey() {
        viewModelScope.launch {
            secretStore.deleteApiKey()
            repo.refreshKeyConfigured()
        }
    }

    fun setGeminiModelId(modelId: String) {
        viewModelScope.launch { repo.setGeminiModelId(modelId) }
    }

    fun testApiKey(key: String) {
        viewModelScope.launch {
            _testResult.value = TestResult.Testing
            val result = verifyApiKey(key)
            _testResult.value =
                if (result.isSuccess) TestResult.Success else TestResult.Failed(result.exceptionOrNull()?.message ?: "error")
        }
    }

    private suspend fun verifyApiKey(key: String): Result<Unit> = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$key"
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> Result.success(Unit)
                    400, 401, 403 -> Result.failure(Exception("Invalid API key"))
                    else -> Result.failure(Exception("Server error ${response.code}"))
                }
            }
        } catch (_: IOException) {
            Result.failure(Exception("Network error"))
        }
    }

    fun exportDiagnostics(): String = DiagnosticsExporter.export(getApplication<Application>())

    /** Writes the diagnostics report to the given content Uri (e.g. from a document picker). */
    fun exportDiagnosticsTo(uri: Uri) {
        val content = DiagnosticsExporter.export(getApplication<Application>())
        viewModelScope.launch {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val output = resolver.openOutputStream(uri) ?: return@launch
                try {
                    output.write(content.toByteArray(Charsets.UTF_8))
                } finally {
                    output.close()
                }
            }
        }
    }

    fun launchableApps(): List<AppEntry> {
        val packageManager = getApplication<Application>().packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .mapNotNull { resolveInfo ->
                val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                if (!appInfo.enabled) {
                    null
                } else {
                    AppEntry(resolveInfo.loadLabel(packageManager).toString(), appInfo.packageName)
                }
            }
            .distinctBy { app -> app.packageName }
            .sortedBy { app -> app.label }
            .toList()
    }

    fun toggleAppDisabled(packageName: String, disabled: Boolean) {
        viewModelScope.launch {
            repo.updateDock { settings ->
                settings.copy(
                    disabledApps = if (disabled) settings.disabledApps + packageName else settings.disabledApps - packageName,
                )
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch { clearHistory() }
    }

    fun refreshAccessibilityState() {
        val manager = getApplication<Application>().getSystemService(AccessibilityManager::class.java)
        _accessibilityEnabled.value = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                info.resolveInfo.serviceInfo.packageName == getApplication<Application>().packageName &&
                    info.resolveInfo.serviceInfo.name.contains("WhisperTypeAccessibilityService")
            }
    }

    fun refreshPermissions() {
        _micPermissionGranted.value =
            ContextCompat.checkSelfPermission(getApplication<Application>(), Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun refreshAll() {
        refreshPermissions()
        refreshAccessibilityState()
        viewModelScope.launch { repo.refreshKeyConfigured() }
    }

    data class AppEntry(val label: String, val packageName: String)
}
