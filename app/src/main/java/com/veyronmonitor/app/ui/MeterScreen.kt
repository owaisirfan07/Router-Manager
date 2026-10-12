package com.veyronmonitor.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veyronmonitor.app.data.EnergyStore
import com.veyronmonitor.app.data.MeterStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val WarnColor = Color(0xFFF59E0B)
private val DangerColor = Color(0xFFEF4444)

private fun d(ms: Long, p: String) = SimpleDateFormat(p, Locale.US).format(Date(ms))
private fun u(v: Double) = if (v >= 100) "%.0f".format(v) else "%.1f".format(v)
private fun rs(v: Long) = "Rs " + "%,d".format(v)

@Composable
fun MeterScreen(
    readings: List<MeterStore.Reading>,
    periods: List<MeterStore.Period>,
    current: MeterStore.Current?,
    rates: MeterStore.Rates,
    warnAt: Double,
    limitAt: Double,
    recentDays: List<EnergyStore.DayRecord>,   // newest first, from the inverter
    onAddReading: (Long, Double, Boolean) -> String?,
    onRemoveReading: (MeterStore.Reading) -> Unit,
    onSetLimits: (Double, Double) -> Unit,
    onSetRates: (MeterStore.Rates) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var showLimits by remember { mutableStateOf(false) }
    var showRates by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<MeterStore.Reading?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("KE Meter", style = MaterialTheme.typography.headlineMedium)
                    Text("Meter dekh kar reading daalein - hisaab KE wala", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Reading")
                }
            }
        }

        if (current == null) {
            item {
                SectionCard("Shuru karein", Icons.Filled.ReceiptLong) {
                    Text(
                        "1. Apne KE bill par \"Present reading\" dekhein aur woh \"Bill wali reading\" ke taur par daalein.\n" +
                            "2. Phir jab chahein meter dekh kar current reading daal dein.\n\n" +
                            "App dono ka farq nikal kar batayegi kitne units use hue, roz ka average aur bill ka andaza.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("Pehli reading daalein") }
                }
            }
        } else {
            item { CurrentCard(current, rates, warnAt, limitAt, onEditLimits = { showLimits = true }, onAdd = { showAdd = true }) }
            item { GridDailyCard(current, recentDays) }
        }

        item {
            SectionCard("Bijli ke rates", Icons.Filled.Payments) {
                val ordered = rates.slabs.filter { it.upTo > 0 }.sortedBy { it.upTo } + rates.slabs.filter { it.upTo <= 0 }
                var prev = 0
                ordered.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(if (s.upTo > 0) "${prev + 1} – ${s.upTo} units" else if (prev == 0) "Har unit" else "${prev + 1}+ units",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("Rs ${"%.2f".format(s.rate)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (s.upTo > 0) prev = s.upTo
                }
                if (rates.fixedRs > 0 || rates.taxPercent > 0) {
                    Text("Fixed Rs ${"%.0f".format(rates.fixedRs)}  •  Tax ${"%.1f".format(rates.taxPercent)}%",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (rates.telescopic) "Har slab apne rate par (telescopic)" else "Saare units usi slab ke rate par",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { showRates = true }) { Text("Rates apne bill se set karein") }
            }
        }

        if (periods.isNotEmpty()) {
            item {
                Text("PICHLE BILL", style = SectionCaption, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
            items(periods, key = { it.to.at }) { p ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d(p.to.at, "MMMM yyyy"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("${d(p.from.at, "d MMM")} – ${d(p.to.at, "d MMM")}  •  ~${"%.1f".format(p.perDay)} units roz",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${u(p.units)} units", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                color = limitColor(p.units, warnAt, limitAt) ?: MaterialTheme.colorScheme.onSurface)
                            Text(rs(MeterStore.bill(p.units, rates)), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (readings.isNotEmpty()) {
            item {
                SectionCard("Saari readings", Icons.Filled.Speed) {
                    readings.reversed().forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(MeterStore.fmt(r.value), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(d(r.at, "EEE, d MMM yyyy, h:mm a") + if (r.billStart) "  •  bill reading" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (r.billStart) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { deleteTarget = r }) {
                                Icon(Icons.Filled.DeleteOutline, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        ReadingDialog(
            firstReading = readings.isEmpty(),
            onDismiss = { showAdd = false },
            onSave = { at, v, bill -> onAddReading(at, v, bill).also { if (it == null) showAdd = false } }
        )
    }
    if (showRates) RatesDialog(rates, onDismiss = { showRates = false }, onSave = { onSetRates(it); showRates = false })
    if (showLimits) {
        var w by remember { mutableStateOf(warnAt.toInt().toString()) }
        var l by remember { mutableStateOf(limitAt.toInt().toString()) }
        AlertDialog(
            onDismissRequest = { showLimits = false },
            title = { Text("Units ki hadd") },
            text = {
                Column {
                    Text("Mahine ka andaza in se upar jaane lage to app warning degi.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(w, { w = it.filter(Char::isDigit) }, label = { Text("Dhyan dein (units)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(l, { l = it.filter(Char::isDigit) }, label = { Text("Hadd (units)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val wv = w.toDoubleOrNull(); val lv = l.toDoubleOrNull()
                    if (wv != null && lv != null && wv in 1.0..lv) { onSetLimits(wv, lv); showLimits = false }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showLimits = false }) { Text("Cancel") } }
        )
    }
    deleteTarget?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Reading delete karein?") },
            text = { Text("${MeterStore.fmt(r.value)} (${d(r.at, "d MMM yyyy")}) hata di jayegi.") },
            confirmButton = { TextButton(onClick = { onRemoveReading(r); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

private fun limitColor(units: Double, warnAt: Double, limitAt: Double): Color? = when {
    units >= limitAt -> DangerColor
    units >= warnAt -> WarnColor
    else -> null
}

@Composable
private fun InfoRow(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun CurrentCard(c: MeterStore.Current, rates: MeterStore.Rates, warnAt: Double, limitAt: Double,
                        onEditLimits: () -> Unit, onAdd: () -> Unit) {
    val proj = c.projected
    val state = proj?.let { limitColor(it, warnAt, limitAt) }
    val accent = state ?: BatteryColor
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("IS BILL MEIN AB TAK", style = SectionCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(u(c.units), fontSize = 46.sp, fontWeight = FontWeight.Bold, lineHeight = 46.sp)
                Spacer(Modifier.width(6.dp))
                Text("units", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 7.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(rs(MeterStore.bill(c.units, rates)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("ab tak ka bill", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            InfoRow("Bill wali reading", "${MeterStore.fmt(c.billReading.value)}  (${d(c.billReading.at, "d MMM")})")
            InfoRow("Current reading", if (c.latest.at == c.billReading.at) "abhi nahi daali" else "${MeterStore.fmt(c.latest.value)}  (${d(c.latest.at, "d MMM, h:mm a")})")
            InfoRow("Din", "${"%.0f".format(c.days)} / ${"%.0f".format(c.totalDays)}")
            InfoRow("Roz ka average", c.perDay?.let { "${"%.1f".format(it)} units" } ?: "--", strong = true)

            if (c.latest.at == c.billReading.at) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Aaj ki meter reading daalein") }
            }

            if (proj != null) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("~${"%.0f".format(proj)}", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = accent)
                    Spacer(Modifier.width(6.dp))
                    Text("units par mahina khatam hoga  •  bill ~${rs(MeterStore.bill(proj, rates))}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 5.dp))
                }
                if (c.projectedFromLastMonth) {
                    Text("Pichle mahine ke roz ke average se (ek din baad apni reading se banega)",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                val max = limitAt * 1.15
                BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp)) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant))
                    Box(Modifier.fillMaxHeight().fillMaxWidth((proj / max).toFloat().coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(50)).background(accent.copy(alpha = 0.35f)))
                    Box(Modifier.fillMaxHeight().fillMaxWidth((c.units / max).toFloat().coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(50)).background(accent))
                    listOf(warnAt to WarnColor, limitAt to DangerColor).forEach { (v, col) ->
                        Box(Modifier.offset(x = maxWidth * (v / max).toFloat() - 1.dp).width(2.dp).fillMaxHeight().background(col))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.clickable(onClick = onEditLimits), verticalAlignment = Alignment.CenterVertically) {
                    Text("Hadd: ${warnAt.toInt()} (dhyan)  •  ${limitAt.toInt()} (rate barhta hai)",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Edit, "Edit", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state != null) {
                    Spacer(Modifier.height(10.dp))
                    Surface(color = state.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.WarningAmber, null, tint = state)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (proj >= limitAt) "Is raftar se ${limitAt.toInt()} units cross ho jayenge - rate barh jayega. Baqi din grid kam use karein."
                                else "Mahina ${warnAt.toInt()} units se upar ja raha hai - ${limitAt.toInt()} ki hadd ke qareeb.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Agli bill reading: ${d(c.nextBillAt, "EEE, d MMM")} (11 baje reminder aayega)",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Grid units per day: from the meter (exact average) and what the inverter itself measured. */
@Composable
private fun GridDailyCard(c: MeterStore.Current, recentDays: List<EnergyStore.DayRecord>) {
    SectionCard("Grid se roz kitne units", Icons.Filled.ElectricalServices) {
        InfoRow("KE meter se (pakka)", c.perDay?.let { "${"%.1f".format(it)} units / din" } ?: "reading daalein", strong = true)
        val days = recentDays.take(7)
        val inv = if (days.isNotEmpty()) days.sumOf { it.gridWh } / 1000.0 / days.size else null
        InfoRow("Inverter ne naape", inv?.let { "${"%.1f".format(it)} units / din" } ?: "--")
        Text(
            "Inverter sirf woh bijli naapta hai jo us ke andar se guzarti hai (battery charging waghera). " +
                "Jab ghar seedha grid par chalta hai, wo bijli inverter nahi ginta - is liye asal number meter wala hai.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (days.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            days.forEach { r ->
                val label = try {
                    SimpleDateFormat("EEE d MMM", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(r.dateKey)!!)
                } catch (_: Exception) { r.dateKey }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("${"%.2f".format(r.gridWh / 1000)} units (inverter)", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Add a meter reading: value, date, and whether it starts a new bill month. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingDialog(firstReading: Boolean, onDismiss: () -> Unit, onSave: (Long, Double, Boolean) -> String?) {
    var text by remember { mutableStateOf("") }
    var at by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val dayOfMonth = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.DAY_OF_MONTH)
    var bill by remember { mutableStateOf(firstReading || dayOfMonth in 7..11) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Meter reading") },
        text = {
            Column {
                Text("Meter par jo total likha hai (kWh) wo daalein.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text, onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' } },
                    label = { Text("Reading") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { picking = true }) {
                    Icon(Icons.Filled.Event, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(d(at, "EEE, d MMM yyyy"))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = !firstReading) { bill = !bill }) {
                    Checkbox(checked = bill, onCheckedChange = { if (!firstReading) bill = it }, enabled = !firstReading)
                    Column {
                        Text("Bill wali reading", style = MaterialTheme.typography.bodyMedium)
                        Text(if (firstReading) "Pehli reading - bill par likhi Present reading" else "Naya mahina yahan se shuru hoga",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = text.toDoubleOrNull()
                error = if (v == null || v <= 0) "Sahi reading daalein." else onSave(at, v, bill)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (picking) {
        val local = Calendar.getInstance().apply { timeInMillis = at }
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = utc.timeInMillis)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { sel ->
                        val uc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
                        val now = Calendar.getInstance()
                        at = Calendar.getInstance().apply {
                            set(uc.get(Calendar.YEAR), uc.get(Calendar.MONTH), uc.get(Calendar.DAY_OF_MONTH),
                                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                    }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}

/** Edit tariff slabs from the bill. */
@Composable
private fun RatesDialog(rates: MeterStore.Rates, onDismiss: () -> Unit, onSave: (MeterStore.Rates) -> Unit) {
    val rows = remember {
        mutableStateListOf(*rates.slabs.map { (if (it.upTo > 0) it.upTo.toString() else "") to "%.2f".format(it.rate) }.toTypedArray())
    }
    var fixed by remember { mutableStateOf(if (rates.fixedRs > 0) "%.0f".format(rates.fixedRs) else "") }
    var tax by remember { mutableStateOf(if (rates.taxPercent > 0) "%.1f".format(rates.taxPercent) else "") }
    var tele by remember { mutableStateOf(rates.telescopic) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bijli ke rates") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Apne KE bill se slabs daalein. Aakhri slab ka \"tak\" khaali chhorein (us se upar sab units).",
                    style = MaterialTheme.typography.bodySmall)
                rows.forEachIndexed { i, (upTo, rate) ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(upTo, { v -> rows[i] = v.filter(Char::isDigit) to rows[i].second },
                            label = { Text("Tak (units)") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(rate, { v -> rows[i] = rows[i].first to v.filter { it.isDigit() || it == '.' } },
                            label = { Text("Rs / unit") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        if (rows.size > 1) IconButton(onClick = { rows.removeAt(i) }) { Icon(Icons.Filled.Close, "Remove") }
                    }
                }
                TextButton(onClick = { rows.add("" to "") }) { Text("+ Slab") }
                Row {
                    OutlinedTextField(fixed, { fixed = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Fixed Rs") },
                        singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(tax, { tax = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Tax %") },
                        singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { tele = !tele }) {
                    Checkbox(checked = tele, onCheckedChange = { tele = it })
                    Text("Har slab apne rate par (telescopic)", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val slabs = rows.mapNotNull { (u, r) -> r.toDoubleOrNull()?.let { MeterStore.Slab(u.toIntOrNull() ?: 0, it) } }
                error = when {
                    slabs.isEmpty() -> "Kam az kam ek rate daalein."
                    slabs.count { it.upTo <= 0 } > 1 -> "Sirf aakhri slab ka \"tak\" khaali ho sakta hai."
                    else -> null
                }
                if (error == null) onSave(MeterStore.Rates(slabs, fixed.toDoubleOrNull() ?: 0.0, tax.toDoubleOrNull() ?: 0.0, tele))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
