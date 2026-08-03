package com.deepseek.coder.data

import com.deepseek.coder.core.AppError
import com.deepseek.coder.core.Outcome
import com.deepseek.coder.core.toOutcome
import com.deepseek.coder.data.remote.api.DeepSeekApi
import com.deepseek.coder.data.remote.dto.ChatCompletionRequest
import com.deepseek.coder.data.remote.dto.ChatMessageDto
import com.deepseek.coder.data.remote.dto.ChatResponseMessageDto
import com.deepseek.coder.data.remote.dto.StreamOptionsDto
import com.deepseek.coder.data.remote.dto.ToolCallDto
import com.deepseek.coder.data.remote.dto.FunctionCallDto
import com.deepseek.coder.data.remote.sse.SseFlowParser
import com.deepseek.coder.data.remote.sse.errorToAppError
import com.deepseek.coder.data.settings.AppSettings
import com.deepseek.coder.data.settings.SettingsRepository
import com.deepseek.coder.domain.models.ChatMessage
import com.deepseek.coder.domain.models.ChatRole
import com.deepseek.coder.domain.models.ChatStreamEvent
import com.deepseek.coder.domain.models.ToolCall
import com.deepseek.coder.domain.models.UsageSnapshot
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates sending chat requests and parsing SSE stream.
 * MS4 will layer in Room persistence and offline retry; Sl.2.2~Sl.2.4 are network-only.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val api: DeepSeekApi,
    private val sseParser: SseFlowParser,
    private val settingsRepository: SettingsRepository
) {

    suspend fun sendChat(
        messages: List<ChatMessage>,
        overrideSettings: (suspend (AppSettings) -> AppSettings)? = null
    ): Flow<ChatStreamEvent> {
        val settings = overrideSettings?.invoke(settingsRepository.current())
            ?: settingsRepository.current()
        val req = buildRequest(messages, settings, stream = true)
        return kotlinx.coroutines.flow.flow {
            val response = try {
                api.chatCompletionsStream(req)
            } catch (t: Throwable) {
                emit(ChatStreamEvent.Failure(mapErr(t)))
                return@flow
            }
            if (!response.isSuccessful) {
                emit(ChatStreamEvent.Failure(response.errorToAppError()))
                return@flow
            }
            val body = response.body()
            if (body == null) {
                emit(ChatStreamEvent.Failure(AppError.Network("empty response body")))
                return@flow
            }
            sseParser.parse(body).collect { emit(it) }
        }
    }

    suspend fun sendChatBlocking(
        messages: List<ChatMessage>,
        overrideSettings: (AppSettings.() -> AppSettings)? = null
    ): Outcome<Pair<ChatMessage, UsageSnapshot?>> =
        runCatching {
            val settings = overrideSettings?.invoke(settingsRepository.current())
                ?: settingsRepository.current()
            val req = buildRequest(messages, settings, stream = false)
            val resp = api.chatCompletions(req)
            if (!resp.isSuccessful) return@runCatching Outcome.Failure(resp.errorToAppError())
            val body = resp.body() ?: return@runCatching Outcome.Failure(AppError.Unknown("empty body"))
            val choice = body.choices.first()
            val msg = dtoToDomain(choice.message)
            val usage = body.usage?.let { u ->
                UsageSnapshot(
                    promptTokens = u.promptTokens,
                    completionTokens = u.completionTokens,
                    reasoningTokens = u.completionDetails?.reasoningTokens ?: 0,
                    totalTokens = u.totalTokens
                )
            }
            Outcome.Success(msg to usage)
        }.let { r: Result<Outcome<Pair<ChatMessage, UsageSnapshot?>>> ->
            r.fold(
                onSuccess = { it },
                onFailure = { t ->
                    val err = when (t) {
                        is AppError -> t
                        else -> mapErr(t)
                    }
                    Outcome.Failure(err)
                }
            )
        }

    /**
     * Non-streaming call with a *pre-built* request DTO.  Used by Orchestrator for JSON-mode
     * classification / decomposition / self-check where we need a custom response_format and
     * do not want the standard system-prompt rewriting to kick in.
     */
    suspend fun sendChatBlockingJsonOverride(
        req: ChatCompletionRequest
    ): Outcome<Pair<ChatMessage, UsageSnapshot?>> = runCatching {
        val resp = api.chatCompletions(req)
        if (!resp.isSuccessful) return@runCatching Outcome.Failure(resp.errorToAppError())
        val body = resp.body() ?: return@runCatching Outcome.Failure(AppError.Unknown("empty body"))
        val choice = body.choices.first()
        val msg = dtoToDomain(choice.message)
        val usage = body.usage?.let { u ->
            UsageSnapshot(
                promptTokens = u.promptTokens,
                completionTokens = u.completionTokens,
                reasoningTokens = u.completionDetails?.reasoningTokens ?: 0,
                totalTokens = u.totalTokens
            )
        }
        Outcome.Success(msg to usage)
    }.fold(
        onSuccess = { it },
        onFailure = { t ->
            val err = when (t) {
                is AppError -> t
                else -> mapErr(t)
            }
            Outcome.Failure(err)
        }
    )

    companion object {
        internal fun buildRequest(
            messages: List<ChatMessage>,
            settings: AppSettings,
            stream: Boolean
        ): ChatCompletionRequest {
            val systemMsgs = buildList {
                if (settings.systemPrompt.isNotBlank()) {
                    add(ChatMessage(role = ChatRole.SYSTEM, text = settings.systemPrompt))
                }
            }
            val msgs = (systemMsgs + messages).map { domainToDto(it) }
            val thinkingBudget: Int? = if (settings.thinkingEnabled && settings.reasoningEffort.enabled()) {
                // Derive budget from max_tokens so effort maps consistently per DeepSeek docs:
                //  - low: max_tokens * 0.25,  medium: max_tokens * 0.5,  high: max_tokens * 1.0
                val ratio = when (settings.reasoningEffort) {
                    AppSettings.ReasoningEffort.LOW -> 0.25
                    AppSettings.ReasoningEffort.MEDIUM -> 0.5
                    AppSettings.ReasoningEffort.HIGH -> 1.0
                    else -> 0.0
                }
                if (ratio > 0) (settings.maxTokens * ratio).toInt().coerceAtLeast(512) else null
            } else null
            return ChatCompletionRequest(
                model = settings.model.id,
                messages = msgs,
                temperature = settings.temperature,
                top_p = settings.topP,
                max_tokens = settings.maxTokens,
                stream = stream,
                streamOptions = if (stream) StreamOptionsDto(include_usage = true) else null,
                reasoningEffort = settings.reasoningEffort.value.takeIf { it.isNotEmpty() },
                thinkingBudget = thinkingBudget
            )
        }

        private fun domainToDto(m: ChatMessage): ChatMessageDto =
            ChatMessageDto(
                role = m.role.value,
                content = m.text.takeIf { it.isNotEmpty() },
                reasoningContent = m.reasoning?.takeIf { it.isNotEmpty() },
                toolCalls = m.toolCalls.takeIf { it.isNotEmpty() }?.map { t ->
                    ToolCallDto(id = t.id, function = FunctionCallDto(name = t.name, arguments = t.argumentsJson))
                },
                toolCallId = m.toolCallId
            )

        internal fun dtoToDomain(dto: ChatResponseMessageDto): ChatMessage =
            ChatMessage(
                role = ChatRole.of(dto.role),
                text = dto.content.orEmpty(),
                reasoning = dto.reasoningContent.orEmpty(),
                toolCalls = dto.toolCalls.orEmpty().map { t ->
                    ToolCall(id = t.id, name = t.function.name, argumentsJson = t.function.arguments)
                },
                toolCallId = dto.toolCallId
            )

        private fun mapErr(t: Throwable): AppError = when (t) {
            is retrofit2.HttpException -> AppError.Http(code = t.code(), message = t.message())
            is java.io.IOException -> AppError.Network(message = t.message ?: "network error", cause = t)
            else -> AppError.Unknown(message = t.message ?: "unknown error", cause = t)
        }
    }
}
