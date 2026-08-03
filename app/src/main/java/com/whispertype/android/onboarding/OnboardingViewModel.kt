package com.whispertype.android.onboarding

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whispertype.android.WhisperTypeApplication
import com.whispertype.android.dictation.DictationState
import com.whispertype.android.gemini.LanguageMode
import com.whispertype.android.security.SecretStore
import com.whispertype.android.settings.DockSettings
import com.whispertype.android.settings.KeySaveState
import com.whispertype.android.settings.SettingsRepository
import com.whispertype.android.settings.SettingsRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The ordered steps of the onboarding flow. */
enum class OnboardingStep {
    INTRO,
    MICROPHONE,
    NOTIFICATIONS,
    ACCESSIBILITY,
    API_KEY,
    LANGUAGE,
    DOCK_PREVIEW,
    DEMO,
    DONE,
}

/**
 * State and actions for the onboarding flow.
 *
 * Reads the shared [SettingsRepository] the same way [SettingsViewModel] does so
 * no second repository instance is created when both screens live in one process.
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val secretStore = SecretStore(application)

    private val repo: SettingsRepository =
        runCatching { WhisperTypeApplication.instance.settingsRepository }.getOrNull()
            ?: SettingsRepositoryImpl(application, secretStore)

    private val _step = MutableStateFlow(OnboardingStep.INTRO)
    val step: StateFlow<OnboardingStep> = _step

    private val _bridgeState = MutableStateFlow<DictationState?>(null)
    val bridgeState: StateFlow<DictationState?> = _bridgeState

    private val _defaultImeLabel = MutableStateFlow<String?>(null)
    val defaultImeLabel: StateFlow<String?> = _defaultImeLabel

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

    val speechMode: StateFlow<LanguageMode> =
        repo.speechMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LanguageMode.ENGLISH)

    val dockSettings: StateFlow<DockSettings> =
        repo.dockSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DockSettings())

    val geminiKeyConfigured: StateFlow<Boolean> =
        repo.geminiKeyConfigured.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        refreshDefaultIme()
        val bridge = runCatching { WhisperTypeApplication.instance.dictationBridge }.getOrNull()
        if (bridge != null) {
            viewModelScope.launch { bridge.state.collect { state -> _bridgeState.value = state } }
        }
    }

    /** Human-readable status for the demo step, derived from the current bridge state. */
    val demoStatus: String
        get() = when (val state = _bridgeState.value) {
            is DictationState.Listening -> "Listening\u2026"
            is DictationState.Success -> "Inserted \u2713"
            is DictationState.Cancelled -> "Cancelled"
            is DictationState.Error -> state.failure.message
            is DictationState.CopyAvailable -> "Ready to copy"
            else -> ""
        }

    fun next() {
        val order = OnboardingStep.entries
        val index = order.indexOf(_step.value)
        if (index < order.lastIndex) {
            _step.value = order[index + 1]
        }
    }

    fun back() {
        val order = OnboardingStep.entries
        val index = order.indexOf(_step.value)
        if (index > 0) {
            _step.value = order[index - 1]
        }
    }

    /** Resolves the default IME's display label, falling back to its package name. */
    fun refreshDefaultIme() {
        _defaultImeLabel.value = runCatching {
            val app = getApplication<Application>()
            val imeId = Settings.Secure.getString(app.contentResolver, "default_input_method")
            if (imeId == null) {
                null
            } else {
                val manager = app.getSystemService(InputMethodManager::class.java)
                val match = manager.inputMethodList.firstOrNull { inputMethod -> inputMethod.id == imeId }
                match?.loadLabel(app.packageManager)?.toString() ?: imeId.substringAfter('/')
            }
        }.getOrNull()
    }

    fun refreshAccessibility() {
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
            ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        _notificationPermissionGranted.value =
            NotificationManagerCompat.from(getApplication()).areNotificationsEnabled()
    }

    fun saveApiKeyNow(key: String) {
        viewModelScope.launch {
            _saveResult.value = KeySaveState.Idle
            val result = secretStore.saveApiKey(key)
            _saveResult.value =
                if (result.isSuccess) {
                    KeySaveState.Saved
                } else {
                    KeySaveState.Failed(result.exceptionOrNull()?.message ?: "error")
                }
            if (result.isSuccess) {
                repo.refreshKeyConfigured()
            }
        }
    }

    fun deleteKey() {
        viewModelScope.launch {
            secretStore.deleteApiKey()
            repo.refreshKeyConfigured()
        }
    }

    fun setMode(mode: LanguageMode) {
        viewModelScope.launch { repo.setSpeechMode(mode) }
    }

    fun updateDock(transform: (DockSettings) -> DockSettings) {
        viewModelScope.launch { repo.updateDock(transform) }
    }

    fun requestDemo(context: Context): Boolean = DemoDictation.requestDemo(context)

    fun completeOnboarding() {
        viewModelScope.launch { repo.setOnboardingCompleted(true) }
    }

    fun finishDemo() {
        runCatching { WhisperTypeApplication.instance.dictationBridge }.getOrNull()?.stop()
    }
}
