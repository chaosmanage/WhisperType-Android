package com.whispertype.android.ui.settings

import android.media.AudioManager
import android.view.KeyEvent
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whispertype.android.R
import com.whispertype.android.audio.AudioInputDevices
import com.whispertype.android.core.audio.AudioInputSelection
import com.whispertype.android.core.model.AudioSourcePreference
import com.whispertype.android.core.model.HotkeyShortcut
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.TranscriptionStyle
import com.whispertype.android.core.groq.GroqKeyValidation
import com.whispertype.android.data.secrets.KeyProvider
import com.whispertype.android.data.settings.SettingsRepository
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Settings screen: runtime toggles from [SettingsRepository] plus Gemini and
 * Groq API key management via [KeyProvider]. Purely a UI shell — all
 * persistence is delegated to the repositories, which carry their own tests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    keyProvider: KeyProvider,
    groqKeyProvider: KeyProvider,
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
    val segmentAtSilence by settings.segmentAtSilence.collectAsStateWithLifecycle(initialValue = false)
    val polishLevel by settings.polishLevel
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_POLISH_LEVEL)
    val audioSourcePreference by settings.audioSourcePreference
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_AUDIO_SOURCE_PREFERENCE)
    val hotkeyKeycode by settings.hotkeyKeycode
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_HOTKEY_KEYCODE)
    val hotkeyModifiers by settings.hotkeyModifiers
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_HOTKEY_MODIFIERS)
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

    // 0.7.0 Groq key management (mirrors the Gemini key card).
    var hasGroqKey by remember { mutableStateOf(groqKeyProvider.hasKey()) }
    var groqKeyInput by remember { mutableStateOf("") }
    var groqKeyFeedback by remember { mutableStateOf<String?>(null) }

    val keySavedMessage = stringResource(R.string.settings_key_saved)
    val keySaveFailedMessage = stringResource(R.string.settings_key_save_failed)
    val keyClearedMessage = stringResource(R.string.settings_key_cleared)
    val groqSavedMessage = stringResource(R.string.settings_groq_saved)
    val groqSaveFailedMessage = stringResource(R.string.settings_groq_save_failed)
    val groqClearedMessage = stringResource(R.string.settings_groq_cleared)
    val groqInvalidMessage = stringResource(R.string.settings_groq_invalid)

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
                    title = stringResource(R.string.settings_segment_at_silence),
                    description = stringResource(R.string.settings_segment_at_silence_desc),
                ) {
                    Switch(
                        checked = segmentAtSilence,
                        onCheckedChange = { scope.launch { settings.setSegmentAtSilence(it) } },
                    )
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

                // 0.6.0: recording input device. Phone mic by default; the
                // connected bluetooth headset only when explicitly selected.
                SettingRow(
                    title = stringResource(R.string.settings_audio_source),
                    description = stringResource(R.string.settings_audio_source_desc),
                ) {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { menuOpen = true }) {
                            Text(stringResource(audioSourceLabelRes(audioSourcePreference)))
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            AudioSourcePreference.entries.forEach { preference ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(audioSourceLabelRes(preference))) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch { settings.setAudioSourcePreference(preference) }
                                    },
                                )
                            }
                        }
                    }
                }
                if (audioSourcePreference == AudioSourcePreference.BLUETOOTH) {
                    // Re-query the connected bluetooth device when the choice
                    // (re)selects Bluetooth, so the status stays current.
                    val context = LocalContext.current
                    val bluetoothDeviceName = remember(context, audioSourcePreference) {
                        context.getSystemService(AudioManager::class.java)?.let { audioManager ->
                            AudioInputSelection.selectBluetoothHeadset(
                                AudioInputDevices(audioManager).listAll(),
                            )?.name
                        }
                    }
                    Text(
                        text = if (bluetoothDeviceName != null) {
                            stringResource(R.string.settings_audio_source_bluetooth_found, bluetoothDeviceName)
                        } else {
                            stringResource(R.string.settings_audio_source_bluetooth_missing)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // 0.6.0: physical-keyboard hotkey to start/complete dictation.
                // Press-to-set capture: tapping the button grabs keyboard focus;
                // the next key (optionally with a Ctrl/Alt/Shift/Meta combo) is
                // recorded. Esc cancels.
                SettingRow(
                    title = stringResource(R.string.settings_hotkey),
                    description = stringResource(R.string.settings_hotkey_desc),
                ) {
                    var capturing by remember { mutableStateOf(false) }
                    val focusRequester = remember { FocusRequester() }
                    LaunchedEffect(capturing) {
                        if (capturing) focusRequester.requestFocus()
                    }
                    Box(
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .focusable()
                            .onKeyEvent { event ->
                                if (!capturing) return@onKeyEvent false
                                val native = event.nativeKeyEvent
                                when (native.action) {
                                    KeyEvent.ACTION_DOWN -> {
                                        when (native.keyCode) {
                                            KeyEvent.KEYCODE_ESCAPE -> {
                                                capturing = false
                                                true
                                            }
                                            in HOTKEY_MODIFIER_KEYS -> true // keep waiting
                                            else -> {
                                                scope.launch {
                                                    settings.setHotkeyKeycode(native.keyCode)
                                                    settings.setHotkeyModifiers(
                                                        native.metaState and HotkeyShortcut.MODIFIER_MASK,
                                                    )
                                                }
                                                capturing = false
                                                true
                                            }
                                        }
                                    }
                                    KeyEvent.ACTION_UP -> false
                                    else -> false
                                }
                            },
                    ) {
                        if (capturing) {
                            Text(stringResource(R.string.settings_hotkey_capture))
                        } else {
                            OutlinedButton(onClick = { capturing = true }) {
                                Text(hotkeyLabel(hotkeyKeycode, hotkeyModifiers))
                            }
                        }
                    }
                    if (capturing) {
                        TextButton(onClick = { capturing = false }) {
                            Text(stringResource(R.string.settings_hotkey_cancel))
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



                // 0.7.0 Groq account (auto-dialed when this key is present).
                Text(
                    text = stringResource(R.string.settings_groq_key),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        if (hasGroqKey) R.string.settings_groq_key_configured else R.string.settings_groq_key_missing,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasGroqKey) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                OutlinedTextField(
                    value = groqKeyInput,
                    onValueChange = { groqKeyInput = it },
                    label = { Text(stringResource(R.string.settings_groq_key_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val candidate = groqKeyInput.trim()
                            if (!GroqKeyValidation.looksValid(candidate)) {
                                groqKeyFeedback = groqInvalidMessage
                            } else {
                                scope.launch {
                                    val saved = groqKeyProvider.storeKey(candidate)
                                    groqKeyFeedback = if (saved) {
                                        hasGroqKey = true
                                        groqKeyInput = ""
                                        groqSavedMessage
                                    } else {
                                        groqSaveFailedMessage
                                    }
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.settings_groq_save))
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                groqKeyProvider.deleteKey()
                                hasGroqKey = false
                                groqKeyFeedback = groqClearedMessage
                            }
                        },
                    ) {
                        Text(stringResource(R.string.settings_groq_clear))
                    }
                }
                groqKeyFeedback?.let {
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

/** 0.7.0: the three user-selectable polish backends (NONE is internal-only). */


@StringRes
private fun audioSourceLabelRes(preference: AudioSourcePreference): Int = when (preference) {
    AudioSourcePreference.DEFAULT -> R.string.audio_source_phone
    AudioSourcePreference.BLUETOOTH -> R.string.audio_source_bluetooth
}

/** 0.6.0 physical-keyboard hotkey choices: (keycode, label). 0 = disabled. */

/**
 * 0.6.0: renders the configured hotkey as a readable label, e.g. `Ctrl + F9`,
 * `Shift + Grave`, or `Off`. Key codes that are themselves modifiers are not
 * capturable as a hotkey (see [HOTKEY_MODIFIER_KEYS]).
 */
@Composable
private fun hotkeyLabel(keycode: Int, modifiers: Int): String {
    if (keycode == 0) return stringResource(R.string.hotkey_off)
    val parts = buildList {
        if (modifiers and HotkeyShortcut.META_CTRL_ON != 0) add(stringResource(R.string.hotkey_mod_ctrl))
        if (modifiers and HotkeyShortcut.META_ALT_ON != 0) add(stringResource(R.string.hotkey_mod_alt))
        if (modifiers and HotkeyShortcut.META_SHIFT_ON != 0) add(stringResource(R.string.hotkey_mod_shift))
        if (modifiers and HotkeyShortcut.META_META_ON != 0) add(stringResource(R.string.hotkey_mod_meta))
        val res = KEY_LABEL_RES[keycode]
        add(if (res != null) stringResource(res) else keyFallbackLabel(keycode))
    }
    return parts.joinToString(" + ")
}

/** Friendly labels for common keys (falls back to the raw KeyEvent name). */
private val KEY_LABEL_RES: Map<Int, Int> = mapOf(
    android.view.KeyEvent.KEYCODE_GRAVE to R.string.hotkey_grave,
    android.view.KeyEvent.KEYCODE_F9 to R.string.hotkey_f9,
    android.view.KeyEvent.KEYCODE_F10 to R.string.hotkey_f10,
    android.view.KeyEvent.KEYCODE_F11 to R.string.hotkey_f11,
    android.view.KeyEvent.KEYCODE_SCROLL_LOCK to R.string.hotkey_scroll_lock,
    android.view.KeyEvent.KEYCODE_SPACE to R.string.hotkey_space,
    android.view.KeyEvent.KEYCODE_ENTER to R.string.hotkey_enter,
    android.view.KeyEvent.KEYCODE_TAB to R.string.hotkey_tab,
)

/** Raw KeyEvent name for an uncaptured-by-default key, e.g. "Keycode F12". */
private fun keyFallbackLabel(keycode: Int): String =
    try {
        android.view.KeyEvent.keyCodeToString(keycode)?.removePrefix("KEYCODE_")
            ?.replace('_', ' ') ?: "Key $keycode"
    } catch (_: Throwable) {
        "Key $keycode"
    }

/** Modifier keys that cannot themselves be the hotkey (they only combine). */
private val HOTKEY_MODIFIER_KEYS: Set<Int> = setOf(
    android.view.KeyEvent.KEYCODE_CTRL_LEFT,
    android.view.KeyEvent.KEYCODE_CTRL_RIGHT,
    android.view.KeyEvent.KEYCODE_ALT_LEFT,
    android.view.KeyEvent.KEYCODE_ALT_RIGHT,
    android.view.KeyEvent.KEYCODE_SHIFT_LEFT,
    android.view.KeyEvent.KEYCODE_SHIFT_RIGHT,
    android.view.KeyEvent.KEYCODE_META_LEFT,
    android.view.KeyEvent.KEYCODE_META_RIGHT,
)
