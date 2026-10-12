package com.veyronmonitor.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.veyronmonitor.app.MainActivity
import com.veyronmonitor.app.R
import com.veyronmonitor.app.data.CredentialStore
import com.veyronmonitor.app.data.EnergyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps counting units while the app is closed. Polls the cloud every
 * [POLL_MS] and shows a small ongoing notification with live solar power
 * and today's units. This is what fixes the "units stuck at 1.99" problem:
 * before, units were only counted while the app was open on screen.
 */
class MonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "veyron_monitor"
        private const val NOTIF_ID = 42
        private const val POLL_MS = 90_000L
        private const val PREFS = "veyron_monitor_prefs"
        private const val KEY_ENABLED = "background_enabled"

        fun isEnabled(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
            if (enabled) start(context) else stop(context)
        }

        /** Starts the service if the user is logged in and hasn't switched it off. */
        fun start(context: Context) {
            if (!isEnabled(context) || CredentialStore.load(context) == null) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
            } catch (_: Exception) {
                // Android may refuse a start from the background; the
                // WorkManager backup keeps counting until the app is opened.
            }
            BackupWorker.schedule(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(null), type)
        } catch (_: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        // Keep the CPU awake while counting. Without this the phone sleeps when the
        // screen is off, readings stop, and the units of that time are lost.
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VeyronMonitor:counting").apply {
                    setReferenceCounted(false); acquire()
                }
        }
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    if (CredentialStore.load(this@MonitorService) == null) { stopSelf(); break }
                    val reading = try { Poller.fetchOnceLocked(this@MonitorService) } catch (_: Exception) { null }
                    notifyUpdate(reading?.optDouble("pvInputPower1", 0.0))
                    delay(POLL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Live units monitor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows live solar power and today's units while counting in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun notifyUpdate(solarW: Double?) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(solarW))
        } catch (_: Exception) { }
    }

    private fun buildNotification(solarW: Double?): Notification {
        val s = EnergyStore.load(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (solarW != null && !solarW.isNaN()) "Solar ${solarW.toInt()} W  •  Aaj ${"%.2f".format(s.daySolarWh / 1000)} units"
                    else "Aaj solar ${"%.2f".format(s.daySolarWh / 1000)} units"
        val text = "Grid ${"%.2f".format(s.dayGridWh / 1000)} units  •  Ghar ${"%.2f".format(s.dayLoadWh / 1000)} units"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sun)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
