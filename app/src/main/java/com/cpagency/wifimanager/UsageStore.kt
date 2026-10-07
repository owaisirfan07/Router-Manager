package com.cpagency.wifimanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Monthly internet data usage, on the same cycle as the internet package
 * (default: renews on the 3rd at 12:00 AM).
 *
 * Two sources:
 *  - Huawei WiFi: the router's own byte counters (exact). Covers every
 *    device on the Huawei WiFi together - this router has no per-device count.
 *  - TP-Link: per-device live speed (bytes/s) sampled every few seconds and
 *    added up over time (an estimate - very close over a month, but only
 *    while this phone can reach the TP-Link).
 *
 * At the cycle boundary the month is saved to history automatically and
 * counting starts again from zero. History entries can be deleted.
 */
object UsageStore {
    private const val PREFS = "usage"
    private const val K = "state"
    private const val K_HIST = "history"
    private const val K_DAY = "cycle_day"
    private const val MAX_TP_GAP_MS = 45_000L
    private const val U32 = 4_294_967_296L

    data class Dev(val mac: String, var name: String, var down: Long = 0, var up: Long = 0, var lastSeen: Long = 0)

    data class Cycle(
        val start: Long,
        val end: Long,
        val huaweiDown: Long,
        val huaweiUp: Long,
        val devices: List<Dev>,
        val manual: Boolean = false
    ) {
        val tpDown get() = devices.sumOf { it.down }
        val tpUp get() = devices.sumOf { it.up }
        val totalDown get() = huaweiDown + tpDown
        val totalUp get() = huaweiUp + tpUp
        val total get() = totalDown + totalUp
    }

    private class State {
        var cycleStart = 0L
        var hwDown = 0L; var hwUp = 0L
        var lastHwSent = -1L; var lastHwRecv = -1L; var lastHwAt = 0L
        var lastTpAt = 0L
        val devs = LinkedHashMap<String, Dev>()
    }

    private var st: State? = null
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cycleDay(c: Context) = prefs(c).getInt(K_DAY, 3)
    @Synchronized fun setCycleDay(c: Context, day: Int) {
        prefs(c).edit().putInt(K_DAY, day.coerceIn(1, 28)).apply()
    }

    /** Most recent "day N, 00:00" at or before [t]. */
    fun cycleStartFor(c: Context, t: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = t
            set(Calendar.DAY_OF_MONTH, cycleDay(c)); set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis > t) cal.add(Calendar.MONTH, -1)
        return cal.timeInMillis
    }

    /** Next "day N, 00:00" strictly after [t]. */
    fun nextBoundary(c: Context, t: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = cycleStartFor(c, t) }
        cal.add(Calendar.MONTH, 1)
        return cal.timeInMillis
    }

    // ---------- persistence ----------

    private fun load(c: Context): State {
        st?.let { return it }
        val s = State()
        try {
            val j = JSONObject(prefs(c).getString(K, "{}"))
            s.cycleStart = j.optLong("cs", 0)
            s.hwDown = j.optLong("hd"); s.hwUp = j.optLong("hu")
            s.lastHwSent = j.optLong("ls", -1); s.lastHwRecv = j.optLong("lr", -1); s.lastHwAt = j.optLong("la")
            s.lastTpAt = j.optLong("lt")
            val d = j.optJSONArray("d") ?: JSONArray()
            for (i in 0 until d.length()) {
                val o = d.getJSONObject(i)
                s.devs[o.getString("m")] = Dev(o.getString("m"), o.optString("n"), o.optLong("dn"), o.optLong("up"), o.optLong("ls"))
            }
        } catch (_: Exception) { }
        if (s.cycleStart == 0L) s.cycleStart = cycleStartFor(c, System.currentTimeMillis())
        st = s
        return s
    }

    private fun devsJson(list: Collection<Dev>) = JSONArray(list.map {
        JSONObject().put("m", it.mac).put("n", it.name).put("dn", it.down).put("up", it.up).put("ls", it.lastSeen)
    })

    private fun save(c: Context, s: State) {
        val j = JSONObject().put("cs", s.cycleStart).put("hd", s.hwDown).put("hu", s.hwUp)
            .put("ls", s.lastHwSent).put("lr", s.lastHwRecv).put("la", s.lastHwAt).put("lt", s.lastTpAt)
            .put("d", devsJson(s.devs.values))
        prefs(c).edit().putString(K, j.toString()).apply()
    }

    @Synchronized fun history(c: Context): List<Cycle> {
        val arr = try { JSONArray(prefs(c).getString(K_HIST, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val d = o.optJSONArray("d") ?: JSONArray()
            Cycle(o.getLong("s"), o.getLong("e"), o.optLong("hd"), o.optLong("hu"),
                (0 until d.length()).map { k -> d.getJSONObject(k).let { x -> Dev(x.getString("m"), x.optString("n"), x.optLong("dn"), x.optLong("up")) } },
                o.optBoolean("man"))
        }.sortedByDescending { it.start }
    }

    private fun saveHistory(c: Context, list: List<Cycle>) {
        val arr = JSONArray(list.sortedBy { it.start }.takeLast(36).map {
            JSONObject().put("s", it.start).put("e", it.end).put("hd", it.huaweiDown).put("hu", it.huaweiUp)
                .put("d", devsJson(it.devices)).put("man", it.manual)
        })
        prefs(c).edit().putString(K_HIST, arr.toString()).apply()
    }

    @Synchronized fun deleteHistory(c: Context, cycle: Cycle) {
        saveHistory(c, history(c).filterNot { it.start == cycle.start && it.end == cycle.end })
    }

    // ---------- cycle handling ----------

    private fun archive(c: Context, s: State, end: Long, manual: Boolean) {
        val cyc = Cycle(s.cycleStart, end, s.hwDown, s.hwUp, s.devs.values.filter { it.down + it.up > 0 }.map { it.copy() }, manual)
        if (cyc.total > 0) saveHistory(c, history(c) + cyc)
        s.hwDown = 0; s.hwUp = 0
        s.devs.values.forEach { it.down = 0; it.up = 0 }
    }

    /** Saves the finished month to history if the renewal date has passed. */
    @Synchronized fun rollIfNeeded(c: Context, now: Long = System.currentTimeMillis()): Boolean {
        val s = load(c)
        var rolled = false
        var boundary = nextBoundary(c, s.cycleStart)
        while (now >= boundary) {
            archive(c, s, boundary, manual = false)
            s.cycleStart = boundary
            boundary = nextBoundary(c, s.cycleStart)
            rolled = true
        }
        if (rolled) save(c, s)
        return rolled
    }

    /** Manual reset: saves what's counted so far to history and starts from zero now. */
    @Synchronized fun resetNow(c: Context) {
        val s = load(c)
        val now = System.currentTimeMillis()
        archive(c, s, now, manual = true)
        s.cycleStart = now
        save(c, s)
    }

    // ---------- feeding data ----------

    private fun delta(old: Long, new: Long): Long = when {
        old < 0 || new < 0 -> 0
        new >= old -> new - old
        old < U32 && old > U32 * 3 / 4 && new < U32 / 4 -> new + U32 - old   // 32-bit counter wrapped
        else -> new                                                          // router restarted: counted from 0 again
    }

    /** Huawei WiFi byte counters (sent = download to devices, received = upload). */
    @Synchronized fun addHuawei(c: Context, sent: Long, recv: Long, now: Long = System.currentTimeMillis()) {
        rollIfNeeded(c, now)
        val s = load(c)
        if (s.lastHwSent >= 0) {
            val secs = ((now - s.lastHwAt) / 1000.0).coerceAtLeast(1.0)
            // ignore impossible jumps (> 1 Gbit/s average)
            val maxBytes = (secs * 125_000_000).toLong()
            val dDown = delta(s.lastHwSent, sent).takeIf { it <= maxBytes } ?: 0
            val dUp = delta(s.lastHwRecv, recv).takeIf { it <= maxBytes } ?: 0
            s.hwDown += dDown; s.hwUp += dUp
        }
        s.lastHwSent = sent; s.lastHwRecv = recv; s.lastHwAt = now
        save(c, s)
    }

    /** TP-Link live speeds -> bytes, over the time since the last sample (max 45 s). */
    @Synchronized fun addTpLink(c: Context, list: List<TpLinkClient.Station>, now: Long = System.currentTimeMillis()) {
        rollIfNeeded(c, now)
        val s = load(c)
        val gap = now - s.lastTpAt
        val secs = if (s.lastTpAt > 0 && gap in 1..MAX_TP_GAP_MS) gap / 1000.0 else 0.0
        for (st in list) {
            val d = s.devs.getOrPut(st.mac) { Dev(st.mac, st.name) }
            if (st.name.isNotBlank() && st.name != "Unknown") d.name = st.name
            if (st.online) {
                d.lastSeen = now
                d.down += (st.downBps * secs).toLong()
                d.up += (st.upBps * secs).toLong()
            }
        }
        s.lastTpAt = now
        save(c, s)
    }

    @Synchronized fun current(c: Context): Cycle {
        rollIfNeeded(c)
        val s = load(c)
        return Cycle(s.cycleStart, nextBoundary(c, s.cycleStart), s.hwDown, s.hwUp,
            s.devs.values.map { it.copy() }.sortedByDescending { it.down + it.up })
    }

    fun lastTpSample(c: Context): Long = synchronized(this) { load(c).lastTpAt }
    fun lastHwSample(c: Context): Long = synchronized(this) { load(c).lastHwAt }

    fun fmt(bytes: Long): String = when {
        bytes >= 1_000_000_000_000 -> "%.2f TB".format(bytes / 1e12)
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1e6)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1e3)
        else -> "$bytes B"
    }
}
