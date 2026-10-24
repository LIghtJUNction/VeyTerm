package com.termux.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VeyTermCapabilityManifestTest {
    private val context = AiSessionContext("session-1", "/home")

    @Test
    fun stdioToolIsAllowedOnlyByAnEnabledSkill() {
        val server = McpServerManifest(
            id = "local-tools",
            version = "1.0.0",
            transport = McpTransport.STDIO,
            command = "/usr/bin/tool-server",
            tools = setOf("list_files"),
            enabled = true,
        )
        val skill = SkillManifest(
            id = "file-browser",
            version = "1.0.0",
            allowedMcpServers = setOf("local-tools"),
            allowedTools = setOf("list_files"),
            enabled = true,
        )
        val request = McpToolCallRequest("request-1", "local-tools", "list_files")

        assertEquals(CapabilityDecision.Allowed, McpSkillPolicy.authorize(server, skill, request, context))
    }

    @Test
    fun httpToolNeedsNetworkPermissionAndBoundedTimeout() {
        val server = McpServerManifest(
            id = "remote-tools",
            version = "1.0.0",
            transport = McpTransport.HTTP,
            endpoint = "https://example.invalid/mcp",
            tools = setOf("search"),
            enabled = true,
        )
        val skill = SkillManifest(
            id = "search",
            version = "1.0.0",
            allowedMcpServers = setOf("remote-tools"),
            allowedTools = setOf("search"),
            enabled = true,
        )
        val request = McpToolCallRequest("request-2", "remote-tools", "search", timeoutMillis = 180_000)

        val decision = McpSkillPolicy.authorize(server, skill, request, context)
        assertEquals(CapabilityDecision.Denied("timeout outside 1..120000ms"), decision)
    }

    @Test
    fun httpToolCannotRunWithoutNetworkPermission() {
        val server = McpServerManifest(
            id = "remote-tools",
            version = "1.0.0",
            transport = McpTransport.HTTP,
            endpoint = "https://example.invalid/mcp",
            tools = setOf("search"),
            enabled = true,
        )
        val skill = SkillManifest(
            id = "search",
            version = "1.0.0",
            allowedMcpServers = setOf("remote-tools"),
            allowedTools = setOf("search"),
            enabled = true,
        )
        val request = McpToolCallRequest("request-3", "remote-tools", "search")

        assertEquals(
            CapabilityDecision.Denied("HTTP MCP server lacks NETWORK permission"),
            McpSkillPolicy.authorize(server, skill, request, context),
        )
    }

    @Test
    fun workerDispatcherTimesOutWithoutBlockingCaller() {
        val workers = Executors.newSingleThreadExecutor()
        val timers = Executors.newSingleThreadScheduledExecutor()
        try {
            val dispatcher = McpToolDispatcher(workers, timers)
            val server = McpServerManifest("local", "1.0.0", McpTransport.STDIO, command = "tool", enabled = true)
            val skill = SkillManifest("skill", "1.0.0", allowedMcpServers = setOf("local"), enabled = true)
            val request = McpToolCallRequest("timeout", "local", "slow", timeoutMillis = 25)
            val callback = CountDownLatch(1)
            var result: McpDispatchResult? = null
            val callbackThread = AtomicReference<Thread>()

            dispatcher.submit(server, skill, request, context, {
                result = it
                callbackThread.set(Thread.currentThread())
                callback.countDown()
            }) { _, _ ->
                Thread.sleep(500)
                "late"
            }

            assertEquals(true, callback.await(1, TimeUnit.SECONDS))
            assertEquals(McpDispatchResult.TimedOut("timeout"), result)
            assertNotEquals(Thread.currentThread(), callbackThread.get())
        } finally {
            workers.shutdownNow()
            timers.shutdownNow()
        }
    }

    @Test
    fun workerDispatcherSupportsCancellation() {
        val workers = Executors.newSingleThreadExecutor()
        val timers = Executors.newSingleThreadScheduledExecutor()
        try {
            val dispatcher = McpToolDispatcher(workers, timers)
            val server = McpServerManifest("local", "1.0.0", McpTransport.STDIO, command = "tool", enabled = true)
            val skill = SkillManifest("skill", "1.0.0", allowedMcpServers = setOf("local"), enabled = true)
            val request = McpToolCallRequest("cancel", "local", "slow", timeoutMillis = 2_000)
            val callback = CountDownLatch(1)
            var result: McpDispatchResult? = null

            val handle = dispatcher.submit(server, skill, request, context, {
                result = it
                callback.countDown()
            }) { _, _ ->
                Thread.sleep(500)
                "cancelled"
            }
            handle.cancel()

            assertEquals(true, callback.await(1, TimeUnit.SECONDS))
            assertEquals(McpDispatchResult.Cancelled("cancel"), result)
        } finally {
            workers.shutdownNow()
            timers.shutdownNow()
        }
    }
}
