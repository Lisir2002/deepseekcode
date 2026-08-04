package com.deepseek.coder.data

import com.deepseek.coder.data.ChatRepository.Companion.buildRequest
import com.deepseek.coder.data.remote.dto.ChatCompletionRequest
import com.deepseek.coder.data.settings.AppSettings
import com.deepseek.coder.domain.models.ChatMessage
import com.deepseek.coder.domain.models.ChatRole
import com.deepseek.coder.domain.models.ToolCall
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatRepository.buildRequest 单元测试 —— 基于 DeepSeek V4 官方文档（2026-08）。
 *
 * 覆盖认知纠正后的关键契约：
 *  - thinking 开关显式传 {"type":"enabled"|"disabled"}
 *  - 思考模式不传 temperature/top_p（官方：不支持，传了不生效）
 *  - reasoning_effort 仅思考模式生效，取值 high/max
 *  - 多轮拼接：未做工具调用的 assistant 消息 reasoning_content 不传（省 token）
 *  - 做了工具调用的 assistant 消息 reasoning_content 需传
 */
class ChatRepositoryRequestTest {

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    private fun baseSettings(
        thinkingEnabled: Boolean = true,
        effort: AppSettings.ReasoningEffort = AppSettings.ReasoningEffort.HIGH,
        temperature: Float = 0.2f,
        topP: Float = 0.95f
    ): AppSettings = AppSettings(
        thinkingEnabled = thinkingEnabled,
        reasoningEffort = effort,
        temperature = temperature,
        topP = topP,
        systemPrompt = "" // 避免注入 system 消息干扰断言
    )

    private fun encode(req: ChatCompletionRequest): String = json.encodeToString(req)

    @Test
    fun `thinking enabled with HIGH effort sets thinking=enabled and reasoning_effort=high`() {
        val req = buildRequest(
            messages = listOf(ChatMessage(role = ChatRole.USER, text = "hi")),
            settings = baseSettings(thinkingEnabled = true, effort = AppSettings.ReasoningEffort.HIGH),
            stream = false
        )
        val out = encode(req)
        assertTrue("应含 thinking.type=enabled", "\"thinking\":{\"type\":\"enabled\"}" in out)
        assertTrue("应含 reasoning_effort=high", "\"reasoning_effort\":\"high\"" in out)
        assertFalse("思考模式不应传 temperature", "\"temperature\"" in out)
        assertFalse("思考模式不应传 top_p", "\"top_p\"" in out)
    }

    @Test
    fun `thinking enabled with MAX effort sets reasoning_effort=max`() {
        val req = buildRequest(
            messages = listOf(ChatMessage(role = ChatRole.USER, text = "hi")),
            settings = baseSettings(thinkingEnabled = true, effort = AppSettings.ReasoningEffort.MAX),
            stream = false
        )
        val out = encode(req)
        assertTrue("\"reasoning_effort\":\"max\"" in out)
        assertTrue("\"thinking\":{\"type\":\"enabled\"}" in out)
    }

    @Test
    fun `thinking disabled sets thinking=disabled and no reasoning_effort and includes sampling params`() {
        val req = buildRequest(
            messages = listOf(ChatMessage(role = ChatRole.USER, text = "hi")),
            settings = baseSettings(thinkingEnabled = false, effort = AppSettings.ReasoningEffort.HIGH),
            stream = false
        )
        val out = encode(req)
        assertTrue("应显式传 thinking=disabled", "\"thinking\":{\"type\":\"disabled\"}" in out)
        assertFalse("非思考模式不应传 reasoning_effort", "\"reasoning_effort\"" in out)
        assertTrue("非思考模式应传 temperature", "\"temperature\":0.2" in out)
        assertTrue("非思考模式应传 top_p", "\"top_p\":0.95" in out)
        assertNull(req.reasoningEffort)
    }

    @Test
    fun `thinking enabled but effort DISABLED omits reasoning_effort`() {
        val req = buildRequest(
            messages = listOf(ChatMessage(role = ChatRole.USER, text = "hi")),
            settings = baseSettings(thinkingEnabled = true, effort = AppSettings.ReasoningEffort.DISABLED),
            stream = false
        )
        val out = encode(req)
        assertTrue("thinking 仍应开启", "\"thinking\":{\"type\":\"enabled\"}" in out)
        assertFalse("effort=DISABLED 时不应传 reasoning_effort", "\"reasoning_effort\"" in out)
    }

    @Test
    fun `assistant without tool_calls omits reasoning_content to save tokens`() {
        val history = listOf(
            ChatMessage(role = ChatRole.USER, text = "q"),
            ChatMessage(
                role = ChatRole.ASSISTANT,
                text = "a",
                reasoning = "should be dropped",
                toolCalls = emptyList()
            ),
            ChatMessage(role = ChatRole.USER, text = "q2")
        )
        val req = buildRequest(history, baseSettings(), stream = false)
        val out = encode(req)
        assertFalse(
            "未做工具调用的 assistant 消息不应回传 reasoning_content",
            "should be dropped" in out
        )
    }

    @Test
    fun `assistant with tool_calls keeps reasoning_content for multi-turn context`() {
        val history = listOf(
            ChatMessage(role = ChatRole.USER, text = "q"),
            ChatMessage(
                role = ChatRole.ASSISTANT,
                text = "",
                reasoning = "need to call tool",
                toolCalls = listOf(ToolCall(id = "call_1", name = "render_mermaid", argumentsJson = "{}"))
            )
        )
        val req = buildRequest(history, baseSettings(), stream = false)
        val out = encode(req)
        assertTrue(
            "做了工具调用的 assistant 消息应回传 reasoning_content",
            "need to call tool" in out
        )
    }

    @Test
    fun `request never includes frequency_penalty or presence_penalty`() {
        val req = buildRequest(
            messages = listOf(ChatMessage(role = ChatRole.USER, text = "hi")),
            settings = baseSettings(),
            stream = false
        )
        val out = encode(req)
        assertFalse("不应含 frequency_penalty（官方已不支持）", "frequency_penalty" in out)
        assertFalse("不应含 presence_penalty（官方已不支持）", "presence_penalty" in out)
    }

    @Test
    fun `request never includes thinking_budget`() {
        val req = buildRequest(
            messages = listOf(ChatMessage(role = ChatRole.USER, text = "hi")),
            settings = baseSettings(),
            stream = false
        )
        val out = encode(req)
        assertFalse("不应含 thinking_budget（旧参数已废弃）", "thinking_budget" in out)
    }

    @Test
    fun `model id is V4 not legacy chat_reasoner_coder`() {
        val req = buildRequest(
            messages = listOf(ChatMessage(role = ChatRole.USER, text = "hi")),
            settings = baseSettings(),
            stream = false
        )
        assertEquals("deepseek-v4-flash", req.model)
    }
}
