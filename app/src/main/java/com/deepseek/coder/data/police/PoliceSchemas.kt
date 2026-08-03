package com.deepseek.coder.data.police

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Police Layer v2.1 — 统一 Schemas + L1 运行时校验
 *
 * 设计依据：SPEC-Police-v2.1.md §3 / §4 / §5
 *  - 1 路由警察 + 12 专家池 + 自适应动态组队 + 组长动态换人 + 升级重组队
 *  - 层级反馈：路由警察 → 组长 → 组员专家，可向上反馈/升级（v2.1 真实接通）
 *  - 三层确定性蒸馏：L1 硬规则（本文件） / L2 prompt / L3 few-shot
 *  - v2.1 修正：GEN 只出决策不写代码；GOVERN 决策+执行分离；CHECK 接 LLM 二次验证
 */
object PoliceSchemas {

    /** 共享 JSON 实例（容错模式：忽略未知字段、宽松解析）。 */
    internal val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
        prettyPrint = false
    }

    // ------------------------------------------------------------------
    // 枚举（白名单，L1 校验依据）
    // ------------------------------------------------------------------

    /** 用户意图（11 类，对应 12 个专家，GENERAL_CHAT 不组队）。 */
    enum class Intent(val raw: String) {
        CODE_GENERATE("CODE_GENERATE"),
        CODE_EXPLAIN("CODE_EXPLAIN"),
        CODE_REFACTOR("CODE_REFACTOR"),
        CODE_FIX_BUG("CODE_FIX_BUG"),
        CODE_TRANSLATE("CODE_TRANSLATE"),
        CODE_REVIEW("CODE_REVIEW"),
        DESIGN_ARCH("DESIGN_ARCH"),
        WRITE_TEST("WRITE_TEST"),
        ADD_DEPENDENCY("ADD_DEPENDENCY"),
        GENERAL_CHAT("GENERAL_CHAT"),
        NEEDS_CLARIFICATION("NEEDS_CLARIFICATION");

        companion object {
            /** 最近邻映射：未知字符串归一化到合法 enum，否则默认 GENERAL_CHAT。 */
            fun coerce(raw: String?): Intent {
                val norm = raw?.trim()?.uppercase()?.replace(" ", "_").orEmpty()
                return entries.firstOrNull { it.raw == norm }
                    ?: when {
                        norm.contains("GENERATE") || norm.contains("GEN") || norm.contains("CREATE") -> CODE_GENERATE
                        norm.contains("EXPLAIN") || norm.contains("DESCRIBE") -> CODE_EXPLAIN
                        norm.contains("REFACTOR") || norm.contains("REWRITE") -> CODE_REFACTOR
                        norm.contains("FIX") || norm.contains("BUG") || norm.contains("DEBUG") -> CODE_FIX_BUG
                        norm.contains("TRANSLATE") || norm.contains("CONVERT") -> CODE_TRANSLATE
                        norm.contains("REVIEW") || norm.contains("AUDIT") -> CODE_REVIEW
                        norm.contains("ARCH") || norm.contains("DESIGN") -> DESIGN_ARCH
                        norm.contains("TEST") -> WRITE_TEST
                        norm.contains("DEPEND") || norm.contains("LIB") || norm.contains("GRADLE") -> ADD_DEPENDENCY
                        norm.contains("CLARIF") || norm.contains("AMBIGU") -> NEEDS_CLARIFICATION
                        else -> GENERAL_CHAT
                    }
            }
        }
    }

    /** 任务难度先验（simple 60% / medium 25% / complex 12% / hard 3%）。 */
    enum class Cap(val raw: String) {
        SIMPLE("simple"), MEDIUM("medium"), COMPLEX("complex"), HARD("hard");
        companion object {
            fun coerce(raw: String?): Cap {
                val norm = raw?.trim()?.lowercase().orEmpty()
                return entries.firstOrNull { it.raw == norm }
                    ?: when {
                        norm.contains("simple") || norm.contains("easy") || norm.contains("trivial") -> SIMPLE
                        norm.contains("complex") || norm.contains("complicated") -> COMPLEX
                        norm.contains("hard") || norm.contains("difficult") -> HARD
                        else -> MEDIUM
                    }
            }
        }
    }

    /** 12 个专家 ID（严格匹配）。 */
    enum class ExpertId(val raw: String, val display: String) {
        GEN("GEN", "生成专家"),
        EXPLAIN("EXPLAIN", "解释专家"),
        REFACTOR("REFACTOR", "重构专家"),
        FIX("FIX", "修复专家"),
        TRANSLATE("TRANSLATE", "翻译专家"),
        REVIEW("REVIEW", "审查专家"),
        ARCH("ARCH", "架构专家"),
        TEST("TEST", "测试专家"),
        DEPS("DEPS", "依赖专家"),
        CLARIFY("CLARIFY", "澄清专家"),
        GOVERN("GOVERN", "治理专家"),
        CHECK("CHECK", "自检专家");

        companion object {
            /** 把字符串列表归一化为合法 ExpertId（剔除未知 ID，保留顺序）。 */
            fun coerceAll(raw: List<String>?): List<ExpertId> {
                val norm = raw?.mapNotNull { id ->
                    val n = id.trim().uppercase()
                    entries.firstOrNull { it.raw == n }
                }?.distinct().orEmpty()
                return norm
            }
        }
    }

    /** 范围标签。 */
    enum class Scope(val raw: String) {
        ANDROID_KOTLIN("ANDROID_KOTLIN"),
        WEB_FRONTEND("WEB_FRONTEND"),
        GENERAL("GENERAL");
        companion object {
            fun coerce(raw: String?): Scope {
                val norm = raw?.trim()?.uppercase().orEmpty()
                return entries.firstOrNull { it.raw == norm } ?: GENERAL
            }
        }
    }

    /** 粒度（决定步数 + 深度）。 */
    enum class Granularity(val raw: String) {
        COARSE("COARSE"),   // 步数 2-3，只给 what
        MEDIUM("MEDIUM"),   // 步数 5-7，给 what + why
        FINE("FINE");       // 步数 10-15，给 what + why + edge + test
        companion object {
            fun coerce(raw: String?): Granularity {
                val norm = raw?.trim()?.uppercase().orEmpty()
                return entries.firstOrNull { it.raw == norm }
                    ?: if (norm.startsWith("FINE")) FINE else if (norm.startsWith("COARSE")) COARSE else MEDIUM
            }
        }
    }

    /** 自检 error_type（6 类）。 */
    enum class ErrorType(val raw: String) {
        SYNTAX_ERROR("syntax_error"),
        TEST_FAILURE("test_failure"),
        TIMEOUT("timeout"),
        LOGIC_ERROR("logic_error"),
        RESOURCE_ERROR("resource_error"),
        NONE("none");
        companion object {
            fun coerce(raw: String?): ErrorType {
                val norm = raw?.trim()?.lowercase().orEmpty()
                return entries.firstOrNull { it.raw == norm }
                    ?: when {
                        norm.contains("syntax") || norm.contains("compile") -> SYNTAX_ERROR
                        norm.contains("test") || norm.contains("assert") -> TEST_FAILURE
                        norm.contains("timeout") || norm.contains("slow") -> TIMEOUT
                        norm.contains("resource") || norm.contains("oom") || norm.contains("memory") -> RESOURCE_ERROR
                        norm.isEmpty() || norm == "ok" || norm == "pass" -> NONE
                        else -> LOGIC_ERROR
                    }
            }
        }
    }

    /** 自检决策（5 类）。 */
    enum class CheckDecision(val raw: String) {
        DONE("DONE"),
        RETRY("RETRY"),
        REWORK("REWORK"),
        ESCALATE("ESCALATE"),
        BLOCKED("BLOCKED");
        companion object {
            fun coerce(raw: String?): CheckDecision {
                val norm = raw?.trim()?.uppercase().orEmpty()
                return entries.firstOrNull { it.raw == norm }
                    ?: if (norm.contains("BLOCK")) BLOCKED
                    else if (norm.contains("ESCALAT") || norm.contains("UPGRADE") || norm.contains("ROUTE")) ESCALATE
                    else if (norm.contains("REWORK") || norm.contains("REWRITE")) REWORK
                    else if (norm.contains("RETRY") || norm.contains("AGAIN")) RETRY
                    else DONE
            }
        }
    }

    /** 上下文治理模式。 */
    enum class GovernMode(val raw: String) {
        KEEP_ALL("KEEP_ALL"),
        COMPRESS("COMPRESS"),
        SUMMARIZE("SUMMARIZE");
        companion object {
            fun coerce(raw: String?): GovernMode {
                val norm = raw?.trim()?.uppercase().orEmpty()
                return entries.firstOrNull { it.raw == norm } ?: KEEP_ALL
            }
        }
    }

    // ------------------------------------------------------------------
    // 路由警察 DTO
    // ------------------------------------------------------------------

    /** 路由警察 Stage 1 输出（短输出，易约束）。 */
    @Serializable
    data class DispatcherStage1Dto(
        val intent: String? = null,
        val cap: String? = null,
        val scope_tag: String? = null,
        val need_clarify: Boolean? = false,
        val refuse_hint: String? = null
    )

    /** 路由警察 Stage 2 输出（组队）。 */
    @Serializable
    data class DispatcherStage2Dto(
        val expert_team: List<String>? = null,
        val team_lead: String? = null,
        val routing_reason: String? = null
    )

    /** 路由警察最终结果（两个 stage 合并 + L1 校验后）。 */
    data class DispatcherResult(
        val intent: Intent,
        val cap: Cap,
        val scope: Scope,
        val needClarify: Boolean,
        val refuseHint: String,
        val expertTeam: List<ExpertId>,
        val teamLead: ExpertId,
        val routingReason: String
    )

    // ------------------------------------------------------------------
    // 组长 DTO
    // ------------------------------------------------------------------

    /** 组长 Stage 1 输出。 */
    @Serializable
    data class TeamLeadStage1Dto(
        val granularity: String? = null,
        val step_count: Int? = null,
        val scope_tag: String? = null
    )

    /** 组长 Stage 2 输出（执行计划）。 */
    @Serializable
    data class TeamLeadStage2Dto(
        val steps: List<PlanStepDto>? = null,
        val milestone_edges: List<MilestoneEdgeDto>? = null,
        val warn: String? = null
    )

    @Serializable
    data class PlanStepDto(
        val id: String? = null,
        val title: String? = null,
        val assigned_expert: String? = null,
        val what: String? = null,
        val why: String? = null,
        val edge_case: String? = null,
        val test_hint: String? = null,
        val depends_on: List<String>? = null,
        val estimated_duration_pct: Float? = null
    )

    @Serializable
    data class MilestoneEdgeDto(
        val from: String? = null,
        val to: String? = null
    )

    /** 组长最终结果。 */
    data class TeamLeadResult(
        val granularity: Granularity,
        val steps: List<PlanStep>,
        val milestoneEdges: List<Pair<String, String>>,
        val warn: String
    )

    data class PlanStep(
        val id: String,
        val title: String,
        val assignedExpert: ExpertId,
        val what: String,
        val why: String,
        val edgeCase: String,
        val testHint: String,
        val dependsOn: List<String>,
        val estimatedDurationPct: Float
    )

    // ------------------------------------------------------------------
    // 专家统一 DTO
    // ------------------------------------------------------------------

    /** 专家输出（统一 Schema，部分专家扩展字段）。 */
    @Serializable
    data class ExpertDto(
        val expert_id: String? = null,
        val decision: String? = null,
        // v2.0 字段（保留向后兼容，GEN v2.1 不再使用 capability_prompt）
        val capability_prompt: String? = null,
        val output_format_hint: String? = null,
        val depends_on: List<String>? = null,
        val feedback_to_lead: String? = null,
        // v2.1 GEN 决策字段（只决策不写代码）
        val tech_stack: List<String>? = null,
        val constraints: List<String>? = null,
        val acceptance_criteria: List<String>? = null,
        val risks: List<String>? = null,
        // CLARIFY 扩展
        val clarify_questions: List<ClarifyQuestionDto>? = null,
        val can_proceed_without: Boolean? = null,
        val proceed_risk: String? = null,
        // GOVERN 扩展
        val mode: String? = null,
        val keep_message_ids: List<String>? = null,
        val compress_message_ids: List<String>? = null,
        val drop_message_ids: List<String>? = null,
        val summary: String? = null,
        val estimated_tokens_after: Int? = null,
        // CHECK 扩展
        val passed: Boolean? = null,
        val error_type: String? = null,
        val error_reason: String? = null,
        val patch_prompt_suffix: String? = null,
        val escalation_reason: String? = null,
        val attempted_approaches_append: String? = null
    )

    @Serializable
    data class ClarifyQuestionDto(
        val id: String? = null,
        val question: String? = null,
        val default_hint: String? = null,
        val can_skip: Boolean? = null
    )

    /** 专家结果（领域模型，L1 校验后）。 */
    data class ExpertResult(
        val expertId: ExpertId,
        val decision: String,
        val capabilityPrompt: String,
        val outputFormatHint: String,
        val dependsOn: List<String>,
        val feedbackToLead: String,
        // v2.1 GEN 决策字段（只决策不写代码）
        val techStack: List<String>,
        val constraints: List<String>,
        val acceptanceCriteria: List<String>,
        val risks: List<String>,
        // CLARIFY
        val clarifyQuestions: List<ClarifyQuestion>,
        val canProceedWithout: Boolean,
        val proceedRisk: String,
        // GOVERN
        val governMode: GovernMode?,
        val keepMessageIds: List<String>,
        val compressMessageIds: List<String>,
        val dropMessageIds: List<String>,
        val summary: String,
        val estimatedTokensAfter: Int?,
        // CHECK
        val passed: Boolean?,
        val errorType: ErrorType?,
        val errorReason: String,
        val patchPromptSuffix: String,
        val escalationReason: String,
        val attemptedApproachesAppend: String
    )

    data class ClarifyQuestion(
        val id: String,
        val question: String,
        val defaultHint: String,
        val canSkip: Boolean
    )

    // ------------------------------------------------------------------
    // L1 运行时校验 + 修复（不可降级）
    // ------------------------------------------------------------------

    /** JSON 三层 repair：L1 直接 parse → L2 正则抽 {...} → L3 失败返回 null。 */
    fun <T> repairParse(raw: String?, deserializer: kotlinx.serialization.KSerializer<T>): T? {
        if (raw.isNullOrBlank()) return null
        // L1: 直接 parse
        runCatching { return json.decodeFromString(deserializer, raw) }
        // L2: 正则抽出第一个 {...} 块再 parse
        val matched = extractJsonObject(raw)
        if (matched != null) {
            runCatching { return json.decodeFromString(deserializer, matched) }
        }
        // L3: 失败
        return null
    }

    /** 抽取字符串中第一个平衡的 JSON 对象（从第一个 `{` 到匹配的 `}`）。 */
    internal fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 路由警察 L1 校验
    // ------------------------------------------------------------------

    /** 把两个 stage DTO 合并 + L1 校验为 [DispatcherResult]。 */
    fun buildDispatcherResult(
        s1: DispatcherStage1Dto,
        s2: DispatcherStage2Dto?,
        fallbackTeam: List<ExpertId>
    ): DispatcherResult {
        val intent = Intent.coerce(s1.intent)
        val cap = Cap.coerce(s1.cap)
        val scope = Scope.coerce(s1.scope_tag)
        val needClarify = s1.need_clarify ?: (intent == Intent.NEEDS_CLARIFICATION)
        val refuseHint = (s1.refuse_hint ?: "").take(200)

        // 组队 L1 校验
        var team = ExpertId.coerceAll(s2?.expert_team)
        if (team.isEmpty()) team = fallbackTeam
        if (team.size < 2) team = (team + ExpertId.GEN).distinct().take(4)
        if (team.size > 4) team = team.take(4)

        // GENERAL_CHAT 不组队
        if (intent == Intent.GENERAL_CHAT) {
            team = emptyList()
        }
        // NEEDS_CLARIFICATION 强制 CLARIFY
        if (intent == Intent.NEEDS_CLARIFICATION) {
            team = (team + ExpertId.CLARIFY).distinct().take(4)
            if (ExpertId.CLARIFY !in team) team = (listOf(ExpertId.CLARIFY) + team).take(4)
        }

        // 组长 L1 校验
        val leadRaw = s2?.team_lead?.trim()?.uppercase().orEmpty()
        val lead = team.firstOrNull { it.raw == leadRaw }
            ?: team.firstOrNull()
            ?: fallbackTeam.first()

        val reason = (s2?.routing_reason ?: "").take(160)

        return DispatcherResult(
            intent = intent,
            cap = cap,
            scope = scope,
            needClarify = needClarify,
            refuseHint = refuseHint,
            expertTeam = team,
            teamLead = lead,
            routingReason = reason
        )
    }

    // ------------------------------------------------------------------
    // 组长 L1 校验
    // ------------------------------------------------------------------

    /** 粒度→步数区间。 */
    fun stepRange(g: Granularity): IntRange = when (g) {
        Granularity.COARSE -> 2..3
        Granularity.MEDIUM -> 5..7
        Granularity.FINE -> 10..15
    }

    /** 把两个 stage DTO 合并 + L1 校验为 [TeamLeadResult]。 */
    fun buildTeamLeadResult(
        s1: TeamLeadStage1Dto,
        s2: TeamLeadStage2Dto?,
        fallbackGranularity: Granularity
    ): TeamLeadResult {
        val granularity = Granularity.coerce(s1.granularity) ?: fallbackGranularity
        val range = stepRange(granularity)
        val rawSteps = s2?.steps.orEmpty()
        val steps = rawSteps.mapIndexedNotNull { i, dto ->
            val id = dto.id?.takeIf { it.isNotBlank() } ?: "s${i + 1}"
            val expert = ExpertId.coerceAll(listOf(dto.assigned_expert.orEmpty())).firstOrNull()
                ?: ExpertId.GEN
            PlanStep(
                id = id,
                title = (dto.title?.takeIf { it.isNotBlank() } ?: "步骤 ${i + 1}").take(80),
                assignedExpert = expert,
                what = (dto.what ?: "").take(800),
                why = (dto.why ?: "").take(400),
                edgeCase = (dto.edge_case ?: "").take(400),
                testHint = (dto.test_hint ?: "").take(400),
                dependsOn = dto.depends_on?.filter { it.isNotBlank() } ?: emptyList(),
                estimatedDurationPct = (dto.estimated_duration_pct ?: 0f).coerceIn(0f, 1f)
            )
        }

        // 步数越界 L1 校验：超上限压缩、低于下限补默认
        val fixedSteps = when {
            steps.isEmpty() -> listOf(
                PlanStep("s1", "执行", ExpertId.GEN, "", "", "", "", emptyList(), 1f)
            )
            steps.size > range.last -> steps.take(range.last)
            steps.size < range.first && granularity != Granularity.COARSE -> {
                // 补默认步骤到下限
                val missing = range.first - steps.size
                steps + (1..missing).map { idx ->
                    PlanStep("s${steps.size + idx}", "补充步骤", ExpertId.GEN, "", "", "", "", emptyList(), 0f)
                }
            }
            else -> steps
        }

        // estimated_duration_pct 归一化（求和 = 1.0 ± 0.03）
        val sum = fixedSteps.sumOf { it.estimatedDurationPct.toDouble() }
        val normalized = if (sum > 0.001) {
            fixedSteps.map { it.copy(estimatedDurationPct = (it.estimatedDurationPct / sum).toFloat()) }
        } else {
            val each = 1f / fixedSteps.size
            fixedSteps.map { it.copy(estimatedDurationPct = each) }
        }

        val edges = s2?.milestone_edges.orEmpty().mapNotNull { e ->
            val from = e?.from?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val to = e?.to?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            from to to
        }

        val warn = (s2?.warn ?: "").take(200)

        return TeamLeadResult(
            granularity = granularity,
            steps = normalized,
            milestoneEdges = edges,
            warn = warn
        )
    }

    // ------------------------------------------------------------------
    // 专家 L1 校验
    // ------------------------------------------------------------------

    /** 把 ExpertDto L1 校验为 [ExpertResult]。 */
    fun buildExpertResult(dto: ExpertDto, expectedId: ExpertId): ExpertResult {
        val id = ExpertId.coerceAll(listOf(dto.expert_id.orEmpty())).firstOrNull() ?: expectedId
        val clarifyQuestions = dto.clarify_questions.orEmpty().mapIndexed { i, q ->
            ClarifyQuestion(
                id = (q.id?.takeIf { it.isNotBlank() } ?: "q${i + 1}").take(20),
                question = (q.question ?: "").take(100),
                defaultHint = (q.default_hint ?: "").take(80),
                canSkip = q.can_skip ?: true
            )
        }.take(3)

        return ExpertResult(
            expertId = id,
            decision = (dto.decision ?: "").take(80),
            capabilityPrompt = (dto.capability_prompt ?: "").take(500),
            outputFormatHint = (dto.output_format_hint ?: "").take(200),
            dependsOn = dto.depends_on?.filter { it.isNotBlank() } ?: emptyList(),
            feedbackToLead = (dto.feedback_to_lead ?: "").take(300),
            // v2.1 GEN 决策字段
            techStack = dto.tech_stack?.filter { it.isNotBlank() }?.take(10) ?: emptyList(),
            constraints = dto.constraints?.filter { it.isNotBlank() }?.take(15) ?: emptyList(),
            acceptanceCriteria = dto.acceptance_criteria?.filter { it.isNotBlank() }?.take(10) ?: emptyList(),
            risks = dto.risks?.filter { it.isNotBlank() }?.take(8) ?: emptyList(),
            clarifyQuestions = clarifyQuestions,
            canProceedWithout = dto.can_proceed_without ?: true,
            proceedRisk = (dto.proceed_risk ?: "").take(160),
            governMode = dto.mode?.let { GovernMode.coerce(it) },
            keepMessageIds = dto.keep_message_ids?.filter { it.isNotBlank() } ?: emptyList(),
            compressMessageIds = dto.compress_message_ids?.filter { it.isNotBlank() } ?: emptyList(),
            dropMessageIds = dto.drop_message_ids?.filter { it.isNotBlank() } ?: emptyList(),
            summary = (dto.summary ?: "").take(2000),
            estimatedTokensAfter = dto.estimated_tokens_after,
            passed = dto.passed,
            errorType = dto.error_type?.let { ErrorType.coerce(it) },
            errorReason = (dto.error_reason ?: "").take(100),
            patchPromptSuffix = (dto.patch_prompt_suffix ?: "").take(500),
            escalationReason = (dto.escalation_reason ?: "").take(160),
            attemptedApproachesAppend = (dto.attempted_approaches_append ?: "").take(200)
        )
    }

    // ------------------------------------------------------------------
    // v2.1 新增：LLM 二次验证 DTO（CHECK 节点激活决策矩阵）
    // ------------------------------------------------------------------

    /** LLM 二次验证输出（扮编译器/测试者，给出真实 error_type）。 */
    @Serializable
    data class LlmVerifyDto(
        val error_type: String? = null,
        val error_reason: String? = null,
        val confidence_bucket: String? = null
    )

    /** LLM 验证结果（领域模型）。 */
    data class LlmVerifyResult(
        val errorType: ErrorType,
        val errorReason: String,
        val confidenceBucket: ConfidenceBucket
    )

    /** 验证置信度桶。 */
    enum class ConfidenceBucket(val raw: String) {
        HIGH("high"), MEDIUM("medium"), LOW("low");
        companion object {
            fun coerce(raw: String?): ConfidenceBucket {
                val norm = raw?.trim()?.lowercase().orEmpty()
                return entries.firstOrNull { it.raw == norm } ?: MEDIUM
            }
        }
    }

    /** 把 LlmVerifyDto L1 校验为 [LlmVerifyResult]。 */
    fun buildLlmVerifyResult(dto: LlmVerifyDto): LlmVerifyResult = LlmVerifyResult(
        errorType = ErrorType.coerce(dto.error_type),
        errorReason = (dto.error_reason ?: "").take(200),
        confidenceBucket = ConfidenceBucket.coerce(dto.confidence_bucket)
    )

    // ------------------------------------------------------------------
    // v2.1 新增：组长动态换人 / 升级重组队 DTO
    // ------------------------------------------------------------------

    /** 组长换人决策输出。 */
    @Serializable
    data class SwapMemberDto(
        val action: String? = null,           // SWAP_MEMBER / KEEP_TEAM
        val remove_expert: String? = null,
        val add_expert: String? = null,
        val reason: String? = null
    )

    /** 换人结果（领域模型）。 */
    data class SwapMemberResult(
        val shouldSwap: Boolean,
        val removeExpert: ExpertId?,
        val addExpert: ExpertId?,
        val reason: String
    )

    /** 路由警察重组队决策输出。 */
    @Serializable
    data class RedispatchDto(
        val new_team: List<String>? = null,
        val new_team_lead: String? = null,
        val resume_from_step: String? = null,
        val routing_reason: String? = null
    )

    /** 重组队结果（领域模型）。 */
    data class RedispatchResult(
        val newTeam: List<ExpertId>,
        val newTeamLead: ExpertId,
        val resumeFromStep: String,
        val routingReason: String
    )

    /** 把 RedispatchDto L1 校验为 [RedispatchResult]。 */
    fun buildRedispatchResult(
        dto: RedispatchDto,
        currentLead: ExpertId,
        fallbackTeam: List<ExpertId>
    ): RedispatchResult {
        var team = ExpertId.coerceAll(dto.new_team)
        if (team.isEmpty()) team = fallbackTeam
        if (team.size < 2) team = (team + currentLead).distinct().take(4)
        if (team.size > 4) team = team.take(4)

        val leadRaw = dto.new_team_lead?.trim()?.uppercase().orEmpty()
        val lead = team.firstOrNull { it.raw == leadRaw }
            ?: team.firstOrNull()
            ?: currentLead

        return RedispatchResult(
            newTeam = team,
            newTeamLead = lead,
            resumeFromStep = (dto.resume_from_step ?: "s1").take(20),
            routingReason = (dto.routing_reason ?: "redispatch after escalation").take(160)
        )
    }
}
