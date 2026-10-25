package com.videodownloader.pro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Servicio en primer plano: mantiene vivo el proceso (y la CPU/WiFi despiertos)
 * mientras haya descargas, aunque el usuario salga de la app.
 */
class DownloadService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: "Descargando…"
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        acquireLocks()
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Descargas", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vdp_download)
            .setContentTitle("Video Downloader Pro")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(pi)
            .build()
    }

    private fun acquireLocks() {
        try {
            if (wake == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vdp:download").apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L)
                }
            }
            if (wifi == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vdp:download").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifi?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wake = null
        wifi = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "vdp_downloads"
        private const val NOTIF_ID = 4711

        fun start(ctx: Context, text: String) {
            try {
                val i = Intent(ctx, DownloadService::class.java).putExtra("text", text)
                androidx.core.content.ContextCompat.startForegroundService(ctx, i)
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, DownloadService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
