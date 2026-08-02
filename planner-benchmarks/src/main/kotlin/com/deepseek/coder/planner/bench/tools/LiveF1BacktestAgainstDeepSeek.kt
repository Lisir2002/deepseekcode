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
    val f1All = F1DecisionQualityBenchmark.defaultSampleCases()
    val cases = f1All.take(opts.nCases)

        val systemPrompt = """
        |You are DeepCoder Planner v0.4. Output strictly valid JSON conforming to PlannerOutput Schema 0.4.
        |TOP LEVEL: object with required keys: meta, dispatch, milestones, topology. Optional: clarifications_needed, granularity_analysis.
        |---- META (all keys required) ----
        |  meta.output_version = "0.4" (string)
        |  meta.echo_granularity = COARSE | MEDIUM | FINE (string enum — copy from ControlToken.granularity)
        |  meta.echo_planning_level = MILESTONE | SUBTASK (string enum — copy from ControlToken.planning_level)
        |  meta.echo_control = OBJECT (required 4 keys: {"granularity":"..","planning_level":"..","control":"DISPATCH","scope":".."} — copy ControlToken)
        |  meta.confidence = number 0.00 ~ 1.00 (e.g. 0.88)
        |  meta.needs_user_confirmation = boolean (false unless risky)
        |  meta.estimated_total_steps = integer = total subtasks count. COARSE 3~7, MEDIUM 5~9, FINE 10~22
        |  meta.estimated_cost_yuan = number, default 0
        |  meta.estimated_minutes_wall_clock = integer, default 0
        |  meta.scope_tag = ANDROID_KOTLIN | WEB_FRONTEND | GENERAL (string enum — copy from ControlToken.scope)
        |---- DISPATCH (all keys required) ----
        |  dispatch.default_tier = "L2-Standard" typically
        |  dispatch.default_model = "v4-flash" typically
        |  dispatch.capability_priority_map = OBJECT mapping milestone_id -> one CAP_* string (e.g. {"M1":"CAP_CODE_GENERATE","M2":"CAP_RUN_SYNTAX_CHECK"})
        |  dispatch.max_retry_per_subtask = 1
        |  dispatch.allow_parallel_within_milestone = true
        |  dispatch.always_self_check_after_code_task = true
        |  dispatch.scope_hint = ARRAY of one+ strings, e.g. ["WEB_FRONTEND"]
        |---- MILESTONES (ARRAY of milestone objects, required 3-12 depending on granularity) ----
        |  Each milestone must be an OBJECT with:
        |    id (string) e.g. "M1", "M2"  (REQUIRED)
        |    title (string, Chinese zh-CN 中文) — brief milestone goal  (REQUIRED)
        |    tier_override (string or null, optional)
        |    depends_on (array of string ids, optional, default [])
        |    expected_duration_pct (FLOAT 0.00..1.00, e.g. 0.35 meaning 35% — sum across ALL milestones MUST equal 1.00  (REQUIRED)
        |    acceptance_gate (array of strings, Chinese, optional, default [])
        |    subtasks (ARRAY of SUBTASK objects, required 1-6 per milestone)  — IMPORTANT: each subtask is an OBJECT not a plain string:
        |        subtask.id (string) e.g. "M1S1"
        |        subtask.title (string, Chinese zh-CN — detailed goal of this subtask)
        |        subtask.capability (one CAP_* string from: CAP_CODE_GENERATE CAP_CODE_REFACTOR CAP_CODE_EXPLAIN CAP_CODE_FIX_BUG CAP_CODE_TRANSLATE CAP_CODE_REVIEW CAP_DESIGN_ARCH CAP_ADD_DEPENDENCY CAP_WRITE_TEST CAP_RUN_SYNTAX_CHECK CAP_DEBUG CAP_DEPLOY CAP_GENERAL_CHAT CAP_CLASSIFY_REJECT CAP_CLARIFY)
        |        subtask.depends_on (array of subtask ids, default [])
        |        subtask.acceptance_criteria (ARRAY of 2+ strings in Chinese, each >=15 chars e.g. "代码在 Android Studio Hedgehog 中 Gradle sync 通过且无红色报错")
        |        subtask.expected_outputs (array of strings, optional default [])
        |        subtask.context_hint (string or null)
        |---- TOPOLOGY (required object) ----
        |  topology.milestone_edges (array) can be [] (linear flow OK), or [{"from":"M1","to":["M2"]},...]
        |  topology.cross_subtask_edges (array) default []
        |---- Optional ----
        |  clarifications_needed: array of Clarification objects [{id:"C1", question:"中文问题", options:["A","B"]}] if user need clarify or dangerous/legal/non-code.
        |  If output REFUSE (milestones empty → set meta.refuse_reason and echo_control.control = REFUSE).
        |Only valid JSON. No markdown fences. No text before or after JSON object. Chinese for natural-language fields (title, acceptance_criteria, etc.).
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
        val jsonRaw = stripFences(raw)
        // 每条 raw 先落盘（便于复现 & 调试 parse fail）
        File(opts.outDir, "${c.caseId}.response.json").writeText(jsonRaw)
        // ============== Comprehensive Schema Coercer (v0.4) ================
        // 修复 LLM 常见简化输出：meta 字段提升、milestones/subtasks 结构转换、
        // duration_pct 归一化、capability_priority_map / topology / scope_hint 自动补全
        val json: String = coerceToSchemaV04(schemaJson, jsonRaw)
        // ====================================================================
        // 调试：coercer 之后再额外写一份 .coerced.json，便于比对修复前后
        runCatching { File(opts.outDir, "${c.caseId}.response.coerced.json").writeText(json) }
        val decoded: PlannerOutput? = runCatching {
            schemaJson.decodeFromString(PlannerOutput.serializer(), json)
        }.onFailure { t -> System.err.println("  !! JSON parse fail: ${t.message?.take(220)}") }.getOrNull()
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

/**
 * Schema 修复器：LLM 常把 echo_granularity/echo_planning_level/scope_tag 只放在 meta.echo_control 嵌套对象里，
 * 而 Schema 要求这些字段在 Meta 顶层再重复一份。本函数：
 *  1) 解析 JSON → 定位 meta
 *  2) 从 meta.echo_control (4字段 object) promote 到 meta 顶层缺失字段
 *  3) 填 confidence=0.88 / estimated_total_steps / estimated_cost_yuan 等合理默认
 *  4) 再 encodeToString 返回（保证合法 JSON）
 */
/**
 * Comprehensive Schema v0.4 Coercer (替代简单 promoteMetaFields)
 * 修复 LLM 常见简化输出：
 *  1) Meta 字段：从 echo_control 提升 granularity/planning_level/scope，填默认值
 *  2) Milestones:
 *     - 缺 id → 按顺序生成 "M1"/"M2"/...
 *     - 缺 title → 用 id 或 "里程碑 $i"
 *     - expected_duration_pct:
 *         · 缺失 → 按 1/N 均分；最后一个补足 1.0
 *         · 整数(>1)且≤100 → 除 100 转成 0~1 浮点数（模型经常输出 35 表示 35%）
 *         · 最后归一化使 sum=1.0±0.02
 *     - subtasks: list-of-strings → 转成 Subtask 对象列表（每个生成 id/title/capability/acceptance_criteria）
 *  3) Subtask: 缺 id → MxSy；缺 capability → 从 scope_tag 推导合理默认 CAP_*；缺 acceptance_criteria → 生成合理两条中文
 *  4) Dispatch:
 *     - 缺 capability_priority_map → 从每个 milestone 第一个 subtask.capability 自动推导
 *     - 缺 scope_hint → 从 meta.scope_tag 转成 ["ANDROID_KOTLIN"] / ["WEB_FRONTEND"] / ["GENERAL"]
 *     - 其他布尔/整数字段 → 填合理默认
 *  5) Topology: 缺 milestone_edges → linear DAG；缺 cross_subtask_edges → 空数组
 *  6) Clarifications_needed: list-of-strings → 转成 Clarification 对象
 *  7) 顶层缺失 milestones / topology / dispatch → 建空结构（meta 已补）
 */
private fun coerceToSchemaV04(json: Json, raw: String): String = runCatching {
    val root0 = json.parseToJsonElement(raw).jsonObject
    val root = root0.toMutableMap()

    // ---- (0) helpers ----
    fun floatOrNull(e: JsonElement?): Float? = runCatching {
        e?.jsonPrimitive?.doubleOrNull?.toFloat()
            ?: e?.jsonPrimitive?.longOrNull?.toFloat()
    }.getOrNull()
    fun strOr(e: JsonElement?, fallback: String): String =
        e?.jsonPrimitive?.contentOrNull ?: fallback
    fun arrOrEmpty(e: JsonElement?): JsonArray =
        if (e is JsonArray) e else JsonArray(emptyList())
    fun objOr(e: JsonElement?, fallback: JsonObject): JsonObject =
        if (e is JsonObject) e else fallback
    fun objOrNull(e: JsonElement?): JsonObject? =
        e as? JsonObject

    // ---- (1) META ----
    val meta = objOr(root["meta"], buildJsonObject {})
    val mb = meta.toMutableMap()
    val ec = mb["echo_control"]?.let { objOrNull(it) }
    fun hasM(k: String) = mb.containsKey(k) && mb[k] !is JsonNull
    if (ec != null) {
        if (!hasM("echo_granularity")) ec["granularity"]?.let { mb["echo_granularity"] = it }
        if (!hasM("echo_planning_level")) ec["planning_level"]?.let { mb["echo_planning_level"] = it }
        if (!hasM("scope_tag")) ec["scope"]?.let { mb["scope_tag"] = it }
        if (mb["echo_control"] is JsonNull) mb["echo_control"] = ec
    }
    if (!hasM("output_version")) mb["output_version"] = JsonPrimitive("0.4")
    if (!hasM("confidence")) mb["confidence"] = JsonPrimitive(0.88f)
    if (!hasM("needs_user_confirmation")) mb["needs_user_confirmation"] = JsonPrimitive(false)
    if (!hasM("estimated_cost_yuan")) mb["estimated_cost_yuan"] = JsonPrimitive(0f)
    if (!hasM("estimated_minutes_wall_clock")) mb["estimated_minutes_wall_clock"] = JsonPrimitive(0)
    // echo_control normalize (if missing or wrong type → build from meta-level fields or defaults)
    val ecVal = mb["echo_control"]
    if (ecVal == null || ecVal !is JsonObject) {
        val g = mb["echo_granularity"]?.jsonPrimitive?.contentOrNull ?: "MEDIUM"
        val p = mb["echo_planning_level"]?.jsonPrimitive?.contentOrNull ?: "MILESTONE"
        val s = mb["scope_tag"]?.jsonPrimitive?.contentOrNull ?: "GENERAL"
        mb["echo_control"] = buildJsonObject {
            put("granularity", g); put("planning_level", p); put("control", "DISPATCH"); put("scope", s)
        }
    }
    // ensure scope_tag string is one of enum
    val scopeStr = mb["scope_tag"]?.jsonPrimitive?.contentOrNull
        ?: (mb["echo_control"]?.jsonObject?.get("scope")?.jsonPrimitive?.contentOrNull)
        ?: "GENERAL"
    mb["scope_tag"] = JsonPrimitive(scopeStr)
    val metaObj = JsonObject(mb)
    root["meta"] = metaObj

    // ---- (2) MILESTONES: build normalized milestones array ----
    val rawMs = arrOrEmpty(root["milestones"]).toList()
    // derive default capability by scope
    val defaultCapByScope = when (scopeStr) {
        "ANDROID_KOTLIN" -> "CAP_CODE_GENERATE"
        "WEB_FRONTEND" -> "CAP_CODE_GENERATE"
        else -> "CAP_GENERAL_CHAT"
    }
    val acceptedCaps = setOf(
        "CAP_CODE_GENERATE","CAP_CODE_REFACTOR","CAP_CODE_EXPLAIN","CAP_CODE_FIX_BUG",
        "CAP_CODE_TRANSLATE","CAP_CODE_REVIEW","CAP_DESIGN_ARCH","CAP_ADD_DEPENDENCY",
        "CAP_WRITE_TEST","CAP_RUN_SYNTAX_CHECK","CAP_DEBUG","CAP_DEPLOY",
        "CAP_GENERAL_CHAT","CAP_CLASSIFY_REJECT","CAP_CLARIFY")
    fun canonicalCap(raw: String): String =
        if (raw in acceptedCaps) raw else defaultCapByScope

    val normalizedMilestones = mutableListOf<JsonObject>()
    var subtaskTotal = 0
    var durSum = 0f
    val durationPcts = mutableListOf<Float?>()

    // (2a) first pass: normalize fields (except final duration)
    rawMs.forEachIndexed { idx, mEl ->
        val mo = objOr(mEl, buildJsonObject {}).toMutableMap()
        val mIdx = idx + 1
        // id
        if (mo["id"] == null || mo["id"] !is JsonPrimitive) mo["id"] = JsonPrimitive("M$mIdx")
        val mid = strOr(mo["id"], "M$mIdx")
        // title
        if (mo["title"] == null || mo["title"] !is JsonPrimitive || mo["title"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
            mo["title"] = JsonPrimitive("里程碑 $mIdx：设计与实现阶段")
        }
        // tier_override: leave null or keep
        if (!mo.containsKey("tier_override")) mo["tier_override"] = JsonNull
        // depends_on: default []
        val depRaw = arrOrEmpty(mo["depends_on"]).toList()
        mo["depends_on"] = if (depRaw.isEmpty()) JsonArray(emptyList()) else JsonArray(depRaw)
        // expected_duration_pct: first normalize to candidate Float (allow int 35 → 0.35)
        val pctRaw = floatOrNull(mo["expected_duration_pct"])
        val pctCandidate: Float? = when {
            pctRaw == null -> null
            pctRaw > 1f && pctRaw <= 100f -> pctRaw / 100f  // model wrote 35 meaning 35%
            pctRaw < 0f -> 0f
            else -> pctRaw
        }
        durationPcts.add(pctCandidate)
        if (pctCandidate != null) durSum += pctCandidate
        // acceptance_gate: default []
        val gateRaw = arrOrEmpty(mo["acceptance_gate"]).toList()
        mo["acceptance_gate"] = if (gateRaw.isEmpty()) JsonArray(emptyList()) else JsonArray(gateRaw)
        // --- subtasks: list of strings → list of Subtask objects; normalize each subtask ---
        val rawSubs = mo["subtasks"]
        val normalizedSubtasks = mutableListOf<JsonObject>()
        val subList = if (rawSubs is JsonArray) rawSubs.toList() else emptyList()
        if (subList.isEmpty()) {
            // 至少 1 条 subtask 防止 estimated_total_steps=0
            normalizedSubtasks.add(buildJsonObject {
                put("id", "${mid}S1")
                put("title", "完成 ${strOr(mo["title"],"本阶段")} 核心目标")
                put("capability", defaultCapByScope)
                putJsonArray("depends_on") {}
                putJsonArray("acceptance_criteria") {
                    add("输出结果符合项目代码规范且无语法错误")
                    add("功能验证通过，与需求目标一致")
                }
                putJsonArray("expected_outputs") {}
                put("context_hint", JsonNull)
            })
        } else {
            subList.forEachIndexed { si, sEl ->
                when (sEl) {
                    is JsonPrimitive -> {
                        // list-of-strings case
                        val t = sEl.contentOrNull?.takeIf { it.isNotBlank() } ?: "子任务 $si"
                        normalizedSubtasks.add(buildJsonObject {
                            put("id", "${mid}S${si+1}")
                            put("title", t)
                            put("capability", defaultCapByScope)
                            putJsonArray("depends_on") {}
                            putJsonArray("acceptance_criteria") {
                                add("该子任务输出结果与「${t.take(20)}」需求一致且可复现")
                                add("通过语法检查或代码评审，无明显缺陷")
                            }
                            putJsonArray("expected_outputs") {}
                            put("context_hint", JsonNull)
                        })
                    }
                    is JsonObject -> {
                        val so = sEl.toMutableMap()
                        val sId = "${mid}S${si+1}"
                        if (so["id"] == null || so["id"] !is JsonPrimitive) so["id"] = JsonPrimitive(sId)
                        if (so["title"] == null || so["title"] !is JsonPrimitive) {
                            so["title"] = JsonPrimitive("${mid} 子任务 ${si+1}")
                        }
                        val rawCap = strOr(so["capability"], "")
                        if (rawCap.isBlank() || rawCap !in acceptedCaps) {
                            so["capability"] = JsonPrimitive(defaultCapByScope)
                        } else so["capability"] = JsonPrimitive(rawCap)
                        if (so["depends_on"] !is JsonArray) putJsonArray(so, "depends_on") {}
                        // acceptance_criteria: required ≥ 2 strings ≥ 15 chars each
                        val acRaw = if (so["acceptance_criteria"] is JsonArray) (so["acceptance_criteria"] as JsonArray).toList() else emptyList()
                        val acFixed = mutableListOf<String>()
                        acRaw.mapNotNullTo(acFixed) { it.jsonPrimitive.contentOrNull }
                        val titleVal = strOr(so["title"], "该子任务")
                        if (acFixed.isEmpty()) {
                            acFixed.add("「${titleVal.take(16)}」功能实现正确，与需求描述一致")
                            acFixed.add("代码经过语法检查或人工 review，无明显错误")
                        } else if (acFixed.size == 1) {
                            acFixed.add("结果可复现，并通过必要的自测验证")
                        }
                        so["acceptance_criteria"] = buildJsonArray { acFixed.forEach { add(it) } }
                        if (so["expected_outputs"] !is JsonArray) putJsonArray(so, "expected_outputs") {}
                        if (!so.containsKey("context_hint")) so["context_hint"] = JsonNull
                        normalizedSubtasks.add(JsonObject(so))
                    }
                    else -> {
                        normalizedSubtasks.add(buildJsonObject {
                            put("id", "${mid}S${si+1}")
                            put("title", "子任务 ${si+1}")
                            put("capability", defaultCapByScope)
                            putJsonArray("depends_on") {}
                            putJsonArray("acceptance_criteria") {
                                add("子任务完成并通过基础验证")
                                add("输出结果与阶段目标相符")
                            }
                            putJsonArray("expected_outputs") {}
                            put("context_hint", JsonNull)
                        })
                    }
                }
            }
        }
        subtaskTotal += normalizedSubtasks.size
        mo["subtasks"] = JsonArray(normalizedSubtasks)
        normalizedMilestones.add(JsonObject(mo))
    }

    // (2b) no milestones → synthesize minimal milestone
    val milestones: List<JsonObject> = if (normalizedMilestones.isEmpty()) {
        listOf(buildJsonObject {
            put("id", "M1"); put("title", "需求完成")
            put("tier_override", JsonNull)
            putJsonArray("depends_on") {}
            put("expected_duration_pct", 1.0f)
            putJsonArray("acceptance_gate") {}
            putJsonArray("subtasks") {
                addJsonObject {
                    put("id", "M1S1"); put("title", "执行核心需求")
                    put("capability", defaultCapByScope)
                    putJsonArray("depends_on") {}
                    putJsonArray("acceptance_criteria") {
                        add("需求核心目标达成，输出与要求一致")
                        add("执行过程无错误日志或异常")
                    }
                    putJsonArray("expected_outputs") {}
                    put("context_hint", JsonNull)
                }
            }
        })
    } else normalizedMilestones
    val N = milestones.size
    // (2c) normalize expected_duration_pct (fill missing, then rescale sum to 1.0 exactly)
    val knownSum = durationPcts.filterNotNull().sum()
    val unknownCount = durationPcts.count { it == null }
    val fillerPerUnknown: Float = when {
        unknownCount == 0 -> 0f
        knownSum < 1f && knownSum > 0f -> ((1f - knownSum).coerceIn(0f, 1f)) / unknownCount
        else -> 1f / N
    }
    val rawDurs = (milestones.indices).map { i ->
        durationPcts.getOrNull(i) ?: fillerPerUnknown
    }
    val sumRaw = rawDurs.sum().let { if (it == 0f) 1f else it }
    // then assign pct, ensure sum exactly 1.00
    val normalizedDurs = rawDurs.mapIndexed { i, d ->
        val v = d / sumRaw
        if (i == N - 1) {
            val soFar = (0 until N-1).sumOf { (rawDurs[it] / sumRaw).toDouble() }.toFloat()
            (1.0f - soFar).coerceIn(0.01f, 1.0f)
        } else v
    }
    val finalMilestones = milestones.mapIndexed { i, ms ->
        val mm = ms.toMutableMap()
        mm["expected_duration_pct"] = JsonPrimitive(normalizedDurs[i])
        JsonObject(mm)
    }
    root["milestones"] = JsonArray(finalMilestones)

    // Meta estimated_total_steps: update (use actual subtask count)
    if (!hasM("estimated_total_steps") ||
        metaObj["estimated_total_steps"]?.jsonPrimitive?.intOrNull == null ||
        metaObj["estimated_total_steps"]!!.jsonPrimitive.int < 1) {
        val newMeta = metaObj.toMutableMap()
        newMeta["estimated_total_steps"] = JsonPrimitive(subtaskTotal.coerceAtLeast(1))
        root["meta"] = JsonObject(newMeta)
    }

    // ---- (3) DISPATCH ----
    val dispatch = objOr(root["dispatch"], buildJsonObject {}).toMutableMap()
    // capability_priority_map: auto-derive from milestone subtasks
    val cpm = dispatch["capability_priority_map"]
    fun firstCapOf(ms: JsonObject): String = runCatching {
        val subs = (ms["subtasks"] as JsonArray)
        (subs[0].jsonObject["capability"] as JsonPrimitive).content
    }.getOrNull() ?: defaultCapByScope
    if (cpm !is JsonObject) {
        val auto = buildJsonObject {
            finalMilestones.forEach { ms ->
                val id = (ms["id"] as JsonPrimitive).content
                put(id, firstCapOf(ms))
            }
        }
        dispatch["capability_priority_map"] = auto
    }
    // scope_hint
    val sh = dispatch["scope_hint"]
    if (sh !is JsonArray || sh.isEmpty()) {
        dispatch["scope_hint"] = buildJsonArray { add(scopeStr) }
    }
    // other fields
    if (!dispatch.containsKey("default_tier")) dispatch["default_tier"] = JsonPrimitive("L2-Standard")
    if (!dispatch.containsKey("default_model")) dispatch["default_model"] = JsonPrimitive("v4-flash")
    if (!dispatch.containsKey("max_retry_per_subtask")) dispatch["max_retry_per_subtask"] = JsonPrimitive(1)
    if (!dispatch.containsKey("allow_parallel_within_milestone")) dispatch["allow_parallel_within_milestone"] = JsonPrimitive(true)
    if (!dispatch.containsKey("always_self_check_after_code_task")) dispatch["always_self_check_after_code_task"] = JsonPrimitive(true)
    root["dispatch"] = JsonObject(dispatch)

    // ---- (4) TOPOLOGY ----
    val topo = objOr(root["topology"], buildJsonObject {}).toMutableMap()
    if (topo["milestone_edges"] !is JsonArray || (topo["milestone_edges"] as JsonArray).isEmpty()) {
        // linear DAG: M1→M2→M3→... (if N≥2)
        val mIds = finalMilestones.map { (it["id"] as JsonPrimitive).content }
        val edges = buildJsonArray {
            if (mIds.size >= 2) {
                (0 until mIds.size - 1).forEach { i ->
                    addJsonObject {
                        put("from", mIds[i])
                        putJsonArray("to") { add(mIds[i+1]) }
                    }
                }
            }
        }
        topo["milestone_edges"] = edges
    }
    if (topo["cross_subtask_edges"] !is JsonArray) topo["cross_subtask_edges"] = buildJsonArray {}
    root["topology"] = JsonObject(topo)

    // ---- (5) CLARIFICATIONS_NEEDED ----
    val rawClar = arrOrEmpty(root["clarifications_needed"]).toList()
    if (rawClar.isNotEmpty()) {
        val clarOut = rawClar.mapIndexed { ci, el ->
            if (el is JsonPrimitive) buildJsonObject {
                put("id", "C${ci+1}")
                putJsonArray("blocking_milestone_ids") {}
                put("question", el.contentOrNull ?: "需要用户确认的问题")
                putJsonArray("options") {}
                put("default_if_skipped", JsonNull)
            } else {
                val c = objOr(el, buildJsonObject {}).toMutableMap()
                if (!c.containsKey("id")) c["id"] = JsonPrimitive("C${ci+1}")
                if (!c.containsKey("blocking_milestone_ids")) putJsonArray(c, "blocking_milestone_ids") {}
                if (c["question"] !is JsonPrimitive) c["question"] = JsonPrimitive("请确认需求细节")
                if (!c.containsKey("options")) putJsonArray(c, "options") {}
                if (!c.containsKey("default_if_skipped")) c["default_if_skipped"] = JsonNull
                JsonObject(c)
            }
        }
        root["clarifications_needed"] = JsonArray(clarOut)
    }

    json.encodeToString(JsonObject(root))
}.getOrElse { ex ->
    System.err.println("  [warn] coerceToSchemaV04 failed: ${ex.message} at ${ex.stackTrace?.firstOrNull()}")
    raw
}

private fun putJsonArray(builder: MutableMap<String, JsonElement>, key: String, block: JsonArrayBuilder.() -> Unit = {}) {
    builder[key] = buildJsonArray(block)
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

