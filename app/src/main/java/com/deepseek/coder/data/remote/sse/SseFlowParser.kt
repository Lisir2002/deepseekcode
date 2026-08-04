package com.deepseek.coder.data.remote.sse

import com.deepseek.coder.core.AppError
import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.data.remote.dto.ChatChunkDto
import com.deepseek.coder.domain.models.ChatStreamEvent
import com.deepseek.coder.domain.models.UsageSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SseFlowParser @Inject constructor(
    private val json: Json
) {
    fun parse(body: ResponseBody): Flow<ChatStreamEvent> = flow {
        emit(ChatStreamEvent.Start)
        val reader = BufferedReader(InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))
        val dataBuffer = StringBuilder()
        val chunks = mutableListOf<ChatChunkDto>()

        try {
            reader.useLines { lines ->
                for (line in lines) {
                    when {
                        line.isBlank() -> {
                            val raw = dataBuffer.toString().trim()
                            dataBuffer.clear()
                            if (raw.isEmpty()) continue
                            if (raw == DONE_MARKER) continue
                            val chunk = runCatching { json.decodeFromString(ChatChunkDto.serializer(), raw) }
                                .onFailure { AppLogger.w(it, "SSE JSON parse failed: %s", raw.take(200)) }
                                .getOrNull() ?: continue
                            chunks.add(chunk)
                            emitChunk(chunk).forEach { emit(it) }
                        }
                        line.startsWith(':') -> Unit
                        line.startsWith(DATA_PREFIX, ignoreCase = true) -> {
                            val value = line.removePrefix(DATA_PREFIX).trimStart()
                            if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                            dataBuffer.append(value)
                        }
                        else -> Unit
                    }
                }
                if (dataBuffer.isNotEmpty()) {
                    val raw = dataBuffer.toString().trim()
                    dataBuffer.clear()
                    if (raw.isNotEmpty() && raw != DONE_MARKER) {
                        runCatching { json.decodeFromString(ChatChunkDto.serializer(), raw) }
                            .getOrNull()
                            ?.let { c ->
                                chunks.add(c)
                                emitChunk(c).forEach { emit(it) }
                            }
                    }
                }
            }
        } catch (t: Throwable) {
            val mapped = when (t) {
                is kotlinx.coroutines.CancellationException -> null
                else -> t.toAppErrorInternal()
            }
            if (mapped != null) emit(ChatStreamEvent.Failure(mapped))
            return@flow
        }

        val lastUsageChunk = chunks.lastOrNull { it.usage != null }
        val usage = lastUsageChunk?.usage?.let { u ->
            UsageSnapshot(
                promptTokens = u.promptTokens,
                completionTokens = u.completionTokens,
                reasoningTokens = u.completionDetails?.reasoningTokens ?: 0,
                totalTokens = u.totalTokens
            )
        }
        // 修复：从最后一个含非空 finish_reason 的 chunk 取，避免被末尾 usage chunk
        // （choices 为空）干扰导致退化为 "stop"，掩盖真实的 "length"（思考用满 token）等情况
        val finishReason = chunks.asReversed()
            .firstOrNull { chunk -> chunk.choices.any { it.finishReason != null } }
            ?.choices?.lastOrNull { it.finishReason != null }?.finishReason
            ?: "stop"
        emit(ChatStreamEvent.Finish(finishReason, usage))
    }

    companion object {
        private const val DATA_PREFIX = "data:"
        private const val DONE_MARKER = "[DONE]"

        private fun emitChunk(chunk: ChatChunkDto): List<ChatStreamEvent> {
            val events = mutableListOf<ChatStreamEvent>()
            chunk.choices.forEach { choice ->
                val delta = choice.delta
                delta.reasoningContent?.takeIf { it.isNotEmpty() }
                    ?.let { events.add(ChatStreamEvent.ReasoningDelta(it)) }
                delta.content?.takeIf { it.isNotEmpty() }
                    ?.let { events.add(ChatStreamEvent.TextDelta(it)) }
                delta.toolCalls?.forEach { tc ->
                    events.add(
                        ChatStreamEvent.ToolCallDelta(
                            index = tc.index ?: 0,
                            nameDelta = tc.function?.name,
                            argsDelta = tc.function?.arguments
                        )
                    )
                }
            }
            return events
        }

        private fun Throwable.toAppErrorInternal(): AppError = when (this) {
            is HttpException -> AppError.Http(code = code(), message = message())
            is java.io.IOException -> AppError.Network(message ?: "stream error", this)
            else -> AppError.Unknown(message ?: "stream error", this)
        }
    }
}

fun <T> Response<T>.errorToAppError(): AppError {
    if (isSuccessful) return AppError.Unknown("unexpected success path")
    val body = errorBody()?.string().orEmpty().take(800)
    val parsed = runCatching {
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true; isLenient = true
        }.decodeFromString(
            com.deepseek.coder.data.remote.dto.DeepSeekErrorEnvelopeDto.serializer(),
            body
        ).error
    }.getOrNull()
    return when (val code = code()) {
        401, 403 -> AppError.Unauthorized(parsed?.message ?: "Invalid or missing API Key")
        else -> AppError.Http(
            code = code,
            message = parsed?.message ?: body.ifBlank { message() },
            cause = null
        )
    }
}
