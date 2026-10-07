package com.cpagency.wifimanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Internet outage log. An outage starts after 2 failed checks in a row
 * (~30 s, so tiny blips don't count) and ends at the first good check.
 */
object OutageStore {
    private const val PREFS = "outages"
    private const val K_LIST = "list"
    private const val K_OPEN = "open"
    private const val MAX = 500

    enum class Kind(val label: String) {
        ISP("ISP / fibre ka masla"),
        INTERNET("ISP se aage internet band"),
        ROUTER("Main router (Huawei) band"),
        UNSEEN("App ne nahi dekha - router dobara connect hua")
    }

    data class Outage(
        val start: Long,
        val end: Long,          // 0 = still going
        val kind: Kind,
        val reason: String,     // e.g. "Fibre signal lost (LOS)"
        val approx: Boolean = false
    ) {
        val durationMs: Long get() = (if (end > 0) end else System.currentTimeMillis()) - start
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun toJson(o: Outage) = JSONObject().put("s", o.start).put("e", o.end).put("k", o.kind.name)
        .put("r", o.reason).put("a", o.approx)

    private fun fromJson(j: JSONObject) = Outage(
        j.getLong("s"), j.optLong("e"), runCatching { Kind.valueOf(j.getString("k")) }.getOrDefault(Kind.ISP),
        j.optString("r"), j.optBoolean("a")
    )

    @Synchronized fun all(c: Context): List<Outage> {
        val arr = try { JSONArray(prefs(c).getString(K_LIST, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }.sortedByDescending { it.start }
    }

    @Synchronized fun open(c: Context): Outage? =
        prefs(c).getString(K_OPEN, null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }

    @Synchronized fun setOpen(c: Context, o: Outage?) {
        prefs(c).edit().apply { if (o == null) remove(K_OPEN) else putString(K_OPEN, toJson(o).toString()) }.apply()
    }

    @Synchronized fun add(c: Context, o: Outage) {
        val list = (all(c) + o).sortedBy { it.start }.takeLast(MAX)
        prefs(c).edit().putString(K_LIST, JSONArray(list.map { toJson(it) }).toString()).apply()
    }

    @Synchronized fun remove(c: Context, o: Outage) {
        val list = all(c).filterNot { it.start == o.start && it.kind == o.kind }.sortedBy { it.start }
        prefs(c).edit().putString(K_LIST, JSONArray(list.map { toJson(it) }).toString()).apply()
    }

    @Synchronized fun clear(c: Context) { prefs(c).edit().remove(K_LIST).apply() }
}
