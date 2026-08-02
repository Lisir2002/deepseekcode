package com.deepseek.coder.domain.models

/**
 * Domain (screen-facing) models.
 * NOT the same as database Entity or remote DTO; mappers convert between layers.
 */

enum class ChatRole(val value: String) {
    SYSTEM("system"), USER("user"), ASSISTANT("assistant"), TOOL("tool");
    companion object { fun of(v: String?) = entries.firstOrNull { it.value == v } ?: USER }
}

data class ChatMessage(
    val id: Long = 0L,
    val role: ChatRole,
    val text: String,
    val reasoning: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val pending: Boolean = false
)

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class ChatSession(
    val id: String,
    val title: String,
    val systemPrompt: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
    val messageCount: Int = 0,
    val cumulativeTokens: Long = 0L
)

data class UsageSnapshot(
    val promptTokens: Int,
    val completionTokens: Int,
    val reasoningTokens: Int,
    val totalTokens: Int
)

sealed class ChatStreamEvent {
    data object Start : ChatStreamEvent()
    data class ReasoningDelta(val delta: String) : ChatStreamEvent()
    data class TextDelta(val delta: String) : ChatStreamEvent()
    data class ToolCallDelta(val index: Int, val nameDelta: String?, val argsDelta: String?) : ChatStreamEvent()
    data class Finish(val reason: String, val usage: UsageSnapshot?) : ChatStreamEvent()
    data class Failure(val error: com.deepseek.coder.core.AppError) : ChatStreamEvent()
}
