package com.deepseek.coder.data.remote.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Request-side Chat message DTO.
 *
 * IMPORTANT: DeepSeek requires `content` to be either a plain String
 *   `"content":"Hello"`
 * or a list of content parts for multi-modal
 *   `"content":[{"type":"text","text":"Hello"}]`.
 *
 * This class is ONLY used for REQUESTS (outgoing).
 * Responses use [ChatResponseMessageDto] because the server may return content as String OR list.
 */
@Serializable
data class ChatMessageDto(
    val role: String, // "system" | "user" | "assistant" | "tool"
    /** Simple text content. Serialized as a plain JSON string (recommended 99% of cases). */
    val content: String? = null,
    val name: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null
)

// ---- Requests ----
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val max_tokens: Int? = null,
    val temperature: Float? = null,
    val top_p: Float? = null,
    @SerialName("response_format") val responseFormat: ResponseFormatDto? = null,
    val stream: Boolean = false,
    @SerialName("stream_options") val streamOptions: StreamOptionsDto? = null,
    /** 思考模式开关，{"type":"enabled"} 或 {"type":"disabled"}。默认 enabled（DeepSeek 官方）。 */
    val thinking: ThinkingDto? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val tools: List<ToolDto>? = null,
    val seed: Long? = null,
    val stop: List<String>? = null
)

/**
 * 思考模式开关 DTO（DeepSeek V4 官方文档）。
 * - type="enabled"：开启思考模式，模型先输出 reasoning_content 再输出 content
 * - type="disabled"：关闭思考模式
 * OpenAI SDK 需通过 extra_body 传入；直接 HTTP 调用放在 body 顶层即可。
 */
@Serializable
data class ThinkingDto(val type: String)

@Serializable
data class ResponseFormatDto(
    val type: String, // "text" | "json_object"
    @SerialName("json_schema") val jsonSchema: JsonSchemaDto? = null
)

@Serializable
data class JsonSchemaDto(
    val name: String,
    val description: String? = null,
    val schema: kotlinx.serialization.json.JsonObject? = null,
    val strict: Boolean = true
)

@Serializable
data class StreamOptionsDto(val include_usage: Boolean = false)

@Serializable
data class ToolDto(val type: String = "function", val function: FunctionToolDto)

@Serializable
data class FunctionToolDto(
    val name: String,
    val description: String? = null,
    val parameters: kotlinx.serialization.json.JsonObject? = null,
    val strict: Boolean? = null
)

/**
 * Custom serializer for DeepSeek response `content` field.
 * Server may return:
 *   - a plain string:  `"content":"hi"`
 *   - a content list: `"content":[{"type":"text","text":"hi"}, ...]`
 *   - null
 * We normalize everything to a plain concatenated String for downstream code.
 */
object ContentAsStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ContentAsString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String?) {
        if (value != null) encoder.encodeString(value)
        else encoder.encodeNull()
    }

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: return decoder.decodeString().takeIf { it.isNotEmpty() }
        val element: JsonElement = jsonDecoder.decodeJsonElement()
        if (element is JsonPrimitive) {
            return if (element.isString) element.content else null
        }
        if (element is JsonArray) {
            return element.joinToString("") { part ->
                runCatching {
                    part.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
                }.getOrDefault("")
            }
        }
        return null
    }
}

/**
 * Response-side Chat message DTO.
 * Uses [ContentAsStringSerializer] for `content` because DeepSeek may return
 * either a JSON string or an array of content parts.
 */
@Serializable
data class ChatResponseMessageDto(
    val role: String,
    @Serializable(with = ContentAsStringSerializer::class)
    val content: String? = null,
    val name: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null
)

@Serializable
data class ToolCallDto(
    val id: String,
    val type: String = "function",
    val function: FunctionCallDto
)

@Serializable
data class FunctionCallDto(val name: String, val arguments: String)

// ---- Responses ----
@Serializable
data class ChatCompletionResponseDto(
    val id: String,
    val choices: List<ChoiceDto>,
    val created: Long,
    val model: String,
    @SerialName("service_tier") val serviceTier: String? = null,
    @SerialName("system_fingerprint") val systemFingerprint: String? = null,
    val `object`: String = "chat.completion",
    val usage: UsageDto? = null
)

@Serializable
data class ChoiceDto(
    @SerialName("finish_reason") val finishReason: String? = null, // stop | length | content_filter | tool_calls | stop_sequence | error
    val index: Int,
    val message: ChatResponseMessageDto,
    @SerialName("logprobs") val logprobs: LogProbsDto? = null
)

@Serializable
data class UsageDto(
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int = 0,
    @SerialName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int,
    @SerialName("completion_tokens_details") val completionDetails: CompletionDetailsDto? = null
)

@Serializable
data class CompletionDetailsDto(
    @SerialName("reasoning_tokens") val reasoningTokens: Int = 0
)

@Serializable
data class LogProbsDto(val content: List<LogProbDto>?)

@Serializable
data class LogProbDto(
    val token: String,
    val logprob: Double,
    val bytes: List<Int>?,
    @SerialName("top_logprobs") val topLogprobs: List<LogProbDto>? = null
)

// ---- Streaming chunks ----
@Serializable
data class ChatChunkDto(
    val id: String? = null,
    val choices: List<ChunkChoiceDto> = emptyList(),
    val created: Long? = null,
    val model: String? = null,
    @SerialName("service_tier") val serviceTier: String? = null,
    @SerialName("system_fingerprint") val systemFingerprint: String? = null,
    val `object`: String? = null,
    val usage: UsageDto? = null
)

@Serializable
data class ChunkChoiceDto(
    val delta: DeltaDto = DeltaDto(),
    @SerialName("finish_reason") val finishReason: String? = null,
    val index: Int = 0,
    @SerialName("logprobs") val logprobs: LogProbsDto? = null
)

@Serializable
data class DeltaDto(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ChunkToolCallDto>? = null
)

@Serializable
data class ChunkToolCallDto(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: ChunkFunctionCallDto? = null
)

@Serializable
data class ChunkFunctionCallDto(val name: String? = null, val arguments: String? = null)

// ---- Error body ----
@Serializable
data class DeepSeekErrorEnvelopeDto(val error: DeepSeekErrorDto? = null)

@Serializable
data class DeepSeekErrorDto(
    val message: String? = null,
    val type: String? = null, // invalid_request_error | authentication_error | rate_limit_error | ...
    val param: String? = null,
    val code: String? = null // api_key_expired | insufficient_quota | rate_limit_exceeded | ...
)
