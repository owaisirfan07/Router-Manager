package com.veyronmonitor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.veyronmonitor.app.data.WarningLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatTime(ts: Long): String =
    SimpleDateFormat("d MMM, hh:mm a", Locale.getDefault()).format(Date(ts))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarningsScreen(entries: List<WarningLogEntry>) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Warnings") }) }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Abhi tak koi warning ya fault record nahi hua.")
                }
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(entries) { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = if (entry.isFault) MaterialTheme.colorScheme.error else Color(0xFFF59E0B)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.message, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "${if (entry.isFault) "Fault" else "Warning"} code ${entry.code} -- ${formatTime(entry.timestamp)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
