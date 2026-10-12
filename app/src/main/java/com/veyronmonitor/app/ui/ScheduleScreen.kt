package com.veyronmonitor.app.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.veyronmonitor.app.data.ChargeSchedule
import java.util.Calendar
import java.util.Locale

private fun timeLabel(hour: Int, minute: Int): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }
    return java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(cal.time)
}

@Composable
private fun rememberTimePicker(initialHour: Int, initialMinute: Int, onPicked: (Int, Int) -> Unit): () -> Unit {
    val context = LocalContext.current
    return {
        TimePickerDialog(context, { _, hour, minute -> onPicked(hour, minute) }, initialHour, initialMinute, false).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    schedules: List<ChargeSchedule>,
    needsExactAlarmPermission: Boolean,
    onRequestExactAlarmPermission: () -> Unit,
    onAdd: (ChargeSchedule) -> Unit,
    onToggle: (id: Long, enabled: Boolean) -> Unit,
    onRemove: (id: Long) -> Unit,
    onBack: () -> Unit
) {
    var startHour by remember { mutableStateOf<Int?>(null) }
    var startMinute by remember { mutableStateOf<Int?>(null) }
    var endHour by remember { mutableStateOf<Int?>(null) }
    var endMinute by remember { mutableStateOf<Int?>(null) }

    val pickStart = rememberTimePicker(22, 0) { h, m -> startHour = h; startMinute = m }
    val pickEnd = rememberTimePicker(23, 0) { h, m -> endHour = h; endMinute = m }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Charging Schedule") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Text(
                    "In waqton pe app khud 'Solar + Utility' ON kar degi, aur end time pe wapas " +
                        "'Solar Only' kar degi. Zaroori: phone us waqt on aur internet se connected " +
                        "hona chahiye, aur is app ko battery-saver se 'unrestricted' allow karein.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (needsExactAlarmPermission) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Exact alarm permission chahiye taake schedule theek waqt pe chale.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRequestExactAlarmPermission) { Text("Permission dein") }
                    }
                }
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Nayi Schedule Add Karein", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = pickStart, modifier = Modifier.weight(1f)) {
                            Text(if (startHour != null) "Start: ${timeLabel(startHour!!, startMinute!!)}" else "Start Time")
                        }
                        OutlinedButton(onClick = pickEnd, modifier = Modifier.weight(1f)) {
                            Text(if (endHour != null) "End: ${timeLabel(endHour!!, endMinute!!)}" else "End Time")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            onAdd(
                                ChargeSchedule(
                                    id = System.currentTimeMillis(),
                                    startHour = startHour!!,
                                    startMinute = startMinute!!,
                                    endHour = endHour!!,
                                    endMinute = endMinute!!
                                )
                            )
                            startHour = null; startMinute = null; endHour = null; endMinute = null
                        },
                        enabled = startHour != null && endHour != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Add Schedule") }
                }
            }

            Text(
                "Maujooda Schedules",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (schedules.isEmpty()) {
                Text(
                    "Koi schedule nahi banaya gaya.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(schedules, key = { it.id }) { s ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                "${timeLabel(s.startHour, s.startMinute)} \u2192 ${timeLabel(s.endHour, s.endMinute)}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text("Solar + Utility, phir Solar Only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = s.enabled, onCheckedChange = { onToggle(s.id, it) })
                            IconButton(onClick = { onRemove(s.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
