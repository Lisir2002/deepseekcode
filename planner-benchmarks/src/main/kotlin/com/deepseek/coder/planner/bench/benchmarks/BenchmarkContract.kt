package com.deepseek.coder.planner.bench.benchmarks

import com.deepseek.coder.planner.bench.schema.PlannerOutput

/**
 * F1~F6 六套评测集统一接口
 * 每个 Benchmark 返回：通过题数/总题数 + 分项目指标明细
 */
data class BenchmarkResult(
    val id: String,          // "F1" ~ "F6"
    val name: String,
    val totalCases: Int,
    val passedCases: Int,
    val passRate: Double = passedCases.toDouble() / totalCases,
    val passed: Boolean,     // 是否达标（按规格阈值）
    val threshold: Double,   // 规格要求的达标阈值
    val subMetrics: Map<String, Any?> = emptyMap(),
    val failedCases: List<String> = emptyList()
) {
    fun summary(): String {
        val mark = if (passed) "✅ PASS" else "❌ FAIL"
        val subs = subMetrics.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        return "[$mark $id $name] 通过率=${"%5.1f%%".format(passRate*100)} ($passedCases/$totalCases)，阈值=${"%5.1f%%".format(threshold*100)}" +
                if (subs.isNotEmpty()) "；子指标: $subs" else "" +
                if (failedCases.isNotEmpty()) "；失败用例: ${failedCases.take(5).joinToString()}${if (failedCases.size>5) "等"+failedCases.size+"个" else ""}" else ""
    }
}

interface PlannerBenchmark {
    val id: String
    val name: String
    val threshold: Double

    /**
     * 对给定的 Planner 输出（按用例 ID 索引）运行评测
     * @param outputs key = caseId, value = PlannerOutput（该用例对应的推理结果）
     */
    fun evaluate(outputs: Map<String, PlannerOutput>): BenchmarkResult
}
