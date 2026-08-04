package com.deepseek.coder.domain.usecases

import com.deepseek.coder.core.DispatcherProvider
import com.deepseek.coder.core.AppError
import com.deepseek.coder.data.ChatRepository
import com.deepseek.coder.data.remote.dto.ToolDto
import com.deepseek.coder.data.settings.AppSettings
import com.deepseek.coder.domain.models.ChatMessage
import com.deepseek.coder.domain.models.ChatStreamEvent
import com.deepseek.coder.data.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 流式发送聊天请求。
 *
 * Skill 系统扩展（v1.2）：
 *  - [skillSystemPrompt]：skill 的 system prompt（覆盖用户全局，由 SkillResolver 解析）
 *  - [tools]：skill 声明的工具（转成 ToolDto）
 *
 * 历史 system 消息过滤（v1.1 决策 1）由调用方在传入 messages 前完成。
 */
@Singleton
class SendChatStreamUseCase @Inject constructor(
    private val repository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        messages: List<ChatMessage>,
        skillSystemPrompt: String? = null,
        tools: List<ToolDto>? = null
    ): Flow<ChatStreamEvent> = channelFlow {
        val job = launch(dispatchers.io) {
            val override: (suspend (AppSettings) -> AppSettings)? =
                if (skillSystemPrompt != null) {
                    { s -> s.copy(systemPrompt = skillSystemPrompt) }
                } else null

            repository.sendChat(messages, overrideSettings = override, tools = tools)
                .catch { t ->
                    val mapped = when (t) {
                        is AppError -> t
                        is kotlinx.coroutines.CancellationException -> null
                        else -> AppError.Unknown(message = t.message ?: "stream error", cause = t)
                    }
                    if (mapped != null) send(ChatStreamEvent.Failure(mapped))
                }
                .collect { event -> send(event) }
        }
        awaitClose { job.cancel() }
    }.flowOn(dispatchers.default)
}
