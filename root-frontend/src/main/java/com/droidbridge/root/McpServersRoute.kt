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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.droidbridge.ui.R
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
    var lanSnapshot by remember { mutableStateOf("{}") }
    var lanPortInput by remember { mutableStateOf("18767") }
    var lanToken by remember { mutableStateOf("") }
    var showLanToken by remember { mutableStateOf(false) }
    var lanMessage by remember { mutableStateOf("") }

    val registryUpdated = stringResource(R.string.mcp_servers_registry_updated)
    val daemonUnavailable = stringResource(R.string.mcp_servers_daemon_unavailable)
    val addFailed = stringResource(R.string.mcp_servers_add_failed)
    val added = stringResource(R.string.mcp_servers_added)
    val stateUpdated = stringResource(R.string.mcp_servers_state_updated)
    val updateFailed = stringResource(R.string.mcp_servers_update_failed)
    val discoveryFinished = stringResource(R.string.mcp_servers_discovery_finished)
    val connectionTestFailed = stringResource(R.string.mcp_servers_connection_test_failed)
    val refreshFailed = stringResource(R.string.mcp_servers_refresh_failed)
    val removed = stringResource(R.string.mcp_servers_removed)
    val removeFailed = stringResource(R.string.mcp_servers_remove_failed)

    fun reload() {
        scope.launch {
            runCatching { connection.mcpServersList() }
                .onSuccess { snapshot = it; message = registryUpdated }
                .onFailure { message = daemonUnavailable }
        }
    }
    fun reloadLan() {
        scope.launch {
            runCatching { connection.mcpLanSettings() }
                .onSuccess { value ->
                    lanSnapshot = value
                    lanPortInput = runCatching {
                        Json.parseToJsonElement(value).jsonObject["port"]?.jsonPrimitive?.contentOrNull ?: "18767"
                    }.getOrDefault("18767")
                }
                .onFailure { lanMessage = daemonUnavailable }
        }
    }
    LaunchedEffect(Unit) { reload(); reloadLan() }
    val servers = runCatching {
        Json.parseToJsonElement(snapshot).jsonObject["servers"]?.jsonArray?.map { it.jsonObject }.orEmpty()
    }.getOrDefault(emptyList())
    val lanObject = runCatching { Json.parseToJsonElement(lanSnapshot).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    val lanEnabled = lanObject["enabled"]?.jsonPrimitive?.contentOrNull == "true"
    val lanEndpoint = lanObject["endpoint"]?.jsonPrimitive?.contentOrNull
    val lanState = stringResource(if (lanEnabled) R.string.mcp_servers_enabled else R.string.mcp_servers_disabled)

    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.mcp_servers_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.mcp_servers_shared_description))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.mcp_lan_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.mcp_lan_description), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.mcp_lan_status, lanState))
                OutlinedTextField(
                    value = lanPortInput,
                    onValueChange = { lanPortInput = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.mcp_lan_port)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (lanEndpoint.isNullOrBlank()) {
                    Text(stringResource(R.string.mcp_lan_no_address), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.mcp_lan_endpoint, lanEndpoint), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.mcp_lan_private_only), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            runCatching { connection.setMcpLanEnabled(!lanEnabled) }
                                .onSuccess { lanSnapshot = it; lanMessage = "" }
                                .onFailure { lanMessage = daemonUnavailable }
                        }
                    }) {
                        Text(stringResource(if (lanEnabled) R.string.mcp_lan_disable else R.string.mcp_lan_enable))
                    }
                    Button(
                        enabled = lanPortInput.toIntOrNull()?.let { it in 1024..65535 } == true,
                        onClick = {
                            scope.launch {
                                runCatching { connection.setMcpLanPort(lanPortInput.toInt()) }
                                    .onSuccess { lanSnapshot = it; lanMessage = "" }
                                    .onFailure { lanMessage = daemonUnavailable }
                            }
                        },
                    ) { Text(stringResource(R.string.mcp_lan_apply_port)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            runCatching { connection.revealMcpLanToken() }
                                .onSuccess { value ->
                                    lanToken = runCatching {
                                        Json.parseToJsonElement(value).jsonObject["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    }.getOrDefault("")
                                    showLanToken = true
                                }
                                .onFailure { lanMessage = daemonUnavailable }
                        }
                    }) { Text(stringResource(R.string.mcp_lan_reveal_token)) }
                    Button(onClick = {
                        scope.launch {
                            runCatching { connection.rotateMcpLanToken() }
                                .onSuccess { status ->
                                    lanSnapshot = status
                                    runCatching { connection.revealMcpLanToken() }.onSuccess { value ->
                                        lanToken = Json.parseToJsonElement(value).jsonObject["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                        showLanToken = true
                                    }
                                }
                                .onFailure { lanMessage = daemonUnavailable }
                        }
                    }) { Text(stringResource(R.string.mcp_lan_rotate_token)) }
                }
                if (lanToken.isNotBlank()) {
                    OutlinedTextField(
                        value = lanToken,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.mcp_lan_token)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showLanToken) VisualTransformation.None else PasswordVisualTransformation(),
                    )
                }
                if (lanMessage.isNotBlank()) Text(lanMessage, color = MaterialTheme.colorScheme.error)
            }
        }
        OutlinedTextField(
            value = id,
            onValueChange = { id = it },
            label = { Text(stringResource(R.string.mcp_servers_service_id)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(stringResource(R.string.mcp_servers_display_name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = { Text(stringResource(R.string.mcp_servers_endpoint_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text(stringResource(R.string.mcp_servers_safety_note), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(R.string.mcp_servers_bearer_token)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = id.isNotBlank() && displayName.isNotBlank() && endpoint.isNotBlank(),
                onClick = {
                    scope.launch {
                        runCatching {
                            connection.addMcpServer(id.trim(), displayName.trim(), endpoint.trim(), token.ifBlank { null })
                        }.onSuccess {
                            snapshot = it
                            val error = runCatching {
                                Json.parseToJsonElement(it).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                            }.getOrNull()
                            message = error?.let { code -> "$addFailed: $code" } ?: added
                            token = ""
                        }.onFailure { message = addFailed }
                    }
                },
            ) { Text(stringResource(R.string.mcp_servers_add)) }
            Button(onClick = { reload() }) { Text(stringResource(R.string.mcp_servers_refresh_list)) }
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
                        Text(
                            stringResource(R.string.mcp_servers_tools_count, count) + " · " +
                                stringResource(if (enabled) R.string.mcp_servers_enabled else R.string.mcp_servers_disabled),
                        )
                        if (!error.isNullOrBlank()) {
                            Text(
                                stringResource(R.string.mcp_servers_connection_error, error),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    runCatching { connection.setMcpServerEnabled(serverId, !enabled) }
                                        .onSuccess { snapshot = it; message = stateUpdated }
                                        .onFailure { message = updateFailed }
                                }
                            }) {
                                Text(stringResource(if (enabled) R.string.mcp_servers_disabled else R.string.mcp_servers_enabled))
                            }
                            Button(onClick = {
                                scope.launch {
                                    runCatching { connection.refreshMcpServer(serverId) }
                                        .onSuccess {
                                            snapshot = it
                                            val reply = runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
                                            val refreshed = reply?.get("refreshed")?.jsonPrimitive?.contentOrNull == "true"
                                            val errorCode = reply?.get("error")?.jsonPrimitive?.contentOrNull
                                            message = if (refreshed) discoveryFinished
                                                else "$connectionTestFailed: ${errorCode ?: "UPSTREAM_UNAVAILABLE"}"
                                        }
                                        .onFailure { message = refreshFailed }
                                }
                            }) { Text(stringResource(R.string.mcp_servers_test_refresh)) }
                            Button(onClick = {
                                scope.launch {
                                    runCatching { connection.removeMcpServer(serverId) }
                                        .onSuccess { snapshot = it; message = removed }
                                        .onFailure { message = removeFailed }
                                }
                            }) { Text(stringResource(R.string.mcp_servers_remove)) }
                        }
                    }
                }
            }
        }
        Button(onClick = onBack) { Text(stringResource(R.string.key_back)) }
    }
}
