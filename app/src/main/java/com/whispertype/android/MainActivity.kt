package com.whispertype.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.whispertype.android.data.history.EncryptedHistoryRepository
import com.whispertype.android.data.history.HistoryStats
import com.whispertype.android.data.secrets.AndroidKeystoreKeyStore
import com.whispertype.android.data.secrets.FileBlobStore
import com.whispertype.android.data.secrets.JavaxAesGcmCipher
import com.whispertype.android.data.secrets.KeystoreKeyProvider
import com.whispertype.android.data.secrets.KeyProvider
import com.whispertype.android.data.settings.SettingsRepository
import com.whispertype.android.platform.accessibility.WhisperTypeAccessibilityService
import com.whispertype.android.platform.runtime.FlowRuntimeService
import com.whispertype.android.ui.history.HistoryScreen
import com.whispertype.android.ui.settings.SettingsScreen
import com.whispertype.android.ui.theme.WhisperTypeTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Feature host. Shows the overlay-permission onboarding when it is missing,
 * otherwise a home/status screen that links to [SettingsScreen] (runtime
 * toggles + Gemini API key). The runtime foreground service is started only
 * after the `Settings.canDrawOverlays()` gate passes, per §4.1 and the Phase 1
 * Samsung gate.
 */
class MainActivity : ComponentActivity() {

    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private val keyProvider by lazy { KeystoreKeyProvider(applicationContext) }
    private val historyRepository by lazy {
        EncryptedHistoryRepository(
            keystore = AndroidKeystoreKeyStore(EncryptedHistoryRepository.DEFAULT_KEY_ALIAS),
            cipher = JavaxAesGcmCipher(),
            blobStore = FileBlobStore(applicationContext, EncryptedHistoryRepository.DEFAULT_FILE_NAME),
            retentionDays = { cachedHistoryRetentionDays },
        )
    }
    @Volatile
    private var cachedHistoryRetentionDays = SettingsRepository.DEFAULT_RETENTION_DAYS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WhisperTypeTheme {
                if (canDrawOverlays()) {
                    HomeScreen(
                        settings = settingsRepository,
                        keyProvider = keyProvider,
                        isAccessibilityEnabled = ::isAccessibilityEnabled,
                    )
                } else {
                    OverlayPermissionScreen(onRequest = ::requestOverlayPermission)
                }
            }
        }
        if (canDrawOverlays()) {
            startRuntime()
        }
        lifecycleScope.launch {
            settingsRepository.historyRetentionDays.collect { cachedHistoryRetentionDays = it }
        }
    }

    override fun onResume() {
        super.onResume()
        if (canDrawOverlays()) {
            startRuntime()
        }
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri(),
            ),
        )
    }

    private fun startRuntime() {
        startForegroundService(Intent(this, FlowRuntimeService::class.java))
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${WhisperTypeAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /** Runtime permissions required for dictation on Android 13+ (minSdk 33). */
    private fun runtimePermissionsNeeded(): List<String> = buildList {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            add(Manifest.permission.RECORD_AUDIO)
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // Screens
    // ------------------------------------------------------------------

    @Composable
    private fun OverlayPermissionScreen(onRequest: () -> Unit) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.overlay_permission_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.overlay_permission_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onRequest) {
                    Text(stringResource(R.string.overlay_permission_button))
                }
            }
        }
    }

    @Composable
    private fun HomeScreen(
        settings: SettingsRepository,
        keyProvider: KeyProvider,
        isAccessibilityEnabled: () -> Boolean,
    ) {
        var showSettings by remember { mutableStateOf(false) }
        var showHistory by remember { mutableStateOf(false) }
        var neededPermissions by remember { mutableStateOf(runtimePermissionsNeeded()) }
        val historyEntries by historyRepository.events().collectAsState(initial = emptyList())
        val historyEnabled by settings.historyEnabled.collectAsState(initial = false)
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            neededPermissions = runtimePermissionsNeeded()
        }
        // 0.4.2: back returns Home from Settings/History instead of closing the app.
        if (showSettings || showHistory) {
            BackHandler {
                showSettings = false
                showHistory = false
            }
        }
        if (showHistory) {
            HistoryScreen(
                historyRepository = historyRepository,
                onBack = { showHistory = false },
                onCopied = { msg -> Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() },
            )
        } else if (showSettings) {
            SettingsScreen(
                settings = settings,
                keyProvider = keyProvider,
                onBack = { showSettings = false },
                onOpenHistory = {
                    showSettings = false
                    showHistory = true
                },
            )
        } else {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .widthIn(max = 420.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.home_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(Modifier.height(24.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            StatusRow(
                                label = stringResource(R.string.home_status_overlay),
                                on = true,
                            )
                            HorizontalDivider()
                            StatusRow(
                                label = stringResource(R.string.home_status_runtime),
                                on = FlowRuntimeService.isRunning,
                            )
                            HorizontalDivider()
                            StatusRow(
                                label = stringResource(R.string.home_status_accessibility),
                                on = isAccessibilityEnabled(),
                            )
                            HorizontalDivider()
                            StatusRow(
                                label = stringResource(R.string.home_status_key),
                                on = keyProvider.hasKey(),
                            )
                            HorizontalDivider()
                            StatusRow(
                                label = stringResource(R.string.home_status_mic),
                                on = hasMicPermission(),
                            )
                            HorizontalDivider()
                            StatusRow(
                                label = stringResource(R.string.home_status_notifications),
                                on = hasNotificationPermission(),
                            )
                        }
                    }
                    if (neededPermissions.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { permissionLauncher.launch(neededPermissions.toTypedArray()) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.home_grant_permissions))
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    StatsCard(
                        historyEnabled = historyEnabled,
                        entries = historyEntries,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { showSettings = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.home_open_settings))
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showHistory = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.home_open_history))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(
                            R.string.home_version,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                            BuildConfig.GIT_COMMIT,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusRow(label: String, on: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(if (on) R.string.status_on else R.string.status_off),
                style = MaterialTheme.typography.bodyLarge,
                color = if (on) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }

    /** 0.4.2 home stats card: sessions, words, today's words, and average WPM
     *  derived from history entries (which carry recorded durations). Shows an
     *  enable hint while history is off. */
    @Composable
    private fun StatsCard(
        historyEnabled: Boolean,
        entries: List<com.whispertype.android.data.history.HistoryRepository.HistoryEntry>,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.home_stats_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (!historyEnabled) {
                    Text(
                        text = stringResource(R.string.home_stats_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val wpm = HistoryStats.wordsPerMinute(entries)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        StatItem(label = stringResource(R.string.home_stats_sessions), value = HistoryStats.sessions(entries).toString())
                        StatItem(
                            label = stringResource(R.string.home_stats_words),
                            value = HistoryStats.totalWords(entries).toString(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        StatItem(
                            label = stringResource(R.string.home_stats_today),
                            value = HistoryStats.todayWords(entries, System.currentTimeMillis()).toString(),
                        )
                        StatItem(
                            label = stringResource(R.string.home_stats_wpm),
                            value = wpm?.roundToInt()?.toString() ?: "—",
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StatItem(label: String, value: String) {
        Column {
            Text(text = value, style = MaterialTheme.typography.titleLarge)
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
