package com.deepseek.coder.planner.bench.tools

import com.deepseek.coder.planner.bench.benchmarks.schemaJson
import com.deepseek.coder.planner.bench.quality.QualityGatePipeline
import com.deepseek.coder.planner.bench.schema.*
import com.deepseek.coder.planner.bench.synthetic.SyntheticPipeline
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.DecimalFormat
import kotlin.math.roundToInt

/**
 * P4-2a CLI：生成 2000 条 Planner pair → 导出 DeepSeek LoRA JSONL + 质检报告
 *
 * 用法：
 *   ./gradlew :planner-benchmarks:run -PmainClass=com.deepseek.coder.planner.bench.tools.GenerateLoraDataset \
 *     --args="--n 2000 --web-ratio 0.4 --seed 42 --out planner-benchmarks/build/lora"
 *
 * 输出：
 *   <out>/lora-train.jsonl       # 训练集：{"messages": [...]} 格式（DeepSeek 官方 LoRA 规范）
 *   <out>/lora-train-meta.json   # 样本分布 + Q1~Q10 通过率统计
 *   <out>/quality-report.csv     # 每行一条样本的 Q1~Q10 通过情况
 *   <out>/prompts-shuffled.txt   # 仅 prompt，方便人工抽查
 *   <out>/planner-outputs.jsonl  # 每条对应 PlannerOutput JSON，用于离线回放
 */
fun main(args: Array<String>) {
    val opts = Opts.parse(args)
    println("=== GenerateLoraDataset ===")
    println("    n=${opts.n}  web-ratio=${opts.webRatio}  seed=${opts.seed}")
    println("    out-dir=${opts.outDir.absolutePath}")

    opts.outDir.mkdirs()
    val pipeline = QualityGatePipeline()
    val synth = SyntheticPipeline(pipeline)

    // ---------- Step 1: 生成 ----------
    val t0 = System.currentTimeMillis()
    val pairs = synth.generate(n = opts.n, webRatio = opts.webRatio, seed = opts.seed)
    val tGen = System.currentTimeMillis() - t0
    check(pairs.size == opts.n) { "合成数量不匹配：期望 ${opts.n}，实际 ${pairs.size}" }

    // ---------- Step 2: 跑 Q1~Q10 质检，记录 ----------
    println("Step 2: Q1~Q10 质检 (n=${pairs.size}) ...")
    val gateNames = (1..10).map { "Q$it" }
    val gateCounts = IntArray(10)
    val qualityReport = StringBuilder().appendLine("id,scope,granularity,Q1,Q2,Q3,Q4,Q5,Q6,Q7,Q8,Q9,Q10,all_pass")
    var allPassCount = 0

    // ---------- Step 3: LoRA messages 格式 + planner-outputs ----------
    val loraFile = File(opts.outDir, "lora-train.jsonl").bufferedWriter()
    val planFile = File(opts.outDir, "planner-outputs.jsonl").bufferedWriter()
    val promptFile = File(opts.outDir, "prompts-shuffled.txt").bufferedWriter()

    // 按 DeepSeek Chat Completions 消息格式： system + user + assistant
    // system: Planner 0.4 契约 + ControlToken 强约束
    // user  : 用户原始需求 + 控制 token 块（粒度 + Scope + PlanningLevel）
    // assistant: JSON 模式输出 — PlannerOutput schema 0.4
    val systemPrompt = """
        |You are DeepCoder Planner v0.4, a workflow-orchestration small model.
        |Schema: PlannerOutput JSON v0.4 (ScopeTag 3-class, Granularity 3档, ControlToken echo).
        |Rules:
        |  1. ALWAYS output strictly JSON, NO markdown fences.
        |  2. Scope auto-classify: ANDROID_KOTLIN / WEB_FRONTEND / GENERAL.
        |  3. Granularity steps range: COARSE [3..7] / MEDIUM [5..9] / FINE [10..22].
        |  4. ALWAYS echo meta.echo_control = {"granularity","planning_level","control","scope"}.
        |  5. Subtask acceptance_criteria >= 15 Chinese chars each, milestone duration_pct sum == 100.
        |  6. Non-code / dangerous / legal-risk → classify CLARIFY + clarifications_needed NOT empty.
        |Output language: FOLLOW user's language preference tag.
    """.trimMargin()

    pairs.forEachIndexed { pairIdx, (prompt, plan) ->
        // 跑质检
        val jsonPlan = schemaJson.encodeToString(PlannerOutput.serializer(), plan)
        val qr = pipeline.runAll(
            jsonString = jsonPlan,
            expectedGranularity = plan.meta.echoGranularity,
            expectedPlanningLevel = plan.meta.echoPlanningLevel,
            expectedControl = plan.meta.echoControl,
            expectedScope = plan.meta.scopeTag,
            expectedScopeHint = plan.dispatch.scopeHint,
            failFast = false
        )
        val qFlags = BooleanArray(10)
        qr.gates.forEach { g ->
            val idx = g.gateId.removePrefix("Q").toIntOrNull()
            if (idx != null) qFlags[idx - 1] = g.pass
        }
        (0..9).forEach { if (qFlags[it]) gateCounts[it]++ }
        val allPass = qFlags.all { it }
        if (allPass) allPassCount++

        // Meta 没有 requestId 字段 → 用序号合成稳定的 sampleId
        val sampleId = "S%05d".format(pairIdx + 1)

        qualityReport.append(sampleId).append(',')
            .append(plan.meta.scopeTag.name).append(',')
            .append(plan.meta.echoGranularity.name).append(',')
            .append(qFlags.joinToString(",") { if (it) "1" else "0" }).append(',')
            .appendLine(if (allPass) "1" else "0")

        promptFile.appendLine("[$allPass] $sampleId | ${plan.meta.scopeTag} | ${plan.meta.echoGranularity}")
        promptFile.appendLine("    $prompt")

        planFile.appendLine(jsonPlan)

        // ---------- LoRA 消息三元组 ----------
        val ctrlGran = plan.meta.echoGranularity.name
        val ctrlScope = plan.meta.scopeTag.name
        val ctrlHint = if (plan.dispatch.scopeHint.isEmpty()) listOf("-") else plan.dispatch.scopeHint
        val userMsg = """
            |[ControlToken Begin]
            |granularity=$ctrlGran
            |scope=$ctrlScope
            |scope_hint=${ctrlHint.joinToString("+")}
            |planning_level=${plan.meta.echoPlanningLevel.name}
            |control=${plan.meta.echoControl.name}
            |language=zh-CN
            |[ControlToken End]
            |
            |$prompt
        """.trimMargin()

        val msgRow = LoraMessageRow(
            messages = listOf(
                LoraMsg("system", systemPrompt),
                LoraMsg("user", userMsg),
                LoraMsg("assistant", jsonPlan)
            )
        )
        loraFile.appendLine(schemaJson.encodeToString(LoraMessageRow.serializer(), msgRow))
    }

    loraFile.close(); planFile.close(); promptFile.close()
    // qualityReport 是 StringBuilder，写出到 CSV 文件
    File(opts.outDir, "quality-report.csv").writeText(qualityReport.toString())

    // ---------- Step 4: Meta 报告 ----------
    val scopeBuckets = pairs.groupingBy { it.second.meta.scopeTag }.eachCount()
    val granBuckets = pairs.groupingBy { it.second.meta.echoGranularity }.eachCount()
    val meta = LoraMeta(
        n = opts.n,
        webRatio = opts.webRatio,
        seed = opts.seed,
        allPassCount = allPassCount,
        gatePassCounts = gateNames.zip(gateCounts.toList()).associate { it.first to it.second },
        scopeDistribution = scopeBuckets.mapKeys { it.key.name },
        granularityDistribution = granBuckets.mapKeys { it.key.name },
        durationMs = tGen,
        lines = mapOf(
            "lora-train.jsonl" to opts.n,
            "planner-outputs.jsonl" to opts.n,
            "quality-report.csv" to opts.n + 1
        )
    )
    val metaFile = File(opts.outDir, "lora-train-meta.json")
    metaFile.writeText(schemaJson.encodeToString(LoraMeta.serializer(), meta))

    val pct = DecimalFormat("0.00%").format(allPassCount.toDouble() / opts.n)
    println("✓ 合成完成，耗时 ${tGen / 1000.0}s")
    println("  全关通过率: $allPassCount/${opts.n} = $pct")
    println("  Q1~Q10: " + gateCounts.mapIndexed { i, c -> "Q${i+1}=$c" }.joinToString(" "))
    println("  Scope: $scopeBuckets")
    println("  Granularity: $granBuckets")
    println("  LoRA 训练集 (messages 格式) → ${metaFile.parentFile.absolutePath}/")
    println("  Meta: ${metaFile.absolutePath}")
}

@Serializable
private data class LoraMsg(val role: String, val content: String)

@Serializable
private data class LoraMessageRow(val messages: List<LoraMsg>)

@Serializable
private data class LoraMeta(
    val n: Int,
    val webRatio: Float,
    val seed: Long,
    val allPassCount: Int,
    val gatePassCounts: Map<String, Int>,
    val scopeDistribution: Map<String, Int>,
    val granularityDistribution: Map<String, Int>,
    val durationMs: Long,
    val lines: Map<String, Int>
)

private class Opts(
    val n: Int,
    val webRatio: Float,
    val seed: Long,
    val outDir: File
) {
    companion object {
        fun parse(args: Array<String>): Opts {
            var n = 2000
            var webRatio = 0.4f
            var seed = 42L
            var out = File("planner-benchmarks/build/lora")
            var i = 0
            while (i in args.indices) {
                when (args[i]) {
                    "--n" -> n = args[++i].toInt()
                    "--web-ratio" -> webRatio = args[++i].toFloat()
                    "--seed" -> seed = args[++i].toLong()
                    "--out" -> out = File(args[++i])
                    "-h", "--help" -> {
                        println("GenerateLoraDataset --n INT --web-ratio FLOAT --seed LONG --out DIR")
                        kotlin.system.exitProcess(0)
                    }
                }
                i++
            }
            return Opts(n, webRatio, seed, out)
        }
    }
}
