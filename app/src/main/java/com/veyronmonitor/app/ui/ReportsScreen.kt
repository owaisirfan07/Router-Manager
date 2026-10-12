package com.veyronmonitor.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.veyronmonitor.app.data.EnergyStore
import com.veyronmonitor.app.data.Insights
import java.text.SimpleDateFormat
import java.util.Locale

private enum class Range(val label: String) { HOUR("Ghanta"), DAY("Din"), MONTH("Mahina"), YEAR("Saal") }

private fun kwh(wh: Double) = if (wh >= 100_000) "%.0f".format(wh / 1000) else "%.1f".format(wh / 1000)

@Composable
fun ReportsScreen(refreshKey: Any, onBack: () -> Unit) {
    val c = LocalContext.current
    var range by remember { mutableStateOf(Range.DAY) }
    val days = remember(refreshKey) { EnergyStore.sampleDays(c).sortedDescending() }
    var day by remember { mutableStateOf(days.firstOrNull() ?: EnergyStore.todayKey()) }

    val buckets = remember(range, day, refreshKey) {
        when (range) {
            Range.HOUR -> Insights.hourly(c, day)
            Range.DAY -> Insights.daily(c, 30)
            Range.MONTH -> Insights.monthly(c, 12)
            Range.YEAR -> Insights.yearly(c)
        }
    }
    val compare = remember(refreshKey) { Insights.monthCompare(c) }
    val savings = remember(refreshKey) { Insights.savings(c) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column {
                    Text("Reports", style = MaterialTheme.typography.headlineMedium)
                    Text("Solar, ghar ka kharch, grid aur battery", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Range.values().forEach { r -> FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) }) }
            }
        }
        if (range == Range.HOUR && days.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(days) { d ->
                        val label = try { SimpleDateFormat("EEE d", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)!!) } catch (_: Exception) { d }
                        FilterChip(selected = d == day, onClick = { day = d }, label = { Text(if (d == EnergyStore.todayKey()) "Aaj" else label) })
                    }
                }
            }
        }

        // ---- solar vs consumption ----
        item {
            SectionCard("Solar vs ghar ka kharch", Icons.Filled.BarChart) {
                GroupedBarsChart(
                    series = listOf(SolarColor to buckets.map { it.solarWh / 1000 }, LoadColor to buckets.map { it.loadWh / 1000 },
                        GridColor to buckets.map { it.gridWh / 1000 }),
                    labels = buckets.map { it.label },
                    axisColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    gridLineColor = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LegendDot(SolarColor, "Solar ${kwh(buckets.sumOf { it.solarWh })}")
                    LegendDot(LoadColor, "Ghar ${kwh(buckets.sumOf { it.loadWh })}")
                    LegendDot(GridColor, "Grid ${kwh(buckets.sumOf { it.gridWh })}")
                }
                Spacer(Modifier.height(6.dp))
                Text("units (kWh). Grid aur ghar ka kharch woh hai jo inverter ne naapa - jab ghar seedha grid par ho, wo hissa is mein nahi.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- battery ----
        item {
            SectionCard("Battery charge / discharge", Icons.Filled.BatteryChargingFull) {
                GroupedBarsChart(
                    series = listOf(BatteryColor to buckets.map { it.chargeWh / 1000 }, DischargeColor to buckets.map { it.dischargeWh / 1000 }),
                    labels = buckets.map { it.label },
                    axisColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    gridLineColor = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LegendDot(BatteryColor, "Charge ${kwh(buckets.sumOf { it.chargeWh })}")
                    LegendDot(DischargeColor, "Discharge ${kwh(buckets.sumOf { it.dischargeWh })}")
                }
                if (buckets.all { it.chargeWh + it.dischargeWh == 0.0 }) {
                    Spacer(Modifier.height(6.dp))
                    Text("Battery ka record is version se banna shuru hoga.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ---- this month vs last ----
        item {
            SectionCard("Is mahina vs pichla mahina", Icons.Filled.CompareArrows) {
                Text("Mahine ke pehle din se aaj tak, pichle mahine ke unhi dinon se muqabla", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                compare.forEach { cmp ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(cmp.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("${kwh(cmp.thisWh)} vs ${kwh(cmp.lastWh)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        val p = cmp.pct
                        Text(p?.let { (if (it >= 0) "+" else "") + "%.0f%%".format(it) } ?: "--",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                            color = when {
                                p == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                (cmp.label == "Solar") == (p >= 0) -> BatteryColor
                                else -> Color(0xFFEF4444)
                            }, modifier = Modifier.widthIn(min = 52.dp))
                    }
                }
            }
        }

        // ---- savings ----
        item {
            SectionCard("Bijli bill ki bachat", Icons.Filled.Savings) {
                Text("Is bill mein (${savings.fromLabel} se)", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                RowKV("Solar ne banaye", "${"%.1f".format(savings.solarUnits)} units")
                savings.gridUnits?.let { RowKV("Grid se liye (KE meter)", "${"%.1f".format(it)} units") }
                savings.billNow?.let { RowKV("Bill ab tak (andaza)", "Rs ${"%,d".format(it)}") }
                savings.billWithoutSolar?.let { RowKV("Solar na hota to", "Rs ${"%,d".format(it)}") }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                RowKV("Bachat", "Rs ${"%,d".format(savings.saved)}", BatteryColor)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (savings.gridUnits == null) "Meter tab mein KE reading aur bill ke rates daalein - phir slabs ke saath pakka hisaab banega."
                    else "Andaza - Meter tab ke rates aur slabs se. Solar ki wajah se slab neeche rehna bhi is mein shamil hai.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RowKV(k: String, v: String, color: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}
