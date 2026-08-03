package com.deepseek.coder.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.coder.core.AppError
import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.data.SessionRepository
import com.deepseek.coder.data.settings.SettingsRepository
import com.deepseek.coder.domain.models.ChatMessage
import com.deepseek.coder.domain.models.ChatRole
import com.deepseek.coder.domain.models.ChatSession
import com.deepseek.coder.domain.models.ChatStreamEvent
import com.deepseek.coder.domain.models.UsageSnapshot
import com.deepseek.coder.domain.usecases.SendChatStreamUseCase
import com.deepseek.coder.domain.workflow.Orchestrator
import com.deepseek.coder.domain.workflow.OrchestratorEvent
import com.deepseek.coder.domain.workflow.WorkflowEvent
import com.deepseek.coder.domain.workflow.WorkflowPlan
import com.deepseek.coder.domain.workflow.WorkflowState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sendStream: SendChatStreamUseCase,
    private val orchestrator: Orchestrator,
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val initialSessionId: String? = savedStateHandle["sessionId"]

    data class UiState(
        val sessionId: String? = null,
        val messages: List<ChatMessage> = emptyList(),
        val input: String = "",
        val streaming: Boolean = false,
        val error: String? = null,
        val thinkingExpanded: Boolean = false,
        val lastUsage: UsageSnapshot? = null,
        val loading: Boolean = true,
        // ---- Orchestrator UI state ----
        val orchestratorEnabled: Boolean = true,
        val workflowState: WorkflowState = WorkflowState.IDLE,
        val classificationLabel: String? = null,
        val plan: WorkflowPlan? = null,
        val activeStepIndex: Int = -1,
        val completedSteps: Set<Int> = emptySet(),
        val selfCheckSummary: String? = null,
        val clarifyingQuestions: List<String>? = null
    ) {
        val canSend: Boolean get() = input.isNotBlank() && !streaming
        val canCancel: Boolean get() = streaming
        val showWorkflowCard: Boolean
            get() = orchestratorEnabled && (
                    workflowState != WorkflowState.IDLE ||
                            plan != null ||
                            classificationLabel != null
                    )
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Mirror settings so we always check orchestratorEnabled using latest preference value. */
    val settingsState = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = com.deepseek.coder.data.settings.AppSettings()
    )

    private var streamingJob: Job? = null
    private var currentRunId: String? = null

    init {
        viewModelScope.launch {
            val sessionId = initialSessionId?.takeUnless { it.isBlank() || it == "new" }
            if (sessionId != null) {
                val existing = sessionRepository.getMessages(sessionId)
                if (existing.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            sessionId = sessionId,
                            messages = existing,
                            loading = false,
                            orchestratorEnabled = settingsState.value.orchestratorEnabled
                        )
                    }
                    return@launch
                }
            }
            _state.update { it.copy(loading = false, orchestratorEnabled = settingsState.value.orchestratorEnabled) }
        }
    }

    fun onInputChanged(value: String) {
        _state.update { it.copy(input = value, error = null) }
    }

    fun toggleThinkingExpanded() {
        _state.update { it.copy(thinkingExpanded = !it.thinkingExpanded) }
    }

    /** Answer clarifying questions while the orchestrator is paused in CLARIFY_QUESTION state. */
    fun answerClarifications(answers: List<String>) {
        val runId = currentRunId ?: return
        viewModelScope.launch { orchestrator.answerClarification(runId, answers) }
        _state.update { it.copy(clarifyingQuestions = null) }
    }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isBlank()) return
        val userMsg = ChatMessage(role = ChatRole.USER, text = text)
        val assistantPlaceholder = ChatMessage(role = ChatRole.ASSISTANT, text = "", reasoning = "", pending = true)
        _state.update {
            it.copy(
                messages = it.messages + userMsg + assistantPlaceholder,
                input = "",
                streaming = true,
                error = null,
                orchestratorEnabled = settingsState.value.orchestratorEnabled,
                workflowState = WorkflowState.IDLE,
                plan = null,
                activeStepIndex = -1,
                completedSteps = emptySet(),
                classificationLabel = null,
                selfCheckSummary = null,
                clarifyingQuestions = null
            )
        }
        streamingJob = viewModelScope.launch {
            val current = _state.value
            val msgs = current.messages.dropLast(1)
            val effectiveSessionId = current.sessionId ?: sessionRepository.createSession(msgs.firstOrNull()?.text)
                .also { s -> _state.update { it.copy(sessionId = s.id) } }
                .id
            val session = sessionRepository.getSession(effectiveSessionId)
                ?: sessionRepository.createSession()
            sessionRepository.saveSnapshot(session, msgs)

            val useOrchestrator = settingsState.value.orchestratorEnabled
            if (useOrchestrator) {
                val runId = UUID.randomUUID().toString()
                currentRunId = runId
                orchestrator.run(runId, msgs, userMsg).collect { evt ->
                    when (evt) {
                        is WorkflowEvent.Orch -> consumeOrch(evt.event, session, msgs, effectiveSessionId, userMsg)
                        is WorkflowEvent.Chat -> consumeChat(evt.event, effectiveSessionId)
                    }
                }
            } else {
                sendStream(msgs).collect { consumeChat(it, effectiveSessionId) }
            }
        }
    }

    fun cancel() {
        streamingJob?.cancel()
        streamingJob = null
        currentRunId = null
        _state.update {
            val msgs = it.messages.toMutableList()
            if (msgs.isNotEmpty() && msgs.last().pending) {
                msgs[msgs.lastIndex] = msgs.last().copy(pending = false)
            }
            persistSnapshot(msgs, it.sessionId)
            it.copy(
                messages = msgs,
                streaming = false,
                workflowState = WorkflowState.IDLE,
                clarifyingQuestions = null
            )
        }
    }

    fun clearChat() {
        cancel()
        _state.update { it.copy(messages = emptyList(), error = null, lastUsage = null, sessionId = null) }
    }

    // -------------------------------------------------------------------------------
    // Orchestrator event consumer
    // -------------------------------------------------------------------------------
    private fun consumeOrch(
        evt: OrchestratorEvent,
        session: ChatSession,
        contextMsgs: List<ChatMessage>,
        sessionId: String,
        userMsg: ChatMessage
    ) {
        when (evt) {
            is OrchestratorEvent.Started -> Unit
            is OrchestratorEvent.StateTransition -> _state.update { it.copy(workflowState = evt.to) }
            is OrchestratorEvent.Classification -> _state.update {
                it.copy(
                    classificationLabel = evt.value.intent.display +
                            " (%.0f%%)".format(evt.value.confidence * 100f)
                )
            }
            is OrchestratorEvent.ClarifyQuestion -> _state.update {
                it.copy(clarifyingQuestions = evt.questions)
            }
            is OrchestratorEvent.PlanProduced -> _state.update { it.copy(plan = evt.plan) }
            is OrchestratorEvent.StepStarted -> _state.update { it.copy(activeStepIndex = evt.step.index) }
            is OrchestratorEvent.StepFinished -> _state.update { s ->
                s.copy(completedSteps = s.completedSteps + evt.step.index)
            }
            is OrchestratorEvent.SelfCheck -> _state.update { s ->
                s.copy(
                    selfCheckSummary = if (evt.result.pass) "通过"
                    else "发现问题：${evt.result.issues.take(2).joinToString(" / ").take(80)}"
                )
            }
            is OrchestratorEvent.ContextTrimmed -> Unit
            is OrchestratorEvent.Completed -> {
                val final = evt.finalAssistant
                if (final.text.isNotBlank()) {
                    _state.update { old ->
                        val msgs = old.messages.toMutableList()
                        // Replace pending assistant placeholder (should be last) with the completed message.
                        val lastIdx = msgs.indexOfLast { it.role == ChatRole.ASSISTANT }
                        if (lastIdx >= 0) {
                            msgs[lastIdx] = final.copy(pending = false)
                        } else {
                            msgs.add(final)
                        }
                        persistSnapshot(msgs, sessionId)
                        old.copy(messages = msgs)
                    }
                }
                _state.update { it.copy(streaming = false, workflowState = WorkflowState.DONE) }
            }
            is OrchestratorEvent.Failed -> _state.update {
                it.copy(
                    streaming = false,
                    workflowState = WorkflowState.FAILURE,
                    error = evt.error.message?.take(200) ?: "orchestrator failed"
                )
            }
        }
    }

    // -------------------------------------------------------------------------------
    // Chat-stream event consumer
    // -------------------------------------------------------------------------------
    private fun consumeChat(event: ChatStreamEvent, sessionId: String) {
        when (event) {
            ChatStreamEvent.Start -> Unit
            is ChatStreamEvent.ReasoningDelta -> appendAssistantDelta(reasoning = event.delta)
            is ChatStreamEvent.TextDelta -> appendAssistantDelta(text = event.delta)
            is ChatStreamEvent.ToolCallDelta -> {
                appendAssistantDelta(text = event.argsDelta ?: "")
            }
            is ChatStreamEvent.Finish -> {
                _state.update { old ->
                    val msgs = old.messages.toMutableList()
                    if (msgs.isNotEmpty()) msgs[msgs.lastIndex] = msgs.last().copy(pending = false)
                    persistSnapshot(msgs, sessionId)
                    if (event.usage != null) {
                        viewModelScope.launch {
                            // Fallback: SettingsRepository now exposes accumulateTokens() via new helper
                            runCatching { settingsRepository.accumulateTokens(event.usage.totalTokens.toLong()) }
                            sessionRepository.touchAndAddTokens(sessionId, event.usage.totalTokens.toLong())
                        }
                    }
                    old.copy(
                        sessionId = sessionId,
                        messages = msgs,
                        streaming = false,
                        lastUsage = event.usage,
                        error = null
                    )
                }
            }
            is ChatStreamEvent.Failure -> _state.update { old ->
                val msgs = old.messages.toMutableList()
                if (msgs.isNotEmpty()) msgs[msgs.lastIndex] = msgs.last().copy(pending = false)
                persistSnapshot(msgs, sessionId)
                old.copy(
                    sessionId = sessionId,
                    messages = msgs,
                    streaming = false,
                    error = event.error.humanReadable()
                )
            }
        }
    }

    private fun appendAssistantDelta(text: String? = null, reasoning: String? = null) {
        _state.update { old ->
            val msgs = old.messages.toMutableList()
            val lastIndex = msgs.indexOfLast { it.role == ChatRole.ASSISTANT }
            if (lastIndex < 0) {
                val new = ChatMessage(
                    role = ChatRole.ASSISTANT,
                    text = text.orEmpty(),
                    reasoning = reasoning.orEmpty(),
                    pending = true
                )
                old.copy(messages = msgs + new)
            } else {
                val prev = msgs[lastIndex]
                msgs[lastIndex] = prev.copy(
                    text = prev.text + text.orEmpty(),
                    reasoning = (prev.reasoning.orEmpty() + reasoning.orEmpty()).ifEmpty { null }
                )
                old.copy(messages = msgs)
            }
        }
    }

    private fun persistSnapshot(msgs: List<ChatMessage>, sessionId: String?) {
        val sid = sessionId ?: return
        viewModelScope.launch {
            runCatching {
                val session = sessionRepository.getSession(sid)
                    ?: sessionRepository.createSession()
                sessionRepository.saveSnapshot(session, msgs)
            }.onFailure { AppLogger.w(it, "persistSnapshot failed") }
        }
    }
}

private fun AppError.humanReadable(): String = when (this) {
    is AppError.Unauthorized -> message
    is AppError.Http -> when (code) {
        400 -> "请求格式错误：$message"
        401 -> "身份验证失败，请检查 API Key"
        403 -> "请求被拒绝：$message"
        404 -> "接口不存在"
        413 -> "输入内容过长，超过最大上下文长度"
        429 -> "请求过于频繁（限流），请稍后再试"
        500, 502, 503, 504 -> "DeepSeek 服务端暂时不可用（$code）"
        else -> "HTTP $code: ${message.take(200)}"
    }
    is AppError.Network -> "网络连接失败，请检查网络"
    is AppError.Api -> message
    is AppError.Storage -> message
    is AppError.Unknown -> "发生错误：${message.take(200)}"
}
