package com.veyronmonitor.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * KE meter readings, typed in by hand.
 *
 *  - A "bill" reading starts a new billing month (the Present reading on the
 *    bill, or what the meter reader noted on the 9th).
 *  - Any other reading is just "where the meter is now" - add as many as you
 *    like. Latest minus the bill reading = units used so far this month.
 *
 * Units, daily average and the bill estimate all come from these readings,
 * so they match KE exactly.
 */
object MeterStore {
    private const val PREFS = "veyron_monitor_meter"
    private const val K_READINGS = "readings"
    private const val K_WARN = "warn_units"
    private const val K_LIMIT = "limit_units"
    private const val K_RATES = "bill_rates"
    const val READING_DAY = 9
    const val READING_HOUR = 11
    private const val DAY_MS = 86_400_000L

    data class Reading(val at: Long, val value: Double, val billStart: Boolean)

    data class Slab(val upTo: Int, val rate: Double)   // upTo = 0 means "all remaining units"

    data class Rates(
        val slabs: List<Slab>,
        val fixedRs: Double = 0.0,
        val taxPercent: Double = 0.0,
        /** true = each slab at its own rate (telescopic); false = all units at the rate of the slab you land in */
        val telescopic: Boolean = true
    )

    data class Period(val from: Reading, val to: Reading) {
        val units get() = to.value - from.value
        val days get() = (to.at - from.at) / DAY_MS.toDouble()
        val perDay get() = if (days >= 0.5) units / days else 0.0
    }

    data class Current(
        val billReading: Reading,
        val latest: Reading,
        val nextBillAt: Long,
        val units: Double,
        val days: Double,
        val perDay: Double?,          // null = not enough days yet
        val projected: Double?,       // whole month estimate
        val projectedFromLastMonth: Boolean
    ) {
        val totalDays get() = (nextBillAt - billReading.at) / DAY_MS.toDouble()
    }

    private val keyFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- readings ----------

    fun readings(context: Context): List<Reading> {
        val arr = try { JSONArray(prefs(context).getString(K_READINGS, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            // older versions only stored monthly readings -> they are all bill readings
            Reading(o.getLong("at"), o.getDouble("value"), o.optBoolean("bill", true))
        }.sortedBy { it.at }
    }

    private fun save(context: Context, list: List<Reading>) {
        val arr = JSONArray()
        list.sortedBy { it.at }.forEach { arr.put(JSONObject().put("at", it.at).put("value", it.value).put("bill", it.billStart)) }
        prefs(context).edit().putString(K_READINGS, arr.toString()).apply()
    }

    /** Returns an error message (Roman Urdu) if the reading doesn't make sense, else null. */
    fun add(context: Context, at: Long, value: Double, billStart: Boolean): String? {
        // same day + same kind replaces the old one; a bill reading and a normal reading can share a day
        val first = readings(context).isEmpty()
        val isBill = billStart || first
        val list = readings(context).filter { !(keyFmt.format(it.at) == keyFmt.format(at) && it.billStart == isBill) }
        val before = list.lastOrNull { it.at < at }
        val after = list.firstOrNull { it.at > at }
        if (before != null && value < before.value) return "Reading pichli reading (${fmt(before.value)}) se kam nahi ho sakti."
        if (after != null && value > after.value) return "Reading agli reading (${fmt(after.value)}) se zyada nahi ho sakti."
        // the very first reading always starts a bill month
        save(context, list + Reading(at, value, isBill))
        return null
    }

    fun remove(context: Context, r: Reading) = save(context, readings(context).filter { it.at != r.at })

    fun fmt(v: Double) = if (v % 1.0 == 0.0) "%,d".format(v.toLong()) else "%,.1f".format(v)

    // ---------- limits & rates ----------

    fun warnAt(context: Context): Double = prefs(context).getFloat(K_WARN, 180f).toDouble()
    fun limitAt(context: Context): Double = prefs(context).getFloat(K_LIMIT, 200f).toDouble()
    fun setLimits(context: Context, warn: Double, limit: Double) {
        prefs(context).edit().putFloat(K_WARN, warn.toFloat()).putFloat(K_LIMIT, limit.toFloat()).apply()
    }

    fun rates(context: Context): Rates {
        val raw = prefs(context).getString(K_RATES, null)
        if (raw != null) try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("slabs")
            val slabs = (0 until arr.length()).map { arr.getJSONObject(it).let { s -> Slab(s.optInt("upTo"), s.optDouble("rate")) } }
            if (slabs.isNotEmpty()) return Rates(slabs, o.optDouble("fixed"), o.optDouble("tax"), o.optBoolean("tele", true))
        } catch (_: Exception) { }
        // default: the single rate already set in the Units tab
        return Rates(listOf(Slab(0, EnergyStore.tariff(context))))
    }

    fun setRates(context: Context, r: Rates) {
        val arr = JSONArray(r.slabs.map { JSONObject().put("upTo", it.upTo).put("rate", it.rate) })
        prefs(context).edit().putString(K_RATES, JSONObject().put("slabs", arr).put("fixed", r.fixedRs)
            .put("tax", r.taxPercent).put("tele", r.telescopic).toString()).apply()
    }

    /** Slabs sorted, with the open-ended one last. */
    private fun ordered(r: Rates) = r.slabs.filter { it.upTo > 0 }.sortedBy { it.upTo } + r.slabs.filter { it.upTo <= 0 }

    /** Estimated bill in Rs for [units] in one month. */
    fun bill(units: Double, r: Rates): Long {
        if (units <= 0) return (r.fixedRs * (1 + r.taxPercent / 100)).roundToLong()
        val slabs = ordered(r)
        var energy = 0.0
        if (r.telescopic) {
            var prev = 0.0
            for (s in slabs) {
                val top = if (s.upTo > 0) s.upTo.toDouble() else Double.MAX_VALUE
                if (units > prev) energy += (minOf(units, top) - prev) * s.rate
                prev = top
                if (units <= top) break
            }
            if (units > prev) energy += (units - prev) * slabs.last().rate  // above the highest slab
        } else {
            energy = units * rateAt(units, r)
        }
        return ((energy + r.fixedRs) * (1 + r.taxPercent / 100)).roundToLong()
    }

    /** Rate of the slab that unit number [units] falls in (what one extra unit costs). */
    fun rateAt(units: Double, r: Rates): Double {
        val slabs = ordered(r)
        return (slabs.firstOrNull { it.upTo > 0 && units <= it.upTo } ?: slabs.last()).rate
    }

    // ---------- dates ----------

    /** The next "9th at 11:00" after [after] (a reading a few days early/late still belongs to that 9th). */
    fun nextReadingDate(after: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = after
            set(Calendar.DAY_OF_MONTH, READING_DAY)
            set(Calendar.HOUR_OF_DAY, READING_HOUR); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        while (c.timeInMillis <= after + 3 * DAY_MS) c.add(Calendar.MONTH, 1)
        return c.timeInMillis
    }

    // ---------- calculations ----------

    /** Finished bill months, newest first. */
    fun periods(context: Context): List<Period> =
        readings(context).filter { it.billStart }.zipWithNext { a, b -> Period(a, b) }.reversed()

    fun current(context: Context): Current? {
        val rs = readings(context)
        val bill = rs.lastOrNull { it.billStart } ?: return null
        val latest = rs.last()
        val next = nextReadingDate(bill.at)
        val units = latest.value - bill.value
        val days = (latest.at - bill.at) / DAY_MS.toDouble()
        val total = (next - bill.at) / DAY_MS.toDouble()
        val perDay = if (days >= 1.0) units / days else null
        val lastMonth = periods(context).firstOrNull()?.perDay?.takeIf { it > 0 }
        val projected = when {
            perDay != null -> perDay * total
            lastMonth != null -> lastMonth * total
            else -> null
        }
        return Current(bill, latest, next, units, max(0.0, days), perDay, projected, perDay == null && lastMonth != null)
    }
}
