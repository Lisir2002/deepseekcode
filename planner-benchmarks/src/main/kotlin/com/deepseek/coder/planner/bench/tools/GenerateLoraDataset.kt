package com.deepseek.coder.planner.bench.tools

import com.deepseek.coder.planner.bench.benchmarks.schemaJson
import com.deepseek.coder.planner.bench.quality.QualityGatePipeline
import com.deepseek.coder.planner.bench.schema.*
import com.deepseek.coder.planner.bench.synthetic.SyntheticPipeline
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintStream
import java.text.DecimalFormat
import kotlin.math.roundToInt

/**
 * P4-2a / Step3-4 CLI：流式生成 2k/20k/200k/2M 条 Planner pair → DeepSeek LoRA JSONL + 质检报告
 *
 * 关键改造（S3-1, OOM-safe）：
 *   1) 用 SyntheticPipeline.generateStreaming 逐 pair emit，永远不把所有 PlannerOutput 一次驻内存
 *   2) 质量 CSV 用 BufferedWriter 追加（非 StringBuilder），200k/2M 行不爆堆
 *   3) --batch-size 默认 10000，每 N 条 flush writers + 打印进度（含 JVM free/total mem，监控内存）
 *   4) 统计量 scopeCounts/granCounts/gateCounts 用 IntArray/HashMap 流式累加
 *   5) 完全可复现：同 seed + n → 字节级完全一致的 lora-train.jsonl
 *
 * 用法：
 *   GenerateLoraDataset --n 2000 --web-ratio 0.4 --seed 42 --out DIR [--batch-size 10000]
 */
fun main(args: Array<String>) {
    val opts = Opts.parse(args)
    println("=== GenerateLoraDataset (STREAMING, OOM-safe) ===")
    println("    n=${opts.n}  web-ratio=${opts.webRatio}  seed=${opts.seed}  batch=${opts.batchSize}")
    println("    out-dir=${opts.outDir.absolutePath}")

    opts.outDir.mkdirs()
    val pipeline = QualityGatePipeline()
    val synth = SyntheticPipeline(pipeline)

    // ---------- Open all writers; CSV header; init counters ----------
    val t0 = System.currentTimeMillis()
    val loraFile: BufferedWriter = File(opts.outDir, "lora-train.jsonl").bufferedWriter(bufferSize = 1_048_576) // 1MB buf
    val planFile: BufferedWriter = File(opts.outDir, "planner-outputs.jsonl").bufferedWriter(bufferSize = 1_048_576)
    val promptFile: BufferedWriter = File(opts.outDir, "prompts-shuffled.txt").bufferedWriter(bufferSize = 512_000)
    val csvFile: BufferedWriter  = File(opts.outDir, "quality-report.csv").bufferedWriter(bufferSize = 512_000)
    csvFile.appendLine("id,scope,granularity,Q1,Q2,Q3,Q4,Q5,Q6,Q7,Q8,Q9,Q10,all_pass")

    val gateNames = (1..10).map { "Q$it" }
    val gateCounts = IntArray(10)
    val scopeCounts = LinkedHashMap<ScopeTag, Int>(ScopeTag.values().associateWith { 0 })
    val granCounts  = LinkedHashMap<Granularity, Int>(Granularity.values().associateWith { 0 })
    var allPassCount = 0
    var emitted = 0

    // ---- system prompt (复用原格式，保持和 P4-S2 n=2k 完全一致，方便 LoRA 后 F1 可比) ----
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

    // ---------- Stream emit one by one ----------
    synth.generateStreaming(n = opts.n, webRatio = opts.webRatio, seed = opts.seed) { globalIdx, id, prompt, plan ->
        val jsonPlan = schemaJson.encodeToString(PlannerOutput.serializer(), plan)
        val qr = pipeline.runAll(
            jsonString = jsonPlan,
            expectedGranularity = plan.meta.echoGranularity,
            expectedPlanningLevel = plan.meta.echoPlanningLevel,
            expectedControl = plan.meta.echoControl.control,
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

        // Meta 无 requestId → 用序号生成 sampleId（全局稳定 0-based）
        val sampleId = "S%07d".format(globalIdx + 1)

        scopeCounts[plan.meta.scopeTag] = (scopeCounts[plan.meta.scopeTag] ?: 0) + 1
        granCounts[plan.meta.echoGranularity]  = (granCounts[plan.meta.echoGranularity]  ?: 0) + 1

        // ---- 写 5 文件（流式 append）----
        csvFile.append(sampleId).append(',')
            .append(plan.meta.scopeTag.name).append(',')
            .append(plan.meta.echoGranularity.name).append(',')
            .append(qFlags.joinToString(",") { if (it) "1" else "0" }).append(',')
            .appendLine(if (allPass) "1" else "0")

        promptFile.appendLine("[$allPass] $sampleId | ${plan.meta.scopeTag} | ${plan.meta.echoGranularity}")
        promptFile.appendLine("    $prompt")

        planFile.appendLine(jsonPlan)

        val ctrlGran = plan.meta.echoGranularity.name
        val ctrlScope = plan.meta.scopeTag.name
        val ctrlHint = if (plan.dispatch.scopeHint.isEmpty()) listOf("-") else plan.dispatch.scopeHint
        val userMsg = """
            |[ControlToken Begin]
            |granularity=$ctrlGran
            |scope=$ctrlScope
            |scope_hint=${ctrlHint.joinToString("+")}
            |planning_level=${plan.meta.echoPlanningLevel.name}
            |control=${plan.meta.echoControl.control.name}
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

        emitted++
        // ---- batch progress flush ----
        if (opts.batchSize in 1..Int.MAX_VALUE && emitted % opts.batchSize == 0) {
            loraFile.flush(); planFile.flush(); promptFile.flush(); csvFile.flush()
            val rt = Runtime.getRuntime()
            val used = (rt.totalMemory() - rt.freeMemory()) / 1048576L
            val total = rt.totalMemory() / 1048576L
            val pct = DecimalFormat("0.0%").format(emitted.toDouble() / opts.n)
            val tNow = (System.currentTimeMillis() - t0) / 1000.0
            println("  progress $emitted/${opts.n} ($pct) | ${tNow.toInt()}s | JVM mem used=${used}MB total=${total}MB max=${rt.maxMemory()/1048576L}MB | Q-pass=$allPassCount/$emitted")
        }
    }

    // ---------- Close writers ----------
    loraFile.close(); planFile.close(); promptFile.close(); csvFile.close()
    val tGen = System.currentTimeMillis() - t0
    val pct = DecimalFormat("0.00%").format(allPassCount.toDouble() / opts.n)

    // ---------- Meta report ----------
    val meta = LoraMeta(
        n = opts.n,
        webRatio = opts.webRatio,
        seed = opts.seed,
        allPassCount = allPassCount,
        gatePassCounts = gateNames.zip(gateCounts.toList()).associate { it.first to it.second },
        scopeDistribution = scopeCounts.filterValues { it > 0 }.mapKeys { it.key.name },
        granularityDistribution = granCounts.filterValues { it > 0 }.mapKeys { it.key.name },
        durationMs = tGen,
        lines = mapOf(
            "lora-train.jsonl" to opts.n,
            "planner-outputs.jsonl" to opts.n,
            "quality-report.csv" to opts.n + 1,
            "prompts-shuffled.txt" to opts.n
        ),
        emitted = emitted
    )
    val metaFile = File(opts.outDir, "lora-train-meta.json")
    metaFile.writeText(schemaJson.encodeToString(LoraMeta.serializer(), meta))

    // ---------- Console summary ----------
    // 把 stderr/stdout 重定向前确保 JVM mem 打印一次最终
    val rt = Runtime.getRuntime()
    val used = (rt.totalMemory() - rt.freeMemory()) / 1048576L
    println("✓ 合成完成，streamed=$emitted/${opts.n}，耗时 ${tGen/1000.0}s，peak JVM heap used≈${used}MB")
    println("  全关通过率: $allPassCount/${opts.n} = $pct")
    println("  Q1~Q10: " + gateCounts.mapIndexed { i, c -> "Q${i+1}=$c" }.joinToString(" "))
    println("  Scope: $scopeCounts")
    println("  Granularity: $granCounts")
    println("  输出目录: ${opts.outDir.absolutePath}/")
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
    val lines: Map<String, Int>,
    val emitted: Int = n
)

private class Opts(
    val n: Int,
    val webRatio: Float,
    val seed: Long,
    val outDir: File,
    val batchSize: Int
) {
    companion object {
        fun parse(args: Array<String>): Opts {
            var n = 2000
            var webRatio = 0.4f
            var seed = 42L
            var out = File("planner-benchmarks/build/lora")
            var batch = 10000
            var i = 0
            while (i in args.indices) {
                when (args[i]) {
                    "--n" -> n = args[++i].toInt().coerceAtLeast(1)
                    "--web-ratio" -> webRatio = args[++i].toFloat().coerceIn(0f, 1f)
                    "--seed" -> seed = args[++i].toLong()
                    "--out" -> out = File(args[++i])
                    "--batch-size" -> batch = args[++i].toInt().coerceAtLeast(100)
                    "-h", "--help" -> {
                        println("GenerateLoraDataset --n INT --web-ratio FLOAT --seed LONG --out DIR [--batch-size INT]")
                        println("  (STREAMING, OOM-safe. For n>=20000 recommended batch=10000)")
                        kotlin.system.exitProcess(0)
                    }
                }
                i++
            }
            return Opts(n, webRatio, seed, out, batch)
        }
    }
}
