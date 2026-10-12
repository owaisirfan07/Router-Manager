package com.veyronmonitor.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WarningLogEntry(
    val timestamp: Long,
    val code: String,
    val message: String,
    val isFault: Boolean
)

/**
 * Watches the live "warning" array and "fault1" field from each poll and
 * records a log entry only when a NEW code appears (not on every single
 * 15-second poll while the same condition persists), so the log reads
 * like a history of events rather than noise.
 */
object WarningLogStore {
    private const val PREFS = "veyron_monitor_warnings"
    private const val KEY_LOG = "log_json"
    private const val KEY_ACTIVE_CODES = "active_codes"
    private const val KEY_LAST_VIEWED_AT = "last_viewed_at"
    private const val MAX_LOG_ENTRIES = 200

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getLog(context: Context): List<WarningLogEntry> {
        val arr = try { JSONArray(prefs(context).getString(KEY_LOG, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            WarningLogEntry(
                timestamp = o.getLong("timestamp"),
                code = o.getString("code"),
                message = o.getString("message"),
                isFault = o.optBoolean("isFault", false)
            )
        }.sortedByDescending { it.timestamp }
    }

    fun lastViewedAt(context: Context): Long = prefs(context).getLong(KEY_LAST_VIEWED_AT, 0L)

    fun markAllViewed(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_VIEWED_AT, System.currentTimeMillis()).apply()
    }

    fun hasUnread(context: Context): Boolean {
        val log = getLog(context)
        val newest = log.maxOfOrNull { it.timestamp } ?: return false
        return newest > lastViewedAt(context)
    }

    /**
     * Call this on every poll with the current warning codes (from the
     * "warning" array) and the current fault code (from "fault1", where
     * "0" or blank means no active fault). Only newly-appeared codes get
     * a fresh log entry; codes that were already active are left alone.
     */
    fun recordActiveCodes(context: Context, warningCodes: List<String>, faultCode: String?) {
        val p = prefs(context)
        val activeNow = mutableSetOf<String>()
        warningCodes.filter { it.isNotBlank() && it != "0" }.forEach { activeNow.add("W:$it") }
        if (!faultCode.isNullOrBlank() && faultCode != "0") activeNow.add("F:$faultCode")

        val previouslyActive = p.getString(KEY_ACTIVE_CODES, "")?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val newlyAppeared = activeNow - previouslyActive

        if (newlyAppeared.isNotEmpty()) {
            val arr = try { JSONArray(p.getString(KEY_LOG, "[]")) } catch (_: Exception) { JSONArray() }
            val now = System.currentTimeMillis()
            newlyAppeared.forEach { tagged ->
                val isFault = tagged.startsWith("F:")
                val code = tagged.substring(2)
                val message = if (isFault) WarningCodes.faultMessage(code) else WarningCodes.warningMessage(code)
                arr.put(
                    JSONObject()
                        .put("timestamp", now)
                        .put("code", code)
                        .put("message", message)
                        .put("isFault", isFault)
                )
            }
            val trimmed = if (arr.length() > MAX_LOG_ENTRIES) {
                JSONArray((arr.length() - MAX_LOG_ENTRIES until arr.length()).map { arr.getJSONObject(it) })
            } else arr
            p.edit().putString(KEY_LOG, trimmed.toString()).apply()
        }

        if (activeNow != previouslyActive) {
            p.edit().putString(KEY_ACTIVE_CODES, activeNow.joinToString(",")).apply()
        }
    }
}
