package com.deepseek.coder.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- FIM (Fill-In-the-Middle) Beta API ----
// DeepSeek V4 FIM Endpoint: POST /beta/completions

@Serializable
data class FimCompletionRequest(
    val model: String,
    val prompt: String,
    val suffix: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    val stream: Boolean = false,
    @SerialName("stream_options") val streamOptions: StreamOptionsDto? = null,
    val stop: List<String>? = null
)

@Serializable
data class FimCompletionResponseDto(
    val id: String,
    val choices: List<FimChoiceDto>,
    val created: Long,
    val model: String,
    val `object`: String = "text_completion",
    val usage: UsageDto? = null
)

@Serializable
data class FimChoiceDto(
    val index: Int,
    val text: String,
    @SerialName("finish_reason") val finishReason: String?,
    val logprobs: LogProbsDto? = null
)

@Serializable
data class FimChunkDto(
    val id: String? = null,
    val choices: List<FimChunkChoiceDto> = emptyList(),
    val created: Long? = null,
    val model: String? = null,
    val `object`: String? = null,
    val usage: UsageDto? = null
)

@Serializable
data class FimChunkChoiceDto(
    val index: Int = 0,
    val delta: FimDeltaDto = FimDeltaDto(),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class FimDeltaDto(
    val text: String? = null
)
