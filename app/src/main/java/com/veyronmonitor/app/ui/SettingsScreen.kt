package com.veyronmonitor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.SolarPower
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** The one parameter we've explicitly confirmed the meaning of against the real i.Solar app. */
private const val CHARGING_PRIORITY_KEY = "PC"

private data class PriorityOption(val value: String, val label: String)

private val CHARGING_PRIORITY_OPTIONS = listOf(
    PriorityOption("1", "Solar First"),
    PriorityOption("2", "Solar + Utility"),
    PriorityOption("3", "Solar Only")
)

/** Raw param strings look like "3 1,2,3" (current value, then comma-separated options). */
private fun currentValueOf(raw: String?): String? = raw?.trim()?.substringBefore(' ')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    deviceName: String,
    params: JSONObject?,
    isLoading: Boolean,
    errorMessage: String?,
    isSaving: Boolean,
    saveError: String?,
    saveSuccessMessage: String?,
    onBack: () -> Unit,
    onSetChargingPriority: (String) -> Unit,
    onOpenSchedule: () -> Unit
) {
    var pendingChoice by remember { mutableStateOf<PriorityOption?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Parameters -- $deviceName") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                errorMessage != null -> Text(
                    errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
                params == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Koi parameter data nahi mila.")
                }
                else -> {
                    val currentPriority = currentValueOf(params.optString(CHARGING_PRIORITY_KEY, null))

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            ElevatedCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.SolarPower, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Charging Priority", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Battery kis source se charge ho -- Solar, Utility, ya dono.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(12.dp))

                                    if (isSaving) {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        Spacer(Modifier.height(12.dp))
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CHARGING_PRIORITY_OPTIONS.forEach { option ->
                                            val selected = option.value == currentPriority
                                            OutlinedButton(
                                                onClick = { if (!selected) pendingChoice = option },
                                                enabled = !isSaving,
                                                colors = if (selected) {
                                                    ButtonDefaults.outlinedButtonColors(
                                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                                    )
                                                } else {
                                                    ButtonDefaults.outlinedButtonColors()
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(option.label, modifier = Modifier.weight(1f))
                                                if (selected) Text("Current", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }

                                    if (saveSuccessMessage != null) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            saveSuccessMessage,
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    if (saveError != null) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(saveError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    }

                                    Spacer(Modifier.height(12.dp))
                                    OutlinedButton(onClick = onOpenSchedule, modifier = Modifier.fillMaxWidth()) {
                                        Text("Automatic Schedule Set Karein")
                                    }
                                }
                            }

                            Text(
                                "Baaqi sab parameters (dekhne k liye, filhal change nahi honge):",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }

                        val keys = params.keys().asSequence().sorted().filter { it != CHARGING_PRIORITY_KEY }.toList()
                        items(keys) { key ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(key, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text(
                                    params.opt(key)?.toString() ?: "--",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    pendingChoice?.let { choice ->
        AlertDialog(
            onDismissRequest = { pendingChoice = null },
            title = { Text("Charging Priority badlein?") },
            text = { Text("Ab se battery \"${choice.label}\" tareeqe se charge hogi. Yeh command seedha inverter ko bheji jaye gi.") },
            confirmButton = {
                TextButton(onClick = {
                    onSetChargingPriority(choice.value)
                    pendingChoice = null
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { pendingChoice = null }) { Text("Cancel") }
            }
        )
    }
}
