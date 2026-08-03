package com.whispertype.android.dictation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.whispertype.android.MainActivity
import com.whispertype.android.R
import com.whispertype.android.WhisperTypeApplication

/**
 * Keeps the dictation session alive and the mic indication visible while
 * recording. Notification actions route Stop/Cancel back to the active
 * [DictationCoordinator]; [onTaskRemoved] tears the session down when the
 * user swipes the app away.
 */
class DictationForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("NotificationPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> coordinator()?.stop()
            ACTION_CANCEL -> coordinator()?.cancel()
            ACTION_OPEN -> startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            else -> startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }
        return START_NOT_STICKY
    }

    private fun coordinator(): DictationCoordinator? =
        (runCatching { WhisperTypeApplication.instance.dictationBridge }.getOrNull() as? DictationCoordinator)

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.mic_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DictationForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, DictationForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, DictationForegroundService::class.java).setAction(ACTION_OPEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.mic_notification_title))
            .setContentText(getString(R.string.mic_notification_text))
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .addAction(0, getString(R.string.notification_action_cancel), cancelIntent)
            .addAction(0, getString(R.string.notification_action_open), openIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        coordinator()?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
    }

    companion object {
        const val EXTRA_SESSION_ID = "wt_session_id"
        const val ACTION_STOP = "com.whispertype.android.action.STOP"
        const val ACTION_CANCEL = "com.whispertype.android.action.CANCEL"
        const val ACTION_OPEN = "com.whispertype.android.action.OPEN"

        private const val CHANNEL_ID = "whispertype_mic"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var instance: DictationForegroundService? = null
    }
}
