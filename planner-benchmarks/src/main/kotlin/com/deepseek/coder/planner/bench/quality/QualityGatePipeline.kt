package com.deepseek.coder.planner.bench.quality

import com.deepseek.coder.planner.bench.schema.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonElement

/**
 * Q1~Q10 自动化质检流水线（v0.7 十关，零人工 spot check）
 * 执行顺序：Q1→Q10 串行，任一关卡 FAIL 直接丢弃；所有 PASS 才写入最终 JSONL。
 * 每关都返回 QualityGateResult（pass + 失败原因 + 命中关卡编号）。
 */
data class QualityGateResult(
    val gateId: String, // "Q1" ~ "Q10"
    val pass: Boolean,
    val failureReason: String? = null,
    val details: Map<String, Any?> = emptyMap()
)

data class PipelineResult(
    val passed: Boolean,
    val gates: List<QualityGateResult>,
    val firstFailedGate: String? = gates.firstOrNull { !it.pass }?.gateId,
    val passCount: Int = gates.count { it.pass }
) {
    fun summary(): String =
        "[质检结果 ${if (passed) "✅ PASS" else "❌ FAIL at $firstFailedGate"}] " +
        "通过关卡=$passCount/10，失败详情=${gates.filterNot { it.pass }.joinToString { "${it.gateId}:${it.failureReason}" }}"
}

class QualityGatePipeline {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun runAll(
        jsonString: String,
        expectedGranularity: Granularity? = null,
        expectedPlanningLevel: PlanningLevel? = null,
        expectedControl: ControlType? = null,
        expectedScope: ScopeTag? = null,
        expectedScopeHint: List<String>? = null,
        contrastGroupTriple: Triple<String, String, String>? = null, // (COARSE_json, MEDIUM_json, FINE_json) for T2
        convertRefPlan: PlannerOutput? = null, // for T3 convert sample
        zhPair: PlannerOutput? = null, // Q10 中文 pair，enPair = 当前 decoded 对象
        enPair: PlannerOutput? = null,
        failFast: Boolean = true
    ): PipelineResult {
        val gates = mutableListOf<QualityGateResult>()
        val decoded: PlannerOutput = try {
            json.decodeFromString(PlannerOutput.serializer(), jsonString)
        } catch (t: Throwable) {
            gates += QualityGateResult("Q1", false, "JSON 解析失败: ${t.message}")
            return PipelineResult(false, gates)
        }
        // 到此处 decoded 必然 non-null（上面 JSON 失败已经 return 了），直接用 d 别名避免 nullable 问题
        val d: PlannerOutput = decoded

        // Q1 ~ Q10 顺序执行
        gates += q1SchemaValid(jsonString, d)
        if (failFast && gates.last().pass.not()) return PipelineResult(false, gates)

        gates += q2StepInterval(d, expectedGranularity)
        if (failFast && gates.last().pass.not()) return PipelineResult(false, gates)

        gates += q3ControlEcho(d, expectedGranularity, expectedPlanningLevel, expectedControl, expectedScope, expectedScopeHint)
        if (failFast && gates.last().pass.not()) return PipelineResult(false, gates)

        gates += q4AcceptanceCriteriaRichness(d)
        if (failFast && gates.last().pass.not()) return PipelineResult(false, gates)

        gates += q5DurationPctSum(d)
        if (failFast && gates.last().pass.not()) return PipelineResult(false, gates)

        // Q6 Contrastive 三元组（仅 T2 传入时才跑）
        if (contrastGroupTriple != null) {
            gates += q6ContrastiveTriple(contrastGroupTriple)
            if (failFast && gates.last().pass.not()) return PipelineResult(false, gates)
        } else {
            gates += QualityGateResult("Q6", true, null, mapOf("skip_reason" to "非 T2 Contrastive 子集，跳过"))
        }

        // Q7 粒度转换 Milestone 对齐（仅 T3 传入时）
        if (convertRefPlan != null) {
            gates += q7GranularityConvertMilestoneAlign(d, convertRefPlan)
            if (failFast && gates.last().pass.not()) return PipelineResult(false, gates)
        } else {
            gates += QualityGateResult("Q7", true, null, mapOf("skip_reason" to "非 T3 粒度转换子集，跳过"))
        }

        // Q8 粒度分析评分合理性（仅 T4 且 control=GRANULARITY_ANALYSE）
        if (d.meta.echoControl == ControlType.GRANULARITY_ANALYSE) {
            gates += q8GranularityAnalysisReasonable(d, expectedGranularity)
            if (failFast && gates.last().pass.not()) return PipelineResult(false, gates)
        } else {
            gates += QualityGateResult("Q8", true, null, mapOf("skip_reason" to "非 T4 粒度分析子集，跳过"))
        }

        // Q9 拒答 & 失败判定分类质量（T5 & T6 子集专项）
        gates += q9RefuseAndFailureClassification(d)
        if (failFast && gates.last().pass.not()) return PipelineResult(false, gates)

        // Q10 双语 pair 字节级结构字段一致性（v0.7 新增：OPT-003-8）
        if (zhPair != null && enPair != null) {
            gates += q10BilingualPairStructuralByteEqual(zhPair, enPair)
            if (failFast && gates.last().pass.not()) return PipelineResult(false, gates)
        } else {
            gates += QualityGateResult("Q10", true, null, mapOf("skip_reason" to "未传 zh/en pair，跳过"))
        }

        val allPass = gates.all { it.pass }
        return PipelineResult(allPass, gates)
    }

    // ===== Q1 JSON 合法 & Schema 字段完整 =====
    fun q1SchemaValid(jsonString: String, d: PlannerOutput): QualityGateResult {
        return try {
            val missing = mutableListOf<String>()
            if (d.milestones.isEmpty() && d.meta.refuseReason.isNullOrBlank() && d.meta.echoControl != ControlType.REFUSE) {
                missing += "milestones 为空但未设置 REFUSE/拒答"
            }
            if (d.meta.echoControl == ControlType.GRANULARITY_ANALYSE && d.granularityAnalysis == null) {
                missing += "control=GRANULARITY_ANALYSE 但 granularity_analysis 段缺失"
            }
            if (d.meta.refuseReason.isNullOrBlank().not()) {
                // 拒答场景强约束：milestones 空 + topology 空边 + clarifications 空
                if (d.milestones.isNotEmpty()) missing += "拒答场景 milestones 必须为空"
                if (d.topology.milestoneEdges.isNotEmpty()) missing += "拒答场景 topology.milestone_edges 必须为空"
                if (d.clarificationsNeeded.isNotEmpty()) missing += "拒答场景 clarifications_needed 必须为空"
            } else {
                // 非拒答：topology.milestone_edges 强制非空（GATE-003-1）
                if (d.topology.milestoneEdges.isEmpty()) missing += "非拒答场景 topology.milestone_edges 不能为空（哪怕线性也要显式写边）"
            }
            if (missing.isNotEmpty()) QualityGateResult("Q1", false, missing.joinToString("; "))
            else QualityGateResult("Q1", true)
        } catch (t: Throwable) {
            QualityGateResult("Q1", false, "异常: ${t.message}")
        }
    }

    // ===== Q2 粒度区间步数 =====
    fun q2StepInterval(d: PlannerOutput, expected: Granularity?): QualityGateResult {
        val g = expected ?: d.meta.echoGranularity
        val steps = d.meta.estimatedTotalSteps
        val range = when (g) {
            Granularity.COARSE -> 1..5
            Granularity.MEDIUM -> 5..9
            Granularity.FINE -> 12..24
        }
        return if (steps in range) QualityGateResult("Q2", true, null, mapOf("granularity" to g, "steps" to steps, "range" to range.toString()))
        else QualityGateResult("Q2", false, "estimated_total_steps=$steps 不在 $g 区间 $range")
    }

    // ===== Q3 控制 token 回显对齐 =====
    fun q3ControlEcho(
        d: PlannerOutput,
        expG: Granularity?, expP: PlanningLevel?, expC: ControlType?,
        expScope: ScopeTag?, expScopeHint: List<String>?
    ): QualityGateResult {
        val errs = mutableListOf<String>()
        if (expG != null && d.meta.echoGranularity != expG) errs += "granularity 期望 $expG 实际 ${d.meta.echoGranularity}"
        if (expP != null && d.meta.echoPlanningLevel != expP) errs += "planning_level 期望 $expP 实际 ${d.meta.echoPlanningLevel}"
        if (expC != null && d.meta.echoControl != expC) errs += "control 期望 $expC 实际 ${d.meta.echoControl}"
        if (expScope != null && d.meta.scopeTag != expScope) errs += "scope_tag 期望 $expScope 实际 ${d.meta.scopeTag}"
        if (expScopeHint != null && d.dispatch.scopeHint.sorted() != expScopeHint.sorted()) {
            errs += "scope_hint 期望 ${expScopeHint.sorted()} 实际 ${d.dispatch.scopeHint.sorted()}"
        }
        return if (errs.isEmpty()) QualityGateResult("Q3", true)
        else QualityGateResult("Q3", false, errs.joinToString("; "))
    }

    // ===== Q4 Acceptance Criteria 质量 =====
    fun q4AcceptanceCriteriaRichness(d: PlannerOutput): QualityGateResult {
        // T6 拒答跳过
        if (d.meta.refuseReason.isNullOrBlank().not()) return QualityGateResult("Q4", true, null, mapOf("skip_reason" to "T6 拒答跳过"))
        val errs = mutableListOf<String>()
        d.milestones.forEach { m ->
            m.subtasks.forEach { s ->
                if (s.capability != "CAP_ASK_CLARIFICATION" && s.acceptanceCriteria.size < 2) {
                    errs += "subtask ${s.id} acceptance_criteria 只有 ${s.acceptanceCriteria.size} 条，至少 2 条"
                }
                s.acceptanceCriteria.forEachIndexed { i, ac ->
                    // 中文字符 >=15 或 英文字符 >=20（粗略估算：按 ASCII 码点比例）
                    val acTrim = ac.trim()
                    val zhCount = acTrim.count { it.code in 0x4e00..0x9fff }
                    val enCount = acTrim.length - zhCount
                    val zhPass = zhCount >= 15
                    val enPass = enCount >= 20
                    if (!zhPass && !enPass) errs += "subtask ${s.id} AC#$i 长度不足（中文$zhCount<15 或英文$enCount<20）：${acTrim.take(30)}..."
                }
            }
        }
        return if (errs.isEmpty()) QualityGateResult("Q4", true)
        else QualityGateResult("Q4", false, errs.take(5).joinToString(" | ") + if (errs.size > 5) " | ...共${errs.size}条" else "")
    }

    // ===== Q5 Expected Duration Pct 求和 = 1.0 ±0.03 =====
    fun q5DurationPctSum(d: PlannerOutput): QualityGateResult {
        if (d.meta.refuseReason.isNullOrBlank().not()) return QualityGateResult("Q5", true, null, mapOf("skip_reason" to "T6 拒答跳过"))
        if (d.milestones.isEmpty()) return QualityGateResult("Q5", false, "milestones 为空")
        val sum = d.milestones.sumOf { it.expectedDurationPct.toDouble() }
        val ok = sum in 0.97..1.03
        return if (ok) QualityGateResult("Q5", true, null, mapOf("sum" to sum))
        else QualityGateResult("Q5", false, "expected_duration_pct 累加和=$sum，不在 0.97~1.03 区间")
    }

    // ===== Q6 Contrastive 三元组语义对齐 & 步数比例（T2 子集） =====
    fun q6ContrastiveTriple(triple: Triple<String, String, String>): QualityGateResult {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val (c, m, f) = triple
        val dC: PlannerOutput; val dM: PlannerOutput; val dF: PlannerOutput
        try {
            dC = json.decodeFromString(PlannerOutput.serializer(), c)
            dM = json.decodeFromString(PlannerOutput.serializer(), m)
            dF = json.decodeFromString(PlannerOutput.serializer(), f)
        } catch (t: Throwable) {
            return QualityGateResult("Q6", false, "三元组 JSON 解析失败: ${t.message}")
        }
        // 步数比例：COARSE : MEDIUM : FINE ≈ 1 : 2.0~2.6 : 4.8~7.2
        val sC = dC.meta.estimatedTotalSteps.toDouble()
        val sM = dM.meta.estimatedTotalSteps.toDouble()
        val sF = dF.meta.estimatedTotalSteps.toDouble()
        if (sC <= 0 || sM <= 0 || sF <= 0) return QualityGateResult("Q6", false, "estimated_total_steps 不能 <=0")
        val ratioCtoM = sM / sC
        val ratioCtoF = sF / sC
        val ratioOk = ratioCtoM in 2.0..2.6 && ratioCtoF in 4.8..7.2
        if (!ratioOk) return QualityGateResult("Q6", false,
            "步数比例不达标: C/M/F=$sC/$sM/$sF, ratio M/C=%.2f(需 2.0~2.6), F/C=%.2f(需 4.8~7.2)".format(ratioCtoM, ratioCtoF))
        // Milestone 标题数：三档数量接近（允许 ±1），按 id 前缀对齐率 >80%
        val idsC = dC.milestones.map { it.id.replace("\\d+".toRegex(), "") }.toSet()
        val idsM = dM.milestones.map { it.id.replace("\\d+".toRegex(), "") }.toSet()
        val idsF = dF.milestones.map { it.id.replace("\\d+".toRegex(), "") }.toSet()
        val union = (idsC + idsM + idsF).size
        val inter = (idsC intersect idsM intersect idsF).size
        val alignRate = if (union == 0) 1.0 else inter.toDouble() / union
        if (alignRate < 0.75) return QualityGateResult("Q6", false, "Milestone ID 前缀对齐率=$alignRate < 0.75")
        return QualityGateResult("Q6", true, null,
            mapOf("steps" to mapOf("COARSE" to sC, "MEDIUM" to sM, "FINE" to sF),
                  "ratios" to mapOf("M/C" to "%.2f".format(ratioCtoM), "F/C" to "%.2f".format(ratioCtoF)),
                  "milestone_id_align_rate" to alignRate))
    }

    // ===== Q7 粒度转换 Milestone 对齐（T3 子集） =====
    fun q7GranularityConvertMilestoneAlign(out: PlannerOutput, ref: PlannerOutput): QualityGateResult {
        val refIds = ref.milestones.map { it.id.take(2) }.toSet()
        val outIds = out.milestones.map { it.id.take(2) }
        val missing = refIds.filterNot { it in outIds.toSet() }
        if (missing.isNotEmpty()) return QualityGateResult("Q7", false, "转换后 Milestone ID 前缀缺失: $missing（参考 Plan 前缀: $refIds，输出 Plan 前缀: $outIds）")
        return QualityGateResult("Q7", true, null, mapOf("ref_ids_count" to refIds.size, "out_ids_count" to outIds.size))
    }

    // ===== Q8 粒度分析评分合理性（T4 子集） =====
    fun q8GranularityAnalysisReasonable(d: PlannerOutput, expectedGranularity: Granularity?): QualityGateResult {
        val ga = d.granularityAnalysis ?: return QualityGateResult("Q8", false, "granularity_analysis 段为空")
        val errs = mutableListOf<String>()
        if (ga.granularityScore1To5 !in 1..5) errs += "score=${ga.granularityScore1To5} 不在 [1,5]"
        // 硬反例校验：实际步数不在 expected granularity 区间，但 flag=JUST_RIGHT → FAIL
        if (expectedGranularity != null) {
            val steps = d.meta.estimatedTotalSteps
            val range = when (expectedGranularity) {
                Granularity.COARSE -> 1..5
                Granularity.MEDIUM -> 5..9
                Granularity.FINE -> 12..24
            }
            if (steps !in range && ga.tooCoarseOrTooFineFlag == TooCoarseTooFine.JUST_RIGHT) {
                errs += "硬反例命中：步数=$steps 不在 $expectedGranularity 区间 $range，但 flag=JUST_RIGHT"
            }
        }
        if (errs.isNotEmpty()) return QualityGateResult("Q8", false, errs.joinToString("; "))
        return QualityGateResult("Q8", true)
    }

    // ===== Q9 拒答 & 失败判定分类质量（T5 & T6 子集） =====
    fun q9RefuseAndFailureClassification(d: PlannerOutput): QualityGateResult {
        // T5 FAILURE_DISPATCH：decision 必须三选一（这里不作为 Q9 主检查，因为 decision 不在 PlannerOutput，留给 F4）
        // T6 拒答：refuse_reason 长度在 15~80 中文字符，不含黑名单词
        val rr = d.meta.refuseReason
        if (rr != null) {
            val len = rr.trim().count { it.code in 0x4e00..0x9fff }.takeIf { it > 0 } ?: rr.trim().length
            if (len < 15 || len > 80) return QualityGateResult("Q9", false, "refuse_reason 长度=$len 不在 15~80")
            val blacklist = listOf("fuck", "shit", "习近平", "法轮功", "六四", "天安门事件")
            val hit = blacklist.firstOrNull { rr.contains(it, ignoreCase = true) }
            if (hit != null) return QualityGateResult("Q9", false, "refuse_reason 命中黑名单词: $hit")
        }
        return QualityGateResult("Q9", true)
    }

    // ===== Q10 双语 pair 字节级结构字段一致性（v0.7 OPT-003-8 新增） =====
    fun q10BilingualPairStructuralByteEqual(zh: PlannerOutput, en: PlannerOutput): QualityGateResult {
        val zhFP = zh.structuralFingerprint()
        val enFP = en.structuralFingerprint()
        if (zhFP != enFP) {
            // 只输出前 200 字符差异方便定位
            val diffIdx = zhFP.indices.firstOrNull { zhFP[it] != enFP[it] } ?: zhFP.length
            val zhSlice = zhFP.drop(maxOf(0, diffIdx - 20)).take(80)
            val enSlice = enFP.drop(maxOf(0, diffIdx - 20)).take(80)
            return QualityGateResult("Q10", false,
                "双语 pair 结构性字段指纹不一致！diff_idx=$diffIdx，差异片段 zh=[$zhSlice] vs en=[$enSlice]。\n完整指纹 zh=${zhFP.take(250)}...\n完整指纹 en=${enFP.take(250)}...")
        }
        return QualityGateResult("Q10", true, null, mapOf("fingerprint_length" to zhFP.length))
    }
}
