package com.whispertype.android.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whispertype.android.R
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.data.history.HistoryRepository
import com.whispertype.android.data.settings.SettingsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * History screen: encrypted dictation transcript list with per-entry copy and
 * delete. The 0.4.2 redesign uses a standard top app bar (back arrow + title)
 * with a subtle delete-all icon action and a confirmation dialog, and moves the
 * history-recording controls (enable + retention) here from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyRepository: HistoryRepository,
    settings: SettingsRepository,
    onBack: () -> Unit,
    onCopied: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clearedLabel = stringResource(R.string.history_cleared)
    val copiedLabel = stringResource(R.string.history_copied)
    val historyEnabled by settings.historyEnabled.collectAsStateWithLifecycle(initialValue = false)
    val retentionDays by settings.historyRetentionDays
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_RETENTION_DAYS)

    var refreshKey by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<HistoryRepository.HistoryEntry>>(emptyList()) }
    var showClearConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(refreshKey) { entries = historyRepository.events().first() }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.history_delete_all_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_all_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        scope.launch {
                            historyRepository.clear()
                            refreshKey++
                        }
                        onCopied(clearedLabel)
                    },
                ) {
                    Text(stringResource(R.string.history_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.history_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.history_back_desc),
                        )
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearConfirm = true },
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.history_delete_all),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 0.4.2: history recording settings live on this page.
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.settings_history),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.settings_history_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = historyEnabled,
                                onCheckedChange = { scope.launch { settings.setHistoryEnabled(it) } },
                            )
                        }
                        if (historyEnabled) {
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
                }
            }
            if (entries.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.history_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(entries, key = { it.id }) { entry ->
                    HistoryEntryCard(
                        entry = entry,
                        onCopy = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(null, entry.text))
                            onCopied(copiedLabel)
                        },
                        onDelete = {
                            scope.launch {
                                historyRepository.delete(entry.id)
                                refreshKey++
                            }
                        },
                    )
                }
            }
        }
    }
}

private val RETENTION_RANGE_DAYS_F: ClosedFloatingPointRange<Float> = 7f..90f

@Composable
private fun HistoryEntryCard(
    entry: HistoryRepository.HistoryEntry,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val languageRes = LanguageMode.entries.firstOrNull { it.name == entry.language }?.let {
        when (it) {
            LanguageMode.ENGLISH -> R.string.language_english
            LanguageMode.HINGLISH -> R.string.language_hinglish
        }
    }
    val languageLabel = if (languageRes != null) stringResource(languageRes) else entry.language
    val fmtLocale: Locale = LocalConfiguration.current.locales[0]
    val formattedTime = SimpleDateFormat("MMM d, HH:mm", fmtLocale)
        .format(Date(entry.timestampMillis))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = entry.text, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = languageLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.history_copy))
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.history_delete))
                }
            }
        }
    }
}
