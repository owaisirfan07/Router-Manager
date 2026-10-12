package com.veyronmonitor.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veyronmonitor.app.data.EnergyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun units(wh: Double): String = when {
    wh >= 100_000 -> "%.0f".format(wh / 1000)
    wh >= 10_000 -> "%.1f".format(wh / 1000)
    else -> "%.2f".format(wh / 1000)
}

private fun rupees(v: Double): String = "Rs " + "%,d".format(v.toLong())

private val keyFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

private fun dayKeyOffset(days: Int): String {
    val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, -days)
    return keyFmt.format(c.time)
}

private fun pretty(key: String, pattern: String): String = try {
    SimpleDateFormat(pattern, Locale.US).format(keyFmt.parse(key)!!)
} catch (_: Exception) { key }

private fun friendlyDay(key: String): String = when (key) {
    dayKeyOffset(0) -> "Aaj"
    dayKeyOffset(1) -> "Kal"
    else -> pretty(key, "EEE d MMM")
}

@Composable
fun UnitsScreen(
    energy: EnergyStore.EnergyState,
    history: List<EnergyStore.DayRecord>,
    sampleDays: List<String>,
    samplesFor: (String) -> List<EnergyStore.Sample>,
    tariff: Double,
    onTariffChange: (Double) -> Unit,
    backgroundEnabled: Boolean,
    onBackgroundToggle: (Boolean) -> Unit,
    batteryRestricted: Boolean,
    onFixBattery: () -> Unit,
    onResetSolar: () -> Unit,
    onResetGrid: () -> Unit,
    onResetLoad: () -> Unit,
    onOpenReports: () -> Unit = {},
    /** When set, tapping the rate opens this (rates come from the Meter tab) instead of the simple rate dialog. */
    onRateTap: (() -> Unit)? = null
) {
    var showTariff by remember { mutableStateOf(false) }
    var resetTarget by remember { mutableStateOf<String?>(null) }
    var selectedDay by remember { mutableStateOf(energy.dayKey) }
    var range by remember { mutableIntStateOf(7) }
    var showAllDays by remember { mutableStateOf(false) }

    val byKey = remember(history) { history.associateBy { it.dateKey } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Units", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        pretty(energy.dayKey, "EEEE, d MMMM yyyy"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(onClick = onOpenReports) {
                    Icon(Icons.Filled.Insights, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Reports")
                }
            }
        }

        // ---------------- Today hero ----------------
        item { TodayHero(energy, tariff, onEditTariff = { onRateTap?.invoke() ?: run { showTariff = true } }) }

        // ---------------- Power curve ----------------
        item {
            SectionCard(title = "Power graph", icon = Icons.Filled.ShowChart) {
                val days = (sampleDays + energy.dayKey).distinct().sortedDescending()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(days) { d ->
                        FilterChip(
                            selected = d == selectedDay,
                            onClick = { selectedDay = d },
                            label = { Text(friendlyDay(d)) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val samples = remember(selectedDay, energy.lastReadingTs) { samplesFor(selectedDay) }
                val dayRec = byKey[selectedDay]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Watts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    if (dayRec != null) {
                        Text(
                            "${units(dayRec.solarWh)} solar  •  ${units(dayRec.gridWh)} grid",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                PowerCurveChart(
                    samples = samples,
                    axisColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    gridLineColor = MaterialTheme.colorScheme.outlineVariant
                )
                if (samples.isEmpty()) {
                    Text(
                        "Is din ka graph abhi nahi hai - readings aate hi yahan curve banta jayega.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Legend(SolarColor, "Solar")
                    Legend(GridColor, "Grid")
                    Legend(LoadColor, "Ghar (load)")
                }
            }
        }

        // ---------------- Daily bars ----------------
        item {
            SectionCard(title = "Roz ke units", icon = Icons.Filled.BarChart) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = range == 7, onClick = { range = 7 }, label = { Text("7 din") })
                    FilterChip(selected = range == 30, onClick = { range = 30 }, label = { Text("30 din") })
                }
                Spacer(Modifier.height(10.dp))
                val window = (range - 1 downTo 0).map { dayKeyOffset(it) }
                val records = window.map { byKey[it] ?: EnergyStore.DayRecord(it, 0.0, 0.0) }
                val labels = window.map { if (range == 7) pretty(it, "EEE") else pretty(it, "d") }
                DailyBarsChart(
                    days = records, labels = labels,
                    axisColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    gridLineColor = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(Modifier.height(6.dp))
                val solarSum = records.sumOf { it.solarWh }
                val gridSum = records.sumOf { it.gridWh }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Legend(SolarColor, "Solar ${units(solarSum)}")
                    Spacer(Modifier.width(16.dp))
                    Legend(GridColor, "Grid ${units(gridSum)}")
                    Spacer(Modifier.weight(1f))
                    Text("units (kWh)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ---------------- Week / month summary ----------------
        item {
            val week = (0..6).mapNotNull { byKey[dayKeyOffset(it)] }
            val monthPrefix = energy.dayKey.take(7)
            val month = history.filter { it.dateKey.startsWith(monthPrefix) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryTile(
                    modifier = Modifier.weight(1f),
                    title = "Is hafte",
                    solarWh = week.sumOf { it.solarWh },
                    gridWh = week.sumOf { it.gridWh },
                    tariff = tariff
                )
                SummaryTile(
                    modifier = Modifier.weight(1f),
                    title = pretty(energy.dayKey, "MMMM"),
                    solarWh = month.sumOf { it.solarWh },
                    gridWh = month.sumOf { it.gridWh },
                    tariff = tariff
                )
            }
        }

        // ---------------- Lifetime meter ----------------
        item {
            SectionCard(title = "Meter (reset ho sakta hai)", icon = Icons.Filled.Speed) {
                MeterRow("Solar", SolarColor, Icons.Filled.SolarPower, energy.solarWh, energy.solarSince) { resetTarget = "solar" }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MeterRow("Grid (KE)", GridColor, Icons.Filled.ElectricalServices, energy.gridWh, energy.gridSince) { resetTarget = "grid" }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MeterRow("Ghar ka kharch", LoadColor, Icons.Filled.Home, energy.loadWh, energy.loadSince) { resetTarget = "load" }
            }
        }

        // ---------------- Background counting ----------------
        item {
            SectionCard(title = "Background counting", icon = Icons.Filled.Sync) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("App band ho tab bhi units ginein", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            if (backgroundEnabled) "Har 1.5 minute reading li jaati hai (notification mein live units)"
                            else "Band hai - units sirf app khuli ho tab ginenge",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = backgroundEnabled, onCheckedChange = onBackgroundToggle)
                }
                if (backgroundEnabled && batteryRestricted) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BatteryAlert, null, tint = SolarDeep)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Phone battery-saver app ko band kar sakta hai. \"Allow\" karein taa ke units miss na hon.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onFixBattery) { Text("Allow") }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Hisaab kaise hota hai: 1 unit = 1 kWh = 1000 watt ka load 1 ghanta. " +
                        "Inverter har ~5 minute watts bhejta hai; app do readings ke beech ka average × waqt jor deti hai " +
                        "(bilkul KE meter ki tarah - wo 3200 blink par 1 unit ginta hai). " +
                        "Agar phone/internet band ho to woh waqt \"miss\" likha jata hai, andaza nahi lagaya jata.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------------- Day by day ----------------
        item {
            Text("DIN BA DIN", style = SectionCaption, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp))
        }
        val list = if (showAllDays) history else history.take(10)
        items(list, key = { it.dateKey }) { r -> DayRow(r, tariff) }
        if (history.size > 10) {
            item {
                TextButton(onClick = { showAllDays = !showAllDays }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showAllDays) "Kam dikhayein" else "Saare ${history.size} din dikhayein")
                }
            }
        }
    }

    if (showTariff) {
        var text by remember { mutableStateOf(if (tariff % 1.0 == 0.0) tariff.toInt().toString() else tariff.toString()) }
        AlertDialog(
            onDismissRequest = { showTariff = false },
            title = { Text("Bijli ka rate") },
            text = {
                Column {
                    Text("Aapke KE bill mein 1 unit kitne ka parta hai (tax mila kar)? Isi se bachat ka andaza lagta hai.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Rs per unit") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toDoubleOrNull()?.takeIf { it > 0 }?.let(onTariffChange)
                    showTariff = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showTariff = false }) { Text("Cancel") } }
        )
    }

    resetTarget?.let { target ->
        val name = when (target) { "solar" -> "Solar"; "grid" -> "Grid"; else -> "Ghar ka kharch" }
        AlertDialog(
            onDismissRequest = { resetTarget = null },
            title = { Text("$name meter reset?") },
            text = { Text("Sirf yeh meter 0 se shuru hoga. Roz ka record aur graph waise hi rahenge.") },
            confirmButton = {
                TextButton(onClick = {
                    when (target) { "solar" -> onResetSolar(); "grid" -> onResetGrid(); else -> onResetLoad() }
                    resetTarget = null
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { resetTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TodayHero(energy: EnergyStore.EnergyState, tariff: Double, onEditTariff: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.red < 0.3f
    val gradient = if (dark)
        Brush.linearGradient(listOf(Color(0xFF3A2A06), Color(0xFF1A2540)))
    else
        Brush.linearGradient(listOf(Color(0xFFFFF1C7), Color(0xFFFFFFFF)))
    val onHero = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(gradient)
            .border(1.dp, SolarColor.copy(alpha = 0.35f), MaterialTheme.shapes.large)
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(SolarColor.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.WbSunny, null, tint = SolarDeep, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(10.dp))
                Text("Aaj solar se bane", style = MaterialTheme.typography.titleMedium, color = onHero)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(units(energy.daySolarWh), fontSize = 52.sp, fontWeight = FontWeight.Bold, color = onHero, lineHeight = 52.sp)
                Spacer(Modifier.width(8.dp))
                Text("units", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onEditTariff).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "≈ ${rupees(energy.daySolarWh / 1000 * tariff)} ki bachat",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = BatteryColor
                )
                Spacer(Modifier.width(6.dp))
                Text("(Rs ${"%.0f".format(tariff)}/unit", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Filled.Edit, "Rate badlein", Modifier.size(14.dp).padding(start = 2.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(")", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroStat(Modifier.weight(1f), "Grid se liye", units(energy.dayGridWh), GridColor)
                HeroStat(Modifier.weight(1f), "Ghar ka kharch", units(energy.dayLoadWh), LoadColor)
                HeroStat(
                    Modifier.weight(1f), "Peak solar",
                    if (energy.dayPeakSolarW > 0) "${energy.dayPeakSolarW.toInt()} W" else "--",
                    SolarDeep,
                    sub = if (energy.dayPeakAt > 0) SimpleDateFormat("h:mm a", Locale.US).format(Date(energy.dayPeakAt)) else null
                )
            }
            val total = energy.daySolarWh + energy.dayGridWh
            if (total > 0) {
                Spacer(Modifier.height(14.dp))
                val share = (energy.daySolarWh / total).toFloat()
                Text("Solar ka hissa: ${(share * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = onHero)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(GridColor.copy(alpha = 0.55f))) {
                    Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(SolarColor))
                }
            }
            if (energy.dayEstWh >= 50) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Is mein ${units(energy.dayEstWh)} units andaza hain - beech mein kuch readings nahi aayi thi, wahan seedhi line se bhara.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (energy.dayMissedMin >= 20) {
                Spacer(Modifier.height(12.dp))
                val hrs = energy.dayMissedMin / 60
                val mins = energy.dayMissedMin % 60
                val gap = when { hrs > 0 && mins > 0 -> "$hrs ghante $mins min"; hrs == 1 -> "1 ghante"; hrs > 0 -> "$hrs ghante"; else -> "$mins min" }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Aaj $gap ka data nahi mila (phone ya internet band tha) - asal units is se thore zyada ho sakte hain.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroStat(modifier: Modifier, label: String, value: String, color: Color, sub: String? = null) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Box(Modifier.size(width = 18.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SummaryTile(modifier: Modifier, title: String, solarWh: Double, gridWh: Double, tariff: Double) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title.uppercase(), style = SectionCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(units(solarWh), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(4.dp))
                Text("solar", style = MaterialTheme.typography.labelMedium, color = SolarDeep, modifier = Modifier.padding(bottom = 3.dp))
            }
            Text("${units(gridWh)} grid units", style = MaterialTheme.typography.bodySmall, color = GridColor)
            Spacer(Modifier.height(6.dp))
            Text("Bachat ${rupees(solarWh / 1000 * tariff)}", style = MaterialTheme.typography.labelMedium,
                color = BatteryColor, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MeterRow(name: String, color: Color, icon: ImageVector, wh: Double, since: Long, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "since " + SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(Date(since)),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(units(wh), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(2.dp))
        IconButton(onClick = onReset) {
            Icon(Icons.Filled.RestartAlt, "Reset $name", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DayRow(r: EnergyStore.DayRecord, tariff: Double) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(friendlyDay(r.dateKey), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    pretty(r.dateKey, "d MMM yyyy") + (if (r.missedMin >= 20) "  •  ${r.missedMin / 60}h ${r.missedMin % 60}m missed" else ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DayVal(units(r.solarWh), "solar", SolarDeep)
            Spacer(Modifier.width(14.dp))
            DayVal(units(r.gridWh), "grid", GridColor)
            Spacer(Modifier.width(14.dp))
            DayVal(rupees(r.solarWh / 1000 * tariff).removePrefix("Rs "), "Rs bacha", BatteryColor)
        }
    }
}

@Composable
private fun DayVal(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
