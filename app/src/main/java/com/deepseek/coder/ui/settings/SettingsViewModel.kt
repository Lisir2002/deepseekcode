package com.deepseek.coder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.coder.data.credentials.CredentialRepository
import com.deepseek.coder.data.settings.AppSettings
import com.deepseek.coder.data.settings.AppSettings.DeepSeekModel
import com.deepseek.coder.data.settings.AppSettings.ReasoningEffort
import com.deepseek.coder.data.settings.AppSettings.ThemeMode
import com.deepseek.coder.domain.usecases.ObserveAppSettingsUseCase
import com.deepseek.coder.domain.usecases.UpdateAppSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeSettings: ObserveAppSettingsUseCase,
    private val updateSettings: UpdateAppSettingsUseCase,
    private val credentialRepository: CredentialRepository
) : ViewModel() {

    data class UiState(
        val settings: AppSettings = AppSettings(),
        val apiKeyTail: String? = null,
        val dirty: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        observeSettings()
            .onEach { s -> _state.update { it.copy(settings = s) } }
            .launchIn(viewModelScope)

        credentialRepository.hasApiKey
            .onEach { has ->
                _state.update { it.copy(apiKeyTail = if (has) credentialRepository.apiKeyTail() else null) }
            }
            .launchIn(viewModelScope)
    }

    fun updateModel(model: DeepSeekModel) = persist { it.copy(model = model) }
    fun updateTemperature(value: Float) = persist { it.copy(temperature = (value * 100f).roundToInt() / 100f) }
    fun updateMaxTokens(value: Int) = persist { it.copy(maxTokens = value.coerceIn(256, 8192)) }
    fun updateEffort(effort: ReasoningEffort) = persist { it.copy(reasoningEffort = effort) }
    fun updateThinkingEnabled(enabled: Boolean) = persist { it.copy(thinkingEnabled = enabled) }
    fun updateSystemPrompt(text: String) = persist { it.copy(systemPrompt = text) }
    fun updateTheme(mode: ThemeMode) = persist { it.copy(themeMode = mode) }
    fun updateEditorFontSize(value: Float) = persist { it.copy(editorFontSizeSp = value.coerceIn(10f, 28f)) }
    fun updateFimEnabled(enabled: Boolean) = persist { it.copy(fimEnabled = enabled) }
    fun updateFimDebounceMs(value: Long) = persist { it.copy(fimDebounceMs = value.coerceIn(200L, 2000L)) }

    fun updateBaseUrl(value: String) = persist { it.copy(baseUrl = value.trim().ifBlank { AppSettings.DEFAULT_BASE_URL }) }
    fun updateBetaBaseUrl(value: String) = persist { it.copy(betaBaseUrl = value.trim().ifBlank { AppSettings.DEFAULT_BETA_BASE_URL }) }

    // ---- v1.1 Orchestrator settings ----
    fun updateOrchestratorEnabled(value: Boolean) = persist { it.copy(orchestratorEnabled = value) }
    fun updateSelfCheckMaxRetry(value: Int) = persist { it.copy(selfCheckMaxRetry = value.coerceIn(0, 5)) }
    fun updateClarificationsAutoAsk(value: Boolean) = persist { it.copy(clarificationsAutoAsk = value) }

    fun clearApiKey() {
        viewModelScope.launch { credentialRepository.clearApiKey() }
    }

    private fun persist(fn: (AppSettings) -> AppSettings) {
        viewModelScope.launch { updateSettings(fn) }
    }
}
