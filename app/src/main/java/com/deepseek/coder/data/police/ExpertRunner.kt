package com.deepseek.coder.data.police

import com.deepseek.coder.core.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Police Layer v2.0 — 专家统一执行器
 *
 * 设计依据：SPEC-Police-v1.0.md (内容为 v2.0) §4
 *  - 12 个专家共享统一输出 Schema（PoliceSchemas.ExpertDto），只是 system prompt 不同
 *  - 通过 ExpertId 路由到对应 prompt（PolicePrompts.expertPrompt）
 *  - 失败时返回 fallback ExpertResult（不阻塞流程）
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

    /** 调用 CHECK 专家（专用，因输入需要执行结果 + attempts + error_type）。 */
    suspend fun runCheck(
        runId: String,
        assistantOutput: String,
        errorHint: String
    ): PoliceSchemas.ExpertResult {
        val enriched = buildString {
            appendLine("待自检的助理输出：")
            appendLine(assistantOutput.take(4000))
            appendLine()
            appendLine("外部错误提示（编译/测试输出，可能为空）：")
            appendLine(errorHint.ifBlank { "(无)" })
        }
        val result = run(runId, PoliceSchemas.ExpertId.CHECK, enriched)
        return applyCheckHardRules(runId, result)
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
    // L1 硬规则：CHECK 专家决策矩阵强制覆盖
    // ------------------------------------------------------------------

    private fun applyCheckHardRules(runId: String, result: PoliceSchemas.ExpertResult): PoliceSchemas.ExpertResult {
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

        return result.copy(decision = afterDedup.raw)
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
