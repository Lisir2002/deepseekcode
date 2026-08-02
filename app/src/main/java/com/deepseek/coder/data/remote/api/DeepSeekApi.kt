package com.deepseek.coder.data.remote.api

import com.deepseek.coder.data.remote.dto.ChatCompletionRequest
import com.deepseek.coder.data.remote.dto.ChatCompletionResponseDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Streaming

/**
 * Retrofit interface for DeepSeek Chat Completions (v4 models).
 * Streaming endpoints return raw [ResponseBody] so we can parse the SSE line-stream manually
 * via a flow-based parser (avoiding per-char Retrofit overhead and memory buffering).
 */
interface DeepSeekApi {

    @POST("chat/completions")
    suspend fun chatCompletions(
        @Body request: ChatCompletionRequest,
        @HeaderMap headers: Map<String, String> = emptyMap()
    ): Response<ChatCompletionResponseDto>

    @Streaming
    @POST("chat/completions")
    suspend fun chatCompletionsStream(
        @Body request: ChatCompletionRequest,
        @HeaderMap headers: Map<String, String> = emptyMap()
    ): Response<ResponseBody>
}
