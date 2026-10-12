package com.veyronmonitor.app.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.veyronmonitor.app.api.TumcApi
import com.veyronmonitor.app.data.CredentialStore
import com.veyronmonitor.app.data.ScheduleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L)
        val isStart = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_START, true)
        val pendingResult = goAsync()

        // A fresh short-lived scope for this one background task -- the
        // receiver instance doesn't outlive onReceive, so we can't use a
        // lifecycle-tied scope here. Work is small (login + one API call)
        // and pendingResult.finish() bounds it to the OS's ~10s window.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val creds = CredentialStore.load(context)
                if (creds != null) {
                    val (username, password) = creds
                    val auth = TumcApi.login(username, password)
                    val device = TumcApi.getDevices(auth).firstOrNull()
                    if (device != null) {
                        val value = if (isStart) "2" else "3" // 2 = Solar+Utility, 3 = Solar Only
                        // Confirmed via network capture: the real command key
                        // is "S06" with value "PCP0X", not "PC"/"X".
                        TumcApi.setParam(auth, device, "S06", "PCP0$value")
                    }
                }
            } catch (_: Exception) {
                // Best-effort: a missed run isn't retried mid-window, but the
                // next scheduled occurrence (tomorrow) will still fire normally.
            } finally {
                // Exact alarms fire once, so re-arm this same slot for tomorrow.
                val schedule = ScheduleStore.getAll(context).firstOrNull { it.id == scheduleId }
                if (schedule != null && schedule.enabled) {
                    val hour = if (isStart) schedule.startHour else schedule.endHour
                    val minute = if (isStart) schedule.startMinute else schedule.endMinute
                    AlarmScheduler.scheduleOne(context, scheduleId, hour, minute, isStart)
                }
                pendingResult.finish()
            }
        }
    }
}
