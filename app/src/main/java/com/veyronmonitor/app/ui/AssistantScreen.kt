package com.veyronmonitor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.veyronmonitor.app.data.SolarAssistant
import kotlinx.coroutines.launch

data class ChatMsg(val fromUser: Boolean, val text: String)

/** Simple question / answer screen for the built-in solar assistant. */
@Composable
fun AssistantScreen(messages: List<ChatMsg>, busy: Boolean, onAsk: (String) -> Unit, onBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column {
                Text("Solar assistant", style = MaterialTheme.typography.titleLarge)
                Text("App ke data se jawab - andaze ko andaza hi bataata hai", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) item {
                Text("Neeche koi sawal chunein ya apna likhein.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(messages) { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.widthIn(max = 320.dp)
                    ) {
                        Text(m.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
            if (busy) item { Text("Soch raha hoon...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SolarAssistant.suggestions) { s ->
                SuggestionChip(onClick = { if (!busy) onAsk(s) }, label = { Text(s) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Sawal likhein...") }, singleLine = true, shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = {
                val q = input.trim()
                if (q.isNotEmpty() && !busy) { onAsk(q); input = "" }
            }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}
