package com.whispertype.android.platform.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whispertype.android.R
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.OverlayIntent
import com.whispertype.android.core.model.OverlayUiState
import com.whispertype.android.ui.theme.WhisperTypeColors
import com.whispertype.android.ui.theme.WhisperTypeTheme

/**
 * Renders the persistent overlay surface for [uiState] and forwards user
 * actions via [onIntent]. The visible surface is derived with [visibilityOf]
 * (PRD §17.2); [OverlayVisibility.Hidden] renders nothing.
 *
 * This composable stays in the platform-overlay package and is importable by
 * the host. No preview annotations are required (AOT-safe).
 */
@Composable
fun WhisperTypeOverlayContent(
    uiState: OverlayUiState,
    onIntent: (OverlayIntent) -> Unit,
) {
    WhisperTypeTheme {
        when (visibilityOf(uiState)) {
            OverlayVisibility.Hidden -> Unit
            OverlayVisibility.IdleBubble -> IdleBubble(onIntent = onIntent)
            OverlayVisibility.Starting -> StartingPanel(onIntent = onIntent)
            OverlayVisibility.Listening -> ListeningPanel(onIntent = onIntent)
            OverlayVisibility.Finalizing ->
                StatusPanel(stringResource(R.string.dictation_finalizing))
            OverlayVisibility.Inserting ->
                StatusPanel(stringResource(R.string.dictation_inserting))
            OverlayVisibility.Success -> Unit
            OverlayVisibility.CopyAvailable -> CopyAvailablePanel(onIntent = onIntent)
            OverlayVisibility.Error -> ErrorPanel(uiState.state as DictationState.Error, onIntent)
        }
    }
}

/** The idle mic bubble: a small rounded target with a >= 48dp touch area. */
@Composable
private fun IdleBubble(onIntent: (OverlayIntent) -> Unit) {
    val minSize = with(LocalDensity.current) { 48.dp }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val startLabel = stringResource(R.string.dictation_start)
    Surface(
        onClick = { onIntent(OverlayIntent.START_DICTATION) },
        modifier = Modifier
            .sizeIn(minWidth = minSize, minHeight = minSize)
            .testTag(stringResource(R.string.test_tag_bubble))
            .semantics { contentDescription = startLabel },
        shape = RoundedCornerShape(50),
        color = if (pressed) {
            WhisperTypeColors.IdleAccent.copy(alpha = 0.75f)
        } else {
            WhisperTypeColors.IdleAccent
        },
        interactionSource = interaction,
    ) {
        Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = WhisperTypeColors.Surface,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Shared panel chrome; every recording/result surface carries testTag "wt_panel". */
@Composable
private fun PanelSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.testTag(stringResource(R.string.test_tag_panel)),
        shape = RoundedCornerShape(16.dp),
        color = WhisperTypeColors.SurfaceRaised,
        tonalElevation = 4.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
private fun StartingPanel(onIntent: (OverlayIntent) -> Unit) {
    PanelSurface {
        Text(
            text = stringResource(R.string.dictation_starting),
            style = MaterialTheme.typography.labelLarge,
            color = WhisperTypeColors.OnSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                tag = stringResource(R.string.test_tag_cancel),
                label = stringResource(R.string.dictation_cancel),
                icon = Icons.Filled.Close,
                onClick = { onIntent(OverlayIntent.CANCEL) },
            )
        }
    }
}

@Composable
private fun ListeningPanel(onIntent: (OverlayIntent) -> Unit) {
    PanelSurface {
        WaveformPlaceholder()
        Text(
            text = stringResource(R.string.dictation_listening),
            style = MaterialTheme.typography.labelLarge,
            color = WhisperTypeColors.OnSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                tag = stringResource(R.string.test_tag_stop),
                label = stringResource(R.string.dictation_stop),
                icon = Icons.Filled.Stop,
                onClick = { onIntent(OverlayIntent.STOP) },
            )
            ActionButton(
                tag = stringResource(R.string.test_tag_cancel),
                label = stringResource(R.string.dictation_cancel),
                icon = Icons.Filled.Close,
                onClick = { onIntent(OverlayIntent.CANCEL) },
            )
        }
    }
}

@Composable
private fun StatusPanel(text: String) {
    PanelSurface {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = WhisperTypeColors.OnSurface,
        )
    }
}


@Composable
private fun CopyAvailablePanel(onIntent: (OverlayIntent) -> Unit) {
    PanelSurface {
        Text(
            text = stringResource(R.string.dictation_copy),
            style = MaterialTheme.typography.labelLarge,
            color = WhisperTypeColors.OnSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                tag = stringResource(R.string.test_tag_copy),
                label = stringResource(R.string.dictation_copy),
                icon = Icons.Filled.ContentCopy,
                onClick = { onIntent(OverlayIntent.COPY) },
            )
            ActionButton(
                tag = stringResource(R.string.test_tag_dismiss),
                label = stringResource(R.string.dictation_dismiss),
                icon = Icons.Filled.Close,
                onClick = { onIntent(OverlayIntent.DISMISS) },
            )
        }
    }
}

@Composable
private fun ErrorPanel(state: DictationState.Error, onIntent: (OverlayIntent) -> Unit) {
    val message: String = state.failure.message.ifBlank {
        stringResource(R.string.dictation_could_not_insert)
    }
    PanelSurface {
        Text(
            text = message,
            style = MaterialTheme.typography.labelLarge,
            color = WhisperTypeColors.ErrorAccent,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                tag = stringResource(R.string.test_tag_dismiss),
                label = stringResource(R.string.dictation_dismiss),
                icon = Icons.Filled.Close,
                onClick = { onIntent(OverlayIntent.DISMISS) },
            )
        }
    }
}

@Composable
private fun ActionButton(
    tag: String,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val minSize = with(LocalDensity.current) { 48.dp }
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .testTag(tag)
            .sizeIn(minWidth = minSize, minHeight = minSize),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = label)
    }
}

/** Static waveform fallback (§17.4 reduced-motion safe); no looping animation. */
@Composable
private fun WaveformPlaceholder() {
    val bars = listOf(8.dp, 20.dp, 12.dp, 24.dp, 10.dp, 16.dp)
    Row(
        modifier = Modifier.height(24.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        bars.forEach { barHeight ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .background(
                        color = WhisperTypeColors.RecordingAccent,
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

