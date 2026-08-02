package com.deepseek.coder.planner.bench.tools

import com.deepseek.coder.planner.bench.benchmarks.*
import com.deepseek.coder.planner.bench.quality.PipelineResult
import com.deepseek.coder.planner.bench.quality.QualityGatePipeline
import com.deepseek.coder.planner.bench.schema.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * P4-4 CLI：调用真实 DeepSeek Chat Completions API 跑 F1/F2/F3/F6 回测
 *
 * 【风险提示】
 *   调用会消耗 DeepSeek 真实 token！默认 dry-run=true（不发请求，打印 curl）。
 *   确定预算后再带 --live 启动。默认只跑 smoke 10 题（--n-cases 调整）。
 */
fun main(args: Array<String>) = runBlocking {
    val opts = BacktestOpts.parse(args)
    println("=== LiveF1BacktestAgainstDeepSeek ===")
    println("  live=${opts.live}  model=${opts.model}  n-cases=${opts.nCases}  out=${opts.outDir}")
    opts.outDir.mkdirs()

    val schemaJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // F1 Case 采样（取前 n 条）
    val f1All = F1DecisionQualityBenchmark.sampleCases()
    val cases = f1All.take(opts.nCases)

    val systemPrompt = """
        |You are DeepCoder Planner v0.4. Output strictly valid JSON conforming to PlannerOutput Schema 0.4.
        |Rules:
        |  - Scope ∈ { ANDROID_KOTLIN, WEB_FRONTEND, GENERAL }
        |  - Steps per Granularity: COARSE 3~7 / MEDIUM 5~9 / FINE 10~22
        |  - ALWAYS echo meta.echo_control block
        |  - If non-code/dangerous/legal → clarifications_needed + CAP_CLASSIFY_REJECT or CAP_CLARIFY
        |Only JSON. No fences.
    """.trimMargin()

    val qp = QualityGatePipeline()
    val actuals = HashMap<String, PlannerOutput>()
    val csv = StringBuilder("case_id,cap_gt,cap_pr,scope_gt,scope_pr,clarify_gt,clarify_pr,confidence_gt,confidence_pr,q_pass\n")
    val okhttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()

    cases.forEachIndexed { i, c ->
        val ctrl = when {
            c.stepsRange.last < 5 -> Granularity.COARSE
            c.stepsRange.first in 5..9 -> Granularity.MEDIUM
            else -> Granularity.FINE
        }
        val expectedStepsMid = (c.stepsRange.first + c.stepsRange.last) / 2
        val userMsg = """
            |[ControlToken Begin] granularity=$ctrl scope=${c.expectedScope} planning_level=MILESTONE control=DISPATCH [ControlToken End]
            |
            |${c.userPrompt}
        """.trimMargin()
        print("[${i+1}/${cases.size}] ${c.caseId} ... ")

        val raw = if (opts.live) {
            deepseekChatBlocking(okhttp, opts, schemaJson, systemPrompt, userMsg)
                ?: error("DeepSeek 返回 null")
        } else {
            // DRY RUN: 用 samplePlanJson 合成一条"假装从接口拿到"的数据，脚本能跑通但不花 token
            val steps = expectedStepsMid.coerceIn(5, 9)
            F3ContrastiveBenchmark.samplePlanJson(ctrl.name, steps, c.expectedScope)
        }

        // 尝试从 JSON 里抽取 PlannerOutput（接口会有 markdown fences/前言等，做暴力抽 JSON）
        val json = stripFences(raw)
        val decoded: PlannerOutput? = runCatching {
            schemaJson.decodeFromString(PlannerOutput.serializer(), json)
        }.onFailure { t -> System.err.println("  !! JSON parse fail: ${t.message}") }.getOrNull()
        val ok = decoded != null
        println(if (ok) "✓ parse OK" else "✗ parse FAIL")

        if (decoded != null) {
            actuals[c.caseId] = decoded
            // F1 对比（字段按真实类定义映射）
            val scopeOk = decoded.meta.scopeTag == c.expectedScope
            val capActual = decoded.milestones.firstOrNull()?.subtasks?.firstOrNull()?.capability
                ?: decoded.dispatch.capabilityPriorityMap.values.firstOrNull()
            val capOk = capActual == c.expectedMainCapability
            val clarifyActual = decoded.clarificationsNeeded.isNotEmpty() || decoded.meta.needsUserConfirmation
            val clarifyOk = clarifyActual == c.expectedClarityNeeded
            val conf = decoded.meta.confidence
            val confActualOk = if (c.expectedLowConfidence) conf < 0.65f else conf >= 0.65f
            val qr: PipelineResult = qp.runAll(json, expectedGranularity = ctrl, failFast = false)
            csv.append(c.caseId).append(',')
                .append(c.expectedMainCapability).append(',').append(capActual ?: "").append(',')
                .append(c.expectedScope.name).append(',').append(decoded.meta.scopeTag.name).append(',')
                .append(c.expectedClarityNeeded).append(',').append(clarifyActual).append(',')
                .append(c.expectedLowConfidence).append(',').append(conf).append(',')
                .appendLine(if (qr.passed) "1" else "0")

            // 每条 raw 落盘（便于复现）
            File(opts.outDir, "${c.caseId}.response.json").writeText(json)
        }
    }

    File(opts.outDir, "f1-backtest.csv").writeText(csv.toString())

    // 跑 F1 evaluate（如果 actuals 里有 ≥ 1 条）
    if (actuals.isNotEmpty()) {
        val f1Bench = F1DecisionQualityBenchmark(cases)
        val res = f1Bench.evaluate(actuals)
        // BenchmarkResult 暂未加 @Serializable → 手动拼 JSON 写文件
        val summaryJson = buildJsonObject {
            put("id", res.id)
            put("name", res.name)
            put("totalCases", res.totalCases)
            put("passedCases", res.passedCases)
            put("passRate", res.passRate)
            put("passed", res.passed)
            put("threshold", res.threshold)
            putJsonArray("failedCases") { res.failedCases.forEach { add(it) } }
            putJsonObject("subMetrics") {
                res.subMetrics.forEach { (k, v) -> put(k, v.toString()) }
            }
        }
        File(opts.outDir, "f1-result.json").writeText(schemaJson.encodeToString(summaryJson))
        println()
        println("================ F1 Result ================")
        println("  passRate = ${String.format("%.2f", res.passRate * 100)}%")
        println("  subMetrics = ${res.subMetrics}")
        println("  cases    = ${res.totalCases} total / ${res.passedCases} passed / ${res.failedCases.size} failed")
        println("  csv:     = ${File(opts.outDir, "f1-backtest.csv").absolutePath}")
    } else {
        println("⚠️ 没有任何实际输出（是不是忘记带 --live 了？）")
    }
}

// ---- DeepSeek Chat HTTP 调用（blocking via runBlocking）----
private fun deepseekChatBlocking(
    okhttp: OkHttpClient,
    opts: BacktestOpts,
    schemaJson: Json,
    system: String,
    user: String
): String? {
    val key = opts.apiKey
    if (key.startsWith("sk-xxxxxxxx")) error("请设置 DEEPSEEK_API_KEY 环境变量（当前是占位）")
    val reqBody = buildJsonObject {
        put("model", opts.model)
        put("response_format", buildJsonObject { put("type", "json_object") })
        put("temperature", 0.2)
        put("max_tokens", 8000)
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            add(buildJsonObject { put("role", "user");   put("content", user) })
        }
    }.toString()
    val httpReq = Request.Builder()
        .url(opts.baseUrl + "/chat/completions")
        .header("Authorization", "Bearer $key")
        .header("Content-Type", "application/json")
        .post(reqBody.toRequestBody("application/json".toMediaType()))
        .build()
    return okhttp.newCall(httpReq).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            System.err.println("!! HTTP ${resp.code} body=$body")
            return null
        }
        val j = schemaJson.parseToJsonElement(body).jsonObject
        j["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content
    }
}

private fun stripFences(raw: String): String {
    val s = raw.trim()
    val withoutTick = s.removeSurrounding("```json", "```").removeSurrounding("```", "```").trim()
    val l = withoutTick.indexOf('{')
    val r = withoutTick.lastIndexOf('}')
    return if (l >= 0 && r > l) withoutTick.substring(l, r + 1) else withoutTick
}

// 注意：不要加 @Serializable（因为 java.io.File 没有默认 serializer）
// parse() 已经用了命令行手动解析，不需要 Kotlin Serialization 处理本类
private data class BacktestOpts(
    val live: Boolean,
    val model: String,
    val nCases: Int,
    val apiKey: String,
    val baseUrl: String,
    val outDir: File,
) {
    companion object {
        fun parse(args: Array<String>): BacktestOpts {
            var live = false
            var model = "deepseek-chat"
            var n = 10
            var outDir = File("planner-benchmarks/build/backtest")
            var i = 0
            while (i in args.indices) {
                when (args[i]) {
                    "--live" -> live = true
                    "--model" -> model = args[++i]
                    "--n-cases" -> n = args[++i].toInt().coerceIn(1, 2000)
                    "--out" -> outDir = File(args[++i])
                    "-h","--help" -> { println("LiveF1BacktestAgainstDeepSeek [--live] [--model M] [--n-cases N] [--out DIR]"); kotlin.system.exitProcess(0) }
                }
                i++
            }
            return BacktestOpts(
                live = live, model = model, nCases = n,
                apiKey = (System.getenv("DEEPSEEK_API_KEY") ?: "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"),
                baseUrl = (System.getenv("DEEPSEEK_BASE_URL") ?: "https://api.deepseek.com/v1").trimEnd('/'),
                outDir = outDir
            )
        }
    }
}
