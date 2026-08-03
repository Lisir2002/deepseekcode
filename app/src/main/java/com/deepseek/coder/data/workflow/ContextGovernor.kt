package com.deepseek.coder.data.workflow

import com.deepseek.coder.core.DispatcherProvider
import com.deepseek.coder.core.AppLogger
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
}
