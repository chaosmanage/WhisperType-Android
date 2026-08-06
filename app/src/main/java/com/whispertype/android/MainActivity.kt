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
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
                        .widthIn(max = 420.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(8.dp))
                    HomeHeader()
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
                                onFix = { startRuntime() },
                            )
                            HorizontalDivider()
                            StatusRow(
                                label = stringResource(R.string.home_status_accessibility),
                                on = isAccessibilityEnabled(),
                                onFix = {
                                    startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                    )
                                },
                            )
                            HorizontalDivider()
                            StatusRow(
                                label = stringResource(R.string.home_status_key),
                                on = keyProvider.hasKey(),
                                onFix = { showSettings = true },
                            )
                            HorizontalDivider()
                            StatusRow(
                                label = stringResource(R.string.home_status_mic),
                                on = hasMicPermission(),
                                onFix = {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                },
                            )
                            HorizontalDivider()
                            StatusRow(
                                label = stringResource(R.string.home_status_notifications),
                                on = hasNotificationPermission(),
                                onFix = {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                                },
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
                    StatsSection(
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
                    Spacer(Modifier.height(24.dp))
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
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    /** 0.4.2 friendly home header: the app logo, title, and a one-line invite. */
    @Composable
    private fun HomeHeader() {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_bubble_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
            )
            Column {
                Text(
                    text = stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    /** A status row. When the status is off and [onFix] is provided, tapping the
     *  row runs the fix (grant the permission / open the relevant settings). */
    @Composable
    private fun StatusRow(
        label: String,
        on: Boolean,
        onFix: (() -> Unit)? = null,
    ) {
        val fixable = !on && onFix != null
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fixable) Modifier.clickable { onFix() } else Modifier)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(
                    when {
                        on -> R.string.status_on
                        fixable -> R.string.status_fix
                        else -> R.string.status_off
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    on -> MaterialTheme.colorScheme.primary
                    fixable -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.error
                },
            )
        }
    }

    /** 0.4.2 colorful dictation stats grid (sessions, words, today, week, WPM,
     *  per-session) derived entirely from history entries — clearing history
     *  resets every stat. Shows an enable hint while history is off. */
    @Composable
    private fun StatsSection(
        historyEnabled: Boolean,
        entries: List<com.whispertype.android.data.history.HistoryRepository.HistoryEntry>,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.home_stats_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))
                if (!historyEnabled) {
                    Text(
                        text = stringResource(R.string.home_stats_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val now = System.currentTimeMillis()
                    val stats = listOf(
                        StatTileData(
                            value = HistoryStats.sessions(entries).toString(),
                            label = stringResource(R.string.home_stats_sessions),
                            color = Color(0xFF0E9B8A),
                        ),
                        StatTileData(
                            value = HistoryStats.totalWords(entries).toString(),
                            label = stringResource(R.string.home_stats_words),
                            color = Color(0xFF3B82F6),
                        ),
                        StatTileData(
                            value = HistoryStats.todayWords(entries, now).toString(),
                            label = stringResource(R.string.home_stats_today),
                            color = Color(0xFFF59E0B),
                        ),
                        StatTileData(
                            value = HistoryStats.weekWords(entries, now).toString(),
                            label = stringResource(R.string.home_stats_week),
                            color = Color(0xFF8B5CF6),
                        ),
                        StatTileData(
                            value = HistoryStats.wordsPerMinute(entries)?.roundToInt()?.toString() ?: "—",
                            label = stringResource(R.string.home_stats_wpm),
                            color = Color(0xFFF43F5E),
                        ),
                        StatTileData(
                            value = HistoryStats.wordsPerSession(entries).toString(),
                            label = stringResource(R.string.home_stats_per_session),
                            color = Color(0xFF22C55E),
                        ),
                    )
                    stats.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { tile ->
                                StatTile(
                                    data = tile,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    private data class StatTileData(val value: String, val label: String, val color: Color)

    @Composable
    private fun StatTile(data: StatTileData, modifier: Modifier = Modifier) {
        Surface(
            modifier = modifier.height(72.dp),
            shape = RoundedCornerShape(12.dp),
            color = data.color.copy(alpha = 0.12f),
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column {
                    Text(
                        text = data.value,
                        style = MaterialTheme.typography.headlineSmall,
                        color = data.color,
                    )
                    Text(
                        text = data.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
