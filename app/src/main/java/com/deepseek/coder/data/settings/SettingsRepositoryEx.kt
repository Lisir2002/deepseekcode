package com.deepseek.coder.data.settings

import android.content.Context
import com.deepseek.coder.data.settings.AppSettings.DeepSeekModel
import com.deepseek.coder.data.settings.AppSettings.ReasoningEffort
import com.deepseek.coder.data.settings.AppSettings.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extension of [SettingsRepository] that proxies DataStore-backed settings so
 * callers don't need to keep both repositories.
 */
@Singleton
class SettingsRepositoryEx @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val parent: SettingsRepository
) {

    val settings: StateFlow<AppSettings> by lazy {
        val scope = object : kotlinx.coroutines.CoroutineScope {
            override val coroutineContext = kotlinx.coroutines.Dispatchers.Default +
                    kotlinx.coroutines.SupervisorJob()
        }
        parent.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    }

    suspend fun current(): AppSettings = parent.current()

    suspend fun setApiKey(key: String) = parent.setApiKey(key)

    suspend fun getApiKey(): String = parent.getApiKey()

    suspend fun updateModel(model: DeepSeekModel) = parent.updateModel(model)

    suspend fun updateTemperature(value: Float) = parent.updateTemperature(value)

    suspend fun updateMaxTokens(value: Int) = parent.updateMaxTokens(value)

    suspend fun updateReasoningEffort(value: ReasoningEffort) = parent.updateReasoningEffort(value)

    suspend fun updateThinkingEnabled(value: Boolean) = parent.updateThinkingEnabled(value)

    suspend fun updateEditorFontSize(value: Float) = parent.updateEditorFontSize(value)

    suspend fun updateFimEnabled(value: Boolean) = parent.updateFimEnabled(value)

    suspend fun updateFimDebounceMs(value: Long) = parent.updateFimDebounceMs(value)

    suspend fun updateBaseUrl(value: String) = parent.updateBaseUrl(value)

    suspend fun updateBetaBaseUrl(value: String) = parent.updateBetaBaseUrl(value)

    suspend fun updateThemeMode(value: ThemeMode) = parent.updateThemeMode(value)

    suspend fun updateSystemPrompt(value: String) = parent.updateSystemPrompt(value)

    suspend fun accumulateTokens(deltaTokens: Long) = parent.accumulateTokens(deltaTokens)
}
