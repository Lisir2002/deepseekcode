package com.deepseek.coder.planner.bench

import com.deepseek.coder.planner.bench.benchmarks.*
import com.deepseek.coder.planner.bench.quality.PipelineResult
import com.deepseek.coder.planner.bench.quality.QualityGatePipeline
import com.deepseek.coder.planner.bench.schema.*
import kotlinx.serialization.encodeToString

/**
 * 统一入口：跑 F1~F6 全部六套评测 + 演示 Q1~Q10 质检流水线
 * 用法：./gradlew :planner-benchmarks:run
 *       或：java -jar planner-benchmarks/build/libs/planner-benchmarks.jar
 */
fun main(args: Array<String>) {
    println("=" .repeat(80))
    println("🚀 DeepCoder Planner · Benchmark Runner v0.7（TDD 阶段：先锁标准，再训模型）")
    println("   规格阈值：F1 Scope≥92% F2≥80% F3≥90% F4≥0.88 F5 precision≥0.92+recall≥0.88 F6≥0.90，Q1~Q10 零人工")
    println("=" .repeat(80))

    // ========== Step 0: 先演示 Q1~Q10 质检流水线（用 F3 样例的最小合法 Plan JSON）==========
    println("\n🧪 [Step 0] Q1~Q10 质检流水线 Demo")
    println("-".repeat(80))
    val jsonSample = F3ContrastiveBenchmark.samplePlanJson("MEDIUM", 7, ScopeTag.WEB_FRONTEND)
    val pipeline = QualityGatePipeline()
    val qResult: PipelineResult = pipeline.runAll(
        jsonString = jsonSample,
        expectedGranularity = Granularity.MEDIUM,
        expectedPlanningLevel = PlanningLevel.MILESTONE,
        expectedControl = ControlType.NORMAL,
        expectedScope = ScopeTag.WEB_FRONTEND,
        failFast = false
    )
    println(qResult.summary())
    qResult.gates.forEach { g ->
        val mark = if (g.pass) "✅" else "❌"
        val reason = if (g.pass) "" else " → ${g.failureReason}"
        println("  $mark ${g.gateId.padEnd(3)} ${reason.take(120)}")
    }
    check(qResult.passed) { "样例 JSON 质检未通过，说明 Q1~Q10 质检代码有 bug：${qResult.summary()}" }
    println("  🎉 Q1~Q10 质检流水线工作正常，样例 Plan JSON 全 10 关通过")

    // ========== Step 1: F1 决策质量 Demo ==========
    println("\n🧪 [Step 1] F1 决策质量 Benchmark（TDD 阶段：用样例 Plan JSON 当占位，真实评测替换为 LoRA 输出）")
    println("-".repeat(80))
    val f1Cases = F1DecisionQualityBenchmark.defaultSampleCases()
    // TDD 占位：给每个 case 生成一个样例合法 JSON，真实上线后替换为 LoRA Planner 输出
    val f1Outputs = f1Cases.associate { c ->
        c.caseId to com.deepseek.coder.planner.bench.benchmarks.schemaJson.decodeFromString(
            PlannerOutput.serializer(),
            F3ContrastiveBenchmark.samplePlanJson(c.expectedGranularity.name, c.stepsRange.first, c.expectedScope)
        ).let { base ->
            // 把第一个 subtask 的 capability 改成 expectedMainCapability，让 F1 的 CAP 分类判定通过占位
            val ms0Fixed = base.milestones[0].copy(subtasks = base.milestones[0].subtasks.mapIndexed { i, s ->
                s.copy(capability = if (i == 0) c.expectedMainCapability else s.capability)
            })
            val clarIfNeed = if (c.expectedClarityNeeded) listOf(
                Clarification("CL1", listOf("M1"), "请确认版本信息：TS/React/Node 版本？",
                    listOf("TS 5.x + React 18 + Node 20", "TS 4.x + React 17 + Node 18"), "TS 5.x + React 18 + Node 20")
            ) else emptyList()
            val confFixed = if (c.expectedLowConfidence) 0.55f else 0.88f
            base.copy(
                meta = base.meta.copy(
                    scopeTag = c.expectedScope,
                    confidence = confFixed,
                    needsUserConfirmation = c.expectedClarityNeeded,
                    estimatedTotalSteps = c.stepsRange.first
                ),
                dispatch = base.dispatch.copy(scopeHint = c.expectedScopeHint,
                    capabilityPriorityMap = (base.dispatch.capabilityPriorityMap.keys.take(1) + listOf(ms0Fixed.id)).associateWith { c.expectedMainCapability }),
                milestones = listOf(ms0Fixed) + base.milestones.drop(1),
                clarificationsNeeded = clarIfNeed
            )
        }
    }
    val f1Result = F1DecisionQualityBenchmark(f1Cases).evaluate(f1Outputs)
    println(f1Result.summary())
    f1Result.subMetrics.forEach { (k, v) -> println("   ➤ $k = $v") }
    if (f1Result.failedCases.isNotEmpty()) println("   ⚠️ 失败详情(前5): ${f1Result.failedCases.take(5).joinToString(" | ")}")

    // ========== Step 2: F2 端到端编译 Demo ==========
    println("\n🧪 [Step 2] F2 端到端编译 Benchmark（TDD 阶段：弱校验 scope/steps；LoRA 上线后替换为真实构建命令）")
    println("-".repeat(80))
    val f2Cases = F2EndToEndBuildBenchmark.defaultSampleCases()
    val f2Outputs = f2Cases.associate { c ->
        c.caseId to com.deepseek.coder.planner.bench.benchmarks.schemaJson.decodeFromString(
            PlannerOutput.serializer(),
            F3ContrastiveBenchmark.samplePlanJson("MEDIUM", c.expectedStepsRange.first,
                when (c.scope) {
                    "ANDROID" -> ScopeTag.ANDROID_KOTLIN
                    "WEB_FRONTEND" -> ScopeTag.WEB_FRONTEND
                    else -> ScopeTag.GENERAL
                }
            )
        )
    }
    val f2Result = F2EndToEndBuildBenchmark(f2Cases).evaluate(f2Outputs)
    println(f2Result.summary())
    f2Result.subMetrics.forEach { (k, v) -> println("   ➤ $k = $v") }

    // ========== Step 3: F3~F6 Demo ==========
    println("\n🧪 [Step 3] F3/F4/F5/F6 四套 Benchmark（TDD 占位，等真实 LoRA 输出 + 人工标注补齐）")
    println("-".repeat(80))
    val f3Groups = F3ContrastiveBenchmark.defaultSampleGroups()
    val f3Outs = emptyMap<String, PlannerOutput>()
    val f3Result = F3ContrastiveBenchmark(f3Groups).evaluate(f3Outs)
    println(f3Result.summary())
    val f4 = F4FailureDispatchBenchmark().evaluate(emptyMap())
    println(f4.summary())
    val f5 = F5SoftPlaceholderBenchmark().evaluate(emptyMap())
    println(f5.summary())
    val f6 = F6BM25MemoryBenchmark().evaluate(emptyMap())
    println(f6.summary())

    // ========== Step 4: Q10 双语 pair 结构指纹一致性 Demo ==========
    println("\n🧪 [Step 4] Q10 双语 pair 结构指纹一致性（v0.7 新增）Demo")
    println("-".repeat(80))
    val zhPlan = sampleBilingualPair(ScopeTag.ANDROID_KOTLIN, Granularity.MEDIUM, "中文标题不会影响指纹", "中文验收标准文字不会影响")
    val enPlan = sampleBilingualPair(ScopeTag.ANDROID_KOTLIN, Granularity.MEDIUM, "English title does not affect fingerprint", "English acceptance criteria text does not affect")
    val q10Result = pipeline.runAll(
        F3ContrastiveBenchmark.samplePlanJson("MEDIUM", 7, ScopeTag.ANDROID_KOTLIN),
        zhPair = zhPlan, enPair = enPlan, failFast = false
    )
    val q10Gate = q10Result.gates.first { it.gateId == "Q10" }
    println("  ${if (q10Gate.pass) "✅ PASS" else "❌ FAIL"} Q10 双语 pair 结构指纹一致性：" + (q10Gate.failureReason ?: "一致性校验通过，不同语言的自然语言外壳不影响结构性字段指纹"))

    // ========== 汇总 ==========
    println("\n" + "=".repeat(80))
    println("📊 TDD 阶段汇总：F1~F6 全部标准锁死 + Q1~Q10 质检流水线全 10 关通过，框架就绪")
    println("=" .repeat(80))
    val all = listOf(f1Result, f2Result, f3Result, f4, f5, f6)
    all.forEach { r ->
        val mark = if (r.passed) "✅" else "🟡"
        println("  $mark ${r.id.padEnd(2)} ${r.name.take(58).padEnd(60)} 通过率=${"%5.1f%%".format(r.passRate*100)}（阈值 ${"%5.1f%%".format(r.threshold*100)}）")
    }
    println("\n💡 下一步：等商务 LoRA 开通 → 跑 Step 1.1~1.5 合成脚本生成数据 → 提交 LoRA 训练 → 把 outputs Map 从占位样例替换为真实推理结果，F1~F6 的分数才有意义。")
}

/** 双语 pair 最小 Demo：除了中文/英文标题和 AC 文字不同，所有结构性字段完全一致 */
private fun sampleBilingualPair(scope: ScopeTag, g: Granularity, m1Title: String, acSuffix: String): PlannerOutput {
    return PlannerOutput(
        meta = Meta(outputVersion="0.4", echoGranularity = g, echoPlanningLevel = PlanningLevel.MILESTONE,
            echoControl = ControlType.NORMAL, confidence=0.88f, estimatedTotalSteps=7, scopeTag = scope),
        dispatch = Dispatch(defaultTier="L2-Standard", defaultModel="v4-flash",
            capabilityPriorityMap = mapOf("M1" to "CAP_CODE_GENERATE", "M2" to "CAP_CODE_GENERATE", "M3" to "CAP_RUN_SYNTAX_CHECK"),
            scopeHint = listOf("DEMO")),
        milestones = listOf(
            Milestone("M1", m1Title, expectedDurationPct = 0.4f,
                acceptanceGate = listOf("构建通过", "Lint 通过"),
                subtasks = listOf(
                    Subtask("M1-T1", "子任务 1", "CAP_CODE_GENERATE", acceptanceCriteria = listOf(
                        "这是 16 个汉字长度的第一条 AC$acSuffix",
                        "这是第二条也超过 15 字的验收条件$acSuffix")))),
            Milestone("M2", "第二个标题$acSuffix", expectedDurationPct = 0.4f,
                acceptanceGate = listOf("Gate 1", "Gate 2"),
                subtasks = listOf(
                    Subtask("M2-T1", "子任务 2", "CAP_CODE_GENERATE", acceptanceCriteria = listOf(
                        "第 1 条标准：需满足条件大于十五字$acSuffix",
                        "第 2 条标准：同样也要长度达标哦$acSuffix")))),
            Milestone("M3", "自检构建", expectedDurationPct = 0.2f,
                acceptanceGate = listOf("APK 生成成功"),
                subtasks = listOf(Subtask("M3-T1", "Gradle assembleDebug", "CAP_RUN_SYNTAX_CHECK", acceptanceCriteria = listOf(
                    "构建命令 exit code = 0 $acSuffix",
                    "没有任何 ERROR 级别日志$acSuffix"))))
        ),
        topology = Topology(milestoneEdges = listOf(
            MilestoneEdge("M1", listOf("M2")),
            MilestoneEdge("M2", listOf("M3")),
            MilestoneEdge("M3", emptyList())
        ))
    )
}
