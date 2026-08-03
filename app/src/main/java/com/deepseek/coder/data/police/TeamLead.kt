package com.deepseek.coder.data.police

import com.deepseek.coder.core.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Police Layer v2.0 — 组长（Team Lead）
 *
 * 设计依据：SPEC-Police-v1.0.md (内容为 v2.0) §3 / §5 / §6
 *  - 由路由警察指定的核心执行专家担任
 *  - two-stage 默认开（Stage 1 决定粒度/步数 → Stage 2 生成完整执行计划）
 *  - 接收组员反馈，判断是否升级回路由警察
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
     * 判断组员反馈是否需要升级回路由警察。
     *
     * @param feedbacks 组员专家的 feedback_to_lead 列表
     * @return true 表示需要升级（重新组队）
     */
    fun shouldEscalate(feedbacks: List<String>): Boolean {
        return feedbacks.any { it.isNotBlank() && it.length > 10 }
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
