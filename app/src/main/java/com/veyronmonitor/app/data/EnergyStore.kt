package com.veyronmonitor.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The app's own "electricity meter".
 *
 * The i.Solar cloud only gives LIVE power (watts), never a "units today"
 * total, so the app builds the units itself: every time the dongle sends a
 * NEW reading, the energy since the previous reading is added
 * (trapezoid rule: average of the two power values x the time between them).
 *
 *   1 unit = 1 kWh = 1000 W running for 1 hour   (same as the KE meter)
 *
 * Readings are fed in from three places - the open app, the background
 * monitor service and a WorkManager backup job - so everything goes through
 * [process], which is synchronised and ignores a reading it has already
 * counted (same device timestamp). That way nothing is counted twice.
 *
 * If there is a gap longer than [MAX_GAP_MS] between two readings (phone
 * off, no internet, dongle offline) the energy in that gap is NOT guessed;
 * the gap is recorded as "missed minutes" so the screen can show how
 * complete today's number is.
 */
object EnergyStore {
    private const val PREFS = "veyron_monitor_energy"
    private const val SAMPLE_PREFS = "veyron_monitor_samples"
    private const val MAX_HISTORY_DAYS = 400
    private const val SAMPLE_DAYS_KEPT = 8
    const val MAX_GAP_MS = 25 * 60_000L
    /** Gaps between 25 min and this are filled by a straight line between the two readings (marked as estimate). */
    const val FILL_GAP_MS = 2 * 3_600_000L

    // v2 keys (Double stored as raw Long bits for full precision)
    private const val K_VERSION = "v2_ready"
    private const val K_SOLAR = "v2_solar_wh"
    private const val K_GRID = "v2_grid_wh"
    private const val K_LOAD = "v2_load_wh"
    private const val K_SOLAR_SINCE = "solar_since"
    private const val K_GRID_SINCE = "grid_since"
    private const val K_LOAD_SINCE = "v2_load_since"
    private const val K_LAST_TS = "v2_last_ts"
    private const val K_LAST_SOLAR_W = "v2_last_solar_w"
    private const val K_LAST_GRID_W = "v2_last_grid_w"
    private const val K_LAST_LOAD_W = "v2_last_load_w"
    private const val K_LAST_SEEN_AT = "v2_last_seen_at"
    private const val K_DAY = "v2_day"
    private const val K_DAY_SOLAR = "v2_day_solar_wh"
    private const val K_DAY_GRID = "v2_day_grid_wh"
    private const val K_DAY_LOAD = "v2_day_load_wh"
    private const val K_DAY_MISSED = "v2_day_missed_min"
    private const val K_DAY_BYPASS = "v2_day_bypass_min"
    private const val K_DAY_PEAK_W = "v2_day_peak_w"
    private const val K_DAY_PEAK_AT = "v2_day_peak_at"
    private const val K_DAY_CHG = "v3_day_charge_wh"
    private const val K_DAY_DIS = "v3_day_discharge_wh"
    private const val K_DAY_EST = "v3_day_est_wh"
    private const val K_LAST_CHG_W = "v3_last_chg_w"
    private const val K_LAST_DIS_W = "v3_last_dis_w"
    private const val K_AVG_DIS_W = "v3_avg_dis_w"
    private const val K_HISTORY = "history_json"
    private const val K_TARIFF = "tariff_rs"

    // old (v1) keys - only read once for migration
    private const val OLD_SOLAR = "solar_wh"
    private const val OLD_GRID = "grid_wh"
    private const val OLD_DAY_KEY = "current_day_key"
    private const val OLD_DAY_SOLAR = "current_day_solar_wh"
    private const val OLD_DAY_GRID = "current_day_grid_wh"

    private val dayFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val tsFormat get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    data class EnergyState(
        val solarWh: Double = 0.0,
        val gridWh: Double = 0.0,
        val loadWh: Double = 0.0,
        val solarSince: Long = 0L,
        val gridSince: Long = 0L,
        val loadSince: Long = 0L,
        val lastReadingTs: Long = 0L,
        val lastSolarW: Double = 0.0,
        val lastGridW: Double = 0.0,
        val lastLoadW: Double = 0.0,
        val lastSeenAt: Long = 0L,
        val dayKey: String = "",
        val daySolarWh: Double = 0.0,
        val dayGridWh: Double = 0.0,
        val dayLoadWh: Double = 0.0,
        val dayMissedMin: Int = 0,
        val dayBypassMin: Int = 0,
        val dayPeakSolarW: Double = 0.0,
        val dayPeakAt: Long = 0L,
        val dayChargeWh: Double = 0.0,
        val dayDischargeWh: Double = 0.0,
        /** Solar Wh today that came from filling a gap (estimate, not measured). */
        val dayEstWh: Double = 0.0,
        val lastChargeW: Double = 0.0,
        val lastDischargeW: Double = 0.0,
        /** Running average of battery drain (W) while discharging - used for "how long will it last". */
        val avgDischargeW: Double = 0.0
    )

    data class DayRecord(
        val dateKey: String,
        val solarWh: Double,
        val gridWh: Double,
        val loadWh: Double = 0.0,
        val missedMin: Int = 0,
        val peakSolarW: Double = 0.0,
        /** Minutes the house ran straight off the grid (work mode "L", bypass) - unmetered by the inverter. */
        val bypassMin: Int = 0,
        /** False for days saved by older versions (no bypass tracking yet). */
        val tracked: Boolean = true,
        val chargeWh: Double = 0.0,
        val dischargeWh: Double = 0.0,
        val estWh: Double = 0.0
    )

    /** One point of a day's power curve. */
    data class Sample(val minuteOfDay: Int, val solarW: Float, val gridW: Float, val loadW: Float, val batteryPct: Float,
                      /** + charging, - discharging */
                      val batteryW: Float = 0f)

    private val lock = Any()
    private val _state = MutableStateFlow(EnergyState())
    val state: StateFlow<EnergyState> = _state.asStateFlow()
    @Volatile private var loaded = false

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun samplePrefs(c: Context) = c.getSharedPreferences(SAMPLE_PREFS, Context.MODE_PRIVATE)

    private fun SharedPreferences.getD(k: String) = Double.fromBits(getLong(k, 0.0.toRawBits()))
    private fun SharedPreferences.Editor.putD(k: String, v: Double) = putLong(k, v.toRawBits())

    fun todayKey(): String = dayFormat.format(System.currentTimeMillis())

    /** Loads state into memory (once) and returns it. */
    fun load(context: Context): EnergyState = synchronized(lock) {
        if (!loaded) {
            migrateIfNeeded(context)
            _state.value = readPrefs(context)
            loaded = true
        }
        rollDayIfNeeded(context, todayKey())
        _state.value
    }

    private fun readPrefs(context: Context): EnergyState {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        return EnergyState(
            solarWh = p.getD(K_SOLAR),
            gridWh = p.getD(K_GRID),
            loadWh = p.getD(K_LOAD),
            solarSince = p.getLong(K_SOLAR_SINCE, now),
            gridSince = p.getLong(K_GRID_SINCE, now),
            loadSince = p.getLong(K_LOAD_SINCE, now),
            lastReadingTs = p.getLong(K_LAST_TS, 0L),
            lastSolarW = p.getD(K_LAST_SOLAR_W),
            lastGridW = p.getD(K_LAST_GRID_W),
            lastLoadW = p.getD(K_LAST_LOAD_W),
            lastSeenAt = p.getLong(K_LAST_SEEN_AT, 0L),
            dayKey = p.getString(K_DAY, null) ?: todayKey(),
            daySolarWh = p.getD(K_DAY_SOLAR),
            dayGridWh = p.getD(K_DAY_GRID),
            dayLoadWh = p.getD(K_DAY_LOAD),
            dayMissedMin = p.getInt(K_DAY_MISSED, 0),
            dayBypassMin = p.getInt(K_DAY_BYPASS, 0),
            dayPeakSolarW = p.getD(K_DAY_PEAK_W),
            dayPeakAt = p.getLong(K_DAY_PEAK_AT, 0L),
            dayChargeWh = p.getD(K_DAY_CHG),
            dayDischargeWh = p.getD(K_DAY_DIS),
            dayEstWh = p.getD(K_DAY_EST),
            lastChargeW = p.getD(K_LAST_CHG_W),
            lastDischargeW = p.getD(K_LAST_DIS_W),
            avgDischargeW = p.getD(K_AVG_DIS_W)
        )
    }

    private fun write(context: Context, s: EnergyState) {
        prefs(context).edit()
            .putD(K_SOLAR, s.solarWh).putD(K_GRID, s.gridWh).putD(K_LOAD, s.loadWh)
            .putLong(K_SOLAR_SINCE, s.solarSince).putLong(K_GRID_SINCE, s.gridSince).putLong(K_LOAD_SINCE, s.loadSince)
            .putLong(K_LAST_TS, s.lastReadingTs)
            .putD(K_LAST_SOLAR_W, s.lastSolarW).putD(K_LAST_GRID_W, s.lastGridW).putD(K_LAST_LOAD_W, s.lastLoadW)
            .putLong(K_LAST_SEEN_AT, s.lastSeenAt)
            .putString(K_DAY, s.dayKey)
            .putD(K_DAY_SOLAR, s.daySolarWh).putD(K_DAY_GRID, s.dayGridWh).putD(K_DAY_LOAD, s.dayLoadWh)
            .putInt(K_DAY_MISSED, s.dayMissedMin)
            .putInt(K_DAY_BYPASS, s.dayBypassMin)
            .putD(K_DAY_PEAK_W, s.dayPeakSolarW).putLong(K_DAY_PEAK_AT, s.dayPeakAt)
            .putD(K_DAY_CHG, s.dayChargeWh).putD(K_DAY_DIS, s.dayDischargeWh).putD(K_DAY_EST, s.dayEstWh)
            .putD(K_LAST_CHG_W, s.lastChargeW).putD(K_LAST_DIS_W, s.lastDischargeW).putD(K_AVG_DIS_W, s.avgDischargeW)
            .apply()
        _state.value = s
    }

    /**
     * Old versions counted in Float with the +3h "currentTime" clock.
     * Keep the lifetime totals and day history, but start the reading
     * clock fresh (otherwise new readings would look 3 hours "older").
     */
    private fun migrateIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(K_VERSION, false)) return
        val e = p.edit()
        e.putD(K_SOLAR, p.getFloat(OLD_SOLAR, 0f).toDouble())
        e.putD(K_GRID, p.getFloat(OLD_GRID, 0f).toDouble())
        e.putLong(K_LOAD_SINCE, System.currentTimeMillis())
        val oldDay = p.getString(OLD_DAY_KEY, null)
        if (oldDay != null) {
            e.putString(K_DAY, oldDay)
            e.putD(K_DAY_SOLAR, p.getFloat(OLD_DAY_SOLAR, 0f).toDouble())
            e.putD(K_DAY_GRID, p.getFloat(OLD_DAY_GRID, 0f).toDouble())
        }
        e.putBoolean(K_VERSION, true).apply()
    }

    /** Parses the reading's own local time ("createTime"); falls back to "currentTime". */
    fun readingTime(json: JSONObject): Long {
        for (key in listOf("createTime", "currentTime")) {
            val raw = json.optString(key, "")
            if (raw.isNotBlank() && raw != "null") {
                try {
                    tsFormat.parse(raw)?.time?.let { return it }
                } catch (_: Exception) { }
            }
        }
        return 0L
    }

    private fun JSONObject.watts(key: String): Double {
        if (!has(key) || isNull(key)) return 0.0
        val v = optDouble(key, 0.0)
        return if (v.isNaN() || v < 0) 0.0 else v
    }

    /**
     * Feed one cloud reading in. Returns true if it was a NEW reading and
     * was counted, false if it was a repeat of one already seen.
     */
    fun process(context: Context, json: JSONObject): Boolean = synchronized(lock) {
        load(context)
        val ts = readingTime(json)
        if (ts <= 0L) return false
        var s = _state.value
        val solar = json.watts("pvInputPower1") + json.watts("pvInputPower2")
        val grid = json.watts("gridPowerInputActiveTotal")
        val load = json.watts("acOutputActivePowerTotal")
        val battery = json.watts("batteryCapacity")
        val chg = json.watts("batteryChargingPower")
        val dis = json.watts("batteryDischargingPower")

        if (ts == s.lastReadingTs) return false
        // Older than what we have: a repeat. Only accept it if the clock
        // jumped back a lot (dongle reset) - then start a new baseline.
        if (ts < s.lastReadingTs && s.lastReadingTs - ts < 6 * 3_600_000L) return false

        val readingDay = dayFormat.format(ts)
        s = rollDayIfNeeded(context, readingDay)

        if (s.lastReadingTs > 0L && ts > s.lastReadingTs) {
            val gapMs = ts - s.lastReadingTs
            if (gapMs <= FILL_GAP_MS) {
                // <= 25 min: measured. 25 min - 2 h: straight line between the two readings (estimate).
                val estimated = gapMs > MAX_GAP_MS
                val h = gapMs / 3_600_000.0
                val chgWh = (s.lastChargeW + chg) / 2.0 * h
                val disWh = (s.lastDischargeW + dis) / 2.0 * h
                val solarWh = (s.lastSolarW + solar) / 2.0 * h
                val gridWh = (s.lastGridW + grid) / 2.0 * h
                val loadWh = (s.lastLoadW + load) / 2.0 * h
                val bypass = if (json.optString("workMode", "").equals("L", ignoreCase = true))
                    (gapMs / 60_000L).toInt() else 0
                s = s.copy(
                    dayChargeWh = s.dayChargeWh + chgWh, dayDischargeWh = s.dayDischargeWh + disWh,
                    dayEstWh = if (estimated) s.dayEstWh + solarWh else s.dayEstWh,
                    dayBypassMin = s.dayBypassMin + bypass,
                    solarWh = s.solarWh + solarWh, gridWh = s.gridWh + gridWh, loadWh = s.loadWh + loadWh,
                    daySolarWh = s.daySolarWh + solarWh, dayGridWh = s.dayGridWh + gridWh, dayLoadWh = s.dayLoadWh + loadWh
                )
            } else if (dayFormat.format(s.lastReadingTs) == readingDay) {
                // Only count the missing part that belongs to today.
                val missed = ((gapMs - 5 * 60_000L) / 60_000L).toInt().coerceAtLeast(0)
                s = s.copy(dayMissedMin = s.dayMissedMin + missed)
            } else {
                // Gap crossed midnight: missing time today = since 00:00.
                s = s.copy(dayMissedMin = s.dayMissedMin + (minuteOfDay(ts) - 5).coerceAtLeast(0))
            }
        } else if (s.lastReadingTs == 0L && s.daySolarWh == 0.0 && s.dayGridWh == 0.0) {
            // Very first reading ever / after reinstall - nothing before it is known today.
            s = s.copy(dayMissedMin = (minuteOfDay(ts) - 5).coerceAtLeast(0))
        }

        if (solar > s.dayPeakSolarW) s = s.copy(dayPeakSolarW = solar, dayPeakAt = ts)
        // remember typical battery drain (slow average) for runtime estimates
        if (dis > 20) s = s.copy(avgDischargeW = if (s.avgDischargeW <= 0) dis else s.avgDischargeW * 0.9 + dis * 0.1)
        s = s.copy(lastReadingTs = ts, lastSolarW = solar, lastGridW = grid, lastLoadW = load,
            lastChargeW = chg, lastDischargeW = dis,
            lastSeenAt = System.currentTimeMillis())
        write(context, s)
        appendSample(context, readingDay, Sample(minuteOfDay(ts), solar.toFloat(), grid.toFloat(), load.toFloat(), battery.toFloat(), (chg - dis).toFloat()))
        true
    }

    private fun minuteOfDay(ts: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** Archives the finished day when the date changes and starts today's counter at zero. */
    private fun rollDayIfNeeded(context: Context, newDay: String): EnergyState {
        var s = _state.value
        if (s.dayKey.isEmpty()) {
            s = s.copy(dayKey = newDay); write(context, s); return s
        }
        if (newDay > s.dayKey) {
            appendHistory(context, DayRecord(s.dayKey, s.daySolarWh, s.dayGridWh, s.dayLoadWh, s.dayMissedMin, s.dayPeakSolarW, s.dayBypassMin,
                true, s.dayChargeWh, s.dayDischargeWh, s.dayEstWh))
            s = s.copy(dayKey = newDay, daySolarWh = 0.0, dayGridWh = 0.0, dayLoadWh = 0.0,
                dayMissedMin = 0, dayBypassMin = 0, dayPeakSolarW = 0.0, dayPeakAt = 0L,
                dayChargeWh = 0.0, dayDischargeWh = 0.0, dayEstWh = 0.0)
            write(context, s)
        }
        return s
    }

    private fun readHistoryArray(context: Context): JSONArray = try {
        JSONArray(prefs(context).getString(K_HISTORY, "[]"))
    } catch (_: Exception) { JSONArray() }

    private fun appendHistory(context: Context, r: DayRecord) {
        val arr = readHistoryArray(context)
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("date") != r.dateKey }
            .toMutableList()
        list.add(JSONObject().put("date", r.dateKey).put("solarWh", r.solarWh).put("gridWh", r.gridWh)
            .put("loadWh", r.loadWh).put("missedMin", r.missedMin).put("peakW", r.peakSolarW).put("bypassMin", r.bypassMin)
            .put("chgWh", r.chargeWh).put("disWh", r.dischargeWh).put("estWh", r.estWh))
        val trimmed = list.sortedBy { it.optString("date") }.takeLast(MAX_HISTORY_DAYS)
        prefs(context).edit().putString(K_HISTORY, JSONArray(trimmed).toString()).apply()
    }

    /** Every day, newest first, with today (so far) as the first entry. */
    fun getHistory(context: Context): List<DayRecord> {
        val s = load(context)
        val arr = readHistoryArray(context)
        val past = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DayRecord(o.getString("date"), o.optDouble("solarWh", 0.0), o.optDouble("gridWh", 0.0),
                o.optDouble("loadWh", 0.0), o.optInt("missedMin", 0), o.optDouble("peakW", 0.0),
                o.optInt("bypassMin", 0), o.has("bypassMin"),
                o.optDouble("chgWh", 0.0), o.optDouble("disWh", 0.0), o.optDouble("estWh", 0.0))
        }.filter { it.dateKey != s.dayKey }
        val today = DayRecord(s.dayKey, s.daySolarWh, s.dayGridWh, s.dayLoadWh, s.dayMissedMin, s.dayPeakSolarW, s.dayBypassMin,
            true, s.dayChargeWh, s.dayDischargeWh, s.dayEstWh)
        return (past + today).sortedByDescending { it.dateKey }
    }

    // ---------- power curve samples (per day, last few days) ----------

    private fun appendSample(context: Context, day: String, sm: Sample) {
        val sp = samplePrefs(context)
        val line = "${sm.minuteOfDay},${sm.solarW.toInt()},${sm.gridW.toInt()},${sm.loadW.toInt()},${sm.batteryPct.toInt()},${sm.batteryW.toInt()}"
        val existing = sp.getString(day, "") ?: ""
        val e = sp.edit().putString(day, if (existing.isEmpty()) line else "$existing;$line")
        // keep only the most recent days
        val days = (sp.all.keys + day).distinct().sorted()
        if (days.size > SAMPLE_DAYS_KEPT) days.take(days.size - SAMPLE_DAYS_KEPT).forEach { e.remove(it) }
        e.apply()
    }

    fun getSamples(context: Context, day: String): List<Sample> {
        val raw = samplePrefs(context).getString(day, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(';').mapNotNull { part ->
            val f = part.split(',')
            if (f.size < 5) null else try {
                Sample(f[0].toInt(), f[1].toFloat(), f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), f.getOrNull(5)?.toFloatOrNull() ?: 0f)
            } catch (_: Exception) { null }
        }.sortedBy { it.minuteOfDay }
    }

    fun sampleDays(context: Context): List<String> =
        samplePrefs(context).all.keys.sorted()

    // ---------- resets & settings ----------

    fun resetSolar(context: Context) = synchronized(lock) {
        load(context); write(context, _state.value.copy(solarWh = 0.0, solarSince = System.currentTimeMillis()))
    }

    fun resetGrid(context: Context) = synchronized(lock) {
        load(context); write(context, _state.value.copy(gridWh = 0.0, gridSince = System.currentTimeMillis()))
    }

    fun resetLoad(context: Context) = synchronized(lock) {
        load(context); write(context, _state.value.copy(loadWh = 0.0, loadSince = System.currentTimeMillis()))
    }

    /** Rupees per unit, used for the "bachat" (savings) estimate. */
    fun tariff(context: Context): Double = prefs(context).getFloat(K_TARIFF, 50f).toDouble()
    fun setTariff(context: Context, rs: Double) {
        prefs(context).edit().putFloat(K_TARIFF, rs.toFloat()).apply()
    }
}
