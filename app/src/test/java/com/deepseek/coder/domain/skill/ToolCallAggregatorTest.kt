package com.deepseek.coder.domain.skill

import com.deepseek.coder.domain.models.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolCallAggregator 单元测试（SPEC-Skill-v1.2 §3.2）。
 *
 * 覆盖：流式 delta 累积、单 tool_call 约束（v1.2 决策 9）、reset。
 */
class ToolCallAggregatorTest {

    @Test
    fun `empty aggregator builds nothing`() {
        val agg = ToolCallAggregator()
        assertFalse(agg.hasPending())
        assertTrue(agg.build().isEmpty())
    }

    @Test
    fun `single tool_call accumulates name and args across deltas`() {
        val agg = ToolCallAggregator()
        agg.append(index = 0, nameDelta = "render_", argsDelta = null)
        agg.append(index = 0, nameDelta = "mermaid", argsDelta = "{\"code\":")
        agg.append(index = 0, nameDelta = null, argsDelta = "\"flowchart TD\"}")

        assertTrue(agg.hasPending())
        val calls = agg.build()
        assertEquals(1, calls.size)
        assertEquals("render_mermaid", calls[0].name)
        assertEquals("{\"code\":\"flowchart TD\"}", calls[0].argumentsJson)
    }

    @Test
    fun `tool_call id is captured when provided`() {
        val agg = ToolCallAggregator()
        agg.append(index = 0, nameDelta = "render_mermaid", argsDelta = "{}", id = "call_abc123")
        val calls = agg.build()
        assertEquals("call_abc123", calls[0].id)
    }

    @Test
    fun `multiple tool_calls only first kept - decision 9 single call constraint`() {
        val agg = ToolCallAggregator()
        agg.append(index = 0, nameDelta = "tool_a", argsDelta = "{}")
        agg.append(index = 1, nameDelta = "tool_b", argsDelta = "{}")

        val calls = agg.build()
        assertEquals("only first tool_call should be kept", 1, calls.size)
        assertEquals("tool_a", calls[0].name)
    }

    @Test
    fun `reset clears accumulated state`() {
        val agg = ToolCallAggregator()
        agg.append(index = 0, nameDelta = "render_mermaid", argsDelta = "{}")
        assertTrue(agg.hasPending())

        agg.reset()
        assertFalse(agg.hasPending())
        assertTrue(agg.build().isEmpty())
    }

    @Test
    fun `fallback id generated when model omits id`() {
        val agg = ToolCallAggregator()
        agg.append(index = 0, nameDelta = "render_mermaid", argsDelta = "{}")
        val calls: List<ToolCall> = agg.build()
        assertTrue("fallback id should start with call_", calls[0].id.startsWith("call_"))
    }
}
