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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.whispertype.android.data.history.EncryptedHistoryRepository
import com.whispertype.android.data.history.HistoryStats
import com.whispertype.android.data.secrets.AndroidKeystoreKeyStore
import com.whispertype.android.data.secrets.FileBlobStore
import com.whispertype.android.data.secrets.JavaxAesGcmCipher
import com.whispertype.android.data.secrets.GroqKeyProvider
import com.whispertype.android.data.secrets.KeystoreKeyProvider
import com.whispertype.android.data.secrets.KeyProvider
import com.whispertype.android.data.secrets.SystemSensitiveClipboard
import com.whispertype.android.data.settings.SettingsRepository
import com.whispertype.android.platform.accessibility.EligibilityExplanation
import com.whispertype.android.platform.accessibility.WhisperTypeAccessibilityService
import com.whispertype.android.platform.runtime.FlowRuntimeService
import com.whispertype.android.ui.dictionary.DictionaryScreen
import com.whispertype.android.ui.history.HistoryScreen
import com.whispertype.android.ui.onboarding.OnboardingScreen
import com.whispertype.android.ui.settings.SettingsScreen
import com.whispertype.android.ui.theme.WhisperTypeTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val groqKeyProvider by lazy { GroqKeyProvider(applicationContext) }
    private val sensitiveClipboard by lazy { SystemSensitiveClipboard(applicationContext) }
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
            val darkMode by settingsRepository.darkMode
                .collectAsStateWithLifecycle(initialValue = false)
            WhisperTypeTheme(darkTheme = darkMode) {
                // Re-evaluate the overlay gate on every resume so returning from
                // the overlay settings screen immediately updates the checklist
                // (0.4.2). Onboarding stays until the user explicitly continues.
                var overlayGranted by remember { mutableStateOf(canDrawOverlays()) }
                val onboardingDone by settingsRepository.onboardingCompleted
                    .collectAsStateWithLifecycle(initialValue = false)
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            overlayGranted = canDrawOverlays()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { /* result handled on the next ON_RESUME refresh */ }
                val scope = rememberCoroutineScope()
                if (overlayGranted && onboardingDone) {
                    HomeScreen(
                        settings = settingsRepository,
                        keyProvider = keyProvider,
                        isAccessibilityEnabled = ::isAccessibilityEnabled,
                    )
                } else {
                    OnboardingScreen(
                        overlayGranted = overlayGranted,
                        keyProvider = keyProvider,
                        hasMic = ::hasMicPermission,
                        hasNotifications = ::hasNotificationPermission,
                        hasAccessibility = ::isAccessibilityEnabled,
                        onRequestOverlay = ::requestOverlayPermission,
                        onRequestMicNotifications = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.RECORD_AUDIO,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ),
                            )
                        },
                        onOpenAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onContinue = {
                            scope.launch { settingsRepository.setOnboardingCompleted(true) }
                        },
                    )
                }
            }
        }
        if (canDrawOverlays()) {
            startRuntime()
        }
        // 0.5.4: re-enabling the app from the kill switch restarts the runtime.
        // The gate inside startRuntime() keeps every other start path (onResume,
        // the Home Runtime tile) from fighting the kill switch while disabled.
        lifecycleScope.launch {
            settingsRepository.appEnabled.collect { enabled ->
                if (enabled && canDrawOverlays() && !FlowRuntimeService.isRunning) {
                    startRuntime()
                }
            }
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
        // 0.5.4: never start the runtime while the app is disabled — the kill
        // switch is a full stop until the user re-enables it.
        lifecycleScope.launch {
            if (settingsRepository.appEnabled.first()) {
                startForegroundService(Intent(this@MainActivity, FlowRuntimeService::class.java))
            }
        }
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
    private fun HomeScreen(
        settings: SettingsRepository,
        keyProvider: KeyProvider,
        isAccessibilityEnabled: () -> Boolean,
    ) {
        var selectedTab by remember { mutableStateOf(0) }
        var settingsScrollToGemini by remember { mutableStateOf(false) }
        var neededPermissions by remember { mutableStateOf(runtimePermissionsNeeded()) }
        // Created once per HomeScreen instance; each collection re-reads the
        // (now IO-dispatched) cold flow instead of rebuilding it per
        // recomposition.
        val historyEvents = remember(historyRepository) { historyRepository.events() }
        val historyEntries by historyEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val historyEnabled by settings.historyEnabled
            .collectAsStateWithLifecycle(initialValue = false)
        val appEnabled by settings.appEnabled.collectAsStateWithLifecycle(initialValue = true)
        // API-key presence is a blob stat read; probe it off Main once when Home
        // appears and again whenever the user returns to this tab (e.g. after
        // saving a key in Settings) — never on every recomposition.
        val hasGeminiKey by produceState(initialValue = false, selectedTab) {
            if (selectedTab == 0) {
                value = withContext(Dispatchers.IO) { keyProvider.hasKey() }
            }
        }
        // 0.5.2: live eligibility mirror so the "why is the bubble hidden" card
        // stays current while the user sits on Home (the runtime updates it via
        // IPC from the accessibility process).
        val eligibility by produceState(initialValue = FlowRuntimeService.currentEligibility) {
            while (true) {
                value = FlowRuntimeService.currentEligibility
                delay(1000)
            }
        }
        val blockingReasons = remember(eligibility, appEnabled) {
            buildList {
                addAll(EligibilityExplanation.blockingReasons(eligibility))
                // A live dictation intentionally hides the idle bubble; it is
                // not a fault worth showing on Home.
                remove(EligibilityExplanation.REASON_SESSION_ACTIVE)
                // Surface the kill switch even when the accessibility process is
                // down (its eligibility push would not be reaching us).
                if (!appEnabled && !contains(EligibilityExplanation.REASON_APP_DISABLED)) {
                    add(EligibilityExplanation.REASON_APP_DISABLED)
                }
            }
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            neededPermissions = runtimePermissionsNeeded()
        }
        // 0.4.2: back returns Home from any other tab instead of closing the app.
        if (selectedTab != 0) {
            BackHandler { selectedTab = 0 }
        }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_home)) },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Filled.History, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_history)) },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Filled.Book, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_dictionary)) },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_settings)) },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (selectedTab) {
                    1 -> HistoryScreen(
                        historyRepository = historyRepository,
                        settings = settings,
                        sensitiveClipboard = sensitiveClipboard,
                        onBack = { selectedTab = 0 },
                        onCopied = { msg -> Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() },
                    )

                    2 -> DictionaryScreen(
                        settings = settings,
                        onBack = { selectedTab = 0 },
                    )

                    3 -> SettingsScreen(
                        settings = settings,
                        keyProvider = keyProvider,
                        groqKeyProvider = groqKeyProvider,
                        onBack = { selectedTab = 0 },
                        scrollToGemini = settingsScrollToGemini,
                        onGeminiScrollDone = { settingsScrollToGemini = false },
                    )

                    else -> Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
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
                            StatsSection(
                                historyEnabled = historyEnabled,
                                entries = historyEntries,
                            )
                            Spacer(Modifier.height(24.dp))
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        StatusTile(
                                            label = stringResource(R.string.home_status_overlay),
                                            on = canDrawOverlays(),
                                            onFix = { requestOverlayPermission() },
                                            modifier = Modifier.weight(1f),
                                        )
                                        StatusTile(
                                            label = stringResource(R.string.home_status_runtime),
                                            on = FlowRuntimeService.isRunning,
                                            onFix = { startRuntime() },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        StatusTile(
                                            label = stringResource(R.string.home_status_accessibility),
                                            on = isAccessibilityEnabled(),
                                            onFix = {
                                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                        StatusTile(
                                            label = stringResource(R.string.home_status_key),
                                            on = hasGeminiKey,
                                            onFix = {
                                                settingsScrollToGemini = true
                                                selectedTab = 3
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        StatusTile(
                                            label = stringResource(R.string.home_status_mic),
                                            on = hasMicPermission(),
                                            onFix = {
                                                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                        StatusTile(
                                            label = stringResource(R.string.home_status_notifications),
                                            on = hasNotificationPermission(),
                                            onFix = {
                                                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                            if (blockingReasons.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                BubbleHiddenCard(
                                    reasons = blockingReasons,
                                    onOpenAccessibility = {
                                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    },
                                )
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

    /** A compact status tile for the 2-column permissions grid. When off and
     *  [onFix] is provided, tapping the tile runs the fix. */
    @Composable
    private fun StatusTile(
        label: String,
        on: Boolean,
        modifier: Modifier = Modifier,
        onFix: (() -> Unit)? = null,
    ) {
        val fixable = !on && onFix != null
        Surface(
            modifier = modifier
                .height(60.dp)
                .then(if (fixable) Modifier.clickable { onFix() } else Modifier),
            shape = RoundedCornerShape(12.dp),
            color = when {
                on -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                else -> MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            when {
                                on -> R.string.status_on
                                fixable -> R.string.status_fix
                                else -> R.string.status_off
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (on) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = 1,
                    )
                }
                if (fixable) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    /** 0.5.2: explains exactly which conditions are currently hiding the bubble,
     *  so a silent drop (e.g. Android clearing the accessibility service after a
     *  force-stop) is diagnosable and recoverable from the app itself. */
    @Composable
    private fun BubbleHiddenCard(reasons: List<String>, onOpenAccessibility: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.diag_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                reasons.forEach { code ->
                    val res = reasonRes(code)
                    if (res != 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(res),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                if (reasons.contains(EligibilityExplanation.REASON_SERVICE_NOT_CONNECTED)) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onOpenAccessibility,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.diag_open_accessibility))
                    }
                }
            }
        }
    }

    /** Maps an [EligibilityExplanation] reason code to its user-facing string. */
    private fun reasonRes(code: String): Int = when (code) {
        EligibilityExplanation.REASON_SERVICE_NOT_CONNECTED -> R.string.diag_reason_service
        EligibilityExplanation.REASON_NO_EDITOR_FOCUS -> R.string.diag_reason_focus
        EligibilityExplanation.REASON_SECURE_FIELD -> R.string.diag_reason_secure
        EligibilityExplanation.REASON_UNCERTAIN_FIELD -> R.string.diag_reason_uncertain
        EligibilityExplanation.REASON_KEYBOARD_HIDDEN -> R.string.diag_reason_keyboard
        EligibilityExplanation.REASON_MICROPHONE_NOT_GRANTED -> R.string.diag_reason_mic
        EligibilityExplanation.REASON_API_KEY_MISSING -> R.string.diag_reason_key
        EligibilityExplanation.REASON_APP_DISABLED -> R.string.diag_reason_app_disabled
        EligibilityExplanation.REASON_SESSION_ACTIVE -> R.string.diag_reason_session
        else -> 0
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
