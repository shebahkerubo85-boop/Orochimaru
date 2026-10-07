package ani.sanin.download

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ani.sanin.R

/**
 * Minimal foreground service whose only job is to keep the process alive (and the OS from killing
 * it) while [DownloadManager] has work to do. All real work happens in the manager's coroutines.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        DownloadManager.init(this)
        return START_STICKY
    }

    private fun startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    DownloadNotifier.CHANNEL_ID,
                    getString(R.string.downloads),
                    android.app.NotificationManager.IMPORTANCE_LOW,
                )
                NotificationManagerCompat.from(this).createNotificationChannel(channel)
            }
            val notification: Notification = NotificationCompat.Builder(this, DownloadNotifier.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.downloads))
                .setOngoing(true)
                .build()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(this, DownloadNotifier.PROGRESS_ID, notification, type)
        } catch (_: Exception) {
            // Foreground start not allowed (background start restrictions) — harmless.
        }
    }

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DownloadService::class.java),
                )
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, DownloadService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}