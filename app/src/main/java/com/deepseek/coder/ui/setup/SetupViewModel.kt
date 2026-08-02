package com.deepseek.coder.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.coder.data.credentials.CredentialRepository
import com.deepseek.coder.data.credentials.CredentialRepository.ValidationError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository
) : ViewModel() {

    data class UiState(
        val rawKey: String = "",
        val validationError: String? = null,
        val saving: Boolean = false,
        val saved: Boolean = false,
        val keyTail: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        credentialRepository.hasApiKey
            .onEach { hasKey ->
                _state.update { s ->
                    s.copy(saved = hasKey, keyTail = if (hasKey) credentialRepository.apiKeyTail() else null)
                }
            }
            .launchIn(viewModelScope)
    }

    fun onRawKeyChanged(value: String) {
        _state.update { it.copy(rawKey = value, validationError = CredentialRepository.validate(value).exceptionOrNull()?.message) }
    }

    fun saveAndContinue(onSaved: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(saving = true, validationError = null) }
            credentialRepository.setApiKey(_state.value.rawKey)
                .onSuccess {
                    _state.update { it.copy(saving = false, saved = true, keyTail = credentialRepository.apiKeyTail()) }
                    onSaved()
                }
                .onFailure { t ->
                    _state.update { it.copy(saving = false, validationError = t.message ?: (t as? ValidationError)?.message ?: "保存失败") }
                }
        }
    }

    fun clearKey() {
        viewModelScope.launch {
            credentialRepository.clearApiKey()
            _state.update { it.copy(rawKey = "", saved = false, keyTail = null) }
        }
    }
}
