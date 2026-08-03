package com.whispertype.android.overlay

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whispertype.android.settings.CancelSide
import com.whispertype.android.settings.DockSettings
import java.util.Locale
import kotlinx.coroutines.delay

/** State-backed inputs for the voice panel, derived from an [OverlayPresentation]. */
data class PanelUiState(
    val text: String? = null,
    val showStop: Boolean = false,
    val showCancel: Boolean = false,
    val amplitude: Float = 0f,
    val elapsedMillis: Long = 0L,
    val copyUi: Boolean = false,
    val errorMessage: String? = null,
    val isListening: Boolean = false,
    val isFinalizing: Boolean = false,
    val isStarting: Boolean = false,
    val isInserting: Boolean = false,
    val resultLength: Int = 0,
) {
    companion object {
        /** Maps a presentation to panel inputs; idle presentations produce defaults. */
        fun fromPresentation(p: OverlayPresentation): PanelUiState = PanelUiState(
            showStop = p.isListening || p.isFinalizing,
            showCancel = p.isListening,
            amplitude = p.amplitude,
            elapsedMillis = p.elapsedMillis,
            copyUi = p.showCopyUi,
            errorMessage = p.errorMessage,
            isListening = p.isListening,
            isFinalizing = p.isFinalizing,
            isStarting = p.isStarting,
            isInserting = p.isInserting,
            resultLength = p.resultLength,
        )
    }
}

/** Full-size panel that covers the keyboard while dictating. */
@Composable
fun VoicePanelOverlay(
    ui: PanelUiState,
    settings: DockSettings,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(visible = true, enter = fadeIn(tween(200))) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            when {
                ui.isStarting -> StartingContent()
                ui.isInserting -> InsertingContent()
                ui.isListening || ui.isFinalizing ->
                    RecordingContent(ui, settings, onStop, onCancel)
                ui.copyUi -> CopyContent(onCopy, onDismiss)
                ui.errorMessage != null -> ErrorContent(ui.errorMessage, onDismiss)
            }
        }
    }
}

@Composable
private fun StartingContent() {
    CenteredColumn {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Starting\u2026", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun InsertingContent() {
    CenteredColumn {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Inserting\u2026", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RecordingContent(
    ui: PanelUiState,
    settings: DockSettings,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    val reducedMotion = rememberReducedMotion()
    CenteredColumn {
        WaveformBars(amplitude = ui.amplitude, barCount = 16, reducedMotion = reducedMotion)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (ui.isFinalizing) "Finalizing\u2026" else "Listening\u2026",
            style = MaterialTheme.typography.titleMedium,
        )
        if (settings.showElapsedTime && ui.isListening) {
            Spacer(modifier = Modifier.height(4.dp))
            val elapsedLabel = remember(ui.elapsedMillis) { formatElapsed(ui.elapsedMillis) }
            Text(
                text = elapsedLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = onStop, modifier = Modifier.size(64.dp)) {
                Icon(imageVector = Icons.Filled.Stop, contentDescription = "Stop dictation")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "Stop", style = MaterialTheme.typography.titleMedium)
        }
        if (ui.showCancel) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (settings.cancelSide == CancelSide.LEFT) {
                    Arrangement.Start
                } else {
                    Arrangement.End
                },
            ) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun CopyContent(onCopy: () -> Unit, onDismiss: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    if (copied) {
        LaunchedEffect(Unit) {
            delay(2000)
            copied = false
        }
    }
    CenteredColumn {
        Text(
            text = if (copied) {
                "Copied \u2713"
            } else {
                "Copied \u2014 paste with SwiftKey/Gboard"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        if (!copied) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = {
                        copied = true
                        onCopy()
                    },
                ) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String?, onDismiss: () -> Unit) {
    CenteredColumn {
        Text(
            text = message ?: "Something went wrong",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onDismiss) { Text("Close") }
    }
}

@Composable
private fun CenteredColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun WaveformBars(amplitude: Float, barCount: Int, reducedMotion: Boolean) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(barCount) { index ->
            val variation = 0.55f + 0.45f * ((index % 3) / 2f)
            val target = if (reducedMotion) {
                0.3f
            } else {
                (0.15f + amplitude.coerceIn(0f, 1f) * variation).coerceIn(0.15f, 1f)
            }
            val heightFraction by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(durationMillis = 120),
            )
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(12.dp + 36.dp * heightFraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getString(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
        ) == "0"
    }
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
