package com.veyronmonitor.app.data

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Battery details (default: Itel 24V 100Ah lithium). */
object BatteryConfig {
    private const val PREFS = "veyron_battery"
    data class Battery(val volts: Double, val ah: Double, val count: Int, val lithium: Boolean, val cutoffPct: Int) {
        /** Usable energy from 100% down to the cutoff. */
        val capacityWh get() = volts * ah * count
    }

    fun get(c: Context): Battery {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Battery(
            p.getFloat("volts", 25.6f).toDouble(), p.getFloat("ah", 100f).toDouble(), p.getInt("count", 1),
            p.getBoolean("lithium", true), p.getInt("cutoff", 20)
        )
    }

    fun set(c: Context, b: Battery) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("volts", b.volts.toFloat()).putFloat("ah", b.ah.toFloat()).putInt("count", b.count)
            .putBoolean("lithium", b.lithium).putInt("cutoff", b.cutoffPct).apply()
    }
}

object Insights {
    private val keyFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun JSONObject.num(k: String): Double = if (has(k) && !isNull(k)) optDouble(k, 0.0).takeIf { !it.isNaN() } ?: 0.0 else 0.0

    fun hm(hours: Double): String {
        val mins = (hours * 60).roundToInt().coerceAtLeast(0)
        return when { mins < 60 -> "$mins min"; mins % 60 == 0 -> "${mins / 60} ghante"; else -> "${mins / 60} ghante ${mins % 60} min" }
    }

    fun clock(ms: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(ms)

    // ---------------- battery runtime ----------------

    data class Runtime(
        val soc: Int,
        val state: String,            // "discharging" / "charging" / "idle"
        val drainW: Double?,          // watts used for the estimate
        val hoursLeft: Double?,       // until cutoff at that drain
        val emptyAt: Long?,
        val hoursToFull: Double?,
        val basis: String             // what the estimate is based on
    )

    /** Recent average battery drain (W) from today's readings in the last [minutes]. */
    private fun recentDrain(c: Context, minutes: Int): Double? {
        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val s = EnergyStore.getSamples(c, EnergyStore.todayKey()).filter { it.minuteOfDay >= nowMin - minutes }
        val drains = s.map { -it.batteryW }.filter { it > 20 }
        return if (drains.size >= 2) drains.average() else null
    }

    fun runtime(c: Context, live: JSONObject?): Runtime? {
        live ?: return null
        val b = BatteryConfig.get(c)
        val soc = live.num("batteryCapacity").roundToInt()
        val dis = live.num("batteryDischargingPower")
        val chg = live.num("batteryChargingPower")
        val availWh = b.capacityWh * max(0, soc - b.cutoffPct) / 100.0
        val now = System.currentTimeMillis()
        return when {
            dis > 20 -> {
                val recent = recentDrain(c, 20)
                val w = recent?.let { (it + dis) / 2 } ?: dis
                val h = availWh / w
                Runtime(soc, "discharging", w, h, now + (h * 3_600_000).toLong(), null,
                    if (recent != null) "pichle 20 min ka average load" else "abhi ka load")
            }
            chg > 20 -> {
                val toFull = b.capacityWh * (100 - soc) / 100.0 / chg
                val avg = EnergyStore.load(c).avgDischargeW.takeIf { it > 20 }
                Runtime(soc, "charging", avg, avg?.let { availWh / it }, null, toFull,
                    "agar abhi bijli jaye - aam load ${avg?.toInt() ?: "--"} W par")
            }
            else -> {
                val avg = EnergyStore.load(c).avgDischargeW.takeIf { it > 20 }
                Runtime(soc, "idle", avg, avg?.let { availWh / it }, null, null,
                    "agar abhi bijli jaye - aam load ${avg?.toInt() ?: "--"} W par")
            }
        }
    }

    // ---------------- reports ----------------

    data class Bucket(val label: String, val solarWh: Double, val loadWh: Double, val gridWh: Double,
                      val chargeWh: Double, val dischargeWh: Double)

    /** Energy per hour for one day, from that day's readings (trapezoid, gaps > 25 min skipped). */
    fun hourly(c: Context, day: String): List<Bucket> {
        val s = EnergyStore.getSamples(c, day)
        val sol = DoubleArray(24); val lo = DoubleArray(24); val gr = DoubleArray(24); val ch = DoubleArray(24); val di = DoubleArray(24)
        s.zipWithNext { a, b ->
            val gap = b.minuteOfDay - a.minuteOfDay
            if (gap in 1..25) {
                val h = gap / 60.0
                val hour = (a.minuteOfDay / 60).coerceIn(0, 23)
                sol[hour] += (a.solarW + b.solarW) / 2.0 * h
                lo[hour] += (a.loadW + b.loadW) / 2.0 * h
                gr[hour] += (a.gridW + b.gridW) / 2.0 * h
                ch[hour] += (max(0f, a.batteryW) + max(0f, b.batteryW)) / 2.0 * h
                di[hour] += (max(0f, -a.batteryW) + max(0f, -b.batteryW)) / 2.0 * h
            }
        }
        return (0 until 24).map { Bucket(if (it == 0) "12a" else if (it < 12) "${it}a" else if (it == 12) "12p" else "${it - 12}p",
            sol[it], lo[it], gr[it], ch[it], di[it]) }
    }

    fun daily(c: Context, days: Int): List<Bucket> {
        val byKey = EnergyStore.getHistory(c).associateBy { it.dateKey }
        return (days - 1 downTo 0).map { back ->
            val cal = Calendar.getInstance(); cal.add(Calendar.DAY_OF_YEAR, -back)
            val k = keyFmt.format(cal.time)
            val r = byKey[k]
            Bucket(cal.get(Calendar.DAY_OF_MONTH).toString(), r?.solarWh ?: 0.0, r?.loadWh ?: 0.0, r?.gridWh ?: 0.0,
                r?.chargeWh ?: 0.0, r?.dischargeWh ?: 0.0)
        }
    }

    fun monthly(c: Context, months: Int = 12): List<Bucket> {
        val hist = EnergyStore.getHistory(c)
        val fmt = SimpleDateFormat("MMM", Locale.US)
        return (months - 1 downTo 0).map { back ->
            val cal = Calendar.getInstance(); cal.set(Calendar.DAY_OF_MONTH, 1); cal.add(Calendar.MONTH, -back)
            val prefix = SimpleDateFormat("yyyy-MM", Locale.US).format(cal.time)
            val m = hist.filter { it.dateKey.startsWith(prefix) }
            Bucket(fmt.format(cal.time), m.sumOf { it.solarWh }, m.sumOf { it.loadWh }, m.sumOf { it.gridWh },
                m.sumOf { it.chargeWh }, m.sumOf { it.dischargeWh })
        }
    }

    fun yearly(c: Context): List<Bucket> =
        EnergyStore.getHistory(c).groupBy { it.dateKey.take(4) }.toSortedMap().map { (y, m) ->
            Bucket(y, m.sumOf { it.solarWh }, m.sumOf { it.loadWh }, m.sumOf { it.gridWh }, m.sumOf { it.chargeWh }, m.sumOf { it.dischargeWh })
        }

    data class Compare(val label: String, val thisWh: Double, val lastWh: Double) {
        val pct: Double? get() = if (lastWh > 100) (thisWh - lastWh) / lastWh * 100 else null
    }

    /** This month (1st..today) vs the same days of last month. */
    fun monthCompare(c: Context): List<Compare> {
        val hist = EnergyStore.getHistory(c)
        val now = Calendar.getInstance()
        val day = now.get(Calendar.DAY_OF_MONTH)
        val thisPrefix = SimpleDateFormat("yyyy-MM", Locale.US).format(now.time)
        val last = (now.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -1) }
        val lastPrefix = SimpleDateFormat("yyyy-MM", Locale.US).format(last.time)
        val a = hist.filter { it.dateKey.startsWith(thisPrefix) }
        val b = hist.filter { it.dateKey.startsWith(lastPrefix) && it.dateKey.takeLast(2).toInt() <= day }
        return listOf(
            Compare("Solar", a.sumOf { it.solarWh }, b.sumOf { it.solarWh }),
            Compare("Ghar ka kharch", a.sumOf { it.loadWh }, b.sumOf { it.loadWh }),
            Compare("Grid (inverter)", a.sumOf { it.gridWh }, b.sumOf { it.gridWh }),
            Compare("Battery discharge", a.sumOf { it.dischargeWh }, b.sumOf { it.dischargeWh })
        )
    }

    // ---------------- bill savings ----------------

    data class Savings(
        val fromKey: String,
        val fromLabel: String,
        val solarUnits: Double,
        val gridUnits: Double?,         // from meter readings (null if none yet)
        val billNow: Long?,
        val billWithoutSolar: Long?,
        val saved: Long,
        val ratePerUnit: Double
    )

    /** Savings in the current bill month: what the bill would be if solar's units had come from KE. */
    fun savings(c: Context): Savings {
        val rates = MeterStore.rates(c)
        val cur = MeterStore.current(c)
        val from = cur?.billReading?.at ?: Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        val fromKey = keyFmt.format(from)
        val solar = EnergyStore.getHistory(c).filter { it.dateKey >= fromKey }.sumOf { it.solarWh } / 1000.0
        return if (cur != null) {
            val grid = cur.units
            val now = MeterStore.bill(grid, rates)
            val without = MeterStore.bill(grid + solar, rates)
            Savings(fromKey, SimpleDateFormat("d MMM", Locale.US).format(from), solar, grid, now, without, without - now, MeterStore.rateAt(grid + solar, rates))
        } else {
            val rate = MeterStore.rateAt(solar, rates)
            Savings(fromKey, SimpleDateFormat("d MMM", Locale.US).format(from), solar, null, null, null, (solar * rate).roundToInt().toLong(), rate)
        }
    }

    /** Rs per unit to use for "bachat" on the Units screen. */
    fun effectiveRate(c: Context): Double {
        val cur = MeterStore.current(c)
        val units = cur?.projected ?: cur?.units ?: 0.0
        return MeterStore.rateAt(units, MeterStore.rates(c))
    }

    // ---------------- weather (free, no key) ----------------

    /** Current cloud cover % near Karachi from Open-Meteo, or null. Call off the main thread. */
    fun cloudCover(): Int? = try {
        val conn = URL("https://api.open-meteo.com/v1/forecast?latitude=24.86&longitude=67.01&current=cloud_cover&timezone=auto")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        val txt = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        JSONObject(txt).getJSONObject("current").optInt("cloud_cover", -1).takeIf { it >= 0 }
    } catch (_: Exception) { null }

    // ---------------- solar diagnosis ----------------

    /** Average solar W at this time of day over the last few days (same hour, from saved readings). */
    fun usualSolarNow(c: Context): Double? {
        val now = Calendar.getInstance()
        val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val days = EnergyStore.sampleDays(c).filter { it != EnergyStore.todayKey() }
        val vals = days.mapNotNull { d ->
            EnergyStore.getSamples(c, d).filter { kotlin.math.abs(it.minuteOfDay - m) <= 30 }.map { it.solarW.toDouble() }
                .takeIf { it.isNotEmpty() }?.average()
        }
        return if (vals.isNotEmpty()) vals.average() else null
    }

    fun solarDiagnosis(c: Context, live: JSONObject?, cloud: Int?): String {
        live ?: return "Abhi inverter ka data nahi aaya - thori der baad poochein."
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val pv = live.num("pvInputPower1")
        val pvV = live.num("pvInputVoltage1")
        val soc = live.num("batteryCapacity")
        val load = live.num("acOutputActivePowerTotal")
        val chg = live.num("batteryChargingPower")
        val usual = usualSolarNow(c)
        val lines = ArrayList<String>()
        lines += "Abhi solar: ${pv.toInt()} W" + (usual?.let { " (pichle dino isi waqt aam taur par ~${it.toInt()} W)" } ?: "")
        when {
            hour < 7 || hour >= 18 -> lines += "• Abhi dhoop ka waqt nahi (subah 7 se pehle ya shaam 6 ke baad) - kam ya zero solar normal hai."
            hour < 9 || hour >= 16 -> lines += "• Subah ya dopehar baad suraj neecha hota hai, is liye production kam hoti hai."
        }
        if (soc >= 95 && chg < 100 && pv > 0 && hour in 9..15)
            lines += "• Battery ${soc.toInt()}% full hai aur ghar ka load ${load.toInt()} W hai. Inverter utni hi solar leta hai jitni zaroorat ho - " +
                "is liye panels ki poori taaqat use nahi ho rahi. Ye kharabi nahi hai. Load barhayein (pump, iron, AC) to solar barh jayega."
        if (hour in 9..15 && pvV in 1.0..150.0)
            lines += "• PV voltage kam hai (${pvV.toInt()} V). Panels par saya, dhool, ya koi tar/connection dheela ho sakta hai."
        if (hour in 9..15 && pvV == 0.0)
            lines += "• PV voltage 0 hai - panels se bijli aa hi nahi rahi. Breaker/MC4 connector check karein."
        cloud?.let {
            when {
                it >= 70 -> lines += "• Karachi mein abhi $it% badal hain - is liye dhoop kam hai."
                it >= 35 -> lines += "• Kuch badal hain ($it%) - production thori kam ho sakti hai."
                else -> lines += "• Aasman zyada tar saaf hai ($it% badal)."
            }
        }
        if (usual != null && hour in 9..15 && pv < usual * 0.6 && lines.size <= 2)
            lines += "• Aaj isi waqt aam din se kaafi kam hai. Badal na hon to panels ki safai check karein."
        if (lines.size == 1) lines += "• Sab normal lag raha hai."
        lines += "\n(Ye andaza hai - inverter ke data aur mausam ki report par based.)"
        return lines.joinToString("\n")
    }
}
