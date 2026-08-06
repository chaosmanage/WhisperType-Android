package com.whispertype.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.whispertype.android.core.dictionary.DictionaryEntry
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.TranscriptionStyle
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
    onOpenHistory: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    val appEnabled by settings.appEnabled.collectAsStateWithLifecycle(initialValue = true)
    val speechMode by settings.speechMode.collectAsStateWithLifecycle(initialValue = LanguageMode.ENGLISH)
    val modelOverride by settings.modelOverride.collectAsStateWithLifecycle(initialValue = null)
    val historyEnabled by settings.historyEnabled.collectAsStateWithLifecycle(initialValue = false)
    val retentionDays by settings.historyRetentionDays
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_RETENTION_DAYS)
    val autoStopSeconds by settings.autoStopSeconds
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_AUTO_STOP_SECONDS)
    val polishLevel by settings.polishLevel
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_POLISH_LEVEL)
    val dictionary by settings.dictionary.collectAsStateWithLifecycle(initialValue = emptyList())
    val bubbleSizeDp by settings.bubbleSizeDp
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_BUBBLE_SIZE_DP)
    val bubbleOpacity by settings.bubbleOpacityPercent
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_BUBBLE_OPACITY_PERCENT)
    val miniDotEnabled by settings.miniDotEnabled.collectAsStateWithLifecycle(initialValue = true)

    var hasKey by remember { mutableStateOf(keyProvider.hasKey()) }
    var keyInput by remember { mutableStateOf("") }
    var keyFeedback by remember { mutableStateOf<String?>(null) }
    var modelInput by remember { mutableStateOf(modelOverride ?: "") }
    var dictionaryWord by remember { mutableStateOf("") }
    var dictionaryReplacement by remember { mutableStateOf("") }

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

            // ------------------------------------------------------------------
            // Recording
            // ------------------------------------------------------------------
            SectionHeader(stringResource(R.string.settings_section_recording))

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

            SettingRow(
                title = stringResource(R.string.settings_auto_stop),
                description = stringResource(R.string.settings_auto_stop_desc),
            ) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { menuOpen = true }) {
                        Text(stringResource(autoStopSecondsLabelRes(autoStopSeconds)))
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        AUTO_STOP_OPTIONS.forEach { seconds ->
                            DropdownMenuItem(
                                text = { Text(stringResource(autoStopSecondsLabelRes(seconds))) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch { settings.setAutoStopSeconds(seconds) }
                                },
                            )
                        }
                    }
                }
            }

            SettingRow(
                title = stringResource(R.string.settings_polish),
                description = stringResource(R.string.settings_polish_desc),
            ) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { menuOpen = true }) {
                        Text(stringResource(polishLevelLabelRes(polishLevel)))
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        TranscriptionStyle.entries.forEach { style ->
                            DropdownMenuItem(
                                text = { Text(stringResource(polishLevelLabelRes(style))) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch { settings.setPolishLevel(style) }
                                },
                            )
                        }
                    }
                }
            }

            SettingRow(
                title = stringResource(R.string.settings_model),
                description = stringResource(R.string.settings_model_desc),
            ) {}
            OutlinedTextField(
                value = modelInput,
                onValueChange = { modelInput = it },
                label = { Text(stringResource(R.string.settings_model_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { scope.launch { settings.setModelOverride(modelInput) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_model_save))
            }

            // ------------------------------------------------------------------
            // Bubble
            // ------------------------------------------------------------------
            SectionHeader(stringResource(R.string.settings_section_bubble))

            SettingRow(
                title = stringResource(R.string.settings_bubble_size),
                description = stringResource(R.string.settings_bubble_size_value, bubbleSizeDp),
            ) {
                Slider(
                    value = bubbleSizeDp.toFloat(),
                    onValueChangeFinished = { /* commit on release only */ },
                    onValueChange = {
                        scope.launch { settings.setBubbleSizeDp(it.roundToInt()) }
                    },
                    valueRange = BUBBLE_SIZE_RANGE_DP_F,
                )
            }

            SettingRow(
                title = stringResource(R.string.settings_bubble_opacity),
                description = stringResource(R.string.settings_bubble_opacity_value, bubbleOpacity),
            ) {
                Slider(
                    value = bubbleOpacity.toFloat(),
                    onValueChangeFinished = { /* commit on release only */ },
                    onValueChange = {
                        scope.launch { settings.setBubbleOpacityPercent(it.roundToInt()) }
                    },
                    valueRange = BUBBLE_OPACITY_RANGE_PERCENT_F,
                )
            }

            SettingRow(
                title = stringResource(R.string.settings_mini_dot),
                description = stringResource(R.string.settings_mini_dot_desc),
            ) {
                Switch(
                    checked = miniDotEnabled,
                    onCheckedChange = { scope.launch { settings.setMiniDotEnabled(it) } },
                )
            }

            SettingRow(
                title = stringResource(R.string.settings_bubble_reset),
                description = stringResource(R.string.settings_bubble_reset_desc),
            ) {
                TextButton(
                    onClick = { scope.launch { settings.resetBubblePosition() } },
                ) {
                    Text(stringResource(R.string.settings_bubble_reset))
                }
            }

            // ------------------------------------------------------------------
            // Dictionary
            // ------------------------------------------------------------------
            SectionHeader(stringResource(R.string.settings_section_dictionary))

            SettingRow(
                title = stringResource(R.string.settings_dictionary),
                description = stringResource(R.string.settings_dictionary_desc),
            ) {}
            Column {
                if (dictionary.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_dictionary_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    dictionary.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (entry.replace.isBlank()) {
                                    entry.match
                                } else {
                                    "${entry.match} → ${entry.replace}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { scope.launch { settings.removeDictionaryEntry(entry.match) } },
                            ) {
                                Text(stringResource(R.string.history_delete))
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = dictionaryWord,
                    onValueChange = { dictionaryWord = it },
                    label = { Text(stringResource(R.string.settings_dictionary_word_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dictionaryReplacement,
                    onValueChange = { dictionaryReplacement = it },
                    label = { Text(stringResource(R.string.settings_dictionary_replacement_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val word = dictionaryWord.trim()
                            if (word.isNotEmpty()) {
                                scope.launch {
                                    settings.addDictionaryEntry(
                                        DictionaryEntry(word, dictionaryReplacement.trim()),
                                    )
                                }
                                dictionaryWord = ""
                                dictionaryReplacement = ""
                            }
                        },
                    ) {
                        Text(stringResource(R.string.settings_dictionary_add))
                    }
                    TextButton(
                        onClick = { scope.launch { settings.clearDictionary() } },
                    ) {
                        Text(stringResource(R.string.settings_dictionary_clear))
                    }
                }
            }

            // ------------------------------------------------------------------
            // History
            // ------------------------------------------------------------------
            SectionHeader(stringResource(R.string.settings_section_history))

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
            SettingRow(
                title = stringResource(R.string.settings_history_view),
                description = "",
            ) {
                OutlinedButton(onClick = onOpenHistory) {
                    Text(">")
                }
            }

            // ------------------------------------------------------------------
            // Gemini account
            // ------------------------------------------------------------------
            SectionHeader(stringResource(R.string.settings_section_gemini))

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

/** 0.4.2 labeled settings section divider. */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
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

private val BUBBLE_SIZE_RANGE_DP_F: ClosedFloatingPointRange<Float> =
    SettingsRepository.MIN_BUBBLE_SIZE_DP.toFloat()..SettingsRepository.MAX_BUBBLE_SIZE_DP.toFloat()

private val BUBBLE_OPACITY_RANGE_PERCENT_F: ClosedFloatingPointRange<Float> =
    SettingsRepository.MIN_BUBBLE_OPACITY_PERCENT.toFloat()..SettingsRepository.MAX_BUBBLE_OPACITY_PERCENT.toFloat()

private val AUTO_STOP_OPTIONS: List<Int> = listOf(15, 30, 60, 120, 300)

@StringRes
private fun autoStopSecondsLabelRes(seconds: Int): Int = when (seconds) {
    15 -> R.string.auto_stop_15s
    30 -> R.string.auto_stop_30s
    60 -> R.string.auto_stop_60s
    120 -> R.string.auto_stop_120s
    300 -> R.string.auto_stop_300s
    else -> R.string.auto_stop_60s
}

@StringRes
private fun polishLevelLabelRes(style: TranscriptionStyle): Int = when (style) {
    TranscriptionStyle.NONE -> R.string.polish_none
    TranscriptionStyle.LOW -> R.string.polish_low
    TranscriptionStyle.MEDIUM -> R.string.polish_medium
    TranscriptionStyle.HIGH -> R.string.polish_high
}
