package com.deepseek.coder.planner.bench.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * DeepCoder Planner Output Schema 0.4 (v0.7 规格)
 * 所有评测脚本和质检流水线共用此 DTO；训练数据合成和 LoRA 推理输出必须严格匹配此 Schema。
 *
 * Schema 版本变更：
 * - 0.1: 基础版 milestones / subtasks / clarifications
 * - 0.2: 分层 (MILESTONE + SUBTASK) + expected_duration_pct + acceptance_gate
 * - 0.3: topology.milestone_edges 强制非空 DAG + meta.refuse_reason + meta.scope_tag + Self-Rerank
 * - 0.4 (v0.7 新): dispatch.scope_hint[] 多场景微调 + ControlToken 升级第3位 scope + 新增 capability CAP_GENERAL_CHAT
 */
@Serializable
data class PlannerOutput(
    val meta: Meta,
    val dispatch: Dispatch,
    val milestones: List<Milestone>,
    val topology: Topology,
    @SerialName("clarifications_needed")
    val clarificationsNeeded: List<Clarification> = emptyList(),
    @SerialName("granularity_analysis")
    val granularityAnalysis: GranularityAnalysis? = null
) {
    /** 结构性字段键值集合，用于 Q10 双语 pair 字节级相等校验 */
    fun structuralFingerprint(): String = buildString {
        append("scope_tag=").append(meta.scopeTag).append('|')
        append("echo_granularity=").append(meta.echoGranularity).append('|')
        append("echo_planning_level=").append(meta.echoPlanningLevel).append('|')
        append("echo_control=").append(meta.echoControl).append('|')
        append("estimated_total_steps=").append(meta.estimatedTotalSteps).append('|')
        append("estimated_cost_yuan=").append(meta.estimatedCostYuan).append('|')
        append("estimated_minutes=").append(meta.estimatedMinutesWallClock).append('|')
        append("default_tier=").append(dispatch.defaultTier).append('|')
        append("default_model=").append(dispatch.defaultModel).append('|')
        append("capability_priority_map=").append(dispatch.capabilityPriorityMap.toSortedMap()).append('|')
        append("max_retry_per_subtask=").append(dispatch.maxRetryPerSubtask).append('|')
        append("allow_parallel_within_milestone=").append(dispatch.allowParallelWithinMilestone).append('|')
        append("scope_hint=").append(dispatch.scopeHint.sorted()).append('|')
        milestones.forEach { m ->
            append("M[").append(m.id).append("]:dur_pct=").append(m.expectedDurationPct)
                .append(":tier_over=").append(m.tierOverride)
                .append(":deps=").append(m.dependsOn.sorted())
                .append(":gates=").append(m.acceptanceGate.size)
                .append(":subtasks_count=").append(m.subtasks.size).append('|')
            m.subtasks.forEach { s ->
                append("  S[").append(s.id).append("]:cap=").append(s.capability)
                    .append(":deps=").append(s.dependsOn.sorted())
                    .append(":ac_count=").append(s.acceptanceCriteria.size).append('|')
            }
        }
        append("topo_milestone_edges=").append(topology.milestoneEdges.sortedBy { it.from })
        append("topo_cross_subtask_count=").append(topology.crossSubtaskEdges.size)
    }
}

@Serializable
data class Meta(
    @SerialName("output_version")
    val outputVersion: String = "0.4",
    /** 合成数据唯一 ID，质检/复现用 */
    @SerialName("request_id")
    val requestId: String = "",
    /** 语言偏好：zh-CN / en-US，双语 pair 一致性 Q10 用 */
    @SerialName("language_tag")
    val languageTag: String = "zh-CN",
    @SerialName("echo_granularity")
    val echoGranularity: Granularity,
    @SerialName("echo_planning_level")
    val echoPlanningLevel: PlanningLevel,
    @SerialName("echo_control")
    val echoControl: ControlType,
    val confidence: Float, // 0.00~1.00
    @SerialName("needs_user_confirmation")
    val needsUserConfirmation: Boolean = false,
    @SerialName("estimated_total_steps")
    val estimatedTotalSteps: Int,
    @SerialName("estimated_cost_yuan")
    val estimatedCostYuan: Float = 0f,
    @SerialName("estimated_minutes_wall_clock")
    val estimatedMinutesWallClock: Int = 0,
    @SerialName("refuse_reason")
    val refuseReason: String? = null,
    @SerialName("scope_tag")
    val scopeTag: ScopeTag
)

@Serializable
enum class Granularity { COARSE, MEDIUM, FINE }

@Serializable
enum class PlanningLevel { MILESTONE, SUBTASK }

@Serializable
enum class ControlType { NORMAL, GRANULARITY_CONVERT, GRANULARITY_ANALYSE, FAILURE_DISPATCH, PLAN_MILESTONE, PLAN_SUBTASK, RERANK, REFUSE }

/** v0.7 Scope 三分类：Planner 自动识别不做手动模式切换 */
@Serializable
enum class ScopeTag { ANDROID_KOTLIN, WEB_FRONTEND, GENERAL }

@Serializable
data class Dispatch(
    @SerialName("default_tier")
    val defaultTier: String, // L1-Light / L2-Standard / L3-Pro
    @SerialName("default_model")
    val defaultModel: String, // v4-flash / v4-pro
    @SerialName("capability_priority_map")
    val capabilityPriorityMap: Map<String, String> = emptyMap(), // milestone_id -> CAP_*
    @SerialName("max_retry_per_subtask")
    val maxRetryPerSubtask: Int = 1,
    @SerialName("allow_parallel_within_milestone")
    val allowParallelWithinMilestone: Boolean = true,
    @SerialName("always_self_check_after_code_task")
    val alwaysSelfCheckAfterCodeTask: Boolean = true,
    /** v0.7 新增：多场景混合微调，例如前端+后端全栈需求 = ["WEB_FRONTEND","BACKEND"] */
    @SerialName("scope_hint")
    val scopeHint: List<String> = emptyList()
)

@Serializable
data class Milestone(
    val id: String,
    val title: String,
    @SerialName("tier_override")
    val tierOverride: String? = null,
    @SerialName("depends_on")
    val dependsOn: List<String> = emptyList(),
    @SerialName("why_this_milestone_first")
    val whyThisMilestoneFirst: String? = null,
    @SerialName("expected_duration_pct")
    val expectedDurationPct: Float, // 累加和=1.0±0.03
    @SerialName("acceptance_gate")
    val acceptanceGate: List<String> = emptyList(),
    val subtasks: List<Subtask> = emptyList()
)

@Serializable
data class Subtask(
    val id: String,
    val title: String,
    val capability: String, // CAP_* 字符串，见 Capability 枚举
    @SerialName("depends_on")
    val dependsOn: List<String> = emptyList(),
    @SerialName("acceptance_criteria")
    val acceptanceCriteria: List<String> = emptyList(),
    @SerialName("expected_outputs")
    val expectedOutputs: List<String> = emptyList(),
    @SerialName("context_hint")
    val contextHint: String? = null
)

/** v0.7 16 项能力图谱（新增 CAP_GENERAL_CHAT） */
enum class Capability(val id: String, val tier: String, val zhName: String) {
    CAP_CODE_GENERATE("CAP_CODE_GENERATE", "L2-Standard", "代码生成"),
    CAP_CODE_REFACTOR("CAP_CODE_REFACTOR", "L2-Standard", "代码重构"),
    CAP_CODE_EXPLAIN("CAP_CODE_EXPLAIN", "L2-Standard", "代码解释"),
    CAP_CODE_FIX_BUG("CAP_CODE_FIX_BUG", "L3-Pro", "修复Bug"),
    CAP_CODE_TRANSLATE("CAP_CODE_TRANSLATE", "L1-Light", "语言转换"),
    CAP_CODE_REVIEW("CAP_CODE_REVIEW", "L2-Standard", "代码评审"),
    CAP_DESIGN_ARCH("CAP_DESIGN_ARCH", "L3-Pro", "架构设计"),
    CAP_FIM_COMPLETE("CAP_FIM_COMPLETE", "L1-Light", "光标中间补全"),
    CAP_WRITE_TEST("CAP_WRITE_TEST", "L2-Standard", "写单元测试"),
    CAP_ADD_DEPENDENCY("CAP_ADD_DEPENDENCY", "L1-Light", "管理依赖"),
    CAP_RUN_SYNTAX_CHECK("CAP_RUN_SYNTAX_CHECK", "LOCAL", "语法自检"),
    CAP_ASK_CLARIFICATION("CAP_ASK_CLARIFICATION", "LOCAL", "追问澄清"),
    CAP_SEARCH_CONTEXT("CAP_SEARCH_CONTEXT", "LOCAL", "检索上下文"),
    CAP_SUMMARISE("CAP_SUMMARISE", "L2-Standard", "长对话压缩摘要"),
    CAP_SWITCH_MODEL("CAP_SWITCH_MODEL", "LOCAL", "切模型档位"),
    CAP_GENERAL_CHAT("CAP_GENERAL_CHAT", "L2-Standard", "通用对话") // v0.7 新增：非编程需求占位 Actor 走通用接口
}

@Serializable
data class Topology(
    val type: String = "dag_with_possible_rework_edges",
    @SerialName("milestone_edges")
    val milestoneEdges: List<MilestoneEdge>,
    @SerialName("cross_subtask_edges")
    val crossSubtaskEdges: List<CrossSubtaskEdge> = emptyList()
)

@Serializable
data class MilestoneEdge(
    val from: String,
    val to: List<String>
)

@Serializable
data class CrossSubtaskEdge(
    val from: String,
    val to: List<String>
)

@Serializable
data class Clarification(
    val id: String,
    @SerialName("blocking_milestone_ids")
    val blockingMilestoneIds: List<String> = emptyList(),
    val question: String,
    val options: List<String> = emptyList(),
    @SerialName("default_if_skipped")
    val defaultIfSkipped: String? = null
)

@Serializable
data class GranularityAnalysis(
    @SerialName("granularity_score_1_to_5")
    val granularityScore1To5: Int,
    @SerialName("actual_mode_detected")
    val actualModeDetected: Granularity,
    @SerialName("matched_user_intent_score_0_to_1")
    val matchedUserIntentScore0To1: Float,
    @SerialName("too_coarse_or_too_fine_flag")
    val tooCoarseOrTooFineFlag: TooCoarseTooFine,
    @SerialName("rewrite_suggestion_if_wrong")
    val rewriteSuggestionIfWrong: String? = null,
    @SerialName("milestone_coverage_rate_0_to_1")
    val milestoneCoverageRate0To1: Float,
    @SerialName("subtask_acceptance_criteria_richness")
    val subtaskAcceptanceCriteriaRichness: Float,
    @SerialName("estimated_rework_probability_if_executed_as_is")
    val estimatedReworkProbabilityIfExecutedAsIs: Float? = null
)

@Serializable
enum class TooCoarseTooFine { TOO_COARSE, JUST_RIGHT, TOO_FINE }

@Serializable
data class FailureDecision(
    val decision: String, // RETRY / REWORK / BLOCKED
    @SerialName("retry_count_remaining")
    val retryCountRemaining: Int? = null,
    @SerialName("patch_prompt_suffix")
    val patchPromptSuffix: String? = null,
    @SerialName("insert_after_id")
    val insertAfterId: String? = null,
    @SerialName("extra_subtasks")
    val extraSubtasks: List<Subtask>? = null,
    val clarification: Clarification? = null
)
