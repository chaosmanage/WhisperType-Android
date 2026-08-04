package com.whispertype.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.whispertype.android.ui.theme.WhisperTypeTheme

/**
 * Feature host. Feature screens are provided by the feature-onboarding and
 * feature-settings workstreams and wired at integration time.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WhisperTypeTheme { /* feature screen host wired at integration */ }
        }
    }
}