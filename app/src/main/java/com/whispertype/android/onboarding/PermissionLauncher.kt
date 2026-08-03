package com.whispertype.android.onboarding

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri

/** Launchers for the permissions and settings screens used during onboarding. */
data class PermissionLaunchers(
    val launchMicrophone: () -> Unit,
    val launchNotifications: () -> Unit,
    val openAppSettings: () -> Unit,
    val openAccessibilitySettings: () -> Unit,
    val openNotificationSettings: () -> Unit,
)

/**
 * Builds permission and settings-screen launchers scoped to the current composition.
 */
@Composable
fun rememberPermissionLaunchers(
    onMicResult: ((Boolean) -> Unit)? = null,
    onNotificationResult: ((Boolean) -> Unit)? = null,
): PermissionLaunchers {
    val context = LocalContext.current

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onMicResult?.invoke(granted)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onNotificationResult?.invoke(granted)
    }

    return PermissionLaunchers(
        launchMicrophone = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        launchNotifications = {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        openAppSettings = {
            startSafely(
                context,
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
            )
        },
        openAccessibilitySettings = {
            startSafely(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        openNotificationSettings = {
            startSafely(
                context,
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        },
    )
}

private fun startSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Settings screen unavailable on this device.
    }
}
