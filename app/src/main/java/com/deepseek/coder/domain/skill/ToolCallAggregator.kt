package com.deepseek.coder.domain.skill

import com.deepseek.coder.domain.models.ToolCall

/**
 * ToolCall 流式聚合器（SPEC-Skill-v1.2 §3.2）。
 *
 * 收到 [com.deepseek.coder.domain.models.ChatStreamEvent.ToolCallDelta] 时累积，
 * finish_reason=tool_calls 时落盘为完整 [ToolCall]。
 *
 * v1.2 决策 9：v1.0 只支持单 tool_call/assistant 消息——若模型返回多个 tool_call，
 * 只取第一个（index 最小），其余忽略并在日志记录。
 */
class ToolCallAggregator {

    private val nameParts = LinkedHashMap<Int, StringBuilder>()
    private val argsParts = LinkedHashMap<Int, StringBuilder>()
    private val ids = LinkedHashMap<Int, String>()

    /** 累积一个 delta 分片。 */
    fun append(index: Int, nameDelta: String?, argsDelta: String?, id: String? = null) {
        if (nameDelta != null) {
            nameParts.getOrPut(index) { StringBuilder() }.append(nameDelta)
        }
        if (argsDelta != null) {
            argsParts.getOrPut(index) { StringBuilder() }.append(argsDelta)
        }
        if (id != null) {
            ids[index] = id
        }
    }

    /** 是否有累积中的 tool_call。 */
    fun hasPending(): Boolean = nameParts.isNotEmpty() || argsParts.isNotEmpty()

    /**
     * 聚合为完整 ToolCall 列表（finish_reason=tool_calls 时调用）。
     *
     * v1.2 决策 9：只返回首个（index 最小），多 call 被丢弃。
     */
    fun build(): List<ToolCall> {
        if (nameParts.isEmpty() && argsParts.isEmpty()) return emptyList()
        val indices = (nameParts.keys + argsParts.keys + ids.keys).sorted()
        val all = indices.mapNotNull { idx ->
            val name = nameParts[idx]?.toString().orEmpty()
            val args = argsParts[idx]?.toString().orEmpty()
            if (name.isBlank() && args.isBlank()) null
            else ToolCall(
                id = ids[idx] ?: "call_${idx}_${System.currentTimeMillis()}",
                name = name,
                argumentsJson = args
            )
        }
        // v1.2 决策 9：单 tool_call 约束
        if (all.size > 1) {
            com.deepseek.coder.core.AppLogger.w(
                null,
                "ToolCallAggregator: model returned %d tool_calls, only first kept (v1.0 single-call constraint)",
                all.size
            )
        }
        return all.take(1)
    }

    /** 重置（开始新一轮聚合）。 */
    fun reset() {
        nameParts.clear()
        argsParts.clear()
        ids.clear()
    }
}
