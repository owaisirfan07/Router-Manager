package com.veyronmonitor.app.schedule

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.veyronmonitor.app.MainActivity
import com.veyronmonitor.app.R
import com.veyronmonitor.app.data.MeterStore
import java.util.Calendar

/**
 * Monthly reminder on the 9th at 11:00 to type in the KE meter reading.
 * Re-arms itself for the next month every time it fires.
 */
object MeterReminder {
    const val EXTRA_OPEN_METER = "open_meter"
    private const val CHANNEL_ID = "meter_reminder"
    private const val NOTIF_ID = 909
    private const val REQ = 9090

    fun nextTrigger(now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, MeterStore.READING_DAY)
            set(Calendar.HOUR_OF_DAY, MeterStore.READING_HOUR); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.MONTH, 1)
        return c.timeInMillis
    }

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, REQ, Intent(context, MeterReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = nextTrigger()
        try {
            if (AlarmScheduler.canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun show(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "KE meter reminder", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Monthly reminder to enter the KE meter reading"
                }
            )
        }
        val open = PendingIntent.getActivity(
            context, REQ,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_METER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sun)
            .setContentTitle("KE meter reading ka waqt")
            .setContentText("Meter dekh kar aaj ki reading app mein daal dein.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (_: SecurityException) { }
    }
}

class MeterReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Skip if a reading was already entered in the last 3 days.
        val last = MeterStore.readings(context).lastOrNull()
        if (last == null || System.currentTimeMillis() - last.at > 3 * 86_400_000L) MeterReminder.show(context)
        MeterReminder.schedule(context)
    }
}
