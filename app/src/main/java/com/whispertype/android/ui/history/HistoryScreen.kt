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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.whispertype.android.R
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.data.history.HistoryRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * History screen: encrypted dictation transcript list with per-entry copy and
 * delete, plus a delete-all action. A UI shell over [HistoryRepository].
 */
@Composable
fun HistoryScreen(
    historyRepository: HistoryRepository,
    onBack: () -> Unit,
    onCopied: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clearedLabel = stringResource(R.string.history_cleared)
    val copiedLabel = stringResource(R.string.history_copied)

    var refreshKey by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<HistoryRepository.HistoryEntry>>(emptyList()) }
    LaunchedEffect(refreshKey) { entries = historyRepository.events().first() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.history_back))
            }
            Text(
                text = stringResource(R.string.history_title),
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(
                onClick = {
                    scope.launch {
                        historyRepository.clear()
                        refreshKey++
                    }
                    onCopied(clearedLabel)
                },
            ) {
                Text(stringResource(R.string.history_delete_all))
            }
        }
        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
