package com.deepseek.coder.data.workflow

import com.deepseek.coder.core.AppError
import com.deepseek.coder.core.DispatcherProvider
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
import com.deepseek.coder.data.police.DispatcherPolice
import com.deepseek.coder.data.police.EscalationTracker
import com.deepseek.coder.data.police.ExpertRunner
import com.deepseek.coder.data.police.PoliceSchemas
import com.deepseek.coder.data.police.TeamLead
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
 * Orchestrator v2.0 — 警察层接入版
 *
 * 设计依据：SPEC-Police-v1.0.md (内容为 v2.0)
 *  FSM 节点 → 警察/专家映射：
 *   - CLASSIFY        → DispatcherPolice.dispatch()       （路由警察：意图 + 动态组队）
 *   - CLARIFY_QUESTION→ ExpertRunner.runClarify()         （CLARIFY 专家：生成澄清问题）
 *   - GOVERN_CONTEXT  → ContextGovernor.trim()            （L1 硬 token 预算，GOVERN 专家决策留作后续增强）
 *   - DECOMPOSE       → TeamLead.plan()                   （组长：two-stage 制定执行计划）
 *   - EXECUTE         → ExpertRunner.run() + Actor 流式   （专家决策 capability → Actor 生成代码）
 *   - SELF_CHECK      → ExpertRunner.runCheck()           （CHECK 专家 + L1 决策矩阵）
 *
 *  原则：只决策不执行。警察/专家只输出 JSON 决策，代码生成仍走 Actor（ChatRepository.sendChat 流式）。
 *  GENERAL_CHAT 不组队、不调 Actor，直接输出 refuseHint。
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

    /** Pending clarifications for runs currently suspended on CLARIFY_QUESTION. */
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
            // ---- Step 1: CLASSIFY (路由警察 two-stage) ----
            transition(WorkflowState.CLASSIFY)
            val dispatch = dispatcherPolice.dispatch(runId, userMessage, history)
            val classification = mapDispatchToClassification(dispatch)
            send(WorkflowEvent.Orch(OrchestratorEvent.Classification(classification)))

            // ---- Step 1b: GENERAL_CHAT → 直接拒答，不组队、不调 Actor ----
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

            // ---- Step 1c: CLARIFY (CLARIFY 专家) ----
            var effectiveDispatch = dispatch
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
                    // 澄清后重新路由
                    transition(WorkflowState.CLASSIFY)
                    effectiveDispatch = dispatcherPolice.dispatch(runId, clarifiedUserMessage, history)
                    send(WorkflowEvent.Orch(OrchestratorEvent.Classification(mapDispatchToClassification(effectiveDispatch))))
                    if (effectiveDispatch.intent == PoliceSchemas.Intent.GENERAL_CHAT) {
                        val refuseMsg = ChatMessage(
                            role = ChatRole.ASSISTANT,
                            text = effectiveDispatch.refuseHint.ifBlank { defaultRefuseHint() }
                        )
                        send(WorkflowEvent.Chat(ChatStreamEvent.Finish(reason = "stop", usage = null)))
                        transition(WorkflowState.DONE)
                        send(WorkflowEvent.Orch(OrchestratorEvent.Completed(finalAssistant = refuseMsg, usage = null, retryCount = 0)))
                        return@channelFlow
                    }
                }
            }

            // ---- Step 2: GOVERN_CONTEXT (L1 硬 token 预算) ----
            transition(WorkflowState.GOVERN_CONTEXT)
            val contextIn = history + clarifiedUserMessage
            val (contextOut, trim) = withContext(dispatchers.default) {
                contextGovernor.trim(contextIn, maxTokens = max(1024, base.maxTokens * 4 / 5))
            }
            send(WorkflowEvent.Orch(OrchestratorEvent.ContextTrimmed(trim.originalCount, trim.finalCount, trim.summarised)))

            // ---- Step 3: DECOMPOSE (组长 two-stage) ----
            transition(WorkflowState.DECOMPOSE)
            val teamPlan = teamLead.plan(runId, clarifiedUserMessage.text, effectiveDispatch)
            val plan = mapTeamPlanToWorkflowPlan(teamPlan)
            send(WorkflowEvent.Orch(OrchestratorEvent.PlanProduced(plan)))

            // ---- Step 4: EXECUTE + SELF_CHECK（专家决策 → Actor 执行 → CHECK 验证）----
            var retryCount = 0
            val maxRetry = 1
            var finalAssistant = ChatMessage(role = ChatRole.ASSISTANT, text = "")
            var usageSnap: UsageSnapshot? = null
            var blocked = false

            for ((stepIdx, planStep) in teamPlan.steps.withIndex()) {
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
                var checkDecision: PoliceSchemas.CheckDecision

                do {
                    // 4a. 专家决策：生成 capability prompt（只决策不执行）
                    val expertInput = buildExpertInput(clarifiedUserMessage.text, planStep, patchSuffix)
                    val expertResult = expertRunner.run(runId, planStep.assignedExpert, expertInput)
                    val capabilityHints = buildString {
                        if (expertResult.capabilityPrompt.isNotBlank()) append(expertResult.capabilityPrompt)
                        if (expertResult.outputFormatHint.isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            append("输出格式：").append(expertResult.outputFormatHint)
                        }
                    }
                    escalationTracker.recordAttempt(runId, capabilityHints.take(200))

                    // 4b. Actor 执行（流式生成代码），专家 capability 作为系统提示增强
                    val stepWithHints = step.copy(
                        systemPromptHints = if (capabilityHints.isNotBlank()) capabilityHints else step.systemPromptHints
                    )
                    val enhancedHistory = buildStepContext(contextOut, stepWithHints, base, classification.intent)
                    val (msg, use) = executeStepStream(enhancedHistory, base, stepWithHints) { ev ->
                        trySend(WorkflowEvent.Chat(ev)).isSuccess
                    }
                    assistantForStep = msg
                    stepUsage = use

                    // 4c. CHECK 专家自检 + L1 决策矩阵
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
                                // 达到重试上限，接受当前输出
                                checkDecision = PoliceSchemas.CheckDecision.DONE
                            }
                        }
                        PoliceSchemas.CheckDecision.ESCALATE -> {
                            escalationTracker.recordEscalation(runId, checkResult.escalationReason)
                            if (escalationTracker.shouldBlock(runId)) {
                                checkDecision = PoliceSchemas.CheckDecision.BLOCKED
                            } else {
                                // 升级但未到上限：接受当前输出（重新组队留作后续轮次增强）
                                checkDecision = PoliceSchemas.CheckDecision.DONE
                            }
                        }
                        PoliceSchemas.CheckDecision.BLOCKED -> blocked = true
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

                if (blocked) break
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

    private fun defaultRefuseHint(): String =
        "这超出代码助手范围。如果你有编程相关的需求（代码生成/调试/重构/审查），我可以帮你。"

    // -------------------------------------------------------------------------------
    // Actor 执行（流式代码生成，保留原有实现）
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
