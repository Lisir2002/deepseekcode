package com.deepseek.coder.data.settings

import android.content.Context
import com.deepseek.coder.data.settings.AppSettings.ReasoningEffort
import com.deepseek.coder.data.settings.AppSettings.ThemeMode
import com.deepseek.coder.data.settings.AppSettings.DeepSeekModel
import com.deepseek.coder.domain.workflow.OrchestratorEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extension of the existing [SettingsRepository] that exposes Orchestrator-level
 * runtime state:
 *   - a replay-shared flow of [OrchestratorEvent]s so ChatViewModel can
 *     render step progress cards without being the *only* subscriber
 *   - a simple counter used by the UI "Reset Orchestrator" action.
 *
 * The DataStore persistence of [AppSettings] lives in the parent interface; we
 * only add *in-memory* pub/sub channels here to keep data-layer concerns clean.
 */
@Singleton
class SettingsRepositoryEx @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val parent: SettingsRepository
) {
    private val _orchEvents = MutableSharedFlow<OrchestratorEvent>(
        extraBufferCapacity = 128,
        replay = 32
    )
    val orchEvents: Flow<OrchestratorEvent> = _orchEvents.asSharedFlow()

    suspend fun emitOrch(event: OrchestratorEvent) = _orchEvents.emit(event)

    // -----------------------------------------------------------------
    // Proxy the DataStore-backed settings so callers don't need to keep
    // both repositories.  Keeps dependency graphs smaller.
    // -----------------------------------------------------------------
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

    // ---- v1.1 new setters ----
    suspend fun updateOrchestratorEnabled(value: Boolean) = parent.update(
        parent.current().copy(orchestratorEnabled = value)
    )

    suspend fun updateCustomFineTuneModelId(value: String?) = parent.update(
        parent.current().copy(customFineTuneModelId = value?.takeIf { it.isNotBlank() })
    )

    suspend fun updateFineTuneDataCollectionEnabled(value: Boolean) = parent.update(
        parent.current().copy(fineTuneDataCollectionEnabled = value)
    )

    suspend fun updateSelfCheckMaxRetry(value: Int) = parent.update(
        parent.current().copy(selfCheckMaxRetry = value.coerceIn(0, 5))
    )

    suspend fun updateClarificationsAutoAsk(value: Boolean) = parent.update(
        parent.current().copy(clarificationsAutoAsk = value)
    )
}
