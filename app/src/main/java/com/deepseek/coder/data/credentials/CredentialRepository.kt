package com.deepseek.coder.data.credentials

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.deepseek.coder.core.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API Key repository backed by EncryptedSharedPreferences.
 *
 * Key validation rule (§DeepSeek platform requirement):
 * - MUST start with `sk-` prefix before persistence (trimmed).
 * - Length MUST be at least 8 characters after prefix.
 * - Never logged; never returned to UI in plaintext (truncated to last 4 chars only for display).
 */
@Singleton
class CredentialRepository @Inject constructor(
    @ApplicationContext private val app: Context
) {
    private val masterKey = MasterKey.Builder(app)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        app,
        FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    val apiKeyFlow: Flow<String?> = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_API_KEY) trySend(prefs.getString(KEY_API_KEY, null))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(prefs.getString(KEY_API_KEY, null))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    val hasApiKey: Flow<Boolean> = apiKeyFlow.map { !it.isNullOrBlank() }

    fun getApiKeyNow(): String? = prefs.getString(KEY_API_KEY, null)

    fun apiKeyTail(): String? {
        val key = getApiKeyNow().orEmpty()
        return if (key.length >= 8) key.takeLast(4) else null
    }

    suspend fun setApiKey(raw: String): Result<Unit> {
        val trimmed = raw.trim()
        return validate(trimmed).fold(
            onSuccess = {
                runCatching { prefs.edit().putString(KEY_API_KEY, trimmed).apply() }
            },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun clearApiKey() {
        runCatching { prefs.edit().remove(KEY_API_KEY).apply() }
            .onFailure { AppLogger.w(it, "clearApiKey failed") }
    }

    sealed class ValidationError(msg: String) : IllegalStateException(msg) {
        data object Empty : ValidationError("API Key 不能为空")
        data object Prefix : ValidationError("API Key 必须以 sk- 开头")
        data object TooShort : ValidationError("API Key 长度过短")
    }

    companion object {
        private const val FILE_NAME = "deepcoder_secret_prefs"
        private const val KEY_API_KEY = "api_key_v1"
        const val API_KEY_PREFIX = "sk-"

        fun validate(value: String): Result<Unit> {
            if (value.isBlank()) return Result.failure(ValidationError.Empty)
            if (!value.startsWith(API_KEY_PREFIX)) return Result.failure(ValidationError.Prefix)
            if (value.length < 8) return Result.failure(ValidationError.TooShort)
            return Result.success(Unit)
        }
    }
}
