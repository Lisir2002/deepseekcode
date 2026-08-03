package com.deepseek.coder.data.police

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Police Layer v2.0 — L1 硬规则：升级计数 + attempted_approaches 去重
 *
 * 设计依据：SPEC-Police-v1.0.md (内容为 v2.0) §5 / §2.1
 *  - escalation_count >= 3 → 强制 BLOCKED（不依赖模型决策）
 *  - attempted_approaches 去重：相似度 > 0.8 强制换思路
 *  - 多轮状态字段注入：plan_state / current_step / attempts / last_error / attempted_approaches / team_history
 *
 * 状态按 runId 隔离，run 结束后由 [clear] 清理。
 */
@Singleton
class EscalationTracker @Inject constructor() {

    /** 单次 run 的累积状态。 */
    data class RunState(
        val runId: String,
        var attempts: Int = 0,
        var escalationCount: Int = 0,
        var currentStep: Int = 0,
        var planState: String = "",
        var lastError: String = "",
        val attemptedApproaches: MutableList<String> = mutableListOf(),
        val teamHistory: MutableList<TeamRound> = mutableListOf()
    )

    data class TeamRound(
        val round: Int,
        val team: List<String>,
        val outcome: String
    )

    private val states = ConcurrentHashMap<String, RunState>()

    /** 获取或创建 run 状态。 */
    fun getOrCreate(runId: String): RunState =
        states.computeIfAbsent(runId) { RunState(runId = runId) }

    /** 记录一次 attempt（每次执行 step 后调用）。 */
    fun recordAttempt(runId: String, approachSummary: String) {
        val s = getOrCreate(runId)
        s.attempts += 1
        if (approachSummary.isNotBlank()) {
            s.attemptedApproaches.add(approachSummary.take(200))
        }
    }

    /** 记录一次升级（组长→路由警察重新组队）。 */
    fun recordEscalation(runId: String, reason: String) {
        val s = getOrCreate(runId)
        s.escalationCount += 1
        s.lastError = reason.take(160)
    }

    /** 记录一次组队。 */
    fun recordTeamRound(runId: String, team: List<String>, outcome: String) {
        val s = getOrCreate(runId)
        s.teamHistory.add(TeamRound(round = s.teamHistory.size + 1, team = team, outcome = outcome.take(160)))
    }

    /** 更新执行进度。 */
    fun updateProgress(runId: String, currentStep: Int, planState: String) {
        val s = getOrCreate(runId)
        s.currentStep = currentStep
        s.planState = planState.take(160)
    }

    /** L1 硬规则：升级次数是否已达上限（>= 3 强制 BLOCKED）。 */
    fun shouldBlock(runId: String): Boolean = getOrCreate(runId).escalationCount >= MAX_ESCALATIONS

    /** L1 硬规则：尝试次数是否已达上限（>= MAX_ATTEMPTS 强制 BLOCKED）。 */
    fun shouldBlockByAttempts(runId: String): Boolean = getOrCreate(runId).attempts >= MAX_ATTEMPTS

    /** attempted_approaches 去重检测：新思路与历史相似度 > 0.8 视为重复。 */
    fun isDuplicateApproach(runId: String, newApproach: String): Boolean {
        val s = getOrCreate(runId)
        if (newApproach.isBlank() || s.attemptedApproaches.isEmpty()) return false
        return s.attemptedApproaches.any { existing ->
            similarity(existing, newApproach) > SIMILARITY_THRESHOLD
        }
    }

    /** 序列化为可注入 prompt 的状态摘要（防止多轮状态丢失）。 */
    fun snapshotForPrompt(runId: String): String {
        val s = getOrCreate(runId)
        return buildString {
            appendLine("【当前执行状态】")
            appendLine("- plan_state: ${s.planState.ifBlank { "(初始)" }}")
            appendLine("- current_step: ${s.currentStep}")
            appendLine("- attempts: ${s.attempts}")
            appendLine("- escalation_count: ${s.escalationCount}")
            if (s.lastError.isNotBlank()) appendLine("- last_error: ${s.lastError}")
            if (s.attemptedApproaches.isNotEmpty()) {
                appendLine("- attempted_approaches:")
                s.attemptedApproaches.forEachIndexed { i, a -> appendLine("  [$i] $a") }
            }
            if (s.teamHistory.isNotEmpty()) {
                appendLine("- team_history:")
                s.teamHistory.forEach { r -> appendLine("  round ${r.round}: ${r.team} → ${r.outcome}") }
            }
        }
    }

    /** run 结束时清理状态。 */
    fun clear(runId: String) {
        states.remove(runId)
    }

    companion object {
        const val MAX_ESCALATIONS = 3
        const val MAX_ATTEMPTS = 3
        const val SIMILARITY_THRESHOLD = 0.8

        /**
         * 字符串相似度（基于 Jaccard 三元组，简单高效，无需外部依赖）。
         * 范围 [0,1]，1 表示完全相同。
         */
        internal fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.length < 3 || b.length < 3) {
                return if (a == b) 1.0 else 0.0
            }
            val ta = trigrams(a)
            val tb = trigrams(b)
            val intersection = ta.intersect(tb).size.toDouble()
            val union = ta.union(tb).size.toDouble()
            return if (union == 0.0) 0.0 else intersection / union
        }

        private fun trigrams(s: String): Set<String> {
            val n = 3
            if (s.length < n) return setOf(s)
            return (0..s.length - n).map { s.substring(it, it + n) }.toSet()
        }
    }
}
