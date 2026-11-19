package com.droidbridge.ui.product.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ExternalMcpRegistryContractTest {
    @Test
    fun acceptsNativeRegistryShapeAndOmitsEmptyCredentials() {
        val services = listOf(
            ExternalMcpServiceConfig("mt", "http://127.0.0.1:8787/mcp"),
            ExternalMcpServiceConfig("cloud-1", "https://mcp.example.com/mcp", bearerToken = "secret"),
        )
        assertNull(ExternalMcpRegistryContract.validate(services))
        val json = ExternalMcpRegistryContract.toNativeConfig(services).toString()
        assertTrue(json.contains("\"name\":\"mt\""))
        val first = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonArray[0].jsonObject
        assertFalse("bearer_token" in first)
        assertTrue(json.contains("\"bearer_token\":\"secret\""))
    }

    @Test
    fun rejectsDuplicateNamesUnsafeNamesAndInvalidEndpointShapes() {
        assertEquals(
            "DUPLICATE_NAME",
            ExternalMcpRegistryContract.validate(
                listOf(
                    ExternalMcpServiceConfig("mt", "http://127.0.0.1/mcp"),
                    ExternalMcpServiceConfig("mt", "http://127.0.0.1/mcp"),
                ),
            )?.code,
        )
        assertEquals(
            "INVALID_NAME",
            ExternalMcpRegistryContract.validate(
                listOf(ExternalMcpServiceConfig("MT__root", "http://127.0.0.1/mcp")),
            )?.code,
        )
        assertEquals(
            "INVALID_ENDPOINT",
            ExternalMcpRegistryContract.validate(
                listOf(ExternalMcpServiceConfig("mt", "http://user:pass@127.0.0.1/mcp")),
            )?.code,
        )
        assertEquals(
            "INVALID_ENDPOINT",
            ExternalMcpRegistryContract.validate(
                listOf(ExternalMcpServiceConfig("mt", "http://127.0.0.1/other")),
            )?.code,
        )
    }

    @Test
    fun safeSummaryNeverContainsBearerToken() {
        val summary = ExternalMcpRegistryContract.safeSummary(
            ExternalMcpServiceConfig("mt", "http://127.0.0.1:8787/mcp", bearerToken = "top-secret"),
        ).toString()
        assertTrue(summary.contains("has_bearer_token"))
        assertFalse(summary.contains("top-secret"))
    }
}
