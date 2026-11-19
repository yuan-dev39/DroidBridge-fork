package com.droidbridge.ui.product.mcp

import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An external MCP server configuration held by the UI only while it is being edited.
 *
 * The bearer token must never be written to AppSettings, DataStore, logs, navigation arguments,
 * saved state, or analytics. The Runtime host is responsible for encrypting it at rest.
 */
data class ExternalMcpServiceConfig(
    val name: String,
    val endpoint: String,
    val enabled: Boolean = true,
    val bearerToken: String? = null,
)

/** Structural validation shared by APK and Root frontends; Runtime remains the security authority. */
object ExternalMcpRegistryContract {
    const val SCHEMA_VERSION = 1
    const val MAX_SERVERS = 16
    const val MAX_ENDPOINT_LENGTH = 2048
    const val MAX_BEARER_TOKEN_LENGTH = 4096

    data class ValidationError(val index: Int, val code: String)

    /**
     * Validates the shape accepted by the native registry. This deliberately does not resolve DNS:
     * the Rust Runtime applies the authoritative private-network HTTP endpoint policy.
     */
    fun validate(services: List<ExternalMcpServiceConfig>): ValidationError? {
        if (services.size > MAX_SERVERS) return ValidationError(-1, "TOO_MANY_SERVERS")
        val names = HashSet<String>()
        services.forEachIndexed { index, service ->
            if (!validName(service.name)) return ValidationError(index, "INVALID_NAME")
            if (!names.add(service.name)) return ValidationError(index, "DUPLICATE_NAME")
            if (service.endpoint.length > MAX_ENDPOINT_LENGTH || !validEndpointShape(service.endpoint)) {
                return ValidationError(index, "INVALID_ENDPOINT")
            }
            val token = service.bearerToken
            if (token != null && (
                    token.length > MAX_BEARER_TOKEN_LENGTH ||
                        token.isEmpty() ||
                        token.any { it.code !in 33..126 }
                )
            ) return ValidationError(index, "INVALID_BEARER_TOKEN")
        }
        return null
    }

    /** Encodes only the native registry's strict JSON array format, never a persistence format. */
    fun toNativeConfig(
        services: List<ExternalMcpServiceConfig>,
        clearBearerTokenNames: Set<String> = emptySet(),
    ): JsonArray {
        require(validate(services) == null) { "external MCP configuration is invalid" }
        return buildJsonArray {
            services.forEach { service ->
                add(buildJsonObject {
                    put("name", service.name)
                    put("endpoint", service.endpoint)
                    put("enabled", service.enabled)
                    when {
                        service.name in clearBearerTokenNames -> put("bearer_token", JsonNull)
                        service.bearerToken != null -> put("bearer_token", service.bearerToken)
                    }
                })
            }
        }
    }

    /** A safe summary shape for UI state. It intentionally contains no credential material. */
    fun safeSummary(service: ExternalMcpServiceConfig): JsonObject = buildJsonObject {
        put("name", service.name)
        put("endpoint", service.endpoint)
        put("enabled", service.enabled)
        put("has_bearer_token", !service.bearerToken.isNullOrEmpty())
    }

    fun validName(name: String): Boolean =
        name.isNotEmpty() &&
            name.length <= 24 &&
            !name.contains("__") &&
            name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

    private fun validEndpointShape(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.isAbsolute &&
            (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrEmpty() &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.path == "/mcp"
    }.getOrDefault(false)
}
