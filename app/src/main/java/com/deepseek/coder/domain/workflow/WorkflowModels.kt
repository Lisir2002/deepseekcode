package com.deepseek.coder.domain.workflow

/**
 * Classification of the user's coding request.
 * Drives which Orchestrator sub-workflow (prompts, tools, retry strategy) is activated.
 */
enum class CodeIntent(val display: String) {
    CODE_GENERATE("生成代码"),
    CODE_REFACTOR("重构代码"),
    CODE_EXPLAIN("解释代码"),
    CODE_FIX_BUG("修复 Bug"),
    CODE_TRANSLATE("语言转换"),
    CODE_REVIEW("代码 Review"),
    DESIGN_ARCH("架构设计"),
    FIM_COMPLETE("中间补全"),
    GENERAL_CHAT("通用闲聊"),
    NEEDS_CLARIFICATION("需求澄清");

    companion object {
        fun of(name: String?): CodeIntent =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: GENERAL_CHAT
    }
}

/**
 * A single planned step within an orchestrated run.
 * The Orchestrator can decide to split complex requests into multiple steps
 * executed sequentially; each step reuses the same underlying ChatRepository
 * but with overridden system prompts / tool hints / temperature.
 */
data class WorkflowStep(
    val index: Int,
    val title: String,
    val systemPromptHints: String = "",
    val dependsOn: List<Int> = emptyList(),
    val requiresSelfCheck: Boolean = false
)

/**
 * A multi-step plan produced by the Task Decomposer node.
 * Simple intents (e.g. CODE_EXPLAIN) yield a single-step plan.
 */
data class WorkflowPlan(
    val steps: List<WorkflowStep>,
    val estimatedTotalTokens: Int? = null
)

/**
 * Severity of issues found by the Self-Check node.
 */
data class SelfCheckResult(
    val pass: Boolean,
    val issues: List<String> = emptyList(),
    val suggestedFixPrompt: String? = null
)

/**
 * Summarised intent classification result.  If confidence is below threshold
 * or required info is missing the Orchestrator transitions to the CLARIFY
 * state and emits a CLARIFY_QUESTION OrchestratorEvent downstream.
 */
data class IntentClassification(
    val intent: CodeIntent,
    val confidence: Float,
    val missingInfo: List<String> = emptyList()
)
