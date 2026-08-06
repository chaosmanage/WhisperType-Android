package com.whispertype.android.platform.overlay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import com.whispertype.android.ui.waveform.RealTimeWaveform
import kotlinx.coroutines.delay

/**
 * Renders the persistent overlay surface for [uiState] and forwards user
 * actions via [onIntent]. The visible surface is derived with [visibilityOf]
 * (PRD §17.2); [OverlayVisibility.Hidden] renders nothing. [appearance] carries
 * the 0.4.2 bubble size / opacity / mini-dot settings.
 */
@Composable
fun WhisperTypeOverlayContent(
    uiState: OverlayUiState,
    appearance: OverlayAppearance = OverlayAppearance(),
    onIntent: (OverlayIntent) -> Unit,
    onDragStart: (() -> Unit)? = null,
    onDragBubble: ((dx: Float, dy: Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
) {
    WhisperTypeTheme {
        when (visibilityOf(uiState)) {
            OverlayVisibility.Hidden -> Unit
            OverlayVisibility.IdleBubble -> IdleBubble(
                appearance = appearance,
                onIntent = onIntent,
                onDragStart = onDragStart,
                onDragBubble = onDragBubble,
                onDragEnd = onDragEnd,
            )
            OverlayVisibility.Starting -> StartingCapsule(onIntent = onIntent)
            OverlayVisibility.Listening -> ListeningCapsule(
                amplitude = (uiState.state as DictationState.Listening).amplitude,
                onIntent = onIntent,
            )
            OverlayVisibility.Finalizing ->
                StatusCapsule(stringResource(R.string.dictation_finalizing))
            OverlayVisibility.Recovering -> RecoveringCapsule(onIntent = onIntent)
            OverlayVisibility.Inserting ->
                StatusCapsule(stringResource(R.string.dictation_inserting))
            OverlayVisibility.Success -> Unit
            OverlayVisibility.CopyAvailable -> CopyAvailablePanel(onIntent = onIntent)
            OverlayVisibility.Error -> ErrorPanel(uiState.state as DictationState.Error, onIntent)
        }
    }
}

/** The idle mic bubble (Wispr-style). While idle and [OverlayAppearance
 *  .miniDotEnabled], it auto-minimizes to a tiny dot after a few seconds unless
 *  tapped. A drag moves the bubble; a tap starts dictation. The 0.4.2 bubble
 *  uses the app logo, user-configured size, and opacity. */
@Composable
private fun IdleBubble(
    appearance: OverlayAppearance,
    onIntent: (OverlayIntent) -> Unit,
    onDragStart: (() -> Unit)?,
    onDragBubble: ((dx: Float, dy: Float) -> Unit)?,
    onDragEnd: (() -> Unit)?,
) {
    var minimized by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val startLabel = stringResource(R.string.dictation_start)
    // 0.4.2 mini-dot: minimize after a quiet idle interval; a new Idle resets.
    LaunchedEffect(Unit) {
        minimized = false
        if (appearance.miniDotEnabled) {
            delay(appearance.miniDotAutoMinimizeMs)
            minimized = true
        }
    }
    val touchMin = with(LocalDensity.current) { 48.dp }
    val bubbleSize = with(LocalDensity.current) { appearance.bubbleSizeDp.dp }

    Box(
        modifier = Modifier.pointerInput(onDragBubble) {
            detectDragGestures(
                onDragStart = { onDragStart?.invoke() },
                onDrag = { change, dragAmount ->
                    change.consume()
                    onDragBubble?.invoke(dragAmount.x, dragAmount.y)
                },
                onDragEnd = { onDragEnd?.invoke() },
                onDragCancel = { onDragEnd?.invoke() },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        if (minimized) {
            // Mini dot: tiny visual (10% of the bubble) with a >=48dp touch target.
            Surface(
                onClick = { onIntent(OverlayIntent.START_DICTATION) },
                modifier = Modifier
                    .sizeIn(minWidth = touchMin, minHeight = touchMin)
                    .size(bubbleSize)
                    .alpha(appearance.opacity)
                    .testTag(stringResource(R.string.test_tag_dot))
                    .semantics { contentDescription = startLabel },
                shape = CircleShape,
                color = Color.Transparent,
                interactionSource = interaction,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Surface(
                        shape = CircleShape,
                        color = com.whispertype.android.ui.theme.BrandColors.Teal,
                        modifier = Modifier.size(12.dp),
                    ) {}
                }
            }
        } else {
            // The bubble IS the app logo, round-clipped — no background circle.
            Surface(
                onClick = { onIntent(OverlayIntent.START_DICTATION) },
                modifier = Modifier
                    .sizeIn(minWidth = touchMin, minHeight = touchMin)
                    .size(bubbleSize)
                    .alpha(if (pressed) appearance.opacity * 0.75f else appearance.opacity)
                    .testTag(stringResource(R.string.test_tag_bubble))
                    .semantics { contentDescription = startLabel },
                shape = CircleShape,
                color = Color.Transparent,
                interactionSource = interaction,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_bubble_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            }
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

/** 0.4.2 recording pill: a thin translucent capsule with Cancel (red X) at the
 *  bubble anchor/left, the live waveform in the center, and Done (green check)
 *  on the right. Done commits the dictation; Cancel discards it. */
@Composable
private fun ListeningCapsule(
    amplitude: Float?,
    onIntent: (OverlayIntent) -> Unit,
) {
    Surface(
        modifier = Modifier.testTag(stringResource(R.string.test_tag_panel)),
        shape = RoundedCornerShape(50),
        color = WhisperTypeColors.SurfaceRaised.copy(alpha = 0.85f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PillAction(
                tag = stringResource(R.string.test_tag_cancel),
                label = stringResource(R.string.dictation_cancel),
                icon = Icons.Filled.Close,
                tint = WhisperTypeColors.ErrorAccent,
                onClick = { onIntent(OverlayIntent.CANCEL) },
            )
            RealTimeWaveform(
                amplitude = amplitude ?: 0f,
                modifier = Modifier.size(width = 72.dp, height = 48.dp),
            )
            PillAction(
                tag = stringResource(R.string.test_tag_stop),
                label = stringResource(R.string.dictation_done),
                icon = Icons.Filled.Check,
                tint = WhisperTypeColors.SuccessAccent,
                onClick = { onIntent(OverlayIntent.STOP) },
            )
        }
    }
}

/** Compact starting pill: Cancel at the bubble anchor plus the live waveform. */
@Composable
private fun StartingCapsule(onIntent: (OverlayIntent) -> Unit) {
    Surface(
        modifier = Modifier.testTag(stringResource(R.string.test_tag_panel)),
        shape = RoundedCornerShape(50),
        color = WhisperTypeColors.SurfaceRaised.copy(alpha = 0.85f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PillAction(
                tag = stringResource(R.string.test_tag_cancel),
                label = stringResource(R.string.dictation_cancel),
                icon = Icons.Filled.Close,
                tint = WhisperTypeColors.ErrorAccent,
                onClick = { onIntent(OverlayIntent.CANCEL) },
            )
            RealTimeWaveform(
                amplitude = 0f,
                modifier = Modifier.size(width = 72.dp, height = 48.dp),
            )
        }
    }
}

/** Compact status pill: the message text only. */
@Composable
private fun StatusCapsule(text: String) {
    Surface(
        modifier = Modifier.testTag(stringResource(R.string.test_tag_panel)),
        shape = RoundedCornerShape(50),
        color = WhisperTypeColors.SurfaceRaised.copy(alpha = 0.85f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = WhisperTypeColors.OnSurface,
        )
    }
}

/** 0.4.2 recovering pill: a status message plus a Cancel so the user can always
 *  bail out while the audio-recovery failsafe re-transcribes. */
@Composable
private fun RecoveringCapsule(onIntent: (OverlayIntent) -> Unit) {
    Surface(
        modifier = Modifier.testTag(stringResource(R.string.test_tag_panel)),
        shape = RoundedCornerShape(50),
        color = WhisperTypeColors.SurfaceRaised.copy(alpha = 0.85f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.dictation_recovering),
                style = MaterialTheme.typography.labelLarge,
                color = WhisperTypeColors.OnSurface,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            PillAction(
                tag = stringResource(R.string.test_tag_cancel),
                label = stringResource(R.string.dictation_cancel),
                icon = Icons.Filled.Close,
                tint = WhisperTypeColors.ErrorAccent,
                onClick = { onIntent(OverlayIntent.CANCEL) },
            )
        }
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
            if (state.failure.retryAllowed) {
                ActionButton(
                    tag = stringResource(R.string.test_tag_retry),
                    label = stringResource(R.string.dictation_retry),
                    icon = Icons.Filled.Refresh,
                    onClick = { onIntent(OverlayIntent.RETRY) },
                )
            }
            ActionButton(
                tag = stringResource(R.string.test_tag_dismiss),
                label = stringResource(R.string.dictation_dismiss),
                icon = Icons.Filled.Close,
                onClick = { onIntent(OverlayIntent.DISMISS) },
            )
        }
    }
}

/** A compact circular icon action inside the recording pill (>=48dp touch). */
@Composable
private fun PillAction(
    tag: String,
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    val minSize = with(LocalDensity.current) { 48.dp }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .sizeIn(minWidth = minSize, minHeight = minSize)
            .testTag(tag),
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(20.dp),
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
    iconTint: Color = Color.Unspecified,
) {
    val minSize = with(LocalDensity.current) { 48.dp }
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .testTag(tag)
            .sizeIn(minWidth = minSize, minHeight = minSize),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label)
    }
}
