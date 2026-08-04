package com.deepseek.coder.data.db

import com.deepseek.coder.data.db.entity.ChatMessageEntity
import com.deepseek.coder.data.db.entity.ChatSessionEntity
import com.deepseek.coder.domain.models.ChatMessage
import com.deepseek.coder.domain.models.ChatRole
import com.deepseek.coder.domain.models.ChatSession
import com.deepseek.coder.domain.models.ToolCall
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val mapperJson = Json { ignoreUnknownKeys = true; isLenient = true }

// ---- Session mappers ----
fun ChatSessionEntity.toDomain(): ChatSession =
    ChatSession(
        id = id,
        title = title,
        systemPrompt = systemPrompt,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        messageCount = messageCount,
        cumulativeTokens = cumulativeTokens,
        currentSkillId = currentSkillId
    )

fun ChatSession.toEntity(): ChatSessionEntity =
    ChatSessionEntity(
        id = id,
        title = title,
        systemPrompt = systemPrompt,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        messageCount = messageCount,
        cumulativeTokens = cumulativeTokens,
        currentSkillId = currentSkillId
    )

// ---- Message mappers ----
fun ChatMessageEntity.toDomain(): ChatMessage {
    val toolCalls: List<ToolCall> = toolCallsJson?.takeIf { it.isNotBlank() }?.let { raw ->
        runCatching { mapperJson.decodeFromString<List<ToolCall>>(raw) }
            .getOrNull().orEmpty()
    } ?: emptyList()
    return ChatMessage(
        id = id,
        role = ChatRole.of(role),
        text = text,
        reasoning = reasoning,
        toolCalls = toolCalls,
        toolCallId = toolCallId,
        timestampMs = timestampMs,
        pending = pending,
        skillId = skillId
    )
}

fun ChatMessage.toEntity(sessionId: String, sortOrder: Int): ChatMessageEntity {
    val toolCallsJson = if (toolCalls.isEmpty()) null else {
        runCatching { mapperJson.encodeToString(toolCalls) }.getOrNull()
    }
    return ChatMessageEntity(
        id = if (this.id == 0L) 0L else this.id,
        sessionId = sessionId,
        role = this.role.value,
        text = this.text,
        reasoning = this.reasoning,
        toolCallsJson = toolCallsJson,
        toolCallId = this.toolCallId,
        timestampMs = this.timestampMs,
        pending = this.pending,
        sortOrder = sortOrder,
        skillId = this.skillId
    )
}
