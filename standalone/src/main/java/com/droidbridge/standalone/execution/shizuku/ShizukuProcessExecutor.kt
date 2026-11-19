package com.droidbridge.standalone.execution.shizuku

import com.droidbridge.standalone.execution.android.AndroidExecutionRequest
import com.droidbridge.standalone.execution.android.AndroidExecutionResult
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID
import org.json.JSONObject

internal class ShizukuProcessExecutor(
    private val guardPath: String,
    private val packageName: String,
    private val guardExecutor: ShizukuGuardExecutor,
) {
    suspend fun start(
        candidate: ShizukuSessionLease,
        request: AndroidExecutionRequest,
    ): AndroidExecutionResult {
        val invocation = ShizukuGuardedPlanCodec.decodeInvocation(
            request.payload,
            guardPath,
            packageName,
        )
        val result = guardExecutor.execute(
            candidate,
            request,
            request.executionId,
            invocation.primitive,
            invocation.payload,
        )
        if (invocation.primitive == "process_start") {
            // The UserService process and its guarded child inherit Shizuku's shell UID.
            // Native launch independently rejects any caller whose effective UID is not 2000.
            if (android.os.Process.myUid() != 2_000) {
                throw ShizukuExecutionException("PERMISSION_DENIED")
            }
            result.put("actual_run_as", "shell")
        }
        return AndroidExecutionResult(result.toString().toByteArray())
    }

    fun cancel(
        candidate: ShizukuSessionLease,
        request: AndroidExecutionRequest,
    ): AndroidExecutionResult {
        require(request.descriptors.isEmpty())
        val input = JSONObject(strictUtf8(request.payload))
        require(input.keys().asSequence().all { it == "execution_id" })
        val target = (input.opt("execution_id") as? String)
            ?: throw ShizukuExecutionException("INVALID_ARGUMENT")
        require(UUID.fromString(target).toString() == target)
        candidate.requireCurrent(request, allowCleanupControl = true)
        val cancelled = candidate.remote.cancel(candidate.token, target)
        return AndroidExecutionResult(
            JSONObject().put("cancelled", cancelled).toString().toByteArray(),
        )
    }

    private fun strictUtf8(payload: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
    } catch (error: CharacterCodingException) {
        throw ShizukuExecutionException("INVALID_ARGUMENT")
    }
}
