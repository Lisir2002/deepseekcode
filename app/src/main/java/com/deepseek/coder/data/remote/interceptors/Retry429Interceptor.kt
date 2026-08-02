package com.deepseek.coder.data.remote.interceptors

import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.core.AppError
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * Retries HTTP 429 (rate limit) with exponential backoff and Retry-After header honor.
 * DeepSeek platform:
 *  - v4-flash: 2500 TPM, 80 RPM
 *  - v4-pro:   500  TPM, 80 RPM
 * If a Retry-After (seconds) exists we trust it; otherwise 2^attempt + jitter up to [maxBackoffMs].
 *
 * Other retriable errors (5xx, connect timeout) get limited retries too. Non-retriable (401, 4xx != 429)
 * go through unchanged.
 */
@Singleton
class Retry429Interceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        var response: Response? = null
        while (true) {
            try {
                response?.close()
                response = chain.proceed(request)
                if (!shouldRetry(response, attempt)) return response
                val delayMs = backoffMs(attempt, response)
                AppLogger.w(
                    null,
                    "HTTP %s %s ⇒ %s, retry attempt %d after %dms",
                    request.method, request.url, response.code, attempt + 1, delayMs
                )
                TimeUnit.MILLISECONDS.sleep(delayMs)
                attempt++
            } catch (e: IOException) {
                if (attempt >= MAX_ATTEMPTS) throw e
                val delayMs = backoffMs(attempt, null)
                AppLogger.w(e, "Connect error, retry attempt %d after %dms", attempt + 1, delayMs)
                TimeUnit.MILLISECONDS.sleep(delayMs)
                attempt++
            }
        }
    }

    private fun shouldRetry(r: Response, attempt: Int): Boolean {
        if (attempt >= MAX_ATTEMPTS) return false
        val code = r.code
        return code == 429 || code in 500..504
    }

    private fun backoffMs(attempt: Int, response: Response?): Long {
        val retryAfter = response?.header("Retry-After")?.toLongOrNull()
        if (retryAfter != null && retryAfter > 0L) return (retryAfter * 1000L).coerceAtMost(maxBackoffMs)
        val base = (BASE_BACKOFF_MS * 2.0.pow(attempt.coerceAtMost(5))).toLong()
        val jitter = (Math.random() * JITTER_MS).toLong()
        return min(base + jitter, maxBackoffMs)
    }

    companion object {
        const val MAX_ATTEMPTS = 4
        const val BASE_BACKOFF_MS = 500L
        const val JITTER_MS = 400L
        const val maxBackoffMs = 20_000L
    }
}

fun <T> retrofit2.Response<T>.toHttpError(): AppError.Http? {
    if (isSuccessful) return null
    val body = errorBody()?.string().orEmpty().take(800)
    return AppError.Http(code = code(), message = body.ifBlank { message() })
}

fun Throwable.toAppError(): AppError = when (this) {
    is AppError -> this
    is HttpException -> AppError.Http(code = code(), message = response()?.errorBody()?.string().orEmpty().take(800).ifBlank { message ?: "" })
    is IOException -> AppError.Network(message = this.message ?: "network error", cause = this)
    else -> AppError.Unknown(message = message ?: "unknown error", cause = this)
}
