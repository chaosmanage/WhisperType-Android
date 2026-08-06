package com.whispertype.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.LaunchedEffect
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
    scrollToGemini: Boolean = false,
    onGeminiScrollDone: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    // 0.4.2: the Home "Gemini API key" status tile jumps straight to the Gemini
    // account section (the last card) at the bottom of the screen.
    LaunchedEffect(scrollToGemini) {
        if (scrollToGemini) {
            scrollState.animateScrollTo(scrollState.maxValue)
            onGeminiScrollDone()
        }
    }

    val appEnabled by settings.appEnabled.collectAsStateWithLifecycle(initialValue = true)
    val speechMode by settings.speechMode.collectAsStateWithLifecycle(initialValue = LanguageMode.ENGLISH)
    val autoStopSeconds by settings.autoStopSeconds
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_AUTO_STOP_SECONDS)
    val polishLevel by settings.polishLevel
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_POLISH_LEVEL)
    val bubbleSizeDp by settings.bubbleSizeDp
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_BUBBLE_SIZE_DP)
    val bubbleOpacity by settings.bubbleOpacityPercent
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_BUBBLE_OPACITY_PERCENT)
    val miniDotEnabled by settings.miniDotEnabled.collectAsStateWithLifecycle(initialValue = true)
    val miniDotDelay by settings.miniDotDelaySeconds
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_MINI_DOT_DELAY_SECONDS)
    val darkMode by settings.darkMode.collectAsStateWithLifecycle(initialValue = false)

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
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(stringResource(R.string.settings_section_general)) {
                SettingRow(
                    title = stringResource(R.string.settings_app_enabled),
                    description = stringResource(R.string.settings_app_enabled_desc),
                ) {
                    Switch(
                        checked = appEnabled,
                        onCheckedChange = { scope.launch { settings.setAppEnabled(it) } },
                    )
                }

                SettingRow(
                    title = stringResource(R.string.settings_dark_mode),
                    description = stringResource(R.string.settings_dark_mode_desc),
                ) {
                    Switch(
                        checked = darkMode,
                        onCheckedChange = { scope.launch { settings.setDarkMode(it) } },
                    )
                }
            }

            // ------------------------------------------------------------------
            // Recording
            // ------------------------------------------------------------------
            SettingsSection(stringResource(R.string.settings_section_recording)) {
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
            }

            // ------------------------------------------------------------------
            // Bubble
            // ------------------------------------------------------------------
            SettingsSection(stringResource(R.string.settings_section_bubble)) {
                SettingSlider(
                    title = stringResource(R.string.settings_bubble_size),
                    description = stringResource(R.string.settings_bubble_size_value, bubbleSizeDp),
                    value = bubbleSizeDp.toFloat(),
                    range = BUBBLE_SIZE_RANGE_DP_F,
                    onValueChange = { scope.launch { settings.setBubbleSizeDp(it.roundToInt()) } },
                )

                SettingSlider(
                    title = stringResource(R.string.settings_bubble_opacity),
                    description = stringResource(R.string.settings_bubble_opacity_value, bubbleOpacity),
                    value = bubbleOpacity.toFloat(),
                    range = BUBBLE_OPACITY_RANGE_PERCENT_F,
                    onValueChange = { scope.launch { settings.setBubbleOpacityPercent(it.roundToInt()) } },
                )

                SettingRow(
                    title = stringResource(R.string.settings_mini_dot),
                    description = stringResource(R.string.settings_mini_dot_desc),
                ) {
                    Switch(
                        checked = miniDotEnabled,
                        onCheckedChange = { scope.launch { settings.setMiniDotEnabled(it) } },
                    )
                }

                SettingSlider(
                    title = stringResource(R.string.settings_mini_dot_delay),
                    description = stringResource(R.string.settings_mini_dot_delay_value, miniDotDelay),
                    value = miniDotDelay.toFloat(),
                    range = MINI_DOT_DELAY_RANGE_SECONDS_F,
                    onValueChange = { scope.launch { settings.setMiniDotDelaySeconds(it.roundToInt()) } },
                )

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
            }

            // ------------------------------------------------------------------
            // Gemini account
            // ------------------------------------------------------------------
            SettingsSection(stringResource(R.string.settings_section_gemini)) {
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
}

/** 0.4.2 card-wrapped settings section: a titled card grouping related
 *  controls, matching the home screen's card style. */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

/** 0.4.2 slider row: the label/value on its own line and the full-width slider
 *  beneath it (a slider squeezed into a [SettingRow] truncates the text). */
@Composable
private fun SettingSlider(
    title: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = description, style = MaterialTheme.typography.bodySmall)
            }
        }
        Slider(
            value = value,
            onValueChangeFinished = { /* commit on release only */ },
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
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

private val BUBBLE_SIZE_RANGE_DP_F: ClosedFloatingPointRange<Float> =
    SettingsRepository.MIN_BUBBLE_SIZE_DP.toFloat()..SettingsRepository.MAX_BUBBLE_SIZE_DP.toFloat()

private val BUBBLE_OPACITY_RANGE_PERCENT_F: ClosedFloatingPointRange<Float> =
    SettingsRepository.MIN_BUBBLE_OPACITY_PERCENT.toFloat()..SettingsRepository.MAX_BUBBLE_OPACITY_PERCENT.toFloat()

private val MINI_DOT_DELAY_RANGE_SECONDS_F: ClosedFloatingPointRange<Float> =
    SettingsRepository.MIN_MINI_DOT_DELAY_SECONDS.toFloat()..SettingsRepository.MAX_MINI_DOT_DELAY_SECONDS.toFloat()

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
