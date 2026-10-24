package com.termux.app.ai

import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val SEMVER = Regex("^\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?$")

enum class McpTransport {
    STDIO,
    HTTP,
}

enum class CapabilityPermission {
    READ_ONLY,
    TERMINAL_EXECUTE,
    FILE_READ,
    FILE_WRITE,
    NETWORK,
}

data class McpServerManifest(
    val id: String,
    val version: String,
    val transport: McpTransport,
    val command: String? = null,
    val endpoint: String? = null,
    val permissions: Set<CapabilityPermission> = emptySet(),
    val tools: Set<String> = emptySet(),
    val enabled: Boolean = false,
) {
    init {
        require(id.isNotBlank())
        require(SEMVER.matches(version)) { "MCP server version must be semantic" }
        when (transport) {
            McpTransport.STDIO -> require(!command.isNullOrBlank()) { "stdio MCP server needs command" }
            McpTransport.HTTP -> require(!endpoint.isNullOrBlank()) { "HTTP MCP server needs endpoint" }
        }
    }
}

data class SkillManifest(
    val id: String,
    val version: String,
    val permissions: Set<CapabilityPermission> = emptySet(),
    val allowedMcpServers: Set<String> = emptySet(),
    val allowedTools: Set<String> = emptySet(),
    val enabled: Boolean = false,
) {
    init {
        require(id.isNotBlank())
        require(SEMVER.matches(version)) { "skill version must be semantic" }
    }
}

data class AiSessionContext(
    val sessionId: String,
    val workingDirectory: String,
    val selectedSkillIds: Set<String> = emptySet(),
    val maxContextCharacters: Int = 32_000,
) {
    init {
        require(sessionId.isNotBlank())
        require(workingDirectory.isNotBlank())
        require(maxContextCharacters in 1..1_000_000)
    }
}

data class McpToolCallRequest(
    val requestId: String,
    val serverId: String,
    val toolName: String,
    val timeoutMillis: Long = 30_000,
) {
    init {
        require(requestId.isNotBlank())
        require(serverId.isNotBlank())
        require(toolName.isNotBlank())
    }
}

sealed interface CapabilityDecision {
    data object Allowed : CapabilityDecision
    data class Denied(val reason: String) : CapabilityDecision
}

/**
 * Authorization only. Actual stdio/HTTP I/O belongs on a worker dispatcher and
 * must honor the request timeout and cancellation token at the call boundary.
 */
object McpSkillPolicy {
    fun authorize(
        server: McpServerManifest,
        skill: SkillManifest,
        request: McpToolCallRequest,
        context: AiSessionContext,
    ): CapabilityDecision {
        if (!server.enabled) return CapabilityDecision.Denied("MCP server is disabled")
        if (!skill.enabled) return CapabilityDecision.Denied("skill is disabled")
        if (request.serverId != server.id) return CapabilityDecision.Denied("server mismatch")
        if (server.id !in skill.allowedMcpServers) return CapabilityDecision.Denied("skill cannot use this MCP server")
        if (server.tools.isNotEmpty() && request.toolName !in server.tools) {
            return CapabilityDecision.Denied("tool is not declared by the MCP server")
        }
        if (skill.allowedTools.isNotEmpty() && request.toolName !in skill.allowedTools) {
            return CapabilityDecision.Denied("tool is not allowed by the skill")
        }
        if (request.timeoutMillis !in 1..120_000) return CapabilityDecision.Denied("timeout outside 1..120000ms")
        val missingPermissions = skill.permissions - server.permissions
        if (missingPermissions.isNotEmpty()) {
            return CapabilityDecision.Denied("server does not grant skill permissions: $missingPermissions")
        }
        if (server.transport == McpTransport.HTTP && CapabilityPermission.NETWORK !in server.permissions) {
            return CapabilityDecision.Denied("HTTP MCP server lacks NETWORK permission")
        }
        if (context.sessionId.isBlank()) return CapabilityDecision.Denied("missing AI session context")
        return CapabilityDecision.Allowed
    }
}

data class McpToolResult(val requestId: String, val payload: String)

sealed interface McpDispatchResult {
    data class Completed(val result: McpToolResult) : McpDispatchResult
    data class Denied(val requestId: String, val reason: String) : McpDispatchResult
    data class TimedOut(val requestId: String) : McpDispatchResult
    data class Cancelled(val requestId: String) : McpDispatchResult
    data class Failed(val requestId: String, val message: String) : McpDispatchResult
}

class McpCallHandle internal constructor(private val cancelAction: () -> Unit) {
    fun cancel() = cancelAction()
}

/**
 * Worker-only dispatcher boundary. The callback runs on a worker thread; UI
 * code must post its result back to the main looper explicitly.
 */
class McpToolDispatcher(
    private val workers: ExecutorService,
    private val timers: ScheduledExecutorService,
) {
    fun submit(
        server: McpServerManifest,
        skill: SkillManifest,
        request: McpToolCallRequest,
        context: AiSessionContext,
        callback: (McpDispatchResult) -> Unit,
        handler: (McpToolCallRequest, AiSessionContext) -> String,
    ): McpCallHandle {
        val decision = McpSkillPolicy.authorize(server, skill, request, context)
        if (decision is CapabilityDecision.Denied) {
            callback(McpDispatchResult.Denied(request.requestId, decision.reason))
            return McpCallHandle {}
        }

        val finished = AtomicBoolean(false)
        val futureRef = AtomicReference<Future<*>?>(null)
        val timeoutRef = AtomicReference<ScheduledFuture<*>?>(null)
        fun finish(result: McpDispatchResult) {
            if (finished.compareAndSet(false, true)) {
                timeoutRef.get()?.cancel(false)
                callback(result)
            }
        }

        val future = workers.submit {
            try {
                finish(McpDispatchResult.Completed(McpToolResult(request.requestId, handler(request, context))))
            } catch (_: InterruptedException) {
                finish(McpDispatchResult.Cancelled(request.requestId))
            } catch (error: Throwable) {
                finish(McpDispatchResult.Failed(request.requestId, error.message ?: error::class.java.simpleName))
            }
        }
        futureRef.set(future)
        timeoutRef.set(timers.schedule({
            if (finished.compareAndSet(false, true)) {
                futureRef.get()?.cancel(true)
                callback(McpDispatchResult.TimedOut(request.requestId))
            }
        }, request.timeoutMillis, TimeUnit.MILLISECONDS))

        return McpCallHandle {
            if (finished.compareAndSet(false, true)) {
                timeoutRef.get()?.cancel(false)
                futureRef.get()?.cancel(true)
                callback(McpDispatchResult.Cancelled(request.requestId))
            }
        }
    }
}
