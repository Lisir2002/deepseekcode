package com.deepseek.coder.domain.usecases

import com.deepseek.coder.core.DispatcherProvider
import com.deepseek.coder.core.AppError
import com.deepseek.coder.data.ChatRepository
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

@Singleton
class SendChatStreamUseCase @Inject constructor(
    private val repository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(messages: List<ChatMessage>): Flow<ChatStreamEvent> = channelFlow {
        val job = launch(dispatchers.io) {
            repository.sendChat(messages)
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
