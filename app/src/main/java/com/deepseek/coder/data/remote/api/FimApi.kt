package com.deepseek.coder.data.remote.api

import com.deepseek.coder.data.remote.dto.FimCompletionRequest
import com.deepseek.coder.data.remote.dto.FimCompletionResponseDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Streaming

interface FimApi {

    @POST("completions")
    suspend fun fimCompletions(
        @Body request: FimCompletionRequest,
        @HeaderMap headers: Map<String, String> = emptyMap()
    ): Response<FimCompletionResponseDto>

    @Streaming
    @POST("completions")
    suspend fun fimCompletionsStream(
        @Body request: FimCompletionRequest,
        @HeaderMap headers: Map<String, String> = emptyMap()
    ): Response<ResponseBody>
}
