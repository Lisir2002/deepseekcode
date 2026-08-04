package com.deepseek.coder.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.coder.core.AppError
import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.data.SessionRepository
import com.deepseek.coder.data.settings.SettingsRepository
import com.deepseek.coder.data.skill.AttachedFile
import com.deepseek.coder.data.skill.AttachedFileRepository
import com.deepseek.coder.data.skill.BuiltInSkills
import com.deepseek.coder.data.skill.SkillEnabledRepository
import com.deepseek.coder.data.skill.SkillResolver
import com.deepseek.coder.data.skill.ToolExecutor
import com.deepseek.coder.data.skill.ToolRequestBuilder
import com.deepseek.coder.data.skill.ToolResult
import com.deepseek.coder.domain.models.ChatMessage
import com.deepseek.coder.domain.models.ChatRole
import com.deepseek.coder.domain.models.ChatSession
import com.deepseek.coder.domain.models.ChatStreamEvent
import com.deepseek.coder.domain.models.ToolCall
import com.deepseek.coder.domain.models.UsageSnapshot
import com.deepseek.coder.domain.skill.Skill
import com.deepseek.coder.domain.skill.ToolCallAggregator
import com.deepseek.coder.domain.skill.ToolSpec
import com.deepseek.coder.domain.usecases.SendChatStreamUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sendStream: SendChatStreamUseCase,
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository,
    private val skillResolver: SkillResolver,
    private val toolExecutor: ToolExecutor,
    private val attachedFileRepository: AttachedFileRepository,
    private val skillEnabledRepository: SkillEnabledRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val initialSessionId: String? = savedStateHandle["sessionId"]

    /** UI 展示的工具调用记录（折叠卡片用，v1.2 决策 5/11/15）。 */
    data class ToolCallRecord(
        val name: String,
        val args: String,
        val result: String,
        val durationMs: Long,
        val cacheHit: Boolean
    )

    data class UiState(
        val sessionId: String? = null,
        val messages: List<ChatMessage> = emptyList(),
        val input: String = "",
        val streaming: Boolean = false,
        val error: String? = null,
        val thinkingExpanded: Boolean = false,
        val lastUsage: UsageSnapshot? = null,
        val loading: Boolean = true,
        val currentSkillId: String = "default_chat",
        val skillPickerOpen: Boolean = false,
        /** 当前流式轮次的工具调用记录（每轮清空，结束时并入对应 assistant 消息）。 */
        val pendingToolCalls: List<ToolCallRecord> = emptyList(),
        /** 已附加到沙箱的文件列表（Phase 2，UI chip 展示 + 注入到用户消息上下文）。 */
        val attachedFiles: List<AttachedFile> = emptyList(),
        /** 回路中间失败时保存的失败点历史（重试用，§3.9 决策 3）；非失败态为 null。 */
        val failedHistory: List<ChatMessage>? = null,
        /** 失败时正在用的 skill id（重试时复用）。 */
        val failedSkillId: String? = null
    ) {
        val canSend: Boolean get() = input.isNotBlank() && !streaming
        val canCancel: Boolean get() = streaming
        /** 是否可重试（有失败历史且未在流式中）。 */
        val canRetry: Boolean get() = failedHistory != null && !streaming
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val settingsState = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = com.deepseek.coder.data.settings.AppSettings()
    )

    /** 可用 skill 列表（内置 + 用户自定义合并，反映启用状态，SPEC §6.1 / Phase 4）。 */
    val availableSkills: StateFlow<List<Skill>> = combine(
        skillEnabledRepository.disabledIds,
        skillResolver.userSkills
    ) { disabled, userDefs ->
        val userSkills = userDefs.map { it.toSkill(true) }
        BuiltInSkills.mergedWith(userSkills, disabled)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BuiltInSkills.all
    )

    private var streamingJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        viewModelScope.launch {
            val sessionId = initialSessionId?.takeUnless { it.isBlank() || it == "new" }
            if (sessionId != null) {
                val existing = sessionRepository.getMessages(sessionId)
                val session = sessionRepository.getSession(sessionId)
                if (existing.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            sessionId = sessionId,
                            messages = existing,
                            currentSkillId = session?.currentSkillId ?: "default_chat",
                            loading = false
                        )
                    }
                    return@launch
                }
            }
            _state.update { it.copy(loading = false) }
        }
        // 加载已附加文件列表（Phase 2）
        viewModelScope.launch {
            val files = attachedFileRepository.list()
            _state.update { if (files.isNotEmpty()) it.copy(attachedFiles = files) else it }
        }
    }

    fun onInputChanged(value: String) {
        _state.update { it.copy(input = value, error = null) }
    }

    fun toggleThinkingExpanded() {
        _state.update { it.copy(thinkingExpanded = !it.thinkingExpanded) }
    }

    fun toggleSkillPicker() {
        _state.update { it.copy(skillPickerOpen = !it.skillPickerOpen) }
    }

    /**
     * 添加附加文件（Phase 2，SPEC §4.2）。
     *
     * 由 UI 层通过 SAF 取得 [displayName] 与 [stream] 后传入；ViewModel 落盘到沙箱并刷新列表。
     */
    fun addAttachedFile(displayName: String, stream: java.io.InputStream) {
        viewModelScope.launch {
            val saved = attachedFileRepository.save(displayName, stream)
            if (saved != null) {
                _state.update { it.copy(attachedFiles = it.attachedFiles + saved) }
                AppLogger.i("AttachedFile: added %s (%d bytes)", saved.name, saved.sizeBytes)
            } else {
                _state.update { it.copy(error = "文件附加失败：$displayName（可能超过大小限制）") }
            }
        }
    }

    /** 移除附加文件。 */
    fun removeAttachedFile(path: String) {
        viewModelScope.launch {
            attachedFileRepository.delete(path)
            _state.update { it.copy(attachedFiles = it.attachedFiles.filterNot { f -> f.path == path }) }
        }
    }

    /** 切换当前会话 skill（持久化 currentSkillId）。 */
    fun selectSkill(skillId: String) {
        _state.update { it.copy(currentSkillId = skillId, skillPickerOpen = false) }
        val sid = _state.value.sessionId ?: return
        viewModelScope.launch {
            sessionRepository.updateCurrentSkillId(sid, skillId)
        }
    }

    fun send() {
        val rawInput = _state.value.input.trim()
        if (rawInput.isBlank()) return

        // v1.2 决策 14：@skill 临时切换
        val (effectiveSkill, cleanText) = skillResolver.resolveTemporary(
            rawInput, _state.value.currentSkillId
        )

        // Phase 2：注入附加文件提示到用户消息（让模型知道可读哪些文件）
        val files = _state.value.attachedFiles
        val finalText = if (files.isNotEmpty()) {
            val fileList = files.joinToString("\n") { f -> "- ${f.displayName}（path=\"${f.path}\"）" }
            "$cleanText\n\n[已附加文件，可用 read_attached_file 工具读取]\n$fileList"
        } else cleanText

        val userMsg = ChatMessage(
            role = ChatRole.USER,
            text = finalText,
            skillId = effectiveSkill.id
        )
        val assistantPlaceholder = ChatMessage(
            role = ChatRole.ASSISTANT, text = "", reasoning = "", pending = true,
            skillId = effectiveSkill.id
        )
        _state.update {
            it.copy(
                messages = it.messages + userMsg + assistantPlaceholder,
                input = "",
                streaming = true,
                error = null,
                pendingToolCalls = emptyList(),
                failedHistory = null,
                failedSkillId = null
            )
        }
        streamingJob = viewModelScope.launch {
            val current = _state.value
            val historyForRequest = current.messages.dropLast(1) // 去掉 placeholder
            val sessionId = ensureSession(current)

            runToolLoop(historyForRequest, effectiveSkill, sessionId)
        }
    }

    /**
     * 重试失败的回路（SPEC §3.9 决策 3）。
     *
     * 从失败点续调：重建 messages = 失败时已积累的 history（含已完成 tool 消息），
     * 重新发起 API 调用。不重置 tool_call 计数器（继续累计，避免被绕过硬阈值）。
     */
    fun retry() {
        val failed = _state.value.failedHistory ?: return
        val skillId = _state.value.failedSkillId ?: _state.value.currentSkillId
        val skill = skillResolver.resolve(skillId)
        val sessionId = _state.value.sessionId ?: return

        // 重新挂一个 pending placeholder
        val assistantPlaceholder = ChatMessage(
            role = ChatRole.ASSISTANT, text = "", reasoning = "", pending = true,
            skillId = skill.id
        )
        _state.update {
            it.copy(
                messages = it.messages + assistantPlaceholder,
                streaming = true,
                error = null,
                failedHistory = null,
                failedSkillId = null
            )
        }
        streamingJob = viewModelScope.launch {
            runToolLoop(failed, skill, sessionId)
        }
    }

    /**
     * 工具执行回路（SPEC-Skill-v1.2 §3.1）。
     *
     * 流程：API(stream) → finish_reason?
     *  - stop → 结束
     *  - tool_calls → 聚合 → 执行 → tool 消息回传 → 再次 API（循环）
     *  - 硬阈值 5 次（§3.6）
     *  - 失败 → 保留已完成卡片 + 报错（§3.9）
     */
    private suspend fun runToolLoop(
        initialHistory: List<ChatMessage>,
        skill: Skill,
        sessionId: String
    ) {
        val globalPrompt = settingsRepository.current().systemPrompt
        val skillSystemPrompt = skillResolver.resolveSystemPrompt(skill, globalPrompt)
        val tools = ToolRequestBuilder.buildTools(skill)
        val toolSpecs = skill.tools.associateBy { it.name }

        var history = filterSystemMessages(initialHistory)
        val aggregator = ToolCallAggregator()
        var toolCallCount = 0
        val maxToolCalls = 5

        while (true) {
            aggregator.reset()
            var finishReason = "stop"
            var usage: UsageSnapshot? = null
            var failure: AppError? = null

            sendStream(history, skillSystemPrompt, tools).collect { event ->
                when (event) {
                    ChatStreamEvent.Start -> Unit
                    is ChatStreamEvent.ReasoningDelta -> appendAssistantDelta(reasoning = event.delta)
                    is ChatStreamEvent.TextDelta -> appendAssistantDelta(text = event.delta)
                    is ChatStreamEvent.ToolCallDelta -> {
                        aggregator.append(event.index, event.nameDelta, event.argsDelta)
                    }
                    is ChatStreamEvent.Finish -> {
                        finishReason = event.reason
                        usage = event.usage
                    }
                    is ChatStreamEvent.Failure -> {
                        failure = event.error
                    }
                }
            }

            if (failure != null) {
                finalizeOnFailure(failure!!, sessionId, history, skill.id)
                return
            }

            // 累计 token
            usage?.let {
                runCatching { settingsRepository.accumulateTokens(it.totalTokens.toLong()) }
                sessionRepository.touchAndAddTokens(sessionId, it.totalTokens.toLong())
                _state.update { s -> s.copy(lastUsage = it) }
            }

            // 判断是否 tool_calls
            val isToolCall = finishReason == "tool_calls" && aggregator.hasPending()
            if (!isToolCall) {
                // 正常结束
                finalizeAssistant(usage, sessionId, history)
                return
            }

            // 聚合 tool_call
            val toolCalls = aggregator.build()
            if (toolCalls.isEmpty()) {
                finalizeAssistant(usage, sessionId, history)
                return
            }

            toolCallCount += toolCalls.size
            if (toolCallCount > maxToolCalls) {
                // §3.6 硬阈值
                AppLogger.w(null, "ToolLoop: tool_call count %d > max %d, forcing stop", toolCallCount, maxToolCalls)
                _state.update {
                    it.copy(
                        streaming = false,
                        error = "工具调用次数过多（>$maxToolCalls），已停止"
                    )
                }
                finalizeAssistant(usage, sessionId, history)
                return
            }

            // 只取第一个（v1.2 决策 9），执行
            val tc = toolCalls.first()
            val toolMsg = executeToolCall(tc, toolSpecs[tc.name], sessionId)
            // 把 assistant(含 tool_calls) + tool 消息加入历史
            val assistantMsg = buildAssistantWithToolCalls(tc, skill.id)
            history = history + assistantMsg + toolMsg
        }
    }

    /** 执行单个 tool_call，返回 tool 角色消息 + 更新 UI 卡片。 */
    private suspend fun executeToolCall(
        tc: ToolCall,
        spec: ToolSpec?,
        sessionId: String
    ): ChatMessage {
        val args: JsonObject = runCatching {
            json.decodeFromString(JsonObject.serializer(), tc.argumentsJson.ifBlank { "{}" })
        }.getOrDefault(JsonObject(emptyMap()))

        val start = System.currentTimeMillis()
        val result = if (spec != null) {
            toolExecutor.execute(spec, args)
        } else {
            ToolResult.Failure("未知工具：${tc.name}（skill 未声明）")
        }
        val duration = System.currentTimeMillis() - start

        val content = when (result) {
            is ToolResult.Success -> result.content
            is ToolResult.Failure -> "工具执行失败：${result.error}"
        }

        // 更新 UI 卡片
        _state.update { s ->
            s.copy(pendingToolCalls = s.pendingToolCalls + ToolCallRecord(
                name = tc.name, args = tc.argumentsJson.take(200),
                result = content.take(500), durationMs = duration, cacheHit = false
            ))
        }
        AppLogger.d("ToolLoop: executed %s in %dms, result=%s", tc.name, duration, content.take(80))

        return ChatMessage(
            role = ChatRole.TOOL,
            text = content,
            toolCallId = tc.id,
            skillId = _state.value.currentSkillId
        )
    }

    /** 构造含 tool_calls 的 assistant 消息（加入历史让模型知道它调了什么）。 */
    private fun buildAssistantWithToolCalls(tc: ToolCall, skillId: String): ChatMessage {
        // 把当前流式累积的 assistant 文本清空（tool_call 轮次通常无文本输出）
        val currentAssistantText = _state.value.messages.lastOrNull { it.role == ChatRole.ASSISTANT }?.text.orEmpty()
        return ChatMessage(
            role = ChatRole.ASSISTANT,
            text = currentAssistantText,
            toolCalls = listOf(tc),
            skillId = skillId
        )
    }

    /** v1.1 决策 1：过滤历史中的 system 消息（system 由 skillSystemPrompt 在请求头插入）。 */
    private fun filterSystemMessages(messages: List<ChatMessage>): List<ChatMessage> =
        messages.filter { it.role != ChatRole.SYSTEM }

    /**
     * 正常结束时固化最后一条 assistant 消息。
     *
     * 持久化完整 history（含 tool/assistant(tool_calls) 消息，SPEC §3.8 决策 2）+ 最终 assistant 文本。
     * UI 列表只含 user/assistant 文本消息（tool 消息由折叠卡片展示，不进 UI 列表）。
     */
    private fun finalizeAssistant(usage: UsageSnapshot?, sessionId: String, history: List<ChatMessage>) {
        _state.update { old ->
            val msgs = old.messages.toMutableList()
            val finalAssistant = if (msgs.isNotEmpty() && msgs.last().role == ChatRole.ASSISTANT) {
                msgs.last().copy(pending = false).also { msgs[msgs.lastIndex] = it }
            } else null
            // 持久化：完整 history + 最终 assistant 文本（history 不含最终 assistant 文本）
            val toPersist = if (finalAssistant != null) history + finalAssistant else history
            persistSnapshot(toPersist, sessionId)
            old.copy(
                messages = msgs,
                streaming = false,
                lastUsage = usage ?: old.lastUsage,
                error = null
            )
        }
    }

    /** 回路中间失败：保留已完成卡片 + 报错 + 保存失败点供重试（§3.9 决策 3）。 */
    private fun finalizeOnFailure(
        error: AppError,
        sessionId: String,
        history: List<ChatMessage>,
        skillId: String
    ) {
        _state.update { old ->
            val msgs = old.messages.toMutableList()
            val finalAssistant = if (msgs.isNotEmpty() && msgs.last().role == ChatRole.ASSISTANT) {
                msgs.last().copy(pending = false).also { msgs[msgs.lastIndex] = it }
            } else null
            val toPersist = if (finalAssistant != null) history + finalAssistant else history
            persistSnapshot(toPersist, sessionId)
            old.copy(
                messages = msgs,
                streaming = false,
                error = error.humanReadable(),
                // 保存失败点历史（含已完成 tool 消息）+ skill id，供 retry() 续调
                failedHistory = history,
                failedSkillId = skillId
            )
        }
    }

    private suspend fun ensureSession(current: UiState): String {
        current.sessionId?.let { return it }
        // 新会话用当前会话 skill（@skill 临时切换不写入会话 currentSkillId，SPEC §2.6）
        val session = sessionRepository.createSession(currentSkillId = current.currentSkillId)
        _state.update { it.copy(sessionId = session.id) }
        return session.id
    }

    fun cancel() {
        streamingJob?.cancel()
        streamingJob = null
        _state.update {
            val msgs = it.messages.toMutableList()
            if (msgs.isNotEmpty() && msgs.last().pending) {
                msgs[msgs.lastIndex] = msgs.last().copy(pending = false)
            }
            persistSnapshot(msgs, it.sessionId)
            it.copy(messages = msgs, streaming = false)
        }
    }

    fun clearChat() {
        cancel()
        toolExecutor.clearCache()
        _state.update {
            it.copy(
                messages = emptyList(), error = null, lastUsage = null,
                sessionId = null, pendingToolCalls = emptyList(),
                failedHistory = null, failedSkillId = null
            )
        }
    }

    // -------------------------------------------------------------------------------
    // 流式 delta 累积
    // -------------------------------------------------------------------------------
    private fun appendAssistantDelta(text: String? = null, reasoning: String? = null) {
        _state.update { old ->
            val msgs = old.messages.toMutableList()
            val lastIndex = msgs.indexOfLast { it.role == ChatRole.ASSISTANT }
            if (lastIndex < 0) {
                val new = ChatMessage(
                    role = ChatRole.ASSISTANT,
                    text = text.orEmpty(),
                    reasoning = reasoning.orEmpty(),
                    pending = true,
                    skillId = old.currentSkillId
                )
                old.copy(messages = msgs + new)
            } else {
                val prev = msgs[lastIndex]
                msgs[lastIndex] = prev.copy(
                    text = prev.text + text.orEmpty(),
                    reasoning = (prev.reasoning.orEmpty() + reasoning.orEmpty()).ifEmpty { prev.reasoning }
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
