package com.whispertype.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whispertype.android.R
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.data.secrets.KeyProvider
import com.whispertype.android.data.settings.SettingsRepository
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Settings screen: runtime toggles from [SettingsRepository] plus Gemini API
 * key management via [KeyProvider]. Purely a UI shell — all persistence is
 * delegated to the repositories, which carry their own tests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    keyProvider: KeyProvider,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    val appEnabled by settings.appEnabled.collectAsStateWithLifecycle(initialValue = true)
    val speechMode by settings.speechMode.collectAsStateWithLifecycle(initialValue = LanguageMode.ENGLISH)
    val historyEnabled by settings.historyEnabled.collectAsStateWithLifecycle(initialValue = false)
    val retentionDays by settings.historyRetentionDays
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_RETENTION_DAYS)

    var hasKey by remember { mutableStateOf(keyProvider.hasKey()) }
    var keyInput by remember { mutableStateOf("") }
    var keyFeedback by remember { mutableStateOf<String?>(null) }

    val keySavedMessage = stringResource(R.string.settings_key_saved)
    val keySaveFailedMessage = stringResource(R.string.settings_key_save_failed)
    val keyClearedMessage = stringResource(R.string.settings_key_cleared)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingRow(
                title = stringResource(R.string.settings_app_enabled),
                description = stringResource(R.string.settings_app_enabled_desc),
            ) {
                Switch(
                    checked = appEnabled,
                    onCheckedChange = { scope.launch { settings.setAppEnabled(it) } },
                )
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.settings_speech_mode),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = speechMode == LanguageMode.ENGLISH,
                    onClick = { scope.launch { settings.setSpeechMode(LanguageMode.ENGLISH) } },
                    label = { Text(stringResource(R.string.language_english)) },
                )
                FilterChip(
                    selected = speechMode == LanguageMode.HINGLISH,
                    onClick = { scope.launch { settings.setSpeechMode(LanguageMode.HINGLISH) } },
                    label = { Text(stringResource(R.string.language_hinglish)) },
                )
            }

            HorizontalDivider()

            SettingRow(
                title = stringResource(R.string.settings_history),
                description = stringResource(R.string.settings_history_desc),
            ) {
                Switch(
                    checked = historyEnabled,
                    onCheckedChange = { scope.launch { settings.setHistoryEnabled(it) } },
                )
            }
            if (historyEnabled) {
                Column {
                    Text(
                        text = stringResource(R.string.settings_history_retention),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "$retentionDays ${stringResource(R.string.days_unit)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Slider(
                        value = retentionDays.toFloat(),
                        onValueChangeFinished = { /* commit on release only */ },
                        onValueChange = { scope.launch { settings.setHistoryRetentionDays(it.roundToInt()) } },
                        valueRange = RETENTION_RANGE_DAYS_F,
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.settings_gemini_key),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    if (hasKey) R.string.settings_key_configured else R.string.settings_key_missing,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasKey) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(stringResource(R.string.settings_key_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            val saved = keyProvider.storeKey(keyInput.trim())
                            keyFeedback = if (saved) {
                                hasKey = true
                                keyInput = ""
                                keySavedMessage
                            } else {
                                keySaveFailedMessage
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_key_save))
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            keyProvider.deleteKey()
                            hasKey = false
                            keyFeedback = keyClearedMessage
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_key_clear))
                }
            }
            keyFeedback?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = description, style = MaterialTheme.typography.bodySmall)
        }
        trailing()
    }
}

private val RETENTION_RANGE_DAYS_F: ClosedFloatingPointRange<Float> = 7f..90f
