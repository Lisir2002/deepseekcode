package com.deepseek.coder.data.police

import com.deepseek.coder.core.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Police Layer v2.1 — 组长（Team Lead）
 *
 * 设计依据：SPEC-Police-v2.1.md §3 / §5 / §6
 *  - 由路由警察指定的核心执行专家担任
 *  - two-stage 默认开（Stage 1 决定粒度/步数 → Stage 2 生成完整执行计划）
 *  - v2.1：接收组员反馈 → swapMember 决定动态换人（从 12 池追加/替换）
 *  - v2.1：shouldEscalate 接通，无法解决时升级回路由警察重组队
 *  - 失败时返回 fallback 单步计划（不阻塞流程）
 */
@Singleton
class TeamLead @Inject constructor(
    private val client: PoliceClient
) {

    /**
     * 制定执行计划（two-stage）。
     *
     * @param runId        用于状态注入
     * @param userMessage  用户原始消息
     * @param dispatch     路由警察的组队结果（含 intent/cap/scope/teamLead）
     * @return L1 校验后的 TeamLeadResult
     */
    suspend fun plan(
        runId: String,
        userMessage: String,
        dispatch: PoliceSchemas.DispatcherResult
    ): PoliceSchemas.TeamLeadResult {
        val fallbackGranularity = when (dispatch.cap) {
            PoliceSchemas.Cap.SIMPLE -> PoliceSchemas.Granularity.COARSE
            PoliceSchemas.Cap.MEDIUM -> PoliceSchemas.Granularity.MEDIUM
            PoliceSchemas.Cap.COMPLEX, PoliceSchemas.Cap.HARD -> PoliceSchemas.Granularity.FINE
        }

        val scopeTag = dispatch.scope.raw
        val controlBlock = buildString {
            appendLine("<scope>$scopeTag</scope>")
            appendLine("<intent>${dispatch.intent.raw}</intent>")
            appendLine("<cap>${dispatch.cap.raw}</cap>")
            appendLine("<team_lead>${dispatch.teamLead.raw}</team_lead>")
            appendLine("<team>${dispatch.expertTeam.joinToString(",") { it.raw }}</team>")
        }
        val userBlock = buildString {
            appendLine(controlBlock)
            appendLine()
            appendLine("用户需求：")
            appendLine(userMessage.take(4000))
        }

        // Stage 1 + Stage 2 two-stage 调用
        val (s1, s2) = client.callTwoStage(
            s1System = PolicePrompts.TEAM_LEAD_STAGE1,
            s1User = userBlock,
            s1Serializer = PoliceSchemas.TeamLeadStage1Dto.serializer(),
            s2System = PolicePrompts.TEAM_LEAD_STAGE2,
            s2UserBuilder = { stage1 ->
                val gran = PoliceSchemas.Granularity.coerce(stage1.granularity) ?: fallbackGranularity
                buildString {
                    appendLine(controlBlock)
                    appendLine()
                    appendLine("Stage 1 决策：")
                    appendLine("- granularity: ${gran.raw}")
                    appendLine("- step_count: ${stage1.step_count ?: PoliceSchemas.stepRange(gran).first}")
                    appendLine("- scope_tag: ${stage1.scope_tag ?: scopeTag}")
                    appendLine()
                    appendLine("用户需求：")
                    appendLine(userMessage.take(4000))
                }
            },
            s2Serializer = PoliceSchemas.TeamLeadStage2Dto.serializer()
        )

        return if (s1 != null && s2 != null) {
            PoliceSchemas.buildTeamLeadResult(s1, s2, fallbackGranularity)
        } else if (s1 != null) {
            // Stage 2 失败，用 Stage 1 + fallback 单步计划
            AppLogger.w(message = "TeamLead: stage2 failed, using fallback plan with stage1 granularity")
            PoliceSchemas.buildTeamLeadResult(
                s1,
                PoliceSchemas.TeamLeadStage2Dto(steps = emptyList()),
                fallbackGranularity
            )
        } else {
            // 全失败，单步 fallback
            AppLogger.w(message = "TeamLead: both stages failed, using single-step fallback")
            fallback(fallbackGranularity, dispatch.teamLead)
        }
    }

    /**
     * v2.1：判断组员反馈是否需要升级回路由警察（重新组队）。
     *
     * 升级触发条件（任一命中）：
     * - feedback 明确提到"无法解决/超出范围/需要其他专家"
     * - feedback 长度 > 10 且包含升级关键词
     *
     * 注意：本方法只判断是否升级，不执行换人。换人用 [swapMember]。
     *
     * @param feedbacks 组员专家的 feedback_to_lead 列表
     * @return true 表示需要升级（路由警察重组队）
     */
    fun shouldEscalate(feedbacks: List<String>): Boolean {
        val escalateKeywords = listOf("无法解决", "超出范围", "需要其他", "升级", "无法处理", "不是本", "超出本组")
        return feedbacks.any { fb ->
            fb.isNotBlank() && fb.length > 10 &&
                (escalateKeywords.any { fb.contains(it) } || fb.contains("ESCALATE"))
        }
    }

    /**
     * v2.1：组长动态换人决策（组员反馈新问题但本组可解决时调用）。
     *
     * 场景：GEN 反馈"这任务需要修复而非新生成"→ 组长决定换 GEN→FIX。
     * 换人不算升级（不增加 escalation_count）。
     *
     * @param runId         用于状态注入
     * @param feedback      组员的 feedback_to_lead
     * @param currentTeam   当前组队
     * @return 换人决策（shouldSwap=true 时 removeExpert/addExpert 非空）
     */
    suspend fun swapMember(
        runId: String,
        feedback: String,
        currentTeam: List<PoliceSchemas.ExpertId>
    ): PoliceSchemas.SwapMemberResult {
        val userPrompt = buildString {
            appendLine("组员反馈的新问题：")
            appendLine(feedback.take(1000))
            appendLine()
            appendLine("当前组队：${currentTeam.joinToString(",") { it.raw }}")
            appendLine()
            appendLine("请判断是否需要换人。若本组可解决（调整计划即可）→ KEEP_TEAM；若需换人 → SWAP_MEMBER。")
        }

        val dto = client.callJson(
            systemPrompt = PolicePrompts.TEAM_LEAD_SWAP,
            userPrompt = userPrompt,
            serializer = PoliceSchemas.SwapMemberDto.serializer()
        )

        return if (dto != null) {
            val action = (dto.action ?: "").trim().uppercase()
            val shouldSwap = action == "SWAP_MEMBER"
            val remove = dto.remove_expert?.let {
                PoliceSchemas.ExpertId.coerceAll(listOf(it)).firstOrNull()
            }
            val add = dto.add_expert?.let {
                PoliceSchemas.ExpertId.coerceAll(listOf(it)).firstOrNull()
            }
            // L1 校验：SWAP_MEMBER 但 remove/add 缺一 → 降级为 KEEP_TEAM
            val effectiveSwap = shouldSwap && remove != null && add != null
            PoliceSchemas.SwapMemberResult(
                shouldSwap = effectiveSwap,
                removeExpert = if (effectiveSwap) remove else null,
                addExpert = if (effectiveSwap) add else null,
                reason = (dto.reason ?: "").take(100)
            )
        } else {
            AppLogger.w(message = "TeamLead: swapMember call failed, returning KEEP_TEAM")
            PoliceSchemas.SwapMemberResult(
                shouldSwap = false,
                removeExpert = null,
                addExpert = null,
                reason = "swap decision failed, keep team"
            )
        }
    }

    /** Fallback：单步执行计划（不阻塞流程）。 */
    private fun fallback(
        granularity: PoliceSchemas.Granularity,
        lead: PoliceSchemas.ExpertId
    ): PoliceSchemas.TeamLeadResult {
        return PoliceSchemas.TeamLeadResult(
            granularity = granularity,
            steps = listOf(
                PoliceSchemas.PlanStep(
                    id = "s1",
                    title = "执行",
                    assignedExpert = lead,
                    what = "",
                    why = "",
                    edgeCase = "",
                    testHint = "",
                    dependsOn = emptyList(),
                    estimatedDurationPct = 1f
                )
            ),
            milestoneEdges = emptyList(),
            warn = "fallback plan (team-lead call failed)"
        )
    }
}
