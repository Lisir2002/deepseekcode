package com.deepseek.coder.data

import com.deepseek.coder.core.AppError
import com.deepseek.coder.core.Outcome
import com.deepseek.coder.data.remote.api.FimApi
import com.deepseek.coder.data.remote.dto.FimChunkDto
import com.deepseek.coder.data.remote.dto.FimCompletionRequest
import com.deepseek.coder.data.remote.sse.errorToAppError
import com.deepseek.coder.data.settings.AppSettings
import com.deepseek.coder.data.settings.SettingsRepository
import com.deepseek.coder.domain.models.ChatStreamEvent
import com.deepseek.coder.domain.models.UsageSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FimRepository @Inject constructor(
    private val fimApi: FimApi,
    private val settingsRepository: SettingsRepository,
    private val json: Json
) {

    data class FimRequest(
        val prefix: String,
        val suffix: String = "",
        val maxTokens: Int? = null,
        val temperature: Float? = null,
        val modelOverride: String? = null
    )

    sealed interface FimStreamEvent {
        data object Start : FimStreamEvent
        data class TextDelta(val delta: String) : FimStreamEvent
        data class Finish(val reason: String, val fullText: String, val usage: UsageSnapshot?) : FimStreamEvent
        data class Failure(val error: AppError) : FimStreamEvent
    }

    suspend fun fimBlocking(req: FimRequest): Outcome<Pair<String, UsageSnapshot?>> {
        val settings = settingsRepository.current()
        val request = buildFimRequest(req, settings, stream = false)
        return runCatching {
            val resp = fimApi.fimCompletions(request)
            if (!resp.isSuccessful) return@runCatching Outcome.Failure(resp.errorToAppError())
            val body = resp.body() ?: return@runCatching Outcome.Failure(AppError.Unknown("empty body"))
            val text = body.choices.firstOrNull()?.text.orEmpty()
            val usage = body.usage?.let { u ->
                UsageSnapshot(
                    promptTokens = u.promptTokens,
                    completionTokens = u.completionTokens,
                    reasoningTokens = u.completionDetails?.reasoningTokens ?: 0,
                    totalTokens = u.totalTokens
                )
            }
            Outcome.Success(text to usage)
        }.let { r ->
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
    }

    fun fimStream(req: FimRequest): Flow<FimStreamEvent> = flow {
        val settings = settingsRepository.current()
        val request = buildFimRequest(req, settings, stream = true)
        val response = try {
            fimApi.fimCompletionsStream(request)
        } catch (t: Throwable) {
            emit(FimStreamEvent.Failure(mapErr(t)))
            return@flow
        }
        if (!response.isSuccessful) {
            emit(FimStreamEvent.Failure(response.errorToAppError()))
            return@flow
        }
        val body = response.body()
        if (body == null) {
            emit(FimStreamEvent.Failure(AppError.Network("empty response body")))
            return@flow
        }
        emit(FimStreamEvent.Start)
        val chunks = mutableListOf<FimChunkDto>()
        val buf = StringBuilder()
        val reader = BufferedReader(InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))
        val dataBuf = StringBuilder()
        try {
            reader.useLines { lines ->
                for (line in lines) {
                    when {
                        line.isBlank() -> {
                            val raw = dataBuf.toString().trim(); dataBuf.clear()
                            if (raw.isEmpty() || raw == "[DONE]") continue
                            val chunk = runCatching { json.decodeFromString(FimChunkDto.serializer(), raw) }
                                .getOrNull() ?: continue
                            chunks.add(chunk)
                            chunk.choices.forEach { c ->
                                val text = c.delta.text.orEmpty()
                                if (text.isNotEmpty()) {
                                    buf.append(text)
                                    emit(FimStreamEvent.TextDelta(text))
                                }
                            }
                        }
                        line.startsWith(':') -> Unit
                        line.startsWith("data:", ignoreCase = true) -> {
                            val value = line.removePrefix("data:").trimStart()
                            if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                            dataBuf.append(value)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (t !is kotlinx.coroutines.CancellationException)
                emit(FimStreamEvent.Failure(mapErr(t)))
            return@flow
        }
        val lastUsage = chunks.lastOrNull { it.usage != null }?.usage
        val usage = lastUsage?.let { u ->
            UsageSnapshot(
                promptTokens = u.promptTokens,
                completionTokens = u.completionTokens,
                reasoningTokens = u.completionDetails?.reasoningTokens ?: 0,
                totalTokens = u.totalTokens
            )
        }
        val reason = chunks.lastOrNull()?.choices?.lastOrNull()?.finishReason ?: "stop"
        emit(FimStreamEvent.Finish(reason, buf.toString(), usage))
    }

    companion object {
        internal fun buildFimRequest(
            req: FimRequest,
            settings: AppSettings,
            stream: Boolean
        ): FimCompletionRequest = FimCompletionRequest(
            model = req.modelOverride ?: settings.model.id,
            prompt = req.prefix,
            suffix = req.suffix.ifBlank { null },
            maxTokens = req.maxTokens ?: (settings.maxTokens / 2).coerceAtMost(2048),
            temperature = req.temperature ?: 0.1f,
            topP = settings.topP,
            stream = stream,
            streamOptions = if (stream) com.deepseek.coder.data.remote.dto.StreamOptionsDto(include_usage = true) else null
        )

        private fun mapErr(t: Throwable): AppError = when (t) {
            is retrofit2.HttpException -> AppError.Http(code = t.code(), message = t.message())
            is java.io.IOException -> AppError.Network(message = t.message ?: "fim network error", cause = t)
            else -> AppError.Unknown(message = t.message ?: "fim error", cause = t)
        }
    }
}
