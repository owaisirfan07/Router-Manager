package com.veyronmonitor.app.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.veyronmonitor.app.data.ScheduleStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Keep counting units after a phone restart / app update.
            com.veyronmonitor.app.service.MonitorService.start(context)
            MeterReminder.schedule(context)
            ScheduleStore.getAll(context).forEach { schedule ->
                if (schedule.enabled) AlarmScheduler.schedule(context, schedule)
            }
        }
    }
}
