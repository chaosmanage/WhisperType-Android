package com.whispertype.android.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whispertype.android.WhisperTypeApplication
import com.whispertype.android.dictation.DictationBridge
import com.whispertype.android.dictation.DictationState
import com.whispertype.android.settings.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * End-to-end dictation test screen for the app's own sample field.
 * The WhisperTypeAccessibilityService tracks this field like any other eligible
 * field, so the dock appears at the keyboard edge once the field is focused.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompatibilityTestScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsStateWithLifecycle()
    val micGranted by viewModel.micPermissionGranted.collectAsStateWithLifecycle()
    val notificationGranted by viewModel.notificationPermissionGranted.collectAsStateWithLifecycle()
    val keyConfigured by viewModel.geminiKeyConfigured.collectAsStateWithLifecycle()

    val bridge = remember { runCatching { WhisperTypeApplication.instance.dictationBridge }.getOrNull() }
    val idleState = remember { MutableStateFlow<DictationState?>(null) }
    val dictationStateFlow = bridge?.state ?: idleState
    val dictationState by dictationStateFlow.collectAsStateWithLifecycle()

    val launchers = rememberPermissionLaunchers(
        onMicResult = { viewModel.refreshPermissions() },
        onNotificationResult = { viewModel.refreshPermissions() },
    )

    var demoActive by remember { mutableStateOf(false) }
    var sampleText by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(dictationState) {
        if (dictationState is DictationState.Success ||
            dictationState is DictationState.Error ||
            dictationState is DictationState.Cancelled
        ) {
            demoActive = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compatibility test") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text("Prerequisites", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            PrerequisiteRow("Accessibility", accessibilityEnabled) { launchers.openAccessibilitySettings() }
            PrerequisiteRow("Microphone", micGranted) { launchers.launchMicrophone() }
            PrerequisiteRow("Notifications", notificationGranted) { launchers.launchNotifications() }
            PrerequisiteRow("API key", keyConfigured, onFix = null)
            Spacer(modifier = Modifier.height(24.dp))

            Text("How to test", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            val steps = listOf(
                "Focus the sample field below",
                "Your keyboard appears",
                "Tap the WhisperType dock at the keyboard edge",
                "Speak",
                "Tap Stop",
                "Text is inserted",
                "Keyboard returns",
            )
            steps.forEachIndexed { index, step -> InstructionRow(index + 1, step) }
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = sampleText,
                onValueChange = { sampleText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Sample field") },
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(24.dp))

            DemoControls(
                state = dictationState,
                bridge = bridge,
                demoActive = demoActive,
                enabled = accessibilityEnabled && keyConfigured,
                onRun = { demoActive = DemoDictation.requestDemo(context) },
            )
        }
    }
}

@Composable
private fun PrerequisiteRow(label: String, satisfied: Boolean, onFix: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (satisfied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (satisfied) {
            Text(
                text = "Ready",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (onFix != null) {
            TextButton(onClick = onFix) { Text("Fix") }
        } else {
            Text(
                text = "Add in Settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InstructionRow(number: Int, text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DemoControls(
    state: DictationState?,
    bridge: DictationBridge?,
    demoActive: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    when (state) {
        is DictationState.Listening -> {
            MiniWaveform(amplitude = state.amplitude)
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { bridge?.stop() }) { Text("Stop") }
                TextButton(onClick = { bridge?.cancel() }) { Text("Cancel") }
            }
        }

        is DictationState.Success ->
            Text(
                text = "Inserted \u2713 \u2014 your keyboard should have returned",
                style = MaterialTheme.typography.bodyLarge,
            )

        is DictationState.CopyAvailable -> {
            Text(
                text = "Text is ready to paste into another app.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { bridge?.copyResult() }) { Text("Copy") }
                TextButton(onClick = { bridge?.dismissCopy() }) { Text("Dismiss") }
            }
        }

        is DictationState.Error -> {
            Text(
                text = state.failure.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = { bridge?.dismissCopy() }) { Text("Close") }
        }

        else -> {
            Button(onClick = onRun, enabled = enabled) { Text("Run test dictation") }
            if (demoActive) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Session requested \u2014 look for the dock at your keyboard edge.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun MiniWaveform(amplitude: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(8) { index ->
            val variation = 0.55f + 0.45f * ((index % 3) / 2f)
            val target = (0.15f + amplitude.coerceIn(0f, 1f) * variation).coerceIn(0.15f, 1f)
            val heightFraction by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(durationMillis = 120),
            )
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(12.dp + 36.dp * heightFraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
