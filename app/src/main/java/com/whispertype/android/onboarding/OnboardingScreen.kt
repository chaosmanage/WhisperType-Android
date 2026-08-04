package com.whispertype.android.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whispertype.android.dictation.DictationState
import com.whispertype.android.gemini.LanguageMode
import com.whispertype.android.overlay.DockedMicOverlay
import com.whispertype.android.settings.DockPosition
import com.whispertype.android.settings.DockSettings
import com.whispertype.android.settings.DockSize
import com.whispertype.android.settings.KeySaveState

/**
 * Step-by-step onboarding flow. The host activity is expected to provide the
 * [com.whispertype.android.ui.theme.WhisperTypeTheme] wrapper.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val micGranted by viewModel.micPermissionGranted.collectAsStateWithLifecycle()
    val notificationGranted by viewModel.notificationPermissionGranted.collectAsStateWithLifecycle()
    val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsStateWithLifecycle()

    val launchers = rememberPermissionLaunchers(
        onMicResult = { viewModel.refreshPermissions() },
        onNotificationResult = { viewModel.refreshPermissions() },
    )

    Scaffold(
        bottomBar = {
            OnboardingBottomBar(
                step = step,
                onBack = viewModel::back,
                onNext = {
                    if (step == OnboardingStep.DONE) {
                        viewModel.completeOnboarding()
                        onFinished()
                    } else {
                        viewModel.next()
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            when (step) {
                OnboardingStep.INTRO -> IntroStep()
                OnboardingStep.MICROPHONE -> MicrophoneStep(micGranted, launchers, onSkip = viewModel::next)
                OnboardingStep.NOTIFICATIONS -> NotificationsStep(notificationGranted, launchers, onSkip = viewModel::next)
                OnboardingStep.ACCESSIBILITY -> AccessibilityStep(accessibilityEnabled, launchers, viewModel)
                OnboardingStep.API_KEY -> ApiKeyStep(viewModel)
                OnboardingStep.LANGUAGE -> LanguageStep(viewModel)
                OnboardingStep.DOCK_PREVIEW -> DockPreviewStep(viewModel)
                OnboardingStep.DEMO -> DemoStep(viewModel, launchers)
                OnboardingStep.DONE -> DoneStep()
            }
        }
    }
}

@Composable
private fun OnboardingBottomBar(step: OnboardingStep, onBack: () -> Unit, onNext: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step != OnboardingStep.INTRO) {
                TextButton(onClick = onBack) { Text("Back") }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            Button(onClick = onNext) {
                Text(if (step == OnboardingStep.DONE) "Get started" else "Continue")
            }
        }
    }
}

@Composable
private fun IntroStep() {
    StepColumn {
        Text("Welcome to WhisperType", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        BulletPoint("Keeps your existing keyboard active")
        BulletPoint("A small mic control docks at the keyboard edge")
        BulletPoint("Tap to dictate, tap Stop to insert")
        BulletPoint("No continuous recording")
        BulletPoint("Needs microphone, notifications, Accessibility, and a Gemini API key")
    }
}

@Composable
private fun BulletPoint(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text("\u2022", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun MicrophoneStep(granted: Boolean, launchers: PermissionLaunchers, onSkip: () -> Unit) {
    StepColumn {
        StepTitle("Microphone permission")
        StepBody("WhisperType needs the microphone to capture your voice for dictation. Audio is recorded only while you are dictating.")
        Spacer(modifier = Modifier.height(16.dp))
        PermissionStatusRow(granted = granted, label = "Microphone access")
        Spacer(modifier = Modifier.height(20.dp))
        if (granted) {
            Text(
                text = "Granted \u2014 continue to the next step.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Button(onClick = launchers.launchMicrophone) { Text("Grant microphone") }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onSkip) { Text("Skip for now") }
        }
    }
}

@Composable
private fun NotificationsStep(granted: Boolean, launchers: PermissionLaunchers, onSkip: () -> Unit) {
    StepColumn {
        StepTitle("Notifications")
        StepBody("WhisperType uses notifications to keep you informed while the dictation service is running.")
        Spacer(modifier = Modifier.height(16.dp))
        PermissionStatusRow(granted = granted, label = "Notification access")
        Spacer(modifier = Modifier.height(20.dp))
        if (granted) {
            Text(
                text = "Granted \u2014 continue to the next step.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Button(onClick = launchers.launchNotifications) { Text("Allow notifications") }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onSkip) { Text("Skip for now") }
        }
    }
}

@Composable
private fun AccessibilityStep(enabled: Boolean, launchers: PermissionLaunchers, viewModel: OnboardingViewModel) {
    StepColumn {
        StepTitle("Accessibility service")
        StepBody("WhisperType uses Accessibility to detect the focused text field, learn the keyboard bounds, and insert dictated text for you.")
        Spacer(modifier = Modifier.height(16.dp))
        PermissionStatusRow(granted = enabled, label = "Accessibility")
        Spacer(modifier = Modifier.height(20.dp))
        if (!enabled) {
            Button(onClick = launchers.openAccessibilitySettings) { Text("Open accessibility settings") }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = viewModel::refreshAccessibility) { Text("Check again") }
        } else {
            Text(
                text = "Enabled \u2014 continue to the next step.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ApiKeyStep(viewModel: OnboardingViewModel) {
    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val keyConfigured by viewModel.geminiKeyConfigured.collectAsStateWithLifecycle()
    val saveState = viewModel.saveResult.collectAsStateWithLifecycle().value

    StepColumn {
        StepTitle("Gemini API key")
        StepBody("Enter your Gemini API key to power transcription. The key is stored in the Android Keystore and never leaves the device.")
        Spacer(modifier = Modifier.height(16.dp))
        if (keyConfigured) {
            PermissionStatusRow(granted = true, label = "API key configured")
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = viewModel::deleteKey) { Text("Remove key") }
            Spacer(modifier = Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Gemini API key") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "Hide key" else "Show key",
                    )
                }
            },
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { viewModel.saveApiKeyNow(key.trim()) },
            enabled = key.isNotBlank() && saveState != KeySaveState.Saving,
        ) {
            Text("Save key")
        }
        when (saveState) {
            KeySaveState.Idle -> Unit
            KeySaveState.Saving -> StatusText("Saving\u2026", MaterialTheme.colorScheme.onSurfaceVariant)
            KeySaveState.Saved -> StatusText("Saved", MaterialTheme.colorScheme.primary)
            is KeySaveState.Failed -> StatusText(saveState.message, MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun LanguageStep(viewModel: OnboardingViewModel) {
    val mode by viewModel.speechMode.collectAsStateWithLifecycle()
    StepColumn {
        StepTitle("Dictation language")
        StepBody("Choose the language mode used for transcription.")
        Spacer(modifier = Modifier.height(16.dp))
        LanguageModeSelector(mode = mode, onSelect = viewModel::setMode)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageModeSelector(mode: LanguageMode, onSelect: (LanguageMode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LanguageMode.entries.forEach { entry ->
            FilterChip(
                selected = entry == mode,
                onClick = { onSelect(entry) },
                label = { Text(entry.name.lowercase().replaceFirstChar { it.uppercase() }) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DockPreviewStep(viewModel: OnboardingViewModel) {
    val settings by viewModel.dockSettings.collectAsStateWithLifecycle()
    StepColumn {
        StepTitle("Docked microphone")
        StepBody("WhisperType keeps your keyboard. A small mic circle docks at its top edge.")
        Spacer(modifier = Modifier.height(16.dp))
        DockPreview(settings = settings)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Position", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DockPosition.entries.forEach { position ->
                FilterChip(
                    selected = settings.position == position,
                    onClick = { viewModel.updateDock { it.copy(position = position) } },
                    label = { Text(position.name) },
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text("Size", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DockSize.entries.forEach { size ->
                FilterChip(
                    selected = settings.size == size,
                    onClick = { viewModel.updateDock { it.copy(size = size) } },
                    label = { Text(size.name) },
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "You can change these later in Settings",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DockPreview(settings: DockSettings) {
    val dockSize = when (settings.size) {
        DockSize.COMPACT -> 48.dp
        DockSize.STANDARD -> 56.dp
        DockSize.LARGE -> 64.dp
    }
    BoxWithConstraints(
        modifier = Modifier
            .width(320.dp)
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        val keyboardHeight = 80.dp
        val keyboardTop = maxHeight - keyboardHeight
        val x = when (settings.position) {
            DockPosition.LEFT -> 12.dp
            DockPosition.CENTER -> (maxWidth - dockSize) / 2
            DockPosition.RIGHT -> maxWidth - dockSize - 12.dp
        }
        DockedMicOverlay(
            settings = settings,
            onTap = {},
            modifier = Modifier.offset(x = x, y = keyboardTop - dockSize / 2),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(keyboardHeight)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
private fun DemoStep(viewModel: OnboardingViewModel, launchers: PermissionLaunchers) {
    val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsStateWithLifecycle()
    val keyConfigured by viewModel.geminiKeyConfigured.collectAsStateWithLifecycle()
    val bridgeState by viewModel.bridgeState.collectAsStateWithLifecycle()
    val imeLabel by viewModel.defaultImeLabel.collectAsStateWithLifecycle()
    val context = LocalContext.current

    StepColumn {
        StepTitle("Try it")
        StepBody("Your keyboard stays selected and active. The mic dock appears at its edge \u2014 tap it, speak, then tap Stop to insert.")
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = imeLabel?.let { "Your keyboard: $it remains selected" } ?: "Your keyboard remains selected",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { viewModel.requestDemo(context) },
            enabled = accessibilityEnabled && keyConfigured,
        ) {
            Text("Run test dictation")
        }
        Spacer(modifier = Modifier.height(8.dp))
        val status = when (bridgeState) {
            is DictationState.Starting -> "Starting\u2026"
            is DictationState.Finalizing -> "Finalizing\u2026"
            is DictationState.Inserting -> "Inserting\u2026"
            else -> viewModel.demoStatus
        }
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodyLarge)
        }
        if (!accessibilityEnabled || !keyConfigured) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = when {
                    !accessibilityEnabled -> "Enable the Accessibility service to run the test."
                    else -> "Add a Gemini API key to run the test."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (!accessibilityEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = launchers.openAccessibilitySettings) { Text("Open accessibility settings") }
            }
        }
    }
}

@Composable
private fun DoneStep() {
    StepColumn {
        Text("You're all set", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "WhisperType is ready. Focus any text field, tap the dock at your keyboard edge, and speak. Tap Stop to insert.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun StepBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StepColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        content = content,
    )
}

@Composable
private fun PermissionStatusRow(granted: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (granted) "$label granted" else "$label not granted",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}
