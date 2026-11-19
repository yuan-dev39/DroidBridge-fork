package com.droidbridge.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
fun McpServersRoute(connection: DaemonConnection, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("http://127.0.0.1:8787/mcp") }
    var token by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf("{}") }
    var message by remember { mutableStateOf("") }

    fun reload() {
        scope.launch {
            runCatching { connection.mcpServersList() }
                .onSuccess { snapshot = it; message = "Registry updated" }
                .onFailure { message = it.message ?: "Unable to contact DroidBridge daemon" }
        }
    }
    LaunchedEffect(Unit) { reload() }
    val servers = runCatching {
        Json.parseToJsonElement(snapshot).jsonObject["servers"]?.jsonArray?.map { it.jsonObject }.orEmpty()
    }.getOrDefault(emptyList())

    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("MCP servers", style = MaterialTheme.typography.headlineSmall)
        Text("Changes are shared by the local MCP endpoint and the existing OpenAI Secure Tunnel.")
        OutlinedTextField(id, { id = it }, label = { Text("Service ID (e.g. mt-manager)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(displayName, { displayName = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("MCP endpoint URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Bearer token (optional; write-only)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    runCatching { connection.addMcpServer(id.trim(), displayName.trim(), endpoint.trim(), token.ifBlank { null }) }
                        .onSuccess {
                            snapshot = it
                            val error = runCatching { Json.parseToJsonElement(it).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                            message = error?.let { code -> "Add failed: $code" } ?: "Server added / discovery attempted"
                            token = ""
                        }
                        .onFailure { message = it.message ?: "Add failed" }
                }
            }) { Text("Add server") }
            Button(onClick = { reload() }) { Text("Refresh list") }
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(servers) { server ->
                val serverId = server["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val name = server["display_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val url = server["endpoint"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val enabled = server["enabled"]?.jsonPrimitive?.contentOrNull == "true"
                val count = server["tool_count"]?.jsonPrimitive?.contentOrNull ?: "0"
                val error = server["error"]?.jsonPrimitive?.contentOrNull
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("$name ($serverId)", style = MaterialTheme.typography.titleMedium)
                        Text(url, style = MaterialTheme.typography.bodySmall)
                        Text("Tools: $count · " + if (enabled) "Enabled" else "Disabled")
                        if (!error.isNullOrBlank()) Text("Connection: $error", color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    runCatching { connection.setMcpServerEnabled(serverId, !enabled) }
                                        .onSuccess { snapshot = it; message = "Server state updated" }
                                        .onFailure { message = it.message ?: "Update failed" }
                                }
                            }) { Text(if (enabled) "Disable" else "Enable") }
                            Button(onClick = {
                                scope.launch {
                                    runCatching { connection.refreshMcpServer(serverId) }
                                        .onSuccess { snapshot = it; message = "Discovery finished" }
                                        .onFailure { message = it.message ?: "Refresh failed" }
                                }
                            }) { Text("Test / refresh") }
                            Button(onClick = {
                                scope.launch {
                                    runCatching { connection.removeMcpServer(serverId) }
                                        .onSuccess { snapshot = it; message = "Server removed" }
                                        .onFailure { message = it.message ?: "Remove failed" }
                                }
                            }) { Text("Remove") }
                        }
                    }
                }
            }
        }
        Button(onClick = onBack) { Text("Back") }
    }
}
