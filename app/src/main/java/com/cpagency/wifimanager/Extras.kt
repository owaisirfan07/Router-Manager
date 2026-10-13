package com.cpagency.wifimanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Device nicknames (stored on this phone only) and the list of devices already seen. */
object DeviceStore {
    private const val PREFS = "devices"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun norm(mac: String) = mac.trim().lowercase().replace('-', ':')

    fun nickname(c: Context, mac: String): String? = prefs(c).getString("nick_" + norm(mac), null)?.takeIf { it.isNotBlank() }

    fun setNickname(c: Context, mac: String, name: String?) {
        prefs(c).edit().apply { if (name.isNullOrBlank()) remove("nick_" + norm(mac)) else putString("nick_" + norm(mac), name.trim()) }.apply()
    }

    /** Nickname if set, else the name the router reports, else the MAC. */
    fun display(c: Context, mac: String, routerName: String?): String =
        nickname(c, mac) ?: routerName?.takeIf { it.isNotBlank() && it != "Unknown" } ?: norm(mac)

    // ---- new-device alerts ----

    fun alertsOn(c: Context) = prefs(c).getBoolean("alerts", true)
    fun setAlertsOn(c: Context, on: Boolean) = prefs(c).edit().putBoolean("alerts", on).apply()

    private fun known(c: Context): MutableSet<String> = prefs(c).getStringSet("known", emptySet())!!.toMutableSet()

    /**
     * Returns the MACs in [online] that were never seen before, and remembers them.
     * The very first call for a router only learns the list (no alerts for devices
     * that were already there).
     */
    @Synchronized
    fun newDevices(c: Context, source: String, online: List<String>): List<String> {
        val p = prefs(c)
        val k = known(c)
        val macs = online.map { norm(it) }.filter { it.isNotBlank() && it != "00:00:00:00:00:00" }
        val seededKey = "seeded_$source"
        val fresh = if (p.getBoolean(seededKey, false)) macs.filter { it !in k } else emptyList()
        k.addAll(macs)
        p.edit().putStringSet("known", k).putBoolean(seededKey, true).apply()
        fresh.forEach { p.edit().putLong("first_$it", System.currentTimeMillis()).apply() }
        return fresh
    }

    fun firstSeen(c: Context, mac: String): Long = prefs(c).getLong("first_" + norm(mac), 0L)
}

/** Fibre (optical) signal samples, one every ~15 minutes, kept for 90 days. */
object FibreStore {
    private const val PREFS = "fibre"
    private const val KEEP_MS = 90L * 86_400_000L

    data class Sample(val at: Long, val rx: Double, val tx: Double?, val tempC: Double?)
    data class Day(val key: String, val avgRx: Double, val minRx: Double, val maxRx: Double)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(c: Context, s: Sample) {
        val list = all(c).filter { s.at - it.at < KEEP_MS } + s
        val arr = JSONArray(list.map { JSONObject().put("t", it.at).put("rx", it.rx).put("tx", it.tx ?: JSONObject.NULL).put("c", it.tempC ?: JSONObject.NULL) })
        prefs(c).edit().putString("samples", arr.toString()).putLong("last", s.at).apply()
    }

    fun lastAt(c: Context): Long = prefs(c).getLong("last", 0L)

    @Synchronized
    fun all(c: Context): List<Sample> {
        val arr = try { JSONArray(prefs(c).getString("samples", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Sample(o.getLong("t"), o.getDouble("rx"), if (o.isNull("tx")) null else o.optDouble("tx"), if (o.isNull("c")) null else o.optDouble("c"))
        }.sortedBy { it.at }
    }

    fun daily(c: Context): List<Day> {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return all(c).groupBy { fmt.format(it.at) }.toSortedMap().map { (k, v) ->
            Day(k, v.map { it.rx }.average(), v.minOf { it.rx }, v.maxOf { it.rx })
        }
    }
}

/** The internet package speed the user pays for, to compare with speed tests. */
object PlanStore {
    private const val PREFS = "plan"
    fun downMbps(c: Context): Double? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("down", 0f).toDouble().takeIf { it > 0 }
    fun upMbps(c: Context): Double? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("up", 0f).toDouble().takeIf { it > 0 }
    fun set(c: Context, down: Double?, up: Double?) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("down", (down ?: 0.0).toFloat()).putFloat("up", (up ?: 0.0).toFloat()).apply()
    }

    /** PPPoE username saved by the monitor, used in the ISP report. */
    fun account(c: Context): String? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("account", null)
    fun setAccount(c: Context, v: String?) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("account", v).apply()
}
