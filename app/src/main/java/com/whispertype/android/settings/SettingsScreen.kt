package com.whispertype.android.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whispertype.android.gemini.LanguageMode
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Settings home (Implementation Plan §7.3): status, dock customization,
 * navigation to language and privacy settings, compatibility and diagnostics.
 */
@Composable
fun SettingsScreen(
    onOpenLanguage: () -> Unit,
    onOpenPrivacy: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val dock by viewModel.dockSettings.collectAsStateWithLifecycle()
    val speechMode by viewModel.speechMode.collectAsStateWithLifecycle()
    val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsStateWithLifecycle()
    val micGranted by viewModel.micPermissionGranted.collectAsStateWithLifecycle()
    val notificationGranted by viewModel.notificationPermissionGranted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.refreshAll() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text("WhisperType", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))

        SectionTitle("Status")
        StatusRow(granted = accessibilityEnabled, label = "Accessibility service") {
            context.openAccessibilitySettings()
        }
        StatusRow(granted = micGranted, label = "Microphone permission") {
            context.openMicrophoneSettings()
        }
        StatusRow(granted = notificationGranted, label = "Notifications")
        Spacer(modifier = Modifier.height(16.dp))

        SectionTitle("Dictation")
        LinkRow("Language mode: ${speechMode.displayName()}", onClick = onOpenLanguage)
        LinkRow("API key and history", onClick = onOpenPrivacy)
        Spacer(modifier = Modifier.height(16.dp))

        SectionTitle("Docked microphone")
        OptionRow("Position") {
            DockPosition.entries.forEach { position ->
                FilterChip(
                    selected = dock.position == position,
                    onClick = { viewModel.updateDock { it.copy(position = position) } },
                    label = { Text(position.name) },
                )
            }
        }
        OptionRow("Size") {
            DockSize.entries.forEach { size ->
                FilterChip(
                    selected = dock.size == size,
                    onClick = { viewModel.updateDock { it.copy(size = size) } },
                    label = { Text(size.name) },
                )
            }
        }
        OptionRow("Vertical overlap") {
            DockOverlap.entries.forEach { overlap ->
                FilterChip(
                    selected = dock.overlapMode == overlap,
                    onClick = { viewModel.updateDock { it.copy(overlapMode = overlap) } },
                    label = { Text(overlap.label()) },
                )
            }
        }
        OptionRow("Theme") {
            ThemeMode.entries.forEach { theme ->
                FilterChip(
                    selected = dock.themeMode == theme,
                    onClick = { viewModel.updateDock { it.copy(themeMode = theme) } },
                    label = { Text(theme.name) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Opacity", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${(dock.opacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = dock.opacity,
            onValueChange = { value -> viewModel.updateDock { it.copy(opacity = value) } },
        )
        AccentColorRow(selectedArgb = dock.accentColorArgb) { argb ->
            viewModel.updateDock { it.copy(accentColorArgb = argb) }
        }
        SwitchRow("Haptic feedback", dock.hapticsEnabled) { enabled ->
            viewModel.updateDock { it.copy(hapticsEnabled = enabled) }
        }
        SwitchRow("Start/stop sounds", dock.soundsEnabled) { enabled ->
            viewModel.updateDock { it.copy(soundsEnabled = enabled) }
        }
        SwitchRow("Show elapsed time", dock.showElapsedTime) { enabled ->
            viewModel.updateDock { it.copy(showElapsedTime = enabled) }
        }
        Spacer(modifier = Modifier.height(16.dp))

        SectionTitle("Troubleshooting")
        Button(onClick = { viewModel.exportDiagnostics() }, modifier = Modifier.fillMaxWidth()) {
            Text("Export diagnostics")
        }
        TextButton(
            onClick = { scope.launch { viewModel.clearAllHistory() } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Clear all history", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun StatusRow(granted: Boolean, label: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    CircleShape,
                ),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (onClick != null) {
            Text(
                "Open settings",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(">", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OptionRow(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = { content() })
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun AccentColorRow(selectedArgb: Long, onSelect: (Long) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("Accent color", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ACCENT_PALETTE.forEach { (argb, color) ->
                val selected = selectedArgb == argb
                Box(
                    modifier = Modifier
                        .size(if (selected) 32.dp else 28.dp)
                        .background(if (selected) Color.White else Color.Transparent, CircleShape)
                        .padding(if (selected) 2.dp else 0.dp)
                        .background(color, CircleShape)
                        .clickable { onSelect(argb) },
                )
            }
        }
    }
}

private fun DockOverlap.label(): String = when (this) {
    DockOverlap.MOSTLY_OVER_KEYBOARD -> "Mostly over keyboard"
    DockOverlap.MOSTLY_ABOVE_KEYBOARD -> "Mostly above keyboard"
}

private fun LanguageMode.displayName(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }

private fun Context.openAccessibilitySettings() {
    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
}

private fun Context.openMicrophoneSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData("package:$packageName".toUri()),
        )
    }
}

private val ACCENT_PALETTE = listOf(
    0xFF7C6CFF to Color(0xFF7C6CFF),
    0xFF00696E to Color(0xFF00696E),
    0xFFBA1A1A to Color(0xFFBA1A1A),
    0xFF006B3F to Color(0xFF006B3F),
    0xFF7D5260 to Color(0xFF7D5260),
    0xFF78436F to Color(0xFF78436F),
    0xFF3D4968 to Color(0xFF3D4968),
)
