package com.veyronmonitor.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A single daily recurring schedule: at [startHour]:[startMinute] the app
 * sets the inverter's Charging Priority to "Solar + Utility" (PC=2), and
 * at [endHour]:[endMinute] it sets it back to "Solar Only" (PC=3).
 */
data class ChargeSchedule(
    val id: Long,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val enabled: Boolean = true
)

object ScheduleStore {
    private const val PREFS = "veyron_monitor_schedules"
    private const val KEY_SCHEDULES = "schedules_json"

    fun getAll(context: Context): List<ChargeSchedule> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SCHEDULES, "[]")
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ChargeSchedule(
                id = o.getLong("id"),
                startHour = o.getInt("startHour"),
                startMinute = o.getInt("startMinute"),
                endHour = o.getInt("endHour"),
                endMinute = o.getInt("endMinute"),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }

    private fun saveAll(context: Context, schedules: List<ChargeSchedule>) {
        val arr = JSONArray()
        schedules.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("startHour", s.startHour)
                    .put("startMinute", s.startMinute)
                    .put("endHour", s.endHour)
                    .put("endMinute", s.endMinute)
                    .put("enabled", s.enabled)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SCHEDULES, arr.toString())
            .apply()
    }

    fun add(context: Context, schedule: ChargeSchedule): List<ChargeSchedule> {
        val updated = getAll(context) + schedule
        saveAll(context, updated)
        return updated
    }

    fun remove(context: Context, id: Long): List<ChargeSchedule> {
        val updated = getAll(context).filterNot { it.id == id }
        saveAll(context, updated)
        return updated
    }

    fun setEnabled(context: Context, id: Long, enabled: Boolean): List<ChargeSchedule> {
        val updated = getAll(context).map { if (it.id == id) it.copy(enabled = enabled) else it }
        saveAll(context, updated)
        return updated
    }
}
