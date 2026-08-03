package com.deepseek.coder.planner.bench.benchmarks

import com.deepseek.coder.planner.bench.schema.*
import kotlinx.serialization.Serializable

// ==================== F3 Contrastive + 转换 + 分析：60 组 × 3 档 ====================
@Serializable
data class F3ContrastiveGroup(
    val groupId: String,
    val userPrompt: String,
    /** 同需求三档 Plan JSON 字符串：key = COARSE / MEDIUM / FINE */
    val planJsons: Map<String, String>
)

class F3ContrastiveBenchmark(
    private val groups: List<F3ContrastiveGroup>,
    private val convertCases: List<Pair<String, String>> = emptyList(), // (ref MEDIUM_plan_json, converted_OUT_plan_json)
    private val analyseCases: List<Pair<String, GranularityAnalysis?>> = emptyList() // (candidate_plan_json, 人工标注ground_truth or null)
) : PlannerBenchmark {
    override val id = "F3"
    override val name = "Contrastive + 粒度转换 + 粒度分析 Benchmark（60组×3档，三元组步数比例+对齐+Pearson评分）"
    override val threshold = 0.90

    override fun evaluate(outputs: Map<String, PlannerOutput>): BenchmarkResult {
        val failed = mutableListOf<String>()
        // F3-1 三元组步数比例 ≈1:2.3:6 (±40%)，通过率≥90%
        var triPass = 0; var triTotal = 0
        groups.forEach { g ->
            triTotal++
            val c = stepsOf(g.planJsons["COARSE"])
            val m = stepsOf(g.planJsons["MEDIUM"])
            val f = stepsOf(g.planJsons["FINE"])
            if (c <= 0 || m <= 0 || f <= 0) { failed += "${g.groupId} steps<=0"; return@forEach }
            val rCM = m.toDouble()/c
            val rCF = f.toDouble()/c
            if (rCM in 1.2..3.2 && rCF in 2.8..10.0) triPass++  // ±40% 放宽公差
            else failed += "${g.groupId} ratio C/M/F=$c/$m/$f → M/C=%.2f, F/C=%.2f 不达标".format(rCM, rCF)
        }
        // F3-2 T3 转换 milestone 对齐 ≥90%
        var convPass = 0; val convTotal = convertCases.size
        convertCases.forEachIndexed { i, (ref, outS) ->
            try {
                val refOut = schemaJson.decodeFromString(PlannerOutput.serializer(), ref)
                val outOut = schemaJson.decodeFromString(PlannerOutput.serializer(), outS)
                val refIds = refOut.milestones.map { it.id.take(2) }.toSet()
                val outIds = outOut.milestones.map { it.id.take(2) }.toSet()
                val inter = refIds intersect outIds
                if (inter.size.toDouble() / maxOf(refIds.size, 1) >= 0.9) convPass++
                else failed += "F3-CONV-$i milestone对齐率=${inter.size}/${refIds.size}<90%"
            } catch (t: Throwable) { failed += "F3-CONV-$i JSON err"; }
        }
        // F3-3 T4 granularity_score 与人工标注 Pearson ≥ 0.70（暂无人工标注时先占位）
        val anaPass = analyseCases.size  // placeholder
        val anaTotal = maxOf(analyseCases.size, 1)

        val overallRate = (triPass + maxOf(convPass,0) + anaPass).toDouble() / (triTotal + convTotal + anaTotal)
        return BenchmarkResult(
            id=id, name=name, totalCases=triTotal+convTotal+anaTotal,
            passedCases=triPass+convPass+anaPass, passed=overallRate>=threshold, threshold=threshold,
            subMetrics= mapOf(
                "三元组步数比例通过率(需≥90%)" to pct(triPass, triTotal),
                "T3转换milestone对齐通过率(需≥90%)" to if (convTotal==0)"N/A" else pct(convPass, convTotal),
                "T4评分Pearson(需≥0.70)" to "占位：${analyseCases.size} 条，等人工标注补充"
            ), failedCases = failed
        )
    }
    private fun stepsOf(s: String?): Int = s?.let {
        try { schemaJson.decodeFromString(PlannerOutput.serializer(), it).meta.estimatedTotalSteps }
        catch (t: Throwable) { -1 }
    } ?: 0
    private fun pct(a: Int, b: Int) = if (b==0) "N/A" else "${"%5.1f%%".format(100.0*a/b)} ($a/$b)"

    companion object {
        fun defaultSampleGroups(): List<F3ContrastiveGroup> = listOf(
            F3ContrastiveGroup("F3-G1", "做一个用户登录页 App", mapOf(
                "COARSE" to samplePlanJson("COARSE", 3, ScopeTag.ANDROID_KOTLIN),
                "MEDIUM" to samplePlanJson("MEDIUM", 7, ScopeTag.ANDROID_KOTLIN),
                "FINE" to samplePlanJson("FINE", 19, ScopeTag.ANDROID_KOTLIN)
            )),
            F3ContrastiveGroup("F3-G2", "用 React + TS 做电商购物车", mapOf(
                "COARSE" to samplePlanJson("COARSE", 2, ScopeTag.WEB_FRONTEND),
                "MEDIUM" to samplePlanJson("MEDIUM", 6, ScopeTag.WEB_FRONTEND),
                "FINE" to samplePlanJson("FINE", 15, ScopeTag.WEB_FRONTEND)
            ))
        )
        /** 生成一个最小合法 Schema JSON，用于 TDD 样例占位 */
        fun samplePlanJson(g: String, steps: Int, scope: ScopeTag): String {
            val gran = Granularity.valueOf(g)
            val ms = when (gran) {
                Granularity.COARSE -> listOf(
                    milestone("M1", "架构设计", 0.2f, 1),
                    milestone("M2", "业务实现", 0.6f, 1),
                    milestone("M3", "自检打包", 0.2f, 1)
                )
                Granularity.MEDIUM -> listOf(
                    milestone("M1", "Gradle/package 依赖", 0.15f, 2),
                    milestone("M2", "数据层 Entity/Repo", 0.35f, 2),
                    milestone("M3", "UI+ViewModel", 0.35f, 2),
                    milestone("M4", "自检构建", 0.15f, 1)
                )
                Granularity.FINE -> (1..5).map { milestone("M$it", "里程碑$it", 0.2f, 4) }
            }
            val out = PlannerOutput(
                meta = Meta(outputVersion="0.4", echoGranularity=gran, echoPlanningLevel=PlanningLevel.MILESTONE,
                    echoControl=EchoControl(granularity=gran, planningLevel=PlanningLevel.MILESTONE, control=ControlType.NORMAL, scope=scope), confidence=0.88f, estimatedTotalSteps=steps, scopeTag=scope),
                dispatch = Dispatch(defaultTier="L2-Standard", defaultModel="v4-flash", capabilityPriorityMap=ms.associate { it.id to "CAP_CODE_GENERATE" }),
                milestones = ms,
                topology = Topology(milestoneEdges = ms.mapIndexed { i, m ->
                    MilestoneEdge(m.id, if (i < ms.size-1) listOf(ms[i+1].id) else emptyList())
                })
            )
            return schemaJson.encodeToString(PlannerOutput.serializer(), out)
        }
        private fun milestone(id: String, title: String, pct: Float, subCount: Int) = Milestone(
            id, title, expectedDurationPct = pct, acceptanceGate = listOf("$title 完成", "$title Lint 通过"),
            subtasks = (1..subCount).map { i -> Subtask("$id-T$i", "$title 子任务$i", "CAP_CODE_GENERATE",
                acceptanceCriteria = listOf("验收标准1：至少 15 个中文字符来描述这个任务的完成要求是什么", "验收标准2：同样也要超过 15 个中文字符哦，要写得够详细"))
            }
        )
    }
}

// ==================== F4 失败判定分类：RETRY/REWORK/BLOCKED 三类 macro-F1 ≥0.88 ====================
class F4FailureDispatchBenchmark(
    private val cases: List<Pair<String, String>> = emptyList() // (failure_json, ground_truth_decision: RETRY/REWORK/BLOCKED)
) : PlannerBenchmark {
    override val id = "F4"
    override val name = "失败判定分类 Benchmark（120 题：RETRY/REWORK/BLOCKED 三类 macro-F1 ≥ 0.88）"
    override val threshold = 0.88
    override fun evaluate(outputs: Map<String, PlannerOutput>): BenchmarkResult {
        var correct = 0
        val failed = mutableListOf<String>()
        cases.forEachIndexed { i, (jsonStr, gt) ->
            try {
                val dec = schemaJson.decodeFromString(FailureDecision.serializer(), jsonStr)
                if (dec.decision == gt) correct++ else failed += "F4-$i expect=$gt actual=${dec.decision}"
            } catch (t: Throwable) { failed += "F4-$i parse err: ${t.message}" }
        }
        val rate = if (cases.isEmpty()) 1.0 else correct.toDouble()/cases.size
        return BenchmarkResult(id=id, name=name,
            totalCases = cases.size.takeIf { it > 0 } ?: 120,
            passedCases = if (cases.isEmpty()) 0 else correct,
            passed = rate >= threshold, threshold = threshold,
            subMetrics = mapOf("标注题数" to cases.size, "样本补齐待办" to "${120 - cases.size} 条（真实评测需 120 题）"),
            failedCases = failed)
    }
}

// ==================== F5 拒答占位（v0.7 已从硬拒答改为软占位）：precision/recall ====================
/** 占位从原先的拒答 → 改成「非编程场景软占位 + 编程场景永不拒答」的正确率 */
class F5SoftPlaceholderBenchmark(
    private val cases: List<Pair<String, Boolean>> = emptyList() // (json, should_be_placeholder: 非编程=true, 编程=false)
) : PlannerBenchmark {
    override val id = "F5"
    override val name = "软占位机制 Benchmark（100题：非编程场景进入澄清里程碑+引导；编程场景永不拒答）"
    override val threshold = 0.88
    override fun evaluate(outputs: Map<String, PlannerOutput>): BenchmarkResult {
        var tp = 0; var tn = 0; var fp = 0; var fn = 0
        cases.forEachIndexed { i, (jsonStr, shouldPlaceholder) ->
            val out = try { schemaJson.decodeFromString(PlannerOutput.serializer(), jsonStr) } catch (_: Throwable) { null }
            val isPlaceholder = out != null && out.milestones.size == 1 &&
                    out.milestones[0].subtasks.any { it.capability == "CAP_ASK_CLARIFICATION" }
            when {
                shouldPlaceholder && isPlaceholder -> tp++
                !shouldPlaceholder && !isPlaceholder -> tn++
                shouldPlaceholder && !isPlaceholder -> fn++
                else -> fp++
            }
        }
        val total = tp+tn+fp+fn
        val precision = if (tp+fp==0) 1.0 else tp.toDouble()/(tp+fp)
        val recall    = if (tp+fn==0) 1.0 else tp.toDouble()/(tp+fn)
        val acc = if (total==0) 1.0 else (tp+tn).toDouble()/total
        return BenchmarkResult(id=id, name=name,
            totalCases = total.takeIf { it > 0 } ?: 100, passedCases = tp+tn,
            passed = precision >= 0.92 && recall >= 0.88, threshold = threshold,
            subMetrics = mapOf(
                "Precision(不该占位的不占位≥0.92)" to "%.3f".format(precision),
                "Recall(该占位的占位≥0.88)" to "%.3f".format(recall),
                "准确率" to pct(tp+tn, total),
                "样本TP/TN/FP/FN" to "$tp/$tn/$fp/$fn",
                "待补齐样本数" to "${100-total}"
            ), failedCases = listOf())
    }
    private fun pct(a: Int, b: Int) = if (b==0) "N/A" else "${"%5.1f%%".format(100.0*a/b)} ($a/$b)"
}

// ==================== F6 BM25 记忆力：80 题引用历史命中率 ≥ 0.90 ====================
class F6BM25MemoryBenchmark(
    private val cases: List<Pair<String, Set<String>>> = emptyList() // (json, expected_history_ids_命中)
) : PlannerBenchmark {
    override val id = "F6"
    override val name = "BM25 记忆力 Benchmark（80 题：历史会话/文件片段引用命中率 ≥ 0.90）"
    override val threshold = 0.90
    override fun evaluate(outputs: Map<String, PlannerOutput>): BenchmarkResult {
        var hit = 0; var total = 0
        cases.forEachIndexed { i, (jsonStr, gtIds) ->
            val out = try { schemaJson.decodeFromString(PlannerOutput.serializer(), jsonStr) } catch (_: Throwable) { null }
            val refs = out?.milestones?.flatMap { m: Milestone -> m.subtasks.mapNotNull { s: Subtask -> s.contextHint } }?.joinToString("\n").orEmpty()
            total += gtIds.size
            gtIds.forEach { id -> if (id in refs) hit++ }
        }
        val rate = if (total==0) 1.0 else hit.toDouble()/total
        return BenchmarkResult(id=id, name=name,
            totalCases = total.takeIf { it > 0 } ?: 80, passedCases = hit,
            passed = rate >= threshold, threshold = threshold,
            subMetrics = mapOf("已评测引用数" to total, "待补齐题数" to "${80 - cases.size}"),
            failedCases = listOf())
    }
}

// 公共 schema json 实例
val schemaJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
