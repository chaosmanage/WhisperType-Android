package com.whispertype.android.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * Privacy settings (Implementation Plan §7.3, §8, §16): Gemini API key storage
 * and the optional encrypted local history.
 */
@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val keyConfigured by viewModel.geminiKeyConfigured.collectAsStateWithLifecycle()
    val historyEnabled by viewModel.historyEnabled.collectAsStateWithLifecycle()
    val retentionDays by viewModel.historyRetentionDays.collectAsStateWithLifecycle()
    val saveState = viewModel.saveResult.collectAsStateWithLifecycle().value
    val testState = viewModel.testResult.collectAsStateWithLifecycle().value
    val scope = rememberCoroutineScope()

    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text("Privacy", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Secrets and history never leave this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(20.dp))

        Text("Gemini API key", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Stored encrypted in the Android Keystore and excluded from backups.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (keyConfigured) "Replace API key" else "Gemini API key") },
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
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.saveApiKey(key.trim()) },
                enabled = key.isNotBlank() && saveState != KeySaveState.Saving,
            ) {
                Text(if (keyConfigured) "Replace" else "Save key")
            }
            Button(onClick = { viewModel.testApiKey(key.trim()) }, enabled = key.isNotBlank()) {
                Text("Test connection")
            }
            if (keyConfigured) {
                TextButton(onClick = viewModel::deleteApiKey) { Text("Delete") }
            }
        }
        when (saveState) {
            KeySaveState.Idle -> Unit
            KeySaveState.Saving -> StatusLine("Saving\u2026", MaterialTheme.colorScheme.onSurfaceVariant)
            KeySaveState.Saved -> StatusLine("Key saved", MaterialTheme.colorScheme.primary)
            is KeySaveState.Failed -> StatusLine(saveState.message, MaterialTheme.colorScheme.error)
        }
        when (testState) {
            TestResult.Idle -> Unit
            TestResult.Testing -> StatusLine("Testing\u2026", MaterialTheme.colorScheme.onSurfaceVariant)
            TestResult.Success -> StatusLine("Connection OK", MaterialTheme.colorScheme.primary)
            is TestResult.Failed -> StatusLine(testState.message, MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(24.dp))

        Text("Local history", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Record completed dictations", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = historyEnabled, onCheckedChange = viewModel::setHistoryEnabled)
        }
        Text(
            "Transcripts are encrypted at rest and never leave this device. Off by default.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (historyEnabled) {
            Text("Retention period", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 7, 30).forEach { days ->
                    FilterChip(
                        selected = retentionDays == days,
                        onClick = { viewModel.setRetentionDays(days) },
                        label = { Text(if (days == 1) "1 day" else "$days days") },
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                onClick = { scope.launch { viewModel.clearAllHistory() } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Clear all history", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        TextButton(onClick = onBack) { Text("Back to settings") }
    }
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}
