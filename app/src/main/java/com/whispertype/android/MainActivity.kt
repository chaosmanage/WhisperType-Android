package com.whispertype.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whispertype.android.onboarding.OnboardingScreen
import com.whispertype.android.settings.LanguageSettingsScreen
import com.whispertype.android.settings.PrivacySettingsScreen
import com.whispertype.android.settings.SettingsScreen
import com.whispertype.android.settings.SettingsViewModel
import com.whispertype.android.ui.theme.WhisperTypeTheme

/**
 * Hosts onboarding, settings, language, and privacy screens (Plan §7.1).
 * Not used as the normal dictation surface.
 */
class MainActivity : ComponentActivity() {

    private enum class Screen { SETTINGS, LANGUAGE, PRIVACY }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WhisperTypeTheme {
                val settingsViewModel: SettingsViewModel = viewModel()
                val onboardingCompleted by settingsViewModel.onboardingCompleted
                    .collectAsStateWithLifecycle(initialValue = false)
                var screen by remember { mutableStateOf(Screen.SETTINGS) }

                if (!onboardingCompleted) {
                    OnboardingScreen(onFinished = {})
                } else {
                    when (screen) {
                        Screen.SETTINGS -> SettingsScreen(
                            onOpenLanguage = { screen = Screen.LANGUAGE },
                            onOpenPrivacy = { screen = Screen.PRIVACY },
                        )
                        Screen.LANGUAGE -> LanguageSettingsScreen(onBack = { screen = Screen.SETTINGS })
                        Screen.PRIVACY -> PrivacySettingsScreen(onBack = { screen = Screen.SETTINGS })
                    }
                }
            }
        }
    }
}
