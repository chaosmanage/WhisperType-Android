package com.whispertype.android

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.whispertype.android.platform.runtime.FlowRuntimeService
import com.whispertype.android.ui.theme.WhisperTypeTheme

/**
 * Feature host / overlay-permission onboarding (Phase 1 gate). WhisperType shows
 * a persistent application overlay that requires `Settings.canDrawOverlays()`;
 * the runtime service is started only after that gate passes on the owning
 * device, per §4.1 (SYSTEM_ALERT_WINDOW) and the Phase 1 Samsung gate.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WhisperTypeTheme { OverlayPermissionScreen() }
        }
        if (canDrawOverlays()) {
            startRuntime()
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
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri(),
        )
        startActivity(intent)
    }

    private fun startRuntime() {
        val intent = Intent(this, FlowRuntimeService::class.java)
        // No foreground-session active: keep it simple for Phase 1-4 static stage.
        startForegroundService(intent)
    }

    @Composable
    private fun OverlayPermissionScreen() {
        var granted by remember { mutableStateOf(canDrawOverlays()) }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.overlay_permission_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.overlay_permission_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(24.dp))
                if (granted) {
                    Text(
                        text = stringResource(R.string.overlay_permission_granted),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    OutlinedButton(onClick = {
                        requestOverlayPermission()
                    }) {
                        Text(stringResource(R.string.overlay_permission_button))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    granted = canDrawOverlays()
                    if (granted) {
                        startRuntime()
                        Toast.makeText(
                            this@MainActivity,
                            R.string.runtime_started_toast,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) {
                    Text(stringResource(R.string.overlay_permission_button))
                }
            }
        }
    }
}
