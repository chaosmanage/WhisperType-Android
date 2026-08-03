package com.whispertype.android.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.whispertype.android.settings.DockSettings
import com.whispertype.android.settings.DockSize

/** Circular microphone button shown at the keyboard's top edge. */
@Composable
fun DockedMicOverlay(
    settings: DockSettings,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val size = when (settings.size) {
        DockSize.COMPACT -> 48.dp
        DockSize.STANDARD -> 56.dp
        DockSize.LARGE -> 64.dp
    }
    val hapticFeedback = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = settings.opacity))
            .clickable(
                onClick = {
                    if (settings.hapticsEnabled) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    onTap()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "WhisperType microphone",
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
