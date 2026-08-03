package com.deepseek.coder.data.workflow

import com.deepseek.coder.core.DispatcherProvider
import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.data.police.PoliceSchemas
import com.deepseek.coder.data.settings.AppSettings
import com.deepseek.coder.domain.models.ChatMessage
import com.deepseek.coder.domain.models.ChatRole
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Context governance:
 *   - Hard token budget (characters are used as proxy: budget = maxTokens * 4 average chars/token)
 *   - If budget exceeded:
 *       1. Keep newest ~12 messages intact
 *       2. Summarise older messages (only if older messages contain code blocks or large texts worth compressing)
 *       3. Never drop system message
 *
 * Characters are used as proxy for tokens to avoid pulling a tokenizer dependency.
 * Estimation bias is small (~±25%) and for governance purposes only; the API server does
 * final token-count validation anyway.
 */
@Singleton
class ContextGovernor @Inject constructor(
    private val dispatchers: DispatcherProvider
) {

    data class TrimReport(
        val originalCount: Int,
        val finalCount: Int,
        val summarised: Boolean
    )

    suspend fun trim(
        messages: List<ChatMessage>,
        maxTokens: Int
    ): Pair<List<ChatMessage>, TrimReport> = withContext(dispatchers.default) {
        val charBudget = max(2000, maxTokens * 4)
        val originalSize = messages.totalChars()
        if (originalSize <= charBudget) {
            return@withContext messages to TrimReport(originalSize, originalSize, summarised = false)
        }
        // Strategy: keep system (if present) + newest ~16 messages; prepend a single summary for anything older
        val system = messages.firstOrNull { it.role == ChatRole.SYSTEM }
        val nonSystem = messages.dropWhile { it.role == ChatRole.SYSTEM }
        val tailKeepCount = 16
        if (nonSystem.size <= tailKeepCount) {
            val out = (if (system != null) listOf(system) else emptyList()) + nonSystem
            return@withContext out to TrimReport(originalSize, out.totalChars(), summarised = false)
        }
        val tail = nonSystem.takeLast(tailKeepCount)
        val oldHead = nonSystem.dropLast(tailKeepCount)
        val summarised = buildSummary(oldHead)
        val summaryMessage = ChatMessage(
            role = ChatRole.SYSTEM,
            text = "【历史摘要，供上下文参考】\n$summarised"
        )
        val rebuilt = buildList {
            if (system != null) add(system)
            add(summaryMessage)
            addAll(tail)
        }
        val report = TrimReport(
            originalCount = originalSize,
            finalCount = rebuilt.totalChars(),
            summarised = true
        )
        AppLogger.d("ContextGovernor: chars $originalSize → ${report.finalCount} (budget $charBudget)")
        rebuilt to report
    }

    private fun buildSummary(old: List<ChatMessage>): String {
        // Cheap extractive summary: first 200 chars of each assistant / user message containing code or keywords
        val sb = StringBuilder()
        old.filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }.forEach { m ->
            val line = when {
                m.text.contains("```") -> {
                    val snippet = m.text.lineSequence()
                        .filter { it.startsWith("```") || it.contains("class ") || it.contains("fun ") }
                        .take(3)
                        .joinToString(" ; ")
                    "${m.role.name}: code snippet $snippet ..."
                }
                else -> "${m.role.name}: ${m.text.take(160)}"
            }
            sb.append("- ").append(line).append('\n')
        }
        return sb.toString().take(800).ifBlank { "(历史内容为空或可忽略)" }
    }

    private fun List<ChatMessage>.totalChars(): Int =
        sumOf { it.text.length + (it.reasoning?.length ?: 0) }

    /**
     * v2.1：按 GOVERN 专家的策略执行裁剪（决策与执行分离）。
     *
     * GOVERN 出策略（mode/keep/compress/drop），本方法执行具体裁剪：
     * - KEEP_ALL：原样返回
     * - COMPRESS：compress_message_ids 对应的消息取摘要前缀，drop 的直接删
     * - SUMMARIZE：把所有可压缩的合并成一条 summary 消息
     *
     * 消息 ID 约定：用索引 "m{index}" 标识（0-based，含 system）。
     * 不认识的 ID 一律视为 KEEP_ALL（保守，不丢信息）。
     */
    suspend fun trimByStrategy(
        messages: List<ChatMessage>,
        strategy: PoliceSchemas.ExpertResult
    ): Pair<List<ChatMessage>, TrimReport> = withContext(dispatchers.default) {
        val originalChars = messages.totalChars()
        val mode = strategy.governMode ?: PoliceSchemas.GovernMode.KEEP_ALL

        if (mode == PoliceSchemas.GovernMode.KEEP_ALL || messages.isEmpty()) {
            return@withContext messages to TrimReport(originalChars, originalChars, summarised = false)
        }

        // 给每条消息分配 ID（m0/m1/...）
        val indexed = messages.mapIndexed { i, m -> "m$i" to m }
        val keepIds = strategy.keepMessageIds.toSet()
        val compressIds = strategy.compressMessageIds.toSet()
        val dropIds = strategy.dropMessageIds.toSet()

        // 系统消息始终保留
        val system = messages.firstOrNull { it.role == ChatRole.SYSTEM }

        val result = buildList {
            indexed.forEach { (id, msg) ->
                when {
                    id in dropIds -> { /* 丢弃 */ }
                    id in compressIds -> {
                        // 压缩：取前 160 字符 + 省略号
                        val compressed = msg.copy(
                            text = msg.text.take(160) + if (msg.text.length > 160) "…(已压缩)" else ""
                        )
                        add(compressed)
                    }
                    id in keepIds -> add(msg)
                    msg.role == ChatRole.SYSTEM -> add(msg)  // 系统消息强制保留
                    else -> add(msg)  // 未知 ID 保守保留
                }
            }
        }

        // SUMMARIZE 模式：如果有 summary，替换所有非系统/非 keep 的消息
        val finalResult = if (mode == PoliceSchemas.GovernMode.SUMMARIZE && strategy.summary.isNotBlank()) {
            buildList {
                if (system != null) add(system)
                add(ChatMessage(role = ChatRole.SYSTEM, text = "【历史摘要】\n${strategy.summary.take(2000)}"))
                // 只保留 keepIds 对应的非系统消息
                result.forEach { m ->
                    if (m.role != ChatRole.SYSTEM && m !in listOf(system)) {
                        val idx = messages.indexOf(m)
                        if (idx >= 0 && "m$idx" in keepIds) add(m)
                    }
                }
            }
        } else {
            result
        }

        val finalChars = finalResult.totalChars()
        AppLogger.d("ContextGovernor: strategy ${mode.raw}, chars $originalChars → $finalChars")
        finalResult to TrimReport(originalChars, finalChars, summarised = mode == PoliceSchemas.GovernMode.SUMMARIZE)
    }
}
