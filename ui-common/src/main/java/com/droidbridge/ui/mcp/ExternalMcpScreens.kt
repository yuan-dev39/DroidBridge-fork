package com.droidbridge.ui.mcp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.droidbridge.ui.R
import com.droidbridge.ui.client.RuntimeConnection
import com.droidbridge.ui.product.mcp.ExternalMcpRegistryContract
import com.droidbridge.ui.product.mcp.ExternalMcpServiceConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ExternalMcpEntry(
    val config: ExternalMcpServiceConfig,
    val hasBearerToken: Boolean,
)

data class ExternalMcpUiState(
    val services: List<ExternalMcpEntry> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val failed: Boolean = false,
)

class ExternalMcpViewModel(private val connection: RuntimeConnection) : ViewModel() {
    private val mutableState = MutableStateFlow(ExternalMcpUiState())
    val state = mutableState.asStateFlow()

    fun refresh() {
        mutableState.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            val parsed = runCatching {
                val root = Json.parseToJsonElement(connection.externalMcpSettings()).jsonObject
                require(root["error"] == null)
                val services = root["services"] as? JsonArray ?: JsonArray(emptyList())
                services.map { item ->
                    val value = item.jsonObject
                    ExternalMcpEntry(
                        config = ExternalMcpServiceConfig(
                            name = value["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            endpoint = value["endpoint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            enabled = value["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                        ),
                        hasBearerToken = value["has_bearer_token"]?.jsonPrimitive?.booleanOrNull ?: false,
                    )
                }
            }.getOrNull()
            mutableState.update { it.copy(services = parsed ?: it.services, loading = false, failed = parsed == null) }
        }
    }

    fun save(
        services: List<ExternalMcpServiceConfig>,
        clearBearerTokenNames: Set<String> = emptySet(),
    ) {
        val error = ExternalMcpRegistryContract.validate(services)
        if (error != null) {
            mutableState.update { it.copy(failed = true) }
            return
        }
        mutableState.update { it.copy(saving = true, failed = false) }
        viewModelScope.launch {
            val saved = runCatching {
                val json = ExternalMcpRegistryContract.toNativeConfig(services, clearBearerTokenNames).toString()
                val reply = Json.parseToJsonElement(connection.replaceExternalMcpServices(json)).jsonObject
                reply["applied"]?.jsonPrimitive?.booleanOrNull == true
            }.getOrDefault(false)
            mutableState.update { it.copy(saving = false, failed = !saved) }
            if (saved) refresh()
        }
    }

    fun setEnabled(entry: ExternalMcpEntry, enabled: Boolean) {
        val next = state.value.services.map {
            if (it.config.name == entry.config.name) it.copy(config = it.config.copy(enabled = enabled)) else it
        }
        save(next.map { it.config })
    }

    fun delete(name: String) {
        save(state.value.services.filterNot { it.config.name == name }.map { it.config })
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ExternalMcpRoute(viewModel: ExternalMcpViewModel, back: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ExternalMcpEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ExternalMcpEntry?>(null) }
    Scaffold(
        modifier = Modifier.testTag("route:ExternalMCP"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.external_mcp_title)) },
                navigationIcon = { TextButton(onClick = back) { Text(stringResource(R.string.action_back)) } },
                actions = {
                    TextButton(enabled = !state.saving, onClick = { adding = true }) {
                        Text(stringResource(R.string.external_mcp_add))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) item { ListItem(headlineContent = { Text(stringResource(R.string.state_loading)) }) }
            if (state.failed) item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.external_mcp_error)) },
                    trailingContent = { TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.action_retry)) } },
                )
            }
            if (!state.loading && state.services.isEmpty()) item {
                ListItem(headlineContent = { Text(stringResource(R.string.external_mcp_empty)) })
            }
            items(state.services, key = { it.config.name }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.config.name) },
                    supportingContent = {
                        Column {
                            Text(entry.config.endpoint)
                            Text(stringResource(if (entry.hasBearerToken) R.string.external_mcp_token_saved else R.string.external_mcp_no_token))
                        }
                    },
                    trailingContent = {
                        Column {
                            Switch(
                                checked = entry.config.enabled,
                                enabled = !state.saving,
                                onCheckedChange = { viewModel.setEnabled(entry, it) },
                                modifier = Modifier.testTag("external_mcp:enabled:${entry.config.name}"),
                            )
                            TextButton(enabled = !state.saving, onClick = { editing = entry }) {
                                Text(stringResource(R.string.action_edit))
                            }
                            TextButton(enabled = !state.saving, onClick = { deleting = entry }) {
                                Text(stringResource(R.string.action_delete))
                            }
                        }
                    },
                    modifier = Modifier.testTag("external_mcp:item:${entry.config.name}"),
                )
            }
        }
    }
    if (adding || editing != null) {
        val original = editing
        ExternalMcpEditor(
            initial = original?.config,
            hasSavedToken = original?.hasBearerToken == true,
            saving = state.saving,
            onDismiss = { adding = false; editing = null },
            onSave = { config, token, clearToken ->
                val next = state.services.map { it.config }.filterNot { it.name == original?.config?.name }.toMutableList()
                next += config.copy(bearerToken = token.takeIf { it.isNotBlank() })
                viewModel.save(next, if (clearToken && token.isBlank()) setOf(config.name) else emptySet())
                adding = false
                editing = null
            },
        )
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.external_mcp_delete_title, entry.config.name)) },
            text = { Text(stringResource(R.string.external_mcp_delete_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(entry.config.name); deleting = null }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    LaunchedEffect(Unit) { viewModel.refresh() }
}

@Composable
private fun ExternalMcpEditor(
    initial: ExternalMcpServiceConfig?,
    hasSavedToken: Boolean,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (ExternalMcpServiceConfig, String, Boolean) -> Unit,
) {
    var name by remember(initial?.name) { mutableStateOf(initial?.name.orEmpty()) }
    var endpoint by remember(initial?.endpoint) { mutableStateOf(initial?.endpoint.orEmpty()) }
    var token by remember { mutableStateOf("") }
    var enabled by remember(initial?.enabled) { mutableStateOf(initial?.enabled ?: true) }
    var clearToken by remember { mutableStateOf(false) }
    val normalizedName = name.trim()
    val canSave = !saving && normalizedName.isNotBlank() && endpoint.isNotBlank() &&
        (!hasSavedToken || normalizedName == initial?.name || token.isNotBlank() || clearToken)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.external_mcp_add else R.string.external_mcp_edit)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.external_mcp_name)) }, singleLine = true)
                OutlinedTextField(
                    endpoint, { endpoint = it },
                    label = { Text(stringResource(R.string.external_mcp_endpoint)) },
                    supportingText = { Text(stringResource(R.string.external_mcp_endpoint_hint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    token, { token = it },
                    label = { Text(stringResource(R.string.external_mcp_bearer_token)) },
                    supportingText = { Text(stringResource(if (hasSavedToken) R.string.external_mcp_token_keep_hint else R.string.external_mcp_token_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (hasSavedToken) {
                    androidx.compose.foundation.layout.Row {
                        Checkbox(checked = clearToken, onCheckedChange = { clearToken = it })
                        Text(stringResource(R.string.external_mcp_clear_token))
                    }
                }
                androidx.compose.foundation.layout.Row {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text(stringResource(R.string.external_mcp_enabled))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(ExternalMcpServiceConfig(name.trim(), endpoint.trim(), enabled), token, clearToken)
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
