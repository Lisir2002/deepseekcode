package com.deepseek.coder.data.workflow

import com.deepseek.coder.core.AppError
import com.deepseek.coder.core.DispatcherProvider
import com.deepseek.coder.data.police.DispatcherPolice
import com.deepseek.coder.data.police.EscalationTracker
import com.deepseek.coder.data.police.ExpertRunner
import com.deepseek.coder.data.police.PoliceSchemas
import com.deepseek.coder.data.police.TeamLead
import com.deepseek.coder.data.settings.AppSettings
import com.deepseek.coder.data.settings.SettingsRepository
import com.deepseek.coder.data.workflow.prompts.WorkflowPrompts
import com.deepseek.coder.domain.models.ChatMessage
import com.deepseek.coder.domain.models.ChatRole
import com.deepseek.coder.domain.models.ChatStreamEvent
import com.deepseek.coder.domain.models.UsageSnapshot
import com.deepseek.coder.domain.workflow.CodeIntent
import com.deepseek.coder.domain.workflow.IntentClassification
import com.deepseek.coder.domain.workflow.Orchestrator
import com.deepseek.coder.domain.workflow.OrchestratorEvent
import com.deepseek.coder.domain.workflow.SelfCheckResult
import com.deepseek.coder.domain.workflow.WorkflowEvent
import com.deepseek.coder.domain.workflow.WorkflowPlan
import com.deepseek.coder.domain.workflow.WorkflowState
import com.deepseek.coder.domain.workflow.WorkflowStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Orchestrator v2.1 — 警察层接入版（6 项架构修复）
 *
 * 设计依据：SPEC-Police-v2.1.md
 *  FSM 节点 → 警察/专家映射（v2.1 修正）：
 *   - CLASSIFY        → DispatcherPolice.dispatch()       路由警察 two-stage
 *   - CLARIFY_QUESTION→ ExpertRunner.runClarify()         CLARIFY 专家
 *   - GOVERN_CONTEXT  → runGovern() + Governor.trimByStrategy()  v2.1 决策+执行分离
 *   - DECOMPOSE       → TeamLead.plan()                   组长 two-stage
 *   - EXECUTE         → ExpertRunner.run() + Actor        v2.1 GEN 只出决策，Actor 按决策生成
 *   - SELF_CHECK      → ExpertRunner.runCheck()           v2.1 + LLM 二次验证激活决策矩阵
 *
 *  v2.1 层级反馈回路（真实接通）：
 *   - 专家 feedback_to_lead → 收集反馈
 *   - 组长 shouldEscalate(feedbacks) → 判断升级
 *   - 不升级 → swapMember 动态换人（从 12 池追加/替换）
 *   - 升级 → DispatcherPolice.redispatch() 重组队，从 resume_from_step 恢复
 *   - escalation_count >= 3 → L1 强制 BLOCKED
 */
@Singleton
class OrchestratorImpl @Inject constructor(
    private val chatRepo: com.deepseek.coder.data.ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val contextGovernor: ContextGovernor,
    private val dispatchers: DispatcherProvider,
    private val dispatcherPolice: DispatcherPolice,
    private val teamLead: TeamLead,
    private val expertRunner: ExpertRunner,
    private val escalationTracker: EscalationTracker
) : Orchestrator {

    private val clarifications = mutableMapOf<String, MutableList<String>>()
    private val clarificationsMutex = Mutex()

    override suspend fun answerClarification(runId: String, answers: List<String>) {
        clarificationsMutex.withLock {
            clarifications.getOrPut(runId) { mutableListOf() }.addAll(answers)
        }
    }

    private suspend fun takeClarifications(runId: String): List<String> = clarificationsMutex.withLock {
        clarifications.remove(runId).orEmpty()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun run(
        runId: String,
        history: List<ChatMessage>,
        userMessage: ChatMessage
    ): Flow<WorkflowEvent> = channelFlow {
        val base = settingsRepo.current()
        val currentStateHolder = CurrentState(WorkflowState.IDLE)
        suspend fun transition(to: WorkflowState) {
            val from = currentStateHolder.swap(to)
            send(WorkflowEvent.Orch(OrchestratorEvent.StateTransition(from, to)))
        }
        send(WorkflowEvent.Orch(OrchestratorEvent.Started(runId)))

        try {
            // ---- Step 1: CLASSIFY ----
            transition(WorkflowState.CLASSIFY)
            var dispatch = dispatcherPolice.dispatch(runId, userMessage, history)
            var classification = mapDispatchToClassification(dispatch)
            send(WorkflowEvent.Orch(OrchestratorEvent.Classification(classification)))

            // ---- Step 1b: GENERAL_CHAT 直接拒答 ----
            if (dispatch.intent == PoliceSchemas.Intent.GENERAL_CHAT) {
                val refuseMsg = ChatMessage(
                    role = ChatRole.ASSISTANT,
                    text = dispatch.refuseHint.ifBlank { defaultRefuseHint() }
                )
                send(WorkflowEvent.Chat(ChatStreamEvent.Finish(reason = "stop", usage = null)))
                transition(WorkflowState.DONE)
                send(WorkflowEvent.Orch(OrchestratorEvent.Completed(finalAssistant = refuseMsg, usage = null, retryCount = 0)))
                return@channelFlow
            }

            // ---- Step 1c: CLARIFY ----
            var clarifiedUserMessage = userMessage
            if (dispatch.needClarify || dispatch.intent == PoliceSchemas.Intent.NEEDS_CLARIFICATION) {
                transition(WorkflowState.CLARIFY_QUESTION)
                val clarifyReason = dispatch.refuseHint.ifBlank { dispatch.routingReason }
                val clarifyResult = expertRunner.runClarify(runId, userMessage.text, clarifyReason)
                val questions = clarifyResult.clarifyQuestions
                    .map { it.question }
                    .filter { it.isNotBlank() }
                    .ifEmpty { listOf("请补充更多细节，以便我更好地帮你") }
                send(WorkflowEvent.Orch(OrchestratorEvent.ClarifyQuestion(questions)))

                val answers = waitForClarification(runId, timeoutMs = 90_000, fallback = emptyList())
                if (answers.isNotEmpty()) {
                    clarifiedUserMessage = userMessage.copy(
                        text = buildString {
                            append(userMessage.text)
                            append("\n\n补充信息：")
                            questions.zip(answers).forEach { (q, a) ->
                                append("\n- ").append(q).append("：").append(a)
                            }
                        }
                    )
                    transition(WorkflowState.CLASSIFY)
                    dispatch = dispatcherPolice.dispatch(runId, clarifiedUserMessage, history)
                    send(WorkflowEvent.Orch(OrchestratorEvent.Classification(mapDispatchToClassification(dispatch))))
                    if (dispatch.intent == PoliceSchemas.Intent.GENERAL_CHAT) {
                        val refuseMsg = ChatMessage(
                            role = ChatRole.ASSISTANT,
                            text = dispatch.refuseHint.ifBlank { defaultRefuseHint() }
                        )
                        send(WorkflowEvent.Chat(ChatStreamEvent.Finish(reason = "stop", usage = null)))
                        transition(WorkflowState.DONE)
                        send(WorkflowEvent.Orch(OrchestratorEvent.Completed(finalAssistant = refuseMsg, usage = null, retryCount = 0)))
                        return@channelFlow
                    }
                }
            }

            // ---- Step 2: GOVERN_CONTEXT（v2.1：GOVERN 决策 + Governor 执行）----
            transition(WorkflowState.GOVERN_CONTEXT)
            val contextIn = history + clarifiedUserMessage
            val (contextOut, trim) = if (history.size > 8) {
                // 历史超 8 轮，调 GOVERN 专家出策略
                val historySummary = buildHistorySummaryForGovern(contextIn)
                val governResult = expertRunner.runGovern(runId, historySummary, base.maxTokens)
                val (trimmed, report) = contextGovernor.trimByStrategy(contextIn, governResult)
                trimmed to report
            } else {
                // 历史短，直接用硬 token 预算
                withContext(dispatchers.default) {
                    contextGovernor.trim(contextIn, maxTokens = max(1024, base.maxTokens * 4 / 5))
                }
            }
            send(WorkflowEvent.Orch(OrchestratorEvent.ContextTrimmed(trim.originalCount, trim.finalCount, trim.summarised)))

            // ---- Step 3: DECOMPOSE ----
            transition(WorkflowState.DECOMPOSE)
            var teamPlan = teamLead.plan(runId, clarifiedUserMessage.text, dispatch)
            var plan = mapTeamPlanToWorkflowPlan(teamPlan)
            send(WorkflowEvent.Orch(OrchestratorEvent.PlanProduced(plan)))

            // ---- Step 4: EXECUTE + SELF_CHECK（含 v2.1 层级反馈回路）----
            var retryCount = 0
            val maxRetry = 1
            var finalAssistant = ChatMessage(role = ChatRole.ASSISTANT, text = "")
            var usageSnap: UsageSnapshot? = null
            var blocked = false
            var currentTeam = dispatch.expertTeam
            var currentLead = dispatch.teamLead

            // 支持升级重组队后的恢复执行
            var startStepIdx = 0
            var escalationRounds = 0
            val maxEscalationRounds = 3

            outer@ while (escalationRounds <= maxEscalationRounds) {
                for (stepIdx in startStepIdx until teamPlan.steps.size) {
                    val planStep = teamPlan.steps[stepIdx]
                    escalationTracker.updateProgress(runId, stepIdx, "executing: ${planStep.title}")
                    val step = WorkflowStep(
                        index = stepIdx,
                        title = planStep.title,
                        systemPromptHints = buildStepHints(planStep),
                        dependsOn = planStep.dependsOn.mapNotNull { id ->
                            teamPlan.steps.indexOfFirst { it.id == id }.takeIf { it >= 0 }
                        },
                        requiresSelfCheck = (stepIdx == teamPlan.steps.lastIndex)
                    )
                    transition(WorkflowState.EXECUTE)
                    send(WorkflowEvent.Orch(OrchestratorEvent.StepStarted(step)))

                    var stepAttempts = 0
                    var assistantForStep: ChatMessage = ChatMessage(role = ChatRole.ASSISTANT, text = "")
                    var stepUsage: UsageSnapshot? = null
                    var patchSuffix = ""
                    var checkDecision: PoliceSchemas.CheckDecision = PoliceSchemas.CheckDecision.DONE
                    var stepFeedback: String = ""
                    // v2.1：本步当前专家（动态换人时更新，初始取计划分配的专家）
                    var currentStepExpert = planStep.assignedExpert

                    stepRetry@ do {
                        // 4a. 专家决策（v2.1：GEN 只出决策不写代码，用 currentStepExpert 以支持换人）
                        val expertInput = buildExpertInput(clarifiedUserMessage.text, planStep, patchSuffix)
                        val expertResult = expertRunner.run(runId, currentStepExpert, expertInput)
                        stepFeedback = expertResult.feedbackToLead
                        escalationTracker.recordAttempt(runId, planStep.title.take(200))

                        // v2.1: 收集组员反馈，判断是否需要换人/升级
                        if (stepFeedback.isNotBlank()) {
                            val shouldEscalate = teamLead.shouldEscalate(listOf(stepFeedback))
                            if (shouldEscalate) {
                                // 升级回路由警察重组队
                                transition(WorkflowState.RETRY_FIX)
                                val redispatchResult = dispatcherPolice.redispatch(
                                    runId = runId,
                                    escalationReason = stepFeedback,
                                    currentTeam = currentTeam,
                                    currentLead = currentLead,
                                    failedStepId = planStep.id,
                                    userMessage = clarifiedUserMessage.text
                                )
                                if (redispatchResult == null) {
                                    // L1 强制 BLOCKED
                                    blocked = true
                                    break@outer
                                }
                                // 重组队成功，更新队伍 + 从失败步恢复
                                currentTeam = redispatchResult.newTeam
                                currentLead = redispatchResult.newTeamLead
                                startStepIdx = teamPlan.steps.indexOfFirst { it.id == redispatchResult.resumeFromStep }
                                    .takeIf { it >= 0 } ?: stepIdx
                                escalationRounds += 1
                                // 重新制定计划（新组长）
                                transition(WorkflowState.DECOMPOSE)
                                val newDispatch = dispatch.copy(
                                    expertTeam = currentTeam,
                                    teamLead = currentLead
                                )
                                teamPlan = teamLead.plan(runId, clarifiedUserMessage.text, newDispatch)
                                plan = mapTeamPlanToWorkflowPlan(teamPlan)
                                send(WorkflowEvent.Orch(OrchestratorEvent.PlanProduced(plan)))
                                continue@outer
                            } else {
                                // 不升级，尝试动态换人
                                val swapResult = teamLead.swapMember(runId, stepFeedback, currentTeam)
                                if (swapResult.shouldSwap && swapResult.removeExpert != null && swapResult.addExpert != null) {
                                    currentTeam = currentTeam.map {
                                        if (it == swapResult.removeExpert) swapResult.addExpert!! else it
                                    }
                                    // v2.1：换人对当步专家立即生效，重试本步
                                    if (currentStepExpert == swapResult.removeExpert) {
                                        currentStepExpert = swapResult.addExpert!!
                                    }
                                    stepAttempts += 1
                                    continue@stepRetry
                                }
                                // 不换人，按原计划继续
                            }
                        }

                        // 4b. Actor 执行（v2.1：按专家决策约束生成代码）
                        val decisionHints = buildDecisionHints(expertResult)
                        val stepWithHints = step.copy(
                            systemPromptHints = if (decisionHints.isNotBlank()) decisionHints else step.systemPromptHints
                        )
                        val enhancedHistory = buildStepContext(contextOut, stepWithHints, base, classification.intent)
                        val (msg, use) = executeStepStream(enhancedHistory, base, stepWithHints) { ev ->
                            trySend(WorkflowEvent.Chat(ev)).isSuccess
                        }
                        assistantForStep = msg
                        stepUsage = use

                        // 4c. CHECK 专家自检 + LLM 二次验证
                        transition(WorkflowState.SELF_CHECK)
                        val checkResult = expertRunner.runCheck(runId, assistantForStep.text, "")
                        val check = mapExpertCheckToSelfCheck(checkResult)
                        send(WorkflowEvent.Orch(OrchestratorEvent.SelfCheck(check)))
                        checkDecision = PoliceSchemas.CheckDecision.coerce(checkResult.decision)

                        when (checkDecision) {
                            PoliceSchemas.CheckDecision.RETRY,
                            PoliceSchemas.CheckDecision.REWORK -> {
                                if (stepAttempts < maxRetry && !check.suggestedFixPrompt.isNullOrBlank()) {
                                    transition(WorkflowState.RETRY_FIX)
                                    stepAttempts += 1
                                    retryCount += 1
                                    patchSuffix = check.suggestedFixPrompt
                                } else {
                                    checkDecision = PoliceSchemas.CheckDecision.DONE
                                }
                            }
                            PoliceSchemas.CheckDecision.ESCALATE -> {
                                // CHECK 升级 → 路由重组队
                                val redispatchResult = dispatcherPolice.redispatch(
                                    runId = runId,
                                    escalationReason = checkResult.escalationReason.ifBlank { "CHECK ESCALATE" },
                                    currentTeam = currentTeam,
                                    currentLead = currentLead,
                                    failedStepId = planStep.id,
                                    userMessage = clarifiedUserMessage.text
                                )
                                if (redispatchResult == null) {
                                    blocked = true
                                    break@outer
                                }
                                currentTeam = redispatchResult.newTeam
                                currentLead = redispatchResult.newTeamLead
                                startStepIdx = teamPlan.steps.indexOfFirst { it.id == redispatchResult.resumeFromStep }
                                    .takeIf { it >= 0 } ?: stepIdx
                                escalationRounds += 1
                                transition(WorkflowState.DECOMPOSE)
                                val newDispatch = dispatch.copy(
                                    expertTeam = currentTeam,
                                    teamLead = currentLead
                                )
                                teamPlan = teamLead.plan(runId, clarifiedUserMessage.text, newDispatch)
                                plan = mapTeamPlanToWorkflowPlan(teamPlan)
                                send(WorkflowEvent.Orch(OrchestratorEvent.PlanProduced(plan)))
                                continue@outer
                            }
                            PoliceSchemas.CheckDecision.BLOCKED -> {
                                blocked = true
                            }
                            PoliceSchemas.CheckDecision.DONE -> { /* 继续 */ }
                        }
                    } while (checkDecision == PoliceSchemas.CheckDecision.RETRY ||
                        checkDecision == PoliceSchemas.CheckDecision.REWORK
                    )

                    if (stepUsage != null) usageSnap = stepUsage
                    finalAssistant = finalAssistant.copy(
                        text = finalAssistant.text + if (finalAssistant.text.isBlank()) "" else "\n\n" + stepTitle(step) + "\n" + assistantForStep.text,
                        reasoning = listOfNotNull(finalAssistant.reasoning, assistantForStep.reasoning)
                            .filter { it.isNotBlank() }
                            .joinToString("\n---\n")
                            .ifBlank { null }
                    )
                    send(WorkflowEvent.Orch(OrchestratorEvent.StepFinished(step)))

                    if (blocked) break@outer
                }
                break@outer  // 所有步骤完成
            }

            send(WorkflowEvent.Chat(ChatStreamEvent.Finish(reason = "stop", usage = usageSnap)))
            transition(WorkflowState.DONE)
            send(
                WorkflowEvent.Orch(
                    OrchestratorEvent.Completed(
                        finalAssistant = finalAssistant,
                        usage = usageSnap,
                        retryCount = retryCount
                    )
                )
            )
        } finally {
            escalationTracker.clear(runId)
        }
    }
        .catch { t ->
            emit(WorkflowEvent.Orch(OrchestratorEvent.Failed(t)))
            if (t !is kotlinx.coroutines.CancellationException) {
                emit(
                    WorkflowEvent.Chat(
                        ChatStreamEvent.Failure(
                            when (t) {
                                is AppError -> t
                                else -> AppError.Unknown(t.message ?: "orchestrator error", cause = t)
                            }
                        )
                    )
                )
            }
        }
        .flowOn(dispatchers.default)

    // -------------------------------------------------------------------------------
    // v2.1：专家决策 → Actor 提示构建
    // -------------------------------------------------------------------------------

    /** v2.1：把专家决策（GEN 的 techStack/constraints/... 或其他专家的 capabilityPrompt）转为 Actor 提示。 */
    private fun buildDecisionHints(result: PoliceSchemas.ExpertResult): String = buildString {
        // v2.1 GEN 决策字段优先
        if (result.techStack.isNotEmpty()) {
            appendLine("技术栈：${result.techStack.joinToString(", ")}")
        }
        if (result.constraints.isNotEmpty()) {
            appendLine("实现约束：")
            result.constraints.forEach { appendLine("- $it") }
        }
        if (result.acceptanceCriteria.isNotEmpty()) {
            appendLine("验收点：")
            result.acceptanceCriteria.forEach { appendLine("- $it") }
        }
        if (result.risks.isNotEmpty()) {
            appendLine("风险提示：")
            result.risks.forEach { appendLine("- $it") }
        }
        // v2.0 兼容：其他专家仍用 capability_prompt
        if (result.capabilityPrompt.isNotBlank() && isEmpty()) {
            append(result.capabilityPrompt)
        }
    }.trim()

    // -------------------------------------------------------------------------------
    // 警察层 → 领域模型映射
    // -------------------------------------------------------------------------------

    private fun mapDispatchToClassification(d: PoliceSchemas.DispatcherResult): IntentClassification {
        val intent = mapPoliceIntentToCodeIntent(d.intent)
        val confidence = when (d.cap) {
            PoliceSchemas.Cap.SIMPLE -> 0.9f
            PoliceSchemas.Cap.MEDIUM -> 0.7f
            PoliceSchemas.Cap.COMPLEX -> 0.5f
            PoliceSchemas.Cap.HARD -> 0.3f
        }
        val missingInfo = if (d.needClarify) listOf(d.refuseHint.ifBlank { "需要更多信息" }) else emptyList()
        return IntentClassification(intent, confidence, missingInfo)
    }

    private fun mapPoliceIntentToCodeIntent(i: PoliceSchemas.Intent): CodeIntent = when (i) {
        PoliceSchemas.Intent.CODE_GENERATE -> CodeIntent.CODE_GENERATE
        PoliceSchemas.Intent.CODE_EXPLAIN -> CodeIntent.CODE_EXPLAIN
        PoliceSchemas.Intent.CODE_REFACTOR -> CodeIntent.CODE_REFACTOR
        PoliceSchemas.Intent.CODE_FIX_BUG -> CodeIntent.CODE_FIX_BUG
        PoliceSchemas.Intent.CODE_TRANSLATE -> CodeIntent.CODE_TRANSLATE
        PoliceSchemas.Intent.CODE_REVIEW -> CodeIntent.CODE_REVIEW
        PoliceSchemas.Intent.DESIGN_ARCH -> CodeIntent.DESIGN_ARCH
        PoliceSchemas.Intent.WRITE_TEST -> CodeIntent.CODE_GENERATE
        PoliceSchemas.Intent.ADD_DEPENDENCY -> CodeIntent.CODE_GENERATE
        PoliceSchemas.Intent.GENERAL_CHAT -> CodeIntent.GENERAL_CHAT
        PoliceSchemas.Intent.NEEDS_CLARIFICATION -> CodeIntent.NEEDS_CLARIFICATION
    }

    private fun mapTeamPlanToWorkflowPlan(p: PoliceSchemas.TeamLeadResult): WorkflowPlan {
        val steps = p.steps.mapIndexed { i, s ->
            WorkflowStep(
                index = i,
                title = s.title,
                systemPromptHints = buildStepHints(s),
                dependsOn = s.dependsOn.mapNotNull { id ->
                    p.steps.indexOfFirst { it.id == id }.takeIf { it >= 0 }
                },
                requiresSelfCheck = (i == p.steps.lastIndex)
            )
        }
        return WorkflowPlan(steps = steps)
    }

    private fun buildStepHints(s: PoliceSchemas.PlanStep): String = buildString {
        if (s.what.isNotBlank()) append("目标：").append(s.what).append('\n')
        if (s.why.isNotBlank()) append("原因：").append(s.why).append('\n')
        if (s.edgeCase.isNotBlank()) append("边界：").append(s.edgeCase).append('\n')
        if (s.testHint.isNotBlank()) append("测试提示：").append(s.testHint)
    }.trim()

    private fun mapExpertCheckToSelfCheck(r: PoliceSchemas.ExpertResult): SelfCheckResult {
        val decision = PoliceSchemas.CheckDecision.coerce(r.decision)
        val passed = r.passed ?: (decision == PoliceSchemas.CheckDecision.DONE)
        val issues = listOfNotNull(
            r.errorReason.takeIf { it.isNotBlank() },
            r.escalationReason.takeIf { it.isNotBlank() }
        )
        val fix = r.patchPromptSuffix.takeIf { it.isNotBlank() }
        return SelfCheckResult(pass = passed, issues = issues, suggestedFixPrompt = fix)
    }

    private fun buildExpertInput(
        userMessage: String,
        planStep: PoliceSchemas.PlanStep,
        patchSuffix: String
    ): String = buildString {
        appendLine("用户需求：")
        appendLine(userMessage.take(3000))
        appendLine()
        appendLine("当前步骤：${planStep.title}")
        appendLine("步骤目标：${planStep.what.take(800)}")
        if (patchSuffix.isNotBlank()) {
            appendLine()
            appendLine("上一轮自检反馈（请据此调整思路，不要重复相同方案）：")
            appendLine(patchSuffix.take(800))
        }
    }

    private fun buildHistorySummaryForGovern(history: List<ChatMessage>): String = buildString {
        history.takeLast(12).forEachIndexed { i, m ->
            appendLine("m${history.indexOf(m)} [${m.role.name.lowercase()}]: ${m.text.take(120).replace("\n", " ")}")
        }
    }.take(6000)

    private fun defaultRefuseHint(): String =
        "这超出代码助手范围。如果你有编程相关的需求（代码生成/调试/重构/审查），我可以帮你。"

    // -------------------------------------------------------------------------------
    // Actor 执行（流式代码生成）
    // -------------------------------------------------------------------------------

    private suspend fun executeStepStream(
        context: List<ChatMessage>,
        base: AppSettings,
        step: WorkflowStep,
        sendStream: (ChatStreamEvent) -> Boolean
    ): Pair<ChatMessage, UsageSnapshot?> {
        val overridden = base.copy(
            systemPrompt = WorkflowPrompts.buildRootSystemPrompt(
                baseSystemPrompt = base.systemPrompt
            ) + if (step.systemPromptHints.isNotBlank()) "\n\n${step.systemPromptHints}" else ""
        )
        val textBuf = StringBuilder()
        var reasonBuf: String? = null
        var usageSnap: UsageSnapshot? = null
        chatRepo.sendChat(context) { overridden }.collect { ev ->
            sendStream(ev)
            when (ev) {
                is ChatStreamEvent.TextDelta -> if (ev.delta.isNotEmpty()) textBuf.append(ev.delta)
                is ChatStreamEvent.ReasoningDelta -> if (ev.delta.isNotEmpty()) {
                    reasonBuf = (reasonBuf.orEmpty() + ev.delta)
                }
                is ChatStreamEvent.Finish -> usageSnap = ev.usage
                else -> {}
            }
        }
        val msg = ChatMessage(
            role = ChatRole.ASSISTANT,
            text = textBuf.toString(),
            reasoning = reasonBuf
        )
        return msg to usageSnap
    }

    private fun buildStepContext(
        baseHistory: List<ChatMessage>,
        step: WorkflowStep,
        s: AppSettings,
        intent: CodeIntent
    ): List<ChatMessage> {
        val firstSystem = baseHistory.firstOrNull { it.role == ChatRole.SYSTEM }
        val first = ChatMessage(
            role = ChatRole.SYSTEM,
            text = WorkflowPrompts.buildRootSystemPrompt(firstSystem?.text ?: s.systemPrompt) +
                    if (step.systemPromptHints.isNotBlank()) "\n\n当前步骤提示：${step.systemPromptHints}" else ""
        )
        val intentHint = ChatMessage(
            role = ChatRole.USER,
            text = "【本次任务类型：${intent.display}】请严格按任务类型输出。"
        )
        val rest = baseHistory.dropWhile { it.role == ChatRole.SYSTEM }
        return listOf(first) + listOf(intentHint) + rest
    }

    private fun stepTitle(step: WorkflowStep): String = "### 步骤 ${step.index + 1}：${step.title}"

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun waitForClarification(
        runId: String,
        timeoutMs: Long,
        fallback: List<String>
    ): List<String> = kotlinx.coroutines.flow.callbackFlow {
        val start = System.currentTimeMillis()
        launch(dispatchers.default) {
            while (System.currentTimeMillis() - start < timeoutMs) {
                val got = takeClarifications(runId)
                if (got.isNotEmpty()) {
                    channel.send(got)
                    return@launch
                }
                kotlinx.coroutines.delay(500)
            }
            channel.send(fallback)
        }
        awaitClose()
    }.flowOn(dispatchers.default).first()

    private class CurrentState(var value: WorkflowState) {
        fun swap(new: WorkflowState): WorkflowState {
            val prev = value
            value = new
            return prev
        }
    }
}
