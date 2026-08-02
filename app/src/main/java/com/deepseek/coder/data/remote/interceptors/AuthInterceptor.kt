package com.deepseek.coder.data.remote.interceptors

import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.data.credentials.CredentialRepository
import dagger.Lazy
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds Authorization: Bearer <sk-...> header to every DeepSeek API call.
 * Reads key at request time (not DI time) so user can rotate it without cold restart.
 * If key missing, passes request through unchanged (the upstream returns 401 and UI prompts).
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val credentials: Lazy<CredentialRepository>
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header(HEADER_NO_AUTH) != null) {
            return chain.proceed(original.newBuilder().removeHeader(HEADER_NO_AUTH).build())
        }
        val key = credentials.get().getApiKeyNow()
        val request: Request = original.newBuilder()
            .header("Authorization", "Bearer ${key.orEmpty()}")
            .header("Accept", "application/json")
            .let { b ->
                if (original.header("Content-Type") == null) b.header("Content-Type", "application/json") else b
            }
            .build()
        AppLogger.d("HTTP → %s %s", request.method, request.url)
        return chain.proceed(request)
    }

    companion object { const val HEADER_NO_AUTH = "X-DeepSeek-No-Auth" }
}
