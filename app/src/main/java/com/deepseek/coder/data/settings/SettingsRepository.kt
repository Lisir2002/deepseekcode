package com.deepseek.coder.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.deepseek.coder.data.credentials.CredentialRepository
import com.deepseek.coder.data.settings.AppSettings.DeepSeekModel
import com.deepseek.coder.data.settings.AppSettings.Granularity
import com.deepseek.coder.data.settings.AppSettings.ReasoningEffort
import com.deepseek.coder.data.settings.AppSettings.ThemeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
    private val credentials: CredentialRepository
) {
    private object Keys {
        val MODEL = stringPreferencesKey("model_id")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val TOP_P = floatPreferencesKey("top_p")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val THINKING_ENABLED = stringPreferencesKey("thinking_enabled")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val EDITOR_FONT_SIZE = floatPreferencesKey("editor_font_size")
        val FIM_ENABLED = stringPreferencesKey("fim_enabled")
        val FIM_DEBOUNCE_MS = longPreferencesKey("fim_debounce_ms")
        val BASE_URL = stringPreferencesKey("base_url")
        val BETA_BASE_URL = stringPreferencesKey("beta_base_url")
        val THEME = stringPreferencesKey("theme_mode")
        val CUMULATIVE_TOKENS = longPreferencesKey("cumulative_tokens")
        val ORCH_ENABLED = booleanPreferencesKey("orch_enabled")
        val SELF_CHECK_MAX_RETRY = intPreferencesKey("self_check_max_retry")
        val CLARIFICATIONS_AUTO_ASK = booleanPreferencesKey("clarifications_auto_ask")
    }

    private val defaults = AppSettings()

    private val baseSettings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            model = DeepSeekModel.fromId(p[Keys.MODEL]),
            temperature = p[Keys.TEMPERATURE] ?: defaults.temperature,
            topP = p[Keys.TOP_P] ?: defaults.topP,
            maxTokens = p[Keys.MAX_TOKENS] ?: defaults.maxTokens,
            reasoningEffort = ReasoningEffort.fromValue(p[Keys.REASONING_EFFORT]),
            thinkingEnabled = p[Keys.THINKING_ENABLED]?.toBooleanStrictOrNull() ?: defaults.thinkingEnabled,
            systemPrompt = p[Keys.SYSTEM_PROMPT] ?: defaults.systemPrompt,
            editorFontSizeSp = p[Keys.EDITOR_FONT_SIZE] ?: defaults.editorFontSizeSp,
            fimEnabled = p[Keys.FIM_ENABLED]?.toBooleanStrictOrNull() ?: defaults.fimEnabled,
            fimDebounceMs = p[Keys.FIM_DEBOUNCE_MS] ?: defaults.fimDebounceMs,
            baseUrl = p[Keys.BASE_URL] ?: defaults.baseUrl,
            betaBaseUrl = p[Keys.BETA_BASE_URL] ?: defaults.betaBaseUrl,
            themeMode = p[Keys.THEME]?.let(ThemeMode::valueOf) ?: defaults.themeMode,
            cumulativeTokens = p[Keys.CUMULATIVE_TOKENS] ?: 0L,
            orchestratorEnabled = p[Keys.ORCH_ENABLED] ?: defaults.orchestratorEnabled,
            selfCheckMaxRetry = p[Keys.SELF_CHECK_MAX_RETRY] ?: defaults.selfCheckMaxRetry,
            clarificationsAutoAsk = p[Keys.CLARIFICATIONS_AUTO_ASK] ?: defaults.clarificationsAutoAsk
        )
    }

    private val granularityState = MutableStateFlow(defaults.granularity)
    private val rerankEnabledState = MutableStateFlow(defaults.rerankEnabled)
    private val scopeTagState = MutableStateFlow<String?>(defaults.scopeTag)
    private val scopeHintsState = MutableStateFlow<List<String>>(defaults.scopeHints)

    val granularity: Flow<Granularity> = granularityState
    val rerankEnabled: Flow<Boolean> = rerankEnabledState
    val scopeTag: Flow<String?> = scopeTagState
    val scopeHints: Flow<List<String>> = scopeHintsState

    val settings: Flow<AppSettings> = combine(
        baseSettings,
        granularityState,
        rerankEnabledState,
        scopeTagState,
        scopeHintsState
    ) { base, g, rr, st, sh ->
        base.copy(granularity = g, rerankEnabled = rr, scopeTag = st, scopeHints = sh)
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(value: AppSettings) {
        store.edit { p ->
            p[Keys.MODEL] = value.model.id
            p[Keys.TEMPERATURE] = value.temperature
            p[Keys.TOP_P] = value.topP
            p[Keys.MAX_TOKENS] = value.maxTokens
            p[Keys.REASONING_EFFORT] = value.reasoningEffort.value
            p[Keys.THINKING_ENABLED] = value.thinkingEnabled.toString()
            p[Keys.SYSTEM_PROMPT] = value.systemPrompt
            p[Keys.EDITOR_FONT_SIZE] = value.editorFontSizeSp
            p[Keys.FIM_ENABLED] = value.fimEnabled.toString()
            p[Keys.FIM_DEBOUNCE_MS] = value.fimDebounceMs
            p[Keys.BASE_URL] = value.baseUrl
            p[Keys.BETA_BASE_URL] = value.betaBaseUrl
            p[Keys.THEME] = value.themeMode.name
            p[Keys.CUMULATIVE_TOKENS] = value.cumulativeTokens
            p[Keys.ORCH_ENABLED] = value.orchestratorEnabled
            p[Keys.SELF_CHECK_MAX_RETRY] = value.selfCheckMaxRetry
            p[Keys.CLARIFICATIONS_AUTO_ASK] = value.clarificationsAutoAsk
        }
        granularityState.value = value.granularity
        rerankEnabledState.value = value.rerankEnabled
        scopeTagState.value = value.scopeTag
        scopeHintsState.value = value.scopeHints
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        update(transform(current()))
    }

    suspend fun updateGranularity(v: Granularity) = update { it.copy(granularity = v) }
    suspend fun updateRerankEnabled(v: Boolean) = update { it.copy(rerankEnabled = v) }
    suspend fun updateScopeTag(v: String?) = update { it.copy(scopeTag = AppSettings.fromScope(v)) }
    suspend fun updateScopeHints(v: List<String>) = update { it.copy(scopeHints = v) }

    suspend fun setApiKey(key: String) { credentials.setApiKey(key).getOrThrow() }
    suspend fun getApiKey(): String = credentials.getApiKeyNow().orEmpty()
    suspend fun updateModel(v: DeepSeekModel) = update { it.copy(model = v) }
    suspend fun updateTemperature(v: Float) = update { it.copy(temperature = v) }
    suspend fun updateMaxTokens(v: Int) = update { it.copy(maxTokens = v) }
    suspend fun updateReasoningEffort(v: ReasoningEffort) = update { it.copy(reasoningEffort = v) }
    suspend fun updateThinkingEnabled(v: Boolean) = update { it.copy(thinkingEnabled = v) }
    suspend fun updateEditorFontSize(v: Float) = update { it.copy(editorFontSizeSp = v) }
    suspend fun updateFimEnabled(v: Boolean) = update { it.copy(fimEnabled = v) }
    suspend fun updateFimDebounceMs(v: Long) = update { it.copy(fimDebounceMs = v) }
    suspend fun updateBaseUrl(v: String) = update { it.copy(baseUrl = v) }
    suspend fun updateBetaBaseUrl(v: String) = update { it.copy(betaBaseUrl = v) }
    suspend fun updateThemeMode(v: ThemeMode) = update { it.copy(themeMode = v) }
    suspend fun updateSystemPrompt(v: String) = update { it.copy(systemPrompt = v) }
    suspend fun accumulateTokens(deltaTokens: Long) {
        if (deltaTokens <= 0) return
        update { it.copy(cumulativeTokens = it.cumulativeTokens + deltaTokens) }
    }
}
