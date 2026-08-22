package com.whispertype.android.ui.dictionary

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whispertype.android.R
import com.whispertype.android.core.dictionary.DictionaryEntry
import com.whispertype.android.data.settings.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Custom dictionary screen (0.4.2, spun out of Settings): recurring-word
 * correction rules applied at insertion. A UI shell over [SettingsRepository].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dictionary by settings.dictionary.collectAsStateWithLifecycle(initialValue = emptyList())
    var dictionaryWord by remember { mutableStateOf("") }
    var dictionaryReplacement by remember { mutableStateOf("") }
    var formFeedback by remember { mutableStateOf<DictionaryFeedback?>(null) }
    var feedbackNonce by remember { mutableIntStateOf(0) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // Clear-all is destructive and irreversible; gate it behind a confirm.
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.settings_dictionary_clear_confirm_title)) },
            text = { Text(stringResource(R.string.settings_dictionary_clear_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        scope.launch { settings.clearDictionary() }
                    },
                ) {
                    Text(stringResource(R.string.settings_dictionary_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.history_cancel))
                }
            },
        )
    }

    // Inline form feedback auto-clears; the nonce re-arms the timer when the
    // same message is shown again in quick succession.
    LaunchedEffect(formFeedback, feedbackNonce) {
        if (formFeedback != null) {
            delay(FEEDBACK_CLEAR_DELAY_MS)
            formFeedback = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_dictionary)) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_dictionary_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Add-word form sits ABOVE the entries list so it never sinks out
            // of view as the list grows.
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
                        if (word.isEmpty()) {
                            formFeedback = DictionaryFeedback(
                                messageRes = R.string.settings_dictionary_blank_word,
                                isError = true,
                            )
                            feedbackNonce++
                        } else {
                            val duplicate = dictionary.any { it.match == word }
                            scope.launch {
                                settings.addDictionaryEntry(DictionaryEntry(word, dictionaryReplacement.trim()))
                            }
                            dictionaryWord = ""
                            dictionaryReplacement = ""
                            if (duplicate) {
                                formFeedback = DictionaryFeedback(
                                    messageRes = R.string.settings_dictionary_duplicate,
                                    isError = false,
                                )
                                feedbackNonce++
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_dictionary_add))
                }
                TextButton(onClick = { showClearConfirm = true }) {
                    Text(stringResource(R.string.settings_dictionary_clear))
                }
            }
            formFeedback?.let { feedback ->
                Text(
                    text = stringResource(feedback.messageRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (feedback.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            if (dictionary.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_dictionary_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column {
                        dictionary.forEach { entry ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = if (entry.replace.isBlank()) {
                                            entry.match
                                        } else {
                                            "${entry.match} → ${entry.replace}"
                                        },
                                    )
                                },
                                trailingContent = {
                                    TextButton(
                                        onClick = {
                                            scope.launch { settings.removeDictionaryEntry(entry.match) }
                                        },
                                    ) {
                                        Text(stringResource(R.string.history_delete))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Transient inline message shown under the add-word form. */
private data class DictionaryFeedback(val messageRes: Int, val isError: Boolean)

private const val FEEDBACK_CLEAR_DELAY_MS = 4_000L
