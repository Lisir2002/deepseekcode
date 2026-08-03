package com.deepseek.coder.data.police

import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.domain.models.ChatMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Police Layer v2.1 — 路由警察（Dispatcher）
 *
 * 设计依据：SPEC-Police-v2.1.md §3 / §5 / §6 / §7
 *  - 分析用户问题 → 动态组队 → 指定组长
 *  - two-stage 默认开（Stage 1 意图/难度/范围/澄清/拒答 → Stage 2 组队）
 *  - L1 guard rail 前置（高危词硬拦截 + 软磨硬泡维持拒答）
 *  - GENERAL_CHAT 不组队（直接输出 refuse_hint）
 *  - NEEDS_CLARIFICATION 强制选 CLARIFY 专家
 *  - v2.1 新增：redispatch（组长升级时重组队，从 resume_from_step 恢复）
 */
@Singleton
class DispatcherPolice @Inject constructor(
    private val client: PoliceClient,
    private val tracker: EscalationTracker
) {

    /**
     * 路由分发（two-stage）。
     *
     * @param runId    用于状态注入
     * @param userMessage 用户消息
     * @param history  历史（用于软磨硬泡检测 + 上下文摘要）
     * @return L1 校验后的 DispatcherResult
     */
    suspend fun dispatch(
        runId: String,
        userMessage: ChatMessage,
        history: List<ChatMessage>
    ): PoliceSchemas.DispatcherResult {

        // ---- L1 前置：高危词硬拦截 ----
        if (GuardRails.hitHardBlock(userMessage.text)) {
            AppLogger.w(message = "Dispatcher: hard block triggered")
            return hardRefuseResult()
        }

        // ---- L1 前置：软磨硬泡检测（历史里已有拒答记录 → 维持 GENERAL_CHAT）----
        val priorRefusal = GuardRails.hasPriorRefusal(history)

        // ---- 构造 user prompt（含历史摘要 + control token）----
        val historySummary = buildHistorySummary(history)
        val userBlock = buildString {
            if (priorRefusal) {
                appendLine("【注意】用户历史中已有过 GENERAL_CHAT 拒答记录，若本次消息仍是同类非编程请求，维持 GENERAL_CHAT，不要妥协。")
                appendLine()
            }
            if (historySummary.isNotBlank()) {
                appendLine("【历史摘要】")
                appendLine(historySummary)
                appendLine()
            }
            appendLine("【当前用户消息】")
            appendLine(userMessage.text.take(4000))
        }

        // ---- Two-stage 调用 ----
        val (s1, s2) = client.callTwoStage(
            s1System = PolicePrompts.DISPATCHER_STAGE1,
            s1User = userBlock,
            s1Serializer = PoliceSchemas.DispatcherStage1Dto.serializer(),
            s2System = PolicePrompts.DISPATCHER_STAGE2,
            s2UserBuilder = { stage1 ->
                buildString {
                    appendLine("Stage 1 决策：")
                    appendLine("- intent: ${stage1.intent ?: "CODE_GENERATE"}")
                    appendLine("- cap: ${stage1.cap ?: "medium"}")
                    appendLine("- scope_tag: ${stage1.scope_tag ?: "GENERAL"}")
                    appendLine("- need_clarify: ${stage1.need_clarify ?: false}")
                    appendLine()
                    appendLine("【当前用户消息】")
                    appendLine(userMessage.text.take(4000))
                }
            },
            s2Serializer = PoliceSchemas.DispatcherStage2Dto.serializer()
        )

        // ---- 合并 + L1 校验 ----
        val result = if (s1 != null) {
            // 软磨硬泡强制覆盖：priorRefusal 时强制 GENERAL_CHAT
            val effectiveS1 = if (priorRefusal) {
                s1.copy(intent = PoliceSchemas.Intent.GENERAL_CHAT.raw, refuse_hint = s1.refuse_hint ?: defaultRefuseHint())
            } else s1
            PoliceSchemas.buildDispatcherResult(
                effectiveS1,
                s2,
                fallbackTeam = listOf(PoliceSchemas.ExpertId.GEN, PoliceSchemas.ExpertId.CHECK)
            )
        } else {
            // 全失败 fallback
            AppLogger.w(message = "Dispatcher: both stages failed, using fallback")
            fallbackDispatch(userMessage.text)
        }

        // 记录组队
        tracker.recordTeamRound(
            runId = runId,
            team = result.expertTeam.map { it.raw },
            outcome = "dispatched: intent=${result.intent.raw}, cap=${result.cap.raw}"
        )

        return result
    }

    /**
     * v2.1：路由警察重组队（组长升级时调用）。
     *
     * 当组长 shouldEscalate=true 且 escalation_count < MAX_ESCALATIONS 时调用。
     * 重新组队并指定从哪步恢复（resume_from_step）。
     *
     * L1 硬规则：escalation_count >= MAX_ESCALATIONS 时不再重组队，返回 BLOCKED 标记。
     *
     * @param runId           用于状态注入 + 升级计数
     * @param escalationReason 组长升级原因
     * @param currentTeam     当前组队
     * @param currentLead     当前组长
     * @param failedStepId    失败的 step id
     * @param userMessage     原始用户消息（重组队需要重新分析）
     * @return 重组队结果（newTeam/newTeamLead/resumeFromStep）；escalation 超限返回 null
     */
    suspend fun redispatch(
        runId: String,
        escalationReason: String,
        currentTeam: List<PoliceSchemas.ExpertId>,
        currentLead: PoliceSchemas.ExpertId,
        failedStepId: String,
        userMessage: String
    ): PoliceSchemas.RedispatchResult? {
        // 记录升级
        tracker.recordEscalation(runId, escalationReason)

        // L1: 升级超限 → 不重组队
        if (tracker.shouldBlock(runId)) {
            AppLogger.w(message = "Dispatcher: escalation_count >= ${EscalationTracker.MAX_ESCALATIONS}, BLOCKED")
            return null
        }

        val stateBlock = tracker.snapshotForPrompt(runId)
        val userPrompt = buildString {
            appendLine(stateBlock)
            appendLine()
            appendLine("组长升级报告：")
            appendLine("- escalation_reason: ${escalationReason.take(300)}")
            appendLine("- current_team: ${currentTeam.joinToString(",") { it.raw }}")
            appendLine("- current_lead: ${currentLead.raw}")
            appendLine("- failed_step_id: $failedStepId")
            appendLine()
            appendLine("原始用户需求：")
            appendLine(userMessage.take(3000))
            appendLine()
            appendLine("请重新组队并指定从哪步恢复。")
        }

        val dto = client.callJson(
            systemPrompt = PolicePrompts.DISPATCHER_REDISPATCH,
            userPrompt = userPrompt,
            serializer = PoliceSchemas.RedispatchDto.serializer()
        )

        val result = if (dto != null) {
            PoliceSchemas.buildRedispatchResult(dto, currentLead, currentTeam)
        } else {
            AppLogger.w(message = "Dispatcher: redispatch call failed, using fallback team")
            // Fallback：在当前队伍基础上追加一个专家
            val fallbackAdd = PoliceSchemas.ExpertId.CHECK
            val newTeam = (currentTeam + fallbackAdd).distinct().take(4)
            PoliceSchemas.RedispatchResult(
                newTeam = newTeam,
                newTeamLead = currentLead,
                resumeFromStep = failedStepId,
                routingReason = "redispatch fallback (call failed)"
            )
        }

        // 记录重组队
        tracker.recordTeamRound(
            runId = runId,
            team = result.newTeam.map { it.raw },
            outcome = "redispatch: ${result.routingReason}, resume=${result.resumeFromStep}"
        )

        return result
    }

    // ------------------------------------------------------------------
    // Fallback / 硬拒
    // ------------------------------------------------------------------

    private fun hardRefuseResult(): PoliceSchemas.DispatcherResult {
        return PoliceSchemas.DispatcherResult(
            intent = PoliceSchemas.Intent.GENERAL_CHAT,
            cap = PoliceSchemas.Cap.SIMPLE,
            scope = PoliceSchemas.Scope.GENERAL,
            needClarify = false,
            refuseHint = GuardRails.HARD_REFUSE_MESSAGE,
            expertTeam = emptyList(),
            teamLead = PoliceSchemas.ExpertId.GEN,  // 占位，不会使用
            routingReason = "hard block by guard rail"
        )
    }

    private fun fallbackDispatch(text: String): PoliceSchemas.DispatcherResult {
        // 简单关键词匹配兜底
        val intent = guessIntent(text)
        val cap = if (text.length > 500) PoliceSchemas.Cap.COMPLEX else PoliceSchemas.Cap.MEDIUM
        val expert = intentToExpert(intent)
        return PoliceSchemas.DispatcherResult(
            intent = intent,
            cap = cap,
            scope = PoliceSchemas.Scope.GENERAL,
            needClarify = false,
            refuseHint = "",
            expertTeam = listOf(expert, PoliceSchemas.ExpertId.CHECK),
            teamLead = expert,
            routingReason = "fallback dispatch (stage1 failed)"
        )
    }

    private fun defaultRefuseHint(): String =
        "这超出代码助手范围。如果你有编程相关的需求（代码生成/调试/重构/审查），我可以帮你。"

    private fun buildHistorySummary(history: List<ChatMessage>): String {
        if (history.isEmpty()) return ""
        val recent = history.takeLast(6)
        return buildString {
            recent.forEach { m ->
                val role = m.role.name.lowercase()
                val content = m.text.take(120).replace("\n", " ")
                appendLine("- $role: $content")
            }
        }.take(800)
    }

    private fun guessIntent(text: String): PoliceSchemas.Intent = when {
        text.contains("重构") || text.contains("重写") -> PoliceSchemas.Intent.CODE_REFACTOR
        text.contains("解释") || text.contains("原理") -> PoliceSchemas.Intent.CODE_EXPLAIN
        text.contains("报错") || text.contains("崩溃") || text.contains("修复") ||
            text.contains("bug") || text.contains("异常") -> PoliceSchemas.Intent.CODE_FIX_BUG
        text.contains("翻译") || text.contains("转成") -> PoliceSchemas.Intent.CODE_TRANSLATE
        text.contains("review") || text.contains("审查") -> PoliceSchemas.Intent.CODE_REVIEW
        text.contains("架构") || text.contains("设计") -> PoliceSchemas.Intent.DESIGN_ARCH
        text.contains("测试") || text.contains("test") -> PoliceSchemas.Intent.WRITE_TEST
        text.contains("依赖") || text.contains("gradle") -> PoliceSchemas.Intent.ADD_DEPENDENCY
        text.contains("小说") || text.contains("诗") || text.contains("故事") ||
            text.contains("情书") -> PoliceSchemas.Intent.GENERAL_CHAT
        else -> PoliceSchemas.Intent.CODE_GENERATE
    }

    private fun intentToExpert(intent: PoliceSchemas.Intent): PoliceSchemas.ExpertId = when (intent) {
        PoliceSchemas.Intent.CODE_GENERATE -> PoliceSchemas.ExpertId.GEN
        PoliceSchemas.Intent.CODE_EXPLAIN -> PoliceSchemas.ExpertId.EXPLAIN
        PoliceSchemas.Intent.CODE_REFACTOR -> PoliceSchemas.ExpertId.REFACTOR
        PoliceSchemas.Intent.CODE_FIX_BUG -> PoliceSchemas.ExpertId.FIX
        PoliceSchemas.Intent.CODE_TRANSLATE -> PoliceSchemas.ExpertId.TRANSLATE
        PoliceSchemas.Intent.CODE_REVIEW -> PoliceSchemas.ExpertId.REVIEW
        PoliceSchemas.Intent.DESIGN_ARCH -> PoliceSchemas.ExpertId.ARCH
        PoliceSchemas.Intent.WRITE_TEST -> PoliceSchemas.ExpertId.TEST
        PoliceSchemas.Intent.ADD_DEPENDENCY -> PoliceSchemas.ExpertId.DEPS
        PoliceSchemas.Intent.NEEDS_CLARIFICATION -> PoliceSchemas.ExpertId.CLARIFY
        PoliceSchemas.Intent.GENERAL_CHAT -> PoliceSchemas.ExpertId.GEN  // 占位，不会使用
    }
}
