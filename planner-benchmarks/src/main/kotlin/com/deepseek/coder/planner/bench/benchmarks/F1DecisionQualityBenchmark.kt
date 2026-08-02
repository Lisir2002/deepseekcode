package com.deepseek.coder.planner.bench.benchmarks

import com.deepseek.coder.planner.bench.schema.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * F1 决策质量 Benchmark：150 题
 *   ① 15 类 CAP 分类 macro-F1 ≥ 0.90
 *   ② clarity_needed 触发准确率 ≥ 0.85
 *   ③ meta.confidence <0.65 的错误率 ≤ 5%
 *   ④ 三档粒度 estimated_total_steps 落入对应区间的比例 ≥ 95%
 *   ⑤ (v0.7 新增) Scope 三分类准确率 ≥ 0.92
 */
data class F1Case(
    val caseId: String,
    val userPrompt: String,
    val expectedGranularity: Granularity,
    val expectedScope: ScopeTag,
    val expectedScopeHint: List<String> = emptyList(),
    val expectedMainCapability: String, // 预期主要 CAP_*
    val expectedClarityNeeded: Boolean, // 预期是否需要澄清
    val expectedLowConfidence: Boolean, // 预期 confidence 是否 < 0.65（需要 fallback 的）
    val stepsRange: IntRange // 基于 expectedGranularity 的允许区间（一般和档位一致）
)

class F1DecisionQualityBenchmark(
    private val cases: List<F1Case>
) : PlannerBenchmark {
    override val id = "F1"
    override val name = "决策质量 Benchmark（150题：15类CAP+Scope3分类+粒度区间+Clarity+Confidence）"
    override val threshold = 0.85 // 综合通过率，配合分项 macro-F1

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun evaluate(outputs: Map<String, PlannerOutput>): BenchmarkResult {
        var capCorrect = 0; var capTotal = 0
        var scopeCorrect = 0; var scopeTotal = 0
        var clarityCorrect = 0; var clarityTotal = 0
        var confOk = 0; var confTotal = 0
        var granularityStepInRange = 0; var granularityTotal = 0
        val failed = mutableListOf<String>()

        cases.forEach { case ->
            val out = outputs[case.caseId] ?: run { failed += "${case.caseId}(未找到输出)"; return@forEach }

            // ① CAP 分类：检查 milestones 第一个 subtask 的 capability 或 dispatch.capabilityPriorityMap 的值
            capTotal++
            val firstCap = out.milestones.firstOrNull()?.subtasks?.firstOrNull()?.capability
                ?: out.dispatch.capabilityPriorityMap.values.firstOrNull()
            if (firstCap == case.expectedMainCapability) capCorrect++
            else failed += "${case.caseId} CAP 期望=${case.expectedMainCapability} 实际=$firstCap"

            // ⑤ Scope 三分类（v0.7 新增）
            scopeTotal++
            if (out.meta.scopeTag == case.expectedScope) scopeCorrect++
            else failed += "${case.caseId} Scope 期望=${case.expectedScope} 实际=${out.meta.scopeTag}"

            // ② Clarity needed：检查 clarifications_needed 是否非空 + meta.needsUserConfirmation 是否 true
            clarityTotal++
            val actuallyNeeds = out.clarificationsNeeded.isNotEmpty() || out.meta.needsUserConfirmation
            if (actuallyNeeds == case.expectedClarityNeeded) clarityCorrect++
            else failed += "${case.caseId} Clarity 期望=${case.expectedClarityNeeded} 实际=$actuallyNeeds"

            // ③ Confidence 错误率
            confTotal++
            val lowConf = out.meta.confidence < 0.65f
            if (lowConf == case.expectedLowConfidence) confOk++
            else failed += "${case.caseId} LowConf 期望=${case.expectedLowConfidence} actual=${out.meta.confidence}"

            // ④ 粒度步数落在区间
            granularityTotal++
            if (out.meta.estimatedTotalSteps in case.stepsRange) granularityStepInRange++
            else failed += "${case.caseId} Steps 区间不符：${out.meta.estimatedTotalSteps} 不在 ${case.stepsRange}"
        }

        val macroF1 = listOf(
            capCorrect.toDouble() / capTotal,
            scopeCorrect.toDouble() / scopeTotal,
            clarityCorrect.toDouble() / clarityTotal,
            1.0 - (confTotal - confOk).toDouble() / confTotal,  // 错误率 -> 正确率
            granularityStepInRange.toDouble() / granularityTotal
        ).average()

        val passedCases = capCorrect + scopeCorrect + clarityCorrect + confOk + granularityStepInRange
        val totalCases = capTotal + scopeTotal + clarityTotal + confTotal + granularityTotal
        val passed = macroF1 >= 0.90 && // 15 类 CAP macro-F1 ≥ 0.90（这里近似 5 大分项平均）
                     scopeCorrect.toDouble() / scopeTotal >= 0.92 && // Scope 三分类准确率 ≥ 0.92
                     (confTotal - confOk).toDouble() / confTotal <= 0.05 // Confidence 错误率 ≤5%

        return BenchmarkResult(
            id = id, name = name,
            totalCases = cases.size * 5, passedCases = passedCases,
            passed = passed, threshold = threshold,
            subMetrics = mapOf(
                "CAP分类准确率" to pct(capCorrect, capTotal),
                "Scope三分类准确率" to pct(scopeCorrect, scopeTotal),
                "Clarity触发准确率" to pct(clarityCorrect, clarityTotal),
                "Confidence错误率" to pct(confTotal - confOk, confTotal) + "(越小越好，≤5%)",
                "粒度步数落入区间比例" to pct(granularityStepInRange, granularityTotal),
                "五项macro平均(需≥0.90)" to "%.3f".format(macroF1)
            ),
            failedCases = failed.distinct()
        )
    }

    private fun pct(a: Int, b: Int): String =
        if (b == 0) "N/A" else "${"%5.1f%%".format(100.0*a/b)} ($a/$b)"

    companion object {
        /** 默认样例 15 题用例（覆盖 15 类 CAP 各 1 题 + Scope 三分类各至少 5 题）；真实评测需要补齐到 150 题 */
        fun defaultSampleCases(): List<F1Case> = listOf(
            // --- ANDROID_KOTLIN scope 5 题
            F1Case("F1-AND-01", "用 Kotlin 写个登录页，ViewModel + Room 存 Token，minSdk 26，用 Hilt 注入", Granularity.MEDIUM, ScopeTag.ANDROID_KOTLIN, emptyList(),
                "CAP_CODE_GENERATE", false, false, 5..9),
            F1Case("F1-AND-02", "这个崩溃堆栈帮我修一下：NullPointerException at UserViewModel.kt line 42", Granularity.MEDIUM, ScopeTag.ANDROID_KOTLIN, emptyList(),
                "CAP_CODE_FIX_BUG", false, true, 5..9), // 带堆栈 bug 修复，conf 可以稍高但要求 L3-Pro
            F1Case("F1-AND-03", "重构我的网络层，现在是 Retrofit 裸调用，想改成 Repository 模式 + 加 Resource 密封类", Granularity.FINE, ScopeTag.ANDROID_KOTLIN, emptyList(),
                "CAP_CODE_REFACTOR", false, false, 12..24),
            F1Case("F1-AND-04", "给我 App 的 Clean Architecture 分层设计方案，我要做记账 App", Granularity.COARSE, ScopeTag.ANDROID_KOTLIN, emptyList(),
                "CAP_DESIGN_ARCH", false, false, 1..5),
            F1Case("F1-AND-05", "这两段 Java 代码帮我转成 Kotlin 并尽量 idiomatic", Granularity.MEDIUM, ScopeTag.ANDROID_KOTLIN, emptyList(),
                "CAP_CODE_TRANSLATE", false, false, 5..9),
            // --- WEB_FRONTEND scope 5 题（v0.7 专项加权 40%）
            F1Case("F1-WEB-01", "用 React 18 + TypeScript 写一个 Todo App，包含增删改查 + 本地持久化，样式用 TailwindCSS", Granularity.MEDIUM, ScopeTag.WEB_FRONTEND, emptyList(),
                "CAP_CODE_GENERATE", false, false, 5..9),
            F1Case("F1-WEB-02", "我的 Vue 3 项目 ESLint 报错：'defineProps' is not defined，帮我修复并顺手把整个项目 lint 一遍", Granularity.MEDIUM, ScopeTag.WEB_FRONTEND, emptyList(),
                "CAP_CODE_FIX_BUG", false, false, 5..9),
            F1Case("F1-WEB-03", "解释一下 React 的 useEffect 依赖数组为什么会导致无限重渲染，给我三个常见反模式例子", Granularity.MEDIUM, ScopeTag.WEB_FRONTEND, emptyList(),
                "CAP_CODE_EXPLAIN", false, false, 5..9),
            F1Case("F1-WEB-04", "我现有的 CRA 项目要迁移到 Vite + React 18，帮我拆成 18 步的迁移计划，每步都要验收标准", Granularity.FINE, ScopeTag.WEB_FRONTEND, emptyList(),
                "CAP_CODE_REFACTOR", false, false, 12..24),
            F1Case("F1-WEB-05", "审查这段 Next.js 14 App Router 的代码，指出 Server Components 和 Client Components 的混用错误 + 性能优化建议", Granularity.MEDIUM, ScopeTag.WEB_FRONTEND, emptyList(),
                "CAP_CODE_REVIEW", true, false, 5..9), // 审查通常需要澄清 Node/TS 版本等
            // --- GENERAL scope 5 题（含后端 + DevOps + 算法）
            F1Case("F1-GEN-01", "写个 Python FastAPI 后端，5 个 REST 接口：用户 CRUD，用 SQLAlchemy 2.0 + Pydantic v2，连接 PostgreSQL", Granularity.MEDIUM, ScopeTag.GENERAL, listOf("BACKEND"),
                "CAP_CODE_GENERATE", false, false, 5..9),
            F1Case("F1-GEN-02", "用 Dockerfile + docker-compose.yml 打包我这个 Node 项目：前端 3000 + 后端 8080 + Redis + MySQL，要求多阶段构建和 alpine 镜像", Granularity.MEDIUM, ScopeTag.GENERAL, listOf("DEVOPS", "WEB_FRONTEND", "BACKEND"),
                "CAP_ADD_DEPENDENCY", false, false, 5..9),
            F1Case("F1-GEN-03", "帮我写单元测试：这个 Go 实现的 LRU Cache，覆盖率 ≥ 85%，包括并发访问场景", Granularity.MEDIUM, ScopeTag.GENERAL, listOf("BACKEND"),
                "CAP_WRITE_TEST", false, false, 5..9),
            F1Case("F1-GEN-04", "用双向 BFS 解决这个 LeetCode Hard 单词接龙 II 问题，给我 Kotlin 实现 + 时空复杂度分析", Granularity.MEDIUM, ScopeTag.GENERAL, listOf("ALGORITHM"),
                "CAP_CODE_GENERATE", false, false, 5..9),
            F1Case("F1-GEN-05", "帮我设计一份月度销售报表 PPT 大纲和文案（这是个非编程占位需求，v1.1 再优化）", Granularity.COARSE, ScopeTag.GENERAL, emptyList(),
                "CAP_GENERAL_CHAT", true, true, 1..5) // 非编程：需要澄清 + confidence 低
        )
    }
}
