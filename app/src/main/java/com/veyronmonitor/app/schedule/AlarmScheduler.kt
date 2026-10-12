package com.veyronmonitor.app.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.veyronmonitor.app.data.ChargeSchedule
import java.util.Calendar

/**
 * Turns a [ChargeSchedule] into two exact daily alarms: one that fires at
 * the start time (switches to "Solar + Utility"), one at the end time
 * (switches back to "Solar Only"). Each firing also re-arms itself for
 * the next day (see ScheduleReceiver), since Android exact alarms only
 * fire once.
 */
object AlarmScheduler {

    const val EXTRA_SCHEDULE_ID = "schedule_id"
    const val EXTRA_IS_START = "is_start"

    private fun requestCode(scheduleId: Long, isStart: Boolean): Int =
        (scheduleId * 2 + if (isStart) 0 else 1).toInt()

    private fun pendingIntent(context: Context, scheduleId: Long, isStart: Boolean): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_IS_START, isStart)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode(scheduleId, isStart), intent, flags)
    }

    /** Next occurrence of [hour]:[minute], today if still upcoming, else tomorrow. */
    fun nextTrigger(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun scheduleOne(context: Context, scheduleId: Long, hour: Int, minute: Int, isStart: Boolean) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = nextTrigger(hour, minute)
        val pi = pendingIntent(context, scheduleId, isStart)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (_: SecurityException) {
            // Exact-alarm permission not granted -- caller should prompt via
            // canScheduleExact() before relying on this.
        }
    }

    fun schedule(context: Context, s: ChargeSchedule) {
        if (!s.enabled) return
        scheduleOne(context, s.id, s.startHour, s.startMinute, isStart = true)
        scheduleOne(context, s.id, s.endHour, s.endMinute, isStart = false)
    }

    fun cancel(context: Context, s: ChargeSchedule) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, s.id, true))
        am.cancel(pendingIntent(context, s.id, false))
    }

    fun rescheduleAll(context: Context, schedules: List<ChargeSchedule>) {
        schedules.forEach { s ->
            cancel(context, s)
            if (s.enabled) schedule(context, s)
        }
    }
}
