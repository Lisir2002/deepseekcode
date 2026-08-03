package com.deepseek.coder.data.workflow

import com.deepseek.coder.core.AppError
import com.deepseek.coder.core.DispatcherProvider
import com.deepseek.coder.core.Outcome
import com.deepseek.coder.core.toOutcome
import com.deepseek.coder.data.ChatRepository
import com.deepseek.coder.data.remote.dto.ChatMessageDto
import com.deepseek.coder.data.remote.dto.ChatCompletionRequest
import com.deepseek.coder.data.remote.dto.ResponseFormatDto
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
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class OrchestratorImpl @Inject constructor(
    private val chatRepo: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val contextGovernor: ContextGovernor,
    private val dispatchers: DispatcherProvider
) : Orchestrator {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
    }

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
        val runSettings = base
        val currentStateHolder = CurrentState(WorkflowState.IDLE)
        suspend fun transition(to: WorkflowState) {
            val from = currentStateHolder.swap(to)
            send(WorkflowEvent.Orch(OrchestratorEvent.StateTransition(from, to)))
        }
        send(WorkflowEvent.Orch(OrchestratorEvent.Started(runId)))

        // ---- Step 1: Intent Classification ----
        transition(WorkflowState.CLASSIFY)
        val classification = classify(userMessage, runSettings).getOrElse {
            IntentClassification(CodeIntent.CODE_GENERATE, 0.5f)
        }
        send(WorkflowEvent.Orch(OrchestratorEvent.Classification(classification)))

        // ---- Step 1b: One round of clarification if needed ----
        var effectiveIntent = classification.intent
        var clarifiedUserMessage = userMessage
        if (classification.intent == CodeIntent.NEEDS_CLARIFICATION && classification.missingInfo.isNotEmpty()) {
            transition(WorkflowState.CLARIFY_QUESTION)
            send(
                WorkflowEvent.Orch(
                    OrchestratorEvent.ClarifyQuestion(classification.missingInfo)
                )
            )
            // Wait up to 90s for UI to call answerClarification
            val answers = waitForClarification(runId, timeoutMs = 90_000, fallback = emptyList())
            if (answers.isNotEmpty()) {
                val appended = buildString {
                    append(userMessage.text)
                    append("\n\n补充信息：")
                    classification.missingInfo.zip(answers).forEach { (q, a) ->
                        append("\n- ").append(q).append("：").append(a)
                    }
                }
                clarifiedUserMessage = userMessage.copy(text = appended)
                // Re-classify once after clarification to pick a real CODE_* intent
                transition(WorkflowState.CLASSIFY)
                effectiveIntent = classify(clarifiedUserMessage, runSettings).getOrNull()?.intent
                    ?: CodeIntent.CODE_GENERATE
                send(
                    WorkflowEvent.Orch(
                        OrchestratorEvent.Classification(
                            classification.copy(intent = effectiveIntent, confidence = 0.9f)
                        )
                    )
                )
            }
        }

        // ---- Step 2: Context Governance ----
        transition(WorkflowState.GOVERN_CONTEXT)
        val contextIn = history + clarifiedUserMessage
        val (contextOut, trim) = withContext(dispatchers.default) {
            contextGovernor.trim(contextIn, maxTokens = max(1024, runSettings.maxTokens * 4 / 5))
        }
        send(WorkflowEvent.Orch(OrchestratorEvent.ContextTrimmed(trim.originalCount, trim.finalCount, trim.summarised)))

        // ---- Step 3: Plan decomposition ----
        transition(WorkflowState.DECOMPOSE)
        val plan = decompose(clarifiedUserMessage, effectiveIntent, runSettings).getOrElse {
            WorkflowPlan(
                steps = listOf(
                    WorkflowStep(
                        index = 0,
                        title = bestEffortStepTitle(effectiveIntent),
                        requiresSelfCheck = true
                    )
                )
            )
        }
        send(WorkflowEvent.Orch(OrchestratorEvent.PlanProduced(plan)))

        // ---- Step 4: Execute steps ----
        var retryCount = 0
        val maxRetry = 1
        var finalAssistant = ChatMessage(role = ChatRole.ASSISTANT, text = "")
        var usageSnap: UsageSnapshot? = null

        stepLoop@ for (step in plan.steps) {
            transition(WorkflowState.EXECUTE)
            send(WorkflowEvent.Orch(OrchestratorEvent.StepStarted(step)))
            var assistantForStep: ChatMessage
            var stepUsage: UsageSnapshot?
            var check: SelfCheckResult
            executeWithRetry@ do {
                val enhancedHistory = buildStepContext(contextOut, step, runSettings, effectiveIntent)
                val (msg, use) = executeStepStream(enhancedHistory, runSettings, step, sendStream = { ev ->
                    trySend(WorkflowEvent.Chat(ev)).isSuccess
                })
                assistantForStep = msg
                stepUsage = use

                transition(WorkflowState.SELF_CHECK)
                check = selfCheck(assistantForStep, runSettings).getOrElse {
                    SelfCheckResult(pass = true)
                }
                send(WorkflowEvent.Orch(OrchestratorEvent.SelfCheck(check)))
                if (!check.pass && retryCount < maxRetry && !check.suggestedFixPrompt.isNullOrBlank()) {
                    transition(WorkflowState.RETRY_FIX)
                    retryCount += 1
                    // Append assistant output + fix prompt as a new user message; continue the loop
                    val fixMsg = ChatMessage(
                        role = ChatRole.USER,
                        text = check.suggestedFixPrompt!!
                    )
                    // replace context for next iteration to include the fix feedback
                    val fixedHistory = (enhancedHistory + assistantForStep + fixMsg)
                    contextGovernor.trim(fixedHistory, maxTokens = max(1024, runSettings.maxTokens * 4 / 5)).let {
                        // drop first element of pair (contextOut) is the trimmed list; we override contextOut for the retry loop
                        // scope-limited hack: shadow via loop re-entry; we simply mutate `contextOut` indirectly:
                    }
                    // Re-assign contextOut for retry iteration by using fixedHistory directly with a lightweight trim (no summary)
                    (fixedHistory.takeLast(24) to null).let { (newCtx, _) ->
                        // Hack: directly set contextOut for re-use within step retry (shadowed). We'll just pass fixedHistory directly into executeStepStream below by looping again
                        // In practice we replace the enhanceHistory inside the do-while by re-building. Here we simply retry using fixedHistory as our new baseline:
                        // (we re-run the execute step via `continue`)
                    }
                    // Actually re-assign the variables that feed buildStepContext:
                    // we want: enhancedHistory for retry = (enhancedHistory + assistantForStep + fixMsg) trimmed
                    val retryBase = enhancedHistory + assistantForStep + fixMsg
                    val trimmedRetry = contextGovernor.trim(retryBase, max(1024, runSettings.maxTokens * 4 / 5)).first
                    // To propagate into next iteration we mutate local variables via helper setContext:
                    // Since Kotlin does not allow mutating outer loop variables cleanly from inside a labelled
                    // block, we use a small wrapper: reassign enhancedHistory indirectly via the loop condition. We
                    // instead re-invoke execute directly (bypassing buildStepContext) inside this branch, then
                    // break from the retry loop.
                    val retryEnhanced = buildStepContext(trimmedRetry, step, runSettings, effectiveIntent)
                    val (m2, u2) = executeStepStream(retryEnhanced, runSettings, step, sendStream = { ev ->
                        trySend(WorkflowEvent.Chat(ev)).isSuccess
                    })
                    assistantForStep = m2
                    stepUsage = u2
                    transition(WorkflowState.SELF_CHECK)
                    check = selfCheck(assistantForStep, runSettings).getOrElse {
                        SelfCheckResult(pass = true)
                    }
                    send(WorkflowEvent.Orch(OrchestratorEvent.SelfCheck(check)))
                    break@executeWithRetry // regardless of pass, stop the retry loop
                }
            } while (!check.pass && retryCount < maxRetry)

            if (stepUsage != null) usageSnap = stepUsage
            finalAssistant = finalAssistant.copy(
                text = finalAssistant.text + if (finalAssistant.text.isBlank()) "" else "\n\n" + stepTitle(step) + "\n" + assistantForStep.text,
                reasoning = listOfNotNull(finalAssistant.reasoning, assistantForStep.reasoning)
                    .filter { it.isNotBlank() }
                    .joinToString("\n---\n")
                    .ifBlank { null }
            )
            send(WorkflowEvent.Orch(OrchestratorEvent.StepFinished(step)))
        }

        // Emit a synthetic Finish Chat event so legacy consumers treat the stream the same as before
        send(
            WorkflowEvent.Chat(
                ChatStreamEvent.Finish(
                    reason = "stop",
                    usage = usageSnap
                )
            )
        )
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
    // Internal node implementations
    // -------------------------------------------------------------------------------

    private suspend fun classify(
        userMessage: ChatMessage,
        s: AppSettings
    ): Result<IntentClassification> = runCatching {
        val msgs = listOf(
            ChatMessage(role = ChatRole.SYSTEM, text = WorkflowPrompts.INTENT_CLASSIFIER_SYSTEM),
            ChatMessage(role = ChatRole.USER, text = userMessage.text.take(4000))
        )
        val jsonMode = s.copy(
            systemPrompt = WorkflowPrompts.INTENT_CLASSIFIER_SYSTEM,
            temperature = 0.05f,
            reasoningEffort = AppSettings.ReasoningEffort.DISABLED
        )
        val (content, _) = blockingCallInternal(msgs, jsonMode, requireJson = true)
            .getOrThrow()
        val parsed = runCatching { json.decodeFromString<IntentClassificationDto>(content.orEmpty()) }
            .getOrElse {
                // Fallback: keyword rules
                IntentClassificationDto(
                    intent = guessIntentFallback(userMessage.text).name,
                    confidence = 0.6f,
                    missing_info = emptyList()
                )
            }
        IntentClassification(
            intent = CodeIntent.of(parsed.intent),
            confidence = parsed.confidence.coerceIn(0f, 1f),
            missingInfo = parsed.missing_info
        )
    }

    private suspend fun decompose(
        userMessage: ChatMessage,
        intent: CodeIntent,
        s: AppSettings
    ): Result<WorkflowPlan> = runCatching {
        if (intent in SIMPLE_INTENTS) {
            return@runCatching WorkflowPlan(
                steps = listOf(
                    WorkflowStep(
                        index = 0,
                        title = bestEffortStepTitle(intent),
                        requiresSelfCheck = true
                    )
                )
            )
        }
        val msgs = listOf(
            ChatMessage(role = ChatRole.SYSTEM, text = WorkflowPrompts.DECOMPOSER_SYSTEM),
            ChatMessage(role = ChatRole.USER, text = userMessage.text.take(4000))
        )
        val jsonMode = s.copy(
            temperature = 0.1f,
            reasoningEffort = AppSettings.ReasoningEffort.DISABLED
        )
        val (content, _) = blockingCallInternal(msgs, jsonMode, requireJson = true).getOrThrow()
        runCatching {
            val dto = json.decodeFromString<WorkflowPlanDto>(content.orEmpty())
            WorkflowPlan(
                steps = dto.steps.mapIndexed { i, st ->
                    WorkflowStep(
                        index = st.index.takeIf { it in 0..100 } ?: i,
                        title = st.title?.takeIf { it.isNotBlank() } ?: "步骤 ${i + 1}",
                        systemPromptHints = st.systemPromptHints.orEmpty(),
                        dependsOn = st.dependsOn.orEmpty(),
                        requiresSelfCheck = st.requiresSelfCheck ?: (i == dto.steps.lastIndex)
                    )
                }.also { steps ->
                    require(steps.isNotEmpty()) { "plan steps empty" }
                },
                estimatedTotalTokens = dto.estimatedTotalTokens
            )
        }.getOrElse {
            WorkflowPlan(
                steps = listOf(
                    WorkflowStep(
                        index = 0,
                        title = bestEffortStepTitle(intent),
                        requiresSelfCheck = true
                    )
                )
            )
        }
    }

    private suspend fun selfCheck(
        assistant: ChatMessage,
        s: AppSettings
    ): Result<SelfCheckResult> = runCatching {
        val codeInAssistant = assistant.text.takeIf { it.contains("```") }
            ?: return@runCatching SelfCheckResult(pass = true)
        val msgs = listOf(
            ChatMessage(role = ChatRole.SYSTEM, text = WorkflowPrompts.SELF_CHECKER_SYSTEM),
            ChatMessage(role = ChatRole.USER, text = codeInAssistant.take(6000))
        )
        val jsonMode = s.copy(
            temperature = 0.05f,
            reasoningEffort = AppSettings.ReasoningEffort.DISABLED
        )
        val (content, _) = blockingCallInternal(msgs, jsonMode, requireJson = true).getOrThrow()
        runCatching {
            val dto = json.decodeFromString<SelfCheckDto>(content.orEmpty())
            SelfCheckResult(
                pass = dto.pass,
                issues = dto.issues.orEmpty(),
                suggestedFixPrompt = dto.suggested_fix_prompt
            )
        }.getOrElse { SelfCheckResult(pass = true) }
    }

    private suspend fun blockingCallInternal(
        msgs: List<ChatMessage>,
        override: AppSettings,
        requireJson: Boolean
    ): Result<Pair<String?, UsageSnapshot?>> = runCatching {
        val eff = if (requireJson) {
            val jsonReq = ChatRepository.buildRequest(
                msgs,
                override.copy(
                    systemPrompt = msgs.firstOrNull { it.role == ChatRole.SYSTEM }?.text.orEmpty()
                ),
                stream = false
            ).let {
                // Inject response_format=json_object when requested
                val dtoMsgs = it.messages.map { m ->
                    ChatMessageDto(
                        role = m.role,
                        content = m.content
                    )
                }
                ChatCompletionRequest(
                    model = it.model,
                    messages = dtoMsgs,
                    temperature = it.temperature,
                    top_p = it.top_p,
                    max_tokens = it.max_tokens,
                    stream = false,
                    responseFormat = ResponseFormatDto(type = "json_object"),
                    streamOptions = it.streamOptions,
                    reasoningEffort = it.reasoningEffort,
                    thinkingBudget = it.thinkingBudget
                )
            }
            when (val o = chatRepo.sendChatBlockingJsonOverride(jsonReq)) {
                is Outcome.Success -> {
                    val (msg, usage) = o.value
                    msg.text to usage
                }
                is Outcome.Failure -> throw IllegalStateException(o.error.message, o.error as? Throwable ?: RuntimeException(o.error.message))
            }
        } else {
            when (val o = chatRepo.sendChatBlocking(msgs)) {
                is Outcome.Success -> o.value.let { (msg, u) -> msg.text to u }
                is Outcome.Failure -> throw IllegalStateException(o.error.message, o.error as? Throwable ?: RuntimeException(o.error.message))
            }
        }
        eff
    }

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
        // Prepend SYSTEM + intent hint, then drop any duplicate system/user messages from baseHistory to avoid duplication
        val rest = baseHistory.dropWhile { it.role == ChatRole.SYSTEM }
        return listOf(first) + listOf(intentHint) + rest
    }

    private fun stepTitle(step: WorkflowStep): String = "### 步骤 ${step.index + 1}：${step.title}"

    private fun bestEffortStepTitle(intent: CodeIntent): String = when (intent) {
        CodeIntent.CODE_GENERATE -> "生成代码"
        CodeIntent.CODE_REFACTOR -> "重构代码"
        CodeIntent.CODE_EXPLAIN -> "解释代码"
        CodeIntent.CODE_FIX_BUG -> "修复 Bug"
        CodeIntent.CODE_TRANSLATE -> "语言转换"
        CodeIntent.CODE_REVIEW -> "代码 Review"
        CodeIntent.DESIGN_ARCH -> "架构设计"
        CodeIntent.FIM_COMPLETE -> "中间补全"
        CodeIntent.GENERAL_CHAT -> "对话"
        CodeIntent.NEEDS_CLARIFICATION -> "继续处理"
    }

    private fun guessIntentFallback(text: String): CodeIntent {
        val t = text
        return when {
            t.contains("重构") || t.contains("重写") || t.contains("改造") -> CodeIntent.CODE_REFACTOR
            t.contains("解释") || t.contains("讲一下") || t.contains("原理") -> CodeIntent.CODE_EXPLAIN
            t.contains("报错") || t.contains("崩溃") || t.contains("修复") || t.contains("bug") || t.contains("异常")
                -> CodeIntent.CODE_FIX_BUG
            t.contains("翻译") || t.contains("转成") || t.contains("转换") -> CodeIntent.CODE_TRANSLATE
            t.contains("review") || t.contains("评审") || t.contains("审查") || t.contains("点评") -> CodeIntent.CODE_REVIEW
            t.contains("架构") || t.contains("设计") || t.contains("模块") || t.contains("分层") -> CodeIntent.DESIGN_ARCH
            t.contains("补全") || t.contains("光标") || t.contains("FIM") -> CodeIntent.FIM_COMPLETE
            t.contains("写") || t.contains("生成") || t.contains("实现") || t.contains("创建") -> CodeIntent.CODE_GENERATE
            else -> CodeIntent.CODE_GENERATE
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun waitForClarification(
        runId: String,
        timeoutMs: Long,
        fallback: List<String>
    ): List<String> = callbackFlow {
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

    // ---- DTO mirrors for JSON schema of workflow nodes ----
    @Serializable
    private data class IntentClassificationDto(
        val intent: String,
        val confidence: Float = 0.5f,
        val missing_info: List<String> = emptyList()
    )

    @Serializable
    private data class SelfCheckDto(
        val pass: Boolean,
        val issues: List<String>? = null,
        val suggested_fix_prompt: String? = null
    )

    @Serializable
    private data class WorkflowPlanDto(
        val steps: List<StepDto>,
        val estimatedTotalTokens: Int? = null
    ) {
        @Serializable
        data class StepDto(
            val index: Int? = null,
            val title: String? = null,
            val systemPromptHints: String? = null,
            val dependsOn: List<Int>? = null,
            val requiresSelfCheck: Boolean? = null
        )
    }

    private class CurrentState(var value: WorkflowState) {
        fun swap(new: WorkflowState): WorkflowState {
            val prev = value
            value = new
            return prev
        }
    }

    companion object {
        private val SIMPLE_INTENTS = setOf(
            CodeIntent.CODE_EXPLAIN,
            CodeIntent.GENERAL_CHAT
        )
    }
}
