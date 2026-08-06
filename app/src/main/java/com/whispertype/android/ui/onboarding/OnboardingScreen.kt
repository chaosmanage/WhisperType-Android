package com.whispertype.android.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.whispertype.android.R
import com.whispertype.android.data.secrets.KeyProvider
import kotlinx.coroutines.launch

/**
 * First-run orientation + permission guide (0.4.2). Shown before the overlay
 * permission is granted; walks the user through the four system permissions and
 * explains how dictation works. The checklist re-reads every permission on
 * [Lifecycle.Event.ON_RESUME], so returning from a Settings/permission screen
 * reflects the change immediately and the screen hands off to Home once the
 * overlay permission is granted.
 */
@Composable
fun OnboardingScreen(
    overlayGranted: Boolean,
    keyProvider: KeyProvider,
    hasMic: () -> Boolean,
    hasNotifications: () -> Boolean,
    hasAccessibility: () -> Boolean,
    onRequestOverlay: () -> Unit,
    onRequestMicNotifications: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onContinue: () -> Unit,
) {
    var refresh by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val micGranted = remember(refresh) { hasMic() }
    val notificationsGranted = remember(refresh) { hasNotifications() }
    val accessibilityOn = remember(refresh) { hasAccessibility() }
    val keySet = remember(refresh) { keyProvider.hasKey() }
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var keyFeedback by remember { mutableStateOf<String?>(null) }
    val keySavedLabel = stringResource(R.string.settings_key_saved)
    val keyFailedLabel = stringResource(R.string.settings_key_save_failed)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_bubble_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(22.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(24.dp))

            // How it works
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_how_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    HowStep(1, stringResource(R.string.onboarding_how_1))
                    HowStep(2, stringResource(R.string.onboarding_how_2))
                    HowStep(3, stringResource(R.string.onboarding_how_3))
                }
            }
            Spacer(Modifier.height(16.dp))

            // Permissions
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_setup_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    PermissionRow(
                        title = stringResource(R.string.onboarding_overlay),
                        description = stringResource(R.string.onboarding_overlay_desc),
                        granted = overlayGranted,
                        actionLabel = stringResource(R.string.onboarding_grant),
                        onAction = onRequestOverlay,
                    )
                    PermissionRow(
                        title = stringResource(R.string.onboarding_mic),
                        description = stringResource(R.string.onboarding_mic_desc),
                        granted = micGranted,
                        actionLabel = stringResource(R.string.onboarding_grant),
                        onAction = onRequestMicNotifications,
                    )
                    PermissionRow(
                        title = stringResource(R.string.onboarding_notifications),
                        description = stringResource(R.string.onboarding_notifications_desc),
                        granted = notificationsGranted,
                        actionLabel = stringResource(R.string.onboarding_grant),
                        onAction = onRequestMicNotifications,
                    )
                    PermissionRow(
                        title = stringResource(R.string.onboarding_accessibility),
                        description = stringResource(R.string.onboarding_accessibility_desc),
                        granted = accessibilityOn,
                        actionLabel = stringResource(R.string.onboarding_open),
                        onAction = onOpenAccessibility,
                    )
                    PermissionRow(
                        title = stringResource(R.string.onboarding_key),
                        description = stringResource(R.string.onboarding_key_desc),
                        granted = keySet,
                        actionLabel = null,
                        onAction = {},
                    )
                    if (!keySet) {
                        Column(
                            modifier = Modifier.padding(start = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = keyInput,
                                onValueChange = { keyInput = it },
                                label = { Text(stringResource(R.string.settings_key_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        val saved = keyProvider.storeKey(keyInput.trim())
                                        keyFeedback = if (saved) {
                                            keyInput = ""
                                            refresh++
                                            keySavedLabel
                                        } else {
                                            keyFailedLabel
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.settings_key_save))
                            }
                            keyFeedback?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onContinue,
                enabled = overlayGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_get_started))
            }
            if (!overlayGranted) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_overlay_pending_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.onboarding_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HowStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = number.toString(),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (granted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (granted) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.onboarding_done),
                    tint = Color(0xFF22C55E),
                    modifier = Modifier.size(24.dp),
                )
            } else if (actionLabel != null) {
                OutlinedButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
