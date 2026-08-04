package com.deepseek.coder.data.skill

import com.deepseek.coder.domain.skill.ToolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ToolExecutor 单元测试（SPEC-Skill-v1.2 §3.3 / §3.4）。
 *
 * 覆盖：入口参数校验（决策 10）、会话级 LRU 缓存（决策 13）、路由执行、失败不缓存。
 */
class ToolExecutorTest {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** 可计数的测试工具，用于验证缓存命中。 */
    private class CountingTool(private val tag: String = "echo") : ToolImpl {
        val callCount = AtomicInteger(0)
        override val name: String = tag
        suspend override fun execute(args: JsonObject): ToolResult {
            callCount.incrementAndGet()
            val msg = args["msg"]?.let { (it as JsonPrimitive).content } ?: "none"
            return ToolResult.Success("echo:$msg")
        }

        companion object {
            fun spec(name: String = "echo") = ToolSpec(
                name = name,
                description = "test echo tool",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("msg", buildJsonObject { put("type", "string") })
                    })
                    put("required", JsonArray(listOf(JsonPrimitive("msg"))))
                }
            )
        }
    }

    @Test
    fun `valid args execute successfully`() = runTest {
        val tool = CountingTool()
        val executor = ToolExecutor(setOf(tool))
        val args = buildJsonObject { put("msg", "hello") }
        val result = executor.execute(CountingTool.spec(), args)
        assertTrue("expected Success, got $result", result is ToolResult.Success)
        assertEquals("echo:hello", (result as ToolResult.Success).content)
        assertEquals(1, tool.callCount.get())
    }

    @Test
    fun `missing required field returns Failure without executing`() = runTest {
        val tool = CountingTool()
        val executor = ToolExecutor(setOf(tool))
        val args = buildJsonObject { /* no msg */ }
        val result = executor.execute(CountingTool.spec(), args)
        assertTrue("expected Failure", result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("msg"))
        assertEquals("tool should not be called on validation failure", 0, tool.callCount.get())
    }

    @Test
    fun `wrong type returns Failure`() = runTest {
        val tool = CountingTool()
        val executor = ToolExecutor(setOf(tool))
        val args = buildJsonObject { put("msg", 42) } // integer, not string
        val result = executor.execute(CountingTool.spec(), args)
        assertTrue("expected Failure", result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("string"))
    }

    @Test
    fun `cache hit on second identical call - decision 13`() = runTest {
        val tool = CountingTool()
        val executor = ToolExecutor(setOf(tool))
        val args = buildJsonObject { put("msg", "cached") }

        executor.execute(CountingTool.spec(), args)
        executor.execute(CountingTool.spec(), args)

        assertEquals("second call should hit cache, tool executed once", 1, tool.callCount.get())
    }

    @Test
    fun `different args bypass cache`() = runTest {
        val tool = CountingTool()
        val executor = ToolExecutor(setOf(tool))

        executor.execute(CountingTool.spec(), buildJsonObject { put("msg", "a") })
        executor.execute(CountingTool.spec(), buildJsonObject { put("msg", "b") })

        assertEquals(2, tool.callCount.get())
    }

    @Test
    fun `clearCache forces re-execution`() = runTest {
        val tool = CountingTool()
        val executor = ToolExecutor(setOf(tool))
        val args = buildJsonObject { put("msg", "x") }

        executor.execute(CountingTool.spec(), args)
        executor.clearCache()
        executor.execute(CountingTool.spec(), args)

        assertEquals(2, tool.callCount.get())
    }

    @Test
    fun `unknown tool name returns Failure`() = runTest {
        val executor = ToolExecutor(setOf(CountingTool()))
        val unknownSpec = ToolSpec(
            name = "does_not_exist",
            description = "",
            parameters = buildJsonObject { put("type", "object") }
        )
        val result = executor.execute(unknownSpec, buildJsonObject {})
        assertTrue("expected Failure", result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("未知工具"))
    }

    @Test
    fun `mermaid tool accepts valid flowchart code`() = runTest {
        val executor = ToolExecutor(setOf(MermaidTool()))
        val code = "flowchart TD\n  A --> B"
        val args = buildJsonObject { put("code", code) }
        val result = executor.execute(MermaidTool.spec, args)
        assertTrue("expected Success, got $result", result is ToolResult.Success)
        val content = (result as ToolResult.Success).content
        assertTrue(content.contains("flowchart"))
    }

    @Test
    fun `mermaid tool rejects code without chart type prefix`() = runTest {
        val executor = ToolExecutor(setOf(MermaidTool()))
        val args = buildJsonObject { put("code", "just some text") }
        val result = executor.execute(MermaidTool.spec, args)
        assertTrue("expected Failure", result is ToolResult.Failure)
    }

    @Test
    fun `mermaid tool rejects empty code`() = runTest {
        val executor = ToolExecutor(setOf(MermaidTool()))
        val args = buildJsonObject { put("code", "") }
        val result = executor.execute(MermaidTool.spec, args)
        assertTrue("expected Failure", result is ToolResult.Failure)
    }

    @Test
    fun `enum validation rejects out-of-range value`() = runTest {
        val tool = CountingTool("mode_tool")
        val spec = ToolSpec(
            name = "mode_tool",
            description = "",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("mode", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(listOf(JsonPrimitive("fast"), JsonPrimitive("slow"))))
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("mode"))))
            }
        )
        val executor = ToolExecutor(setOf(tool))
        val args = buildJsonObject { put("mode", "medium") }
        val result = executor.execute(spec, args)
        assertTrue("expected Failure", result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("mode"))
    }
}
