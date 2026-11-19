package com.droidbridge.standalone.runtimehost

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

internal interface ExternalMcpRegistryPort {
    /** Returns OK or a stable native validation/runtime error code. */
    fun replace(servicesJson: String): String
}

internal class NativeExternalMcpRegistry : ExternalMcpRegistryPort {
    override fun replace(servicesJson: String): String =
        NativeRuntime.nativeMcpReplaceExternalServices(servicesJson) ?: "RUNTIME_UNAVAILABLE"
}

/**
 * APK Runtime owner for external MCP configuration. The complete service document, including
 * bearer credentials, is AES-GCM encrypted with an Android Keystore key before atomic persistence.
 * Only redacted service metadata is returned to the UI.
 */
internal class ExternalMcpSettingsController(
    directory: File,
    private val registry: ExternalMcpRegistryPort,
    private val fileSystem: McpSettingsFileSystem,
) {
    private val file = File(directory, FILE_NAME)
    private val lock = Any()
    private var startupFailure: String? = null

    fun restore(): String = synchronized(lock) {
        when (val loaded = load()) {
            is LoadResult.Failure -> {
                startupFailure = loaded.code
                registry.replace("[]")
                errorReply(loaded.code)
            }
            is LoadResult.Success -> {
                val raw = loaded.raw
                if (raw == null) {
                    startupFailure = null
                    successReply()
                } else {
                    val result = registry.replace(raw)
                    startupFailure = result.takeUnless { it == OK }
                    if (result == OK) successReply() else {
                        registry.replace("[]")
                        errorReply(result)
                    }
                }
            }
        }
    }

    fun settings(): String = synchronized(lock) {
        startupFailure?.let { return errorReply(it) }
        when (val loaded = load()) {
            is LoadResult.Failure -> errorReply(loaded.code)
            is LoadResult.Success -> {
                val services = runCatching { JSONArray(loaded.raw ?: "[]") }.getOrNull()
                    ?: return errorReply(INVALID_CONFIG)
                val redacted = JSONArray()
                for (index in 0 until services.length()) {
                    val item = services.optJSONObject(index) ?: continue
                    redacted.put(
                        JSONObject()
                            .put("name", item.optString("name"))
                            .put("endpoint", item.optString("endpoint"))
                            .put("enabled", item.optBoolean("enabled", true))
                            .put("has_bearer_token", item.has("bearer_token") && !item.isNull("bearer_token") &&
                                item.optString("bearer_token").isNotEmpty()),
                    )
                }
                JSONObject()
                    .put("schema_version", SCHEMA_VERSION)
                    .put("services", redacted)
                    .toString()
            }
        }
    }

    fun replace(raw: String): String = synchronized(lock) {
        if (raw.length > MAX_CONFIG_CHARS) return errorReply(INVALID_CONFIG)
        val incoming = runCatching { JSONArray(raw) }.getOrNull() ?: return errorReply(INVALID_CONFIG)
        val previous = when (val loaded = load()) {
            is LoadResult.Success -> loaded.raw
            is LoadResult.Failure -> return errorReply(loaded.code)
        }
        val effective = mergeSavedCredentials(incoming, previous)
        val encoded = effective.toString()
        val oldBytes = runCatching { if (file.exists()) file.readBytes() else null }
            .getOrElse { return errorReply(PERSISTENCE_FAILED) }
        val persisted = runCatching { writeEncrypted(encoded) }
        if (persisted.isFailure) {
            rollbackFile(oldBytes)
            return errorReply(PERSISTENCE_FAILED)
        }

        val applied = registry.replace(encoded)
        if (applied != OK) {
            val rolledBack = rollbackFile(oldBytes)
            return errorReply(if (rolledBack) applied else PERSISTENCE_FAILED)
        }
        startupFailure = null
        JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("applied", true)
            .put("service_count", effective.length())
            .toString()
    }

    fun reload(): String = synchronized(lock) {
        when (val loaded = load()) {
            is LoadResult.Failure -> errorReply(loaded.code)
            is LoadResult.Success -> {
                val raw = loaded.raw ?: return errorReply(NOT_CONFIGURED)
                val applied = registry.replace(raw)
                if (applied == OK) {
                    startupFailure = null
                    JSONObject().put("schema_version", SCHEMA_VERSION).put("reloaded", true).toString()
                } else errorReply(applied)
            }
        }
    }

    private sealed interface LoadResult {
        data class Success(val raw: String?) : LoadResult
        data class Failure(val code: String) : LoadResult
    }

    private fun load(): LoadResult {
        if (!file.exists()) return LoadResult.Success(null)
        return runCatching {
            check(fileSystem.isOwnerOnly(file))
            val bytes = file.readBytes()
            require(bytes.size in (IV_BYTES + TAG_BYTES + 1)..MAX_ENCRYPTED_BYTES)
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val ciphertext = bytes.copyOfRange(IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertext).decodeToString()
        }.fold(
            onSuccess = { raw ->
                if (runCatching { JSONArray(raw) }.isSuccess) LoadResult.Success(raw)
                else LoadResult.Failure(INVALID_CONFIG)
            },
            onFailure = { LoadResult.Failure(CREDENTIAL_STORE_UNAVAILABLE) },
        )
    }

    private fun mergeSavedCredentials(incoming: JSONArray, previousRaw: String?): JSONArray {
        if (previousRaw == null) return incoming
        val previous = runCatching { JSONArray(previousRaw) }.getOrNull() ?: return incoming
        val oldByName = buildMap {
            for (index in 0 until previous.length()) {
                val item = previous.optJSONObject(index) ?: continue
                put(item.optString("name"), item)
            }
        }
        for (index in 0 until incoming.length()) {
            val item = incoming.optJSONObject(index) ?: continue
            if (item.has("bearer_token")) continue
            val old = oldByName[item.optString("name")] ?: continue
            if (old.has("bearer_token") && !old.isNull("bearer_token")) {
                item.put("bearer_token", old.get("bearer_token"))
            }
        }
        return incoming
    }

    private fun rollbackFile(previousBytes: ByteArray?): Boolean =
        runCatching {
            if (previousBytes == null) {
                if (file.exists() && !file.delete()) error("cannot remove failed snapshot")
                file.parentFile?.takeIf { it.exists() }?.let(fileSystem::syncDirectory)
            } else {
                writeBytesAtomically(previousBytes)
            }
        }.isSuccess

    private fun writeEncrypted(raw: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(raw.encodeToByteArray())
        val bytes = ByteBuffer.allocate(cipher.iv.size + encrypted.size)
            .put(cipher.iv)
            .put(encrypted)
            .array()
        writeBytesAtomically(bytes)
    }

    private fun writeBytesAtomically(bytes: ByteArray) {
        require(bytes.size <= MAX_ENCRYPTED_BYTES)
        val directory = checkNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, "$FILE_NAME.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                fileSystem.restrictToOwner(temporary)
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            fileSystem.syncDirectory(directory)
            check(fileSystem.isOwnerOnly(file))
        } finally {
            if (temporary.exists()) check(temporary.delete())
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun errorReply(code: String): String =
        JSONObject().put("schema_version", SCHEMA_VERSION).put("error", code).toString()

    private fun successReply(): String =
        JSONObject().put("schema_version", SCHEMA_VERSION).put("services", JSONArray()).toString()

    private companion object {
        const val FILE_NAME = "external-mcp.enc"
        const val KEY_ALIAS = "com.droidbridge.external-mcp.v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val SCHEMA_VERSION = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val TAG_BITS = 128
        const val MAX_CONFIG_CHARS = 65_536
        const val MAX_ENCRYPTED_BYTES = 70_000
        const val OK = "OK"
        const val INVALID_CONFIG = "INVALID_CONFIG"
        const val PERSISTENCE_FAILED = "PERSISTENCE_FAILED"
        const val CREDENTIAL_STORE_UNAVAILABLE = "CREDENTIAL_STORE_UNAVAILABLE"
        const val NOT_CONFIGURED = "NOT_CONFIGURED"
    }
}
