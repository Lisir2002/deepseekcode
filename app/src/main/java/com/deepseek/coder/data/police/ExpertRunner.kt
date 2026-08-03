package com.deepseek.coder.data.police

import com.deepseek.coder.core.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Police Layer v2.1 — 专家统一执行器
 *
 * 设计依据：SPEC-Police-v2.1.md §4
 *  - 12 个专家共享统一输出 Schema（PoliceSchemas.ExpertDto），只是 system prompt 不同
 *  - 通过 ExpertId 路由到对应 prompt（PolicePrompts.expertPrompt）
 *  - 失败时返回 fallback ExpertResult（不阻塞流程）
 *  - v2.1：runCheck 接入 LLM 二次验证，激活完整决策矩阵
 *  - v2.1：fallback 填充 GEN 决策字段（techStack/constraints/...）
 */
@Singleton
class ExpertRunner @Inject constructor(
    private val client: PoliceClient,
    private val tracker: EscalationTracker
) {

    /**
     * 调用单个专家。
     *
     * @param runId       用于状态注入 + attempt 计数
     * @param expertId    要调用的专家 ID
     * @param userPrompt  用户输入（含上下文 + 状态注入）
     * @return L1 校验后的 ExpertResult，调用失败返回 fallback
     */
    suspend fun run(
        runId: String,
        expertId: PoliceSchemas.ExpertId,
        userPrompt: String
    ): PoliceSchemas.ExpertResult {
        val system = PolicePrompts.expertPrompt(expertId)
        // 状态注入（防止多轮状态丢失）
        val stateBlock = tracker.snapshotForPrompt(runId)
        val fullUser = if (stateBlock.isNotBlank()) "$stateBlock\n\n$userPrompt" else userPrompt

        val serializer = PoliceSchemas.ExpertDto.serializer()
        val dto: PoliceSchemas.ExpertDto? = client.callJson(system, fullUser, serializer)

        return if (dto != null) {
            PoliceSchemas.buildExpertResult(dto, expectedId = expertId)
        } else {
            AppLogger.w(message = "ExpertRunner: $expertId call failed, returning fallback")
            fallback(expertId)
        }
    }

    /** 调用 CLARIFY 专家（专用，因输入需要路由警察的 need_clarify_reason）。 */
    suspend fun runClarify(
        runId: String,
        userPrompt: String,
        clarifyReason: String
    ): PoliceSchemas.ExpertResult {
        val enriched = buildString {
            appendLine("用户原始消息：")
            appendLine(userPrompt.take(2000))
            appendLine()
            appendLine("路由警察指出的信息缺口：")
            appendLine(clarifyReason.ifBlank { "(未明确)" })
        }
        return run(runId, PoliceSchemas.ExpertId.CLARIFY, enriched)
    }

    /**
     * 调用 CHECK 专家（专用，因输入需要执行结果 + attempts + error_type）。
     *
     * v2.1：先调 [PoliceClient.callVerify] 获取 LLM 二次验证的 error_type，
     * 作为 errorHint 传给 CHECK 专家，激活完整决策矩阵（test_failure/timeout/logic_error 等）。
     * 验证失败时退化为 v2.0 的肉眼判断模式（errorHint 为空）。
     */
    suspend fun runCheck(
        runId: String,
        assistantOutput: String,
        errorHint: String
    ): PoliceSchemas.ExpertResult {
        // v2.1: LLM 二次验证（扮编译器/测试者）
        val verifyResult = client.callVerify(assistantOutput)
        val mergedErrorHint = buildString {
            if (errorHint.isNotBlank()) {
                appendLine("外部错误提示：")
                appendLine(errorHint)
            }
            if (verifyResult != null) {
                appendLine("LLM 二次验证结果：")
                appendLine("- error_type: ${verifyResult.errorType.raw}")
                appendLine("- error_reason: ${verifyResult.errorReason}")
                appendLine("- confidence_bucket: ${verifyResult.confidenceBucket.raw}")
            }
        }

        val enriched = buildString {
            appendLine("待自检的助理输出：")
            appendLine(assistantOutput.take(4000))
            appendLine()
            appendLine("外部错误提示 + LLM 二次验证（编译/测试输出，可能为空）：")
            appendLine(mergedErrorHint.ifBlank { "(无)" })
        }
        var result = run(runId, PoliceSchemas.ExpertId.CHECK, enriched)

        // v2.1: 若 CHECK 未给 error_type 但 LLM 验证给了，用 LLM 的覆盖
        if (verifyResult != null && result.errorType == null) {
            result = result.copy(
                errorType = verifyResult.errorType,
                errorReason = if (result.errorReason.isBlank()) verifyResult.errorReason else result.errorReason
            )
        }
        return applyCheckHardRules(runId, result, verifyResult)
    }

    /** 调用 GOVERN 专家（专用，因输入需要完整历史 + token 预算）。 */
    suspend fun runGovern(
        runId: String,
        historySummary: String,
        tokenBudget: Int
    ): PoliceSchemas.ExpertResult {
        val enriched = buildString {
            appendLine("历史消息摘要：")
            appendLine(historySummary.take(6000))
            appendLine()
            appendLine("token 预算：$tokenBudget")
        }
        return run(runId, PoliceSchemas.ExpertId.GOVERN, enriched)
    }

    // ------------------------------------------------------------------
    // L1 硬规则：CHECK 专家决策矩阵强制覆盖（v2.1 含 confidence_bucket=low）
    // ------------------------------------------------------------------

    private fun applyCheckHardRules(
        runId: String,
        result: PoliceSchemas.ExpertResult,
        verifyResult: PoliceSchemas.LlmVerifyResult? = null
    ): PoliceSchemas.ExpertResult {
        val state = tracker.getOrCreate(runId)
        val decision = PoliceSchemas.CheckDecision.coerce(result.decision)
        val errorType = result.errorType ?: PoliceSchemas.ErrorType.NONE

        // L1: passed=true 但 decision≠DONE → 强制 DONE
        val effectiveDecision = if (result.passed == true && decision != PoliceSchemas.CheckDecision.DONE) {
            PoliceSchemas.CheckDecision.DONE
        } else {
            decision
        }

        // L1: attempts >= 3 强制 BLOCKED
        val afterAttempts = if (state.attempts >= EscalationTracker.MAX_ATTEMPTS &&
            effectiveDecision != PoliceSchemas.CheckDecision.DONE
        ) {
            PoliceSchemas.CheckDecision.BLOCKED
        } else effectiveDecision

        // L1: RETRY 但 error_type=test_failure/timeout/logic_error → 强制 REWORK
        val afterMatrix = if (afterAttempts == PoliceSchemas.CheckDecision.RETRY) {
            when (errorType) {
                PoliceSchemas.ErrorType.TEST_FAILURE,
                PoliceSchemas.ErrorType.TIMEOUT,
                PoliceSchemas.ErrorType.LOGIC_ERROR -> PoliceSchemas.CheckDecision.REWORK
                else -> afterAttempts
            }
        } else afterAttempts

        // L1: attempted_approaches 去重 → 强制 REWORK 或 BLOCKED
        val afterDedup = if (afterMatrix == PoliceSchemas.CheckDecision.RETRY &&
            tracker.isDuplicateApproach(runId, result.patchPromptSuffix)
        ) {
            AppLogger.w(message = "ExpertRunner: duplicate approach detected, forcing REWORK")
            if (state.attempts >= EscalationTracker.MAX_ATTEMPTS - 1) {
                PoliceSchemas.CheckDecision.BLOCKED
            } else {
                PoliceSchemas.CheckDecision.REWORK
            }
        } else afterMatrix

        // v2.1 L1: confidence_bucket=low 且 attempts>=2 → 强制 BLOCKED
        val afterConfidence = if (verifyResult != null &&
            verifyResult.confidenceBucket == PoliceSchemas.ConfidenceBucket.LOW &&
            state.attempts >= 2 &&
            afterDedup != PoliceSchemas.CheckDecision.DONE
        ) {
            AppLogger.w(message = "ExpertRunner: low confidence + attempts>=2, forcing BLOCKED")
            PoliceSchemas.CheckDecision.BLOCKED
        } else afterDedup

        return result.copy(decision = afterConfidence.raw)
    }

    // ------------------------------------------------------------------
    // Fallback（L3 失败兜底，不阻塞流程）
    // ------------------------------------------------------------------

    private fun fallback(id: PoliceSchemas.ExpertId): PoliceSchemas.ExpertResult = when (id) {
        PoliceSchemas.ExpertId.CHECK -> PoliceSchemas.ExpertResult(
            expertId = id,
            decision = PoliceSchemas.CheckDecision.DONE.raw,  // 自检失败默认通过，避免阻塞
            capabilityPrompt = "",
            outputFormatHint = "",
            dependsOn = emptyList(),
            feedbackToLead = "",
            techStack = emptyList(),
            constraints = emptyList(),
            acceptanceCriteria = emptyList(),
            risks = emptyList(),
            clarifyQuestions = emptyList(),
            canProceedWithout = true,
            proceedRisk = "",
            governMode = null,
            keepMessageIds = emptyList(),
            compressMessageIds = emptyList(),
            dropMessageIds = emptyList(),
            summary = "",
            estimatedTokensAfter = null,
            passed = true,
            errorType = PoliceSchemas.ErrorType.NONE,
            errorReason = "",
            patchPromptSuffix = "",
            escalationReason = "",
            attemptedApproachesAppend = ""
        )
        PoliceSchemas.ExpertId.CLARIFY -> PoliceSchemas.ExpertResult(
            expertId = id,
            decision = "ask_clarification",
            capabilityPrompt = "",
            outputFormatHint = "",
            dependsOn = emptyList(),
            feedbackToLead = "",
            techStack = emptyList(),
            constraints = emptyList(),
            acceptanceCriteria = emptyList(),
            risks = emptyList(),
            clarifyQuestions = listOf(
                PoliceSchemas.ClarifyQuestion(
                    id = "q1",
                    question = "请补充更多细节，以便我更好地帮你",
                    defaultHint = "",
                    canSkip = true
                )
            ),
            canProceedWithout = true,
            proceedRisk = "",
            governMode = null,
            keepMessageIds = emptyList(),
            compressMessageIds = emptyList(),
            dropMessageIds = emptyList(),
            summary = "",
            estimatedTokensAfter = null,
            passed = null,
            errorType = null,
            errorReason = "",
            patchPromptSuffix = "",
            escalationReason = "",
            attemptedApproachesAppend = ""
        )
        else -> PoliceSchemas.ExpertResult(
            expertId = id,
            decision = "",
            capabilityPrompt = "（专家调用失败，按默认方式执行）",
            outputFormatHint = "",
            dependsOn = emptyList(),
            feedbackToLead = "",
            techStack = emptyList(),
            constraints = emptyList(),
            acceptanceCriteria = emptyList(),
            risks = emptyList(),
            clarifyQuestions = emptyList(),
            canProceedWithout = true,
            proceedRisk = "",
            governMode = null,
            keepMessageIds = emptyList(),
            compressMessageIds = emptyList(),
            dropMessageIds = emptyList(),
            summary = "",
            estimatedTokensAfter = null,
            passed = null,
            errorType = null,
            errorReason = "",
            patchPromptSuffix = "",
            escalationReason = "",
            attemptedApproachesAppend = ""
        )
    }
}
