package com.deepseek.coder.data.police

import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.core.Outcome
import com.deepseek.coder.data.ChatRepository
import com.deepseek.coder.data.remote.dto.ChatCompletionRequest
import com.deepseek.coder.data.remote.dto.ChatMessageDto
import com.deepseek.coder.data.remote.dto.ResponseFormatDto
import com.deepseek.coder.data.settings.AppSettings
import com.deepseek.coder.data.settings.SettingsRepository
import com.deepseek.coder.domain.models.ChatMessage
import com.deepseek.coder.domain.models.ChatRole
import kotlinx.serialization.KSerializer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Police Layer v2.1 — 警察/专家统一调用客户端
 *
 * 设计依据：SPEC-Police-v2.1.md §1.3 / §2.1 / §6
 *  - 全部用 deepseek-v4-flash 非思考模式（决策任务不需要思考模式）
 *  - response_format = json_object
 *  - temperature = 0.05（决策任务低温稳定）
 *  - JSON 三层 repair（L1 直接 parse → L2 抽 {...} → L3 失败重试一次）
 *  - 支持单 stage / two-stage 调用
 *  - v2.1 新增 callVerify（LLM 二次验证，给 CHECK 提供真实 error_type）
 *
 * 不直接生成代码（只决策），代码生成仍走 Actor（ChatRepository.sendChat 流式）。
 */
@Singleton
class PoliceClient @Inject constructor(
    private val chatRepo: ChatRepository,
    private val settingsRepo: SettingsRepository
) {

    /**
     * 单次 JSON-mode 调用 + 三层 repair 解析。
     *
     * @param systemPrompt 角色 system prompt
     * @param userPrompt   用户输入（含 control token + 状态注入）
     * @param serializer   输出 DTO 的反序列化器
     * @param maxRetries   L3 失败时重试次数（默认 1 次）
     * @return 解析后的 DTO，三层 repair + 重试全失败时返回 null
     */
    suspend fun <T> callJson(
        systemPrompt: String,
        userPrompt: String,
        serializer: KSerializer<T>,
        maxRetries: Int = 1
    ): T? {
        var lastRaw: String? = null
        repeat(maxRetries + 1) { attempt ->
            val raw = callRaw(systemPrompt, userPrompt) ?: return@repeat
            lastRaw = raw
            // L1 + L2 repair
            val parsed = PoliceSchemas.repairParse(raw, serializer)
            if (parsed != null) {
                return parsed
            }
            AppLogger.w(message = "PoliceClient: repair failed (attempt ${attempt + 1}), raw=${raw.take(200)}")
        }
        // L3 全失败
        AppLogger.e(message = "PoliceClient: all repair attempts failed. lastRaw=${lastRaw?.take(200)}")
        return null
    }

    /**
     * Two-stage 调用：Stage 1 输出短 JSON，Stage 2 把 stage 1 结果作为变量拼入再生成完整输出。
     *
     * Stage 1 失败时不调 Stage 2（降级为单 stage 重试由调用方决定）。
     *
     * @param s1System    Stage 1 system prompt
     * @param s1User      Stage 1 user prompt
     * @param s1Serializer Stage 1 输出反序列化器
     * @param s2System    Stage 2 system prompt
     * @param s2UserBuilder 把 stage 1 结果转成 stage 2 user prompt 的函数
     * @param s2Serializer Stage 2 输出反序列化器
     * @return Stage 2 解析结果，stage 1 或 stage 2 失败时返回 null
     */
    suspend fun <A, B> callTwoStage(
        s1System: String,
        s1User: String,
        s1Serializer: KSerializer<A>,
        s2System: String,
        s2UserBuilder: (A) -> String,
        s2Serializer: KSerializer<B>
    ): Pair<A?, B?> {
        val s1 = callJson(s1System, s1User, s1Serializer) ?: return null to null
        val s2User = runCatching { s2UserBuilder(s1) }.getOrElse {
            AppLogger.w(message = "PoliceClient: stage2 user builder failed: ${it.message}")
            return s1 to null
        }
        val s2 = callJson(s2System, s2User, s2Serializer)
        return s1 to s2
    }

    /**
     * v2.1 LLM 二次验证（给 CHECK 提供真实 error_type）。
     *
     * 扮演编译器/测试者审查助理输出，返回 error_type/error_reason/confidence_bucket。
     * 失败时返回 null（CHECK 将退化为 v2.0 的肉眼判断模式）。
     */
    suspend fun callVerify(assistantOutput: String): PoliceSchemas.LlmVerifyResult? {
        val userPrompt = buildString {
            appendLine("待验证的助理输出：")
            appendLine(assistantOutput.take(4000))
            appendLine()
            appendLine("请审查以上代码，判断 error_type。")
        }
        val dto = callJson(
            systemPrompt = PolicePrompts.LLM_VERIFY,
            userPrompt = userPrompt,
            serializer = PoliceSchemas.LlmVerifyDto.serializer()
        ) ?: return null
        return PoliceSchemas.buildLlmVerifyResult(dto)
    }

    /** 裸调用（不带 repair，返回原始字符串），供 callJson 内部使用。 */
    private suspend fun callRaw(systemPrompt: String, userPrompt: String): String? {
        val settings = settingsRepo.current()
        // 强制 v4-flash 非思考模式，低温稳定
        val policeSettings = settings.copy(
            model = AppSettings.DeepSeekModel.V4_FLASH,
            temperature = 0.05f,
            reasoningEffort = AppSettings.ReasoningEffort.DISABLED,
            thinkingEnabled = false,
            maxTokens = 1024.coerceAtMost(settings.maxTokens),  // 决策任务不需要长输出
            systemPrompt = systemPrompt
        )
        val msgs = listOf(
            ChatMessage(role = ChatRole.SYSTEM, text = systemPrompt),
            ChatMessage(role = ChatRole.USER, text = userPrompt.take(8000))
        )
        val baseReq = ChatRepository.buildRequest(msgs, policeSettings, stream = false)
        val jsonReq = ChatCompletionRequest(
            model = baseReq.model,
            messages = baseReq.messages.map { ChatMessageDto(role = it.role, content = it.content) },
            temperature = baseReq.temperature,
            top_p = baseReq.top_p,
            max_tokens = baseReq.max_tokens,
            stream = false,
            responseFormat = ResponseFormatDto(type = "json_object"),
            streamOptions = null,
            reasoningEffort = baseReq.reasoningEffort,
            thinkingBudget = baseReq.thinkingBudget
        )
        return when (val o = chatRepo.sendChatBlockingJsonOverride(jsonReq)) {
            is Outcome.Success -> o.value.first.text.takeIf { it.isNotBlank() }
            is Outcome.Failure -> {
                AppLogger.w(message = "PoliceClient: API call failed: ${o.error.message}")
                null
            }
        }
    }
}
