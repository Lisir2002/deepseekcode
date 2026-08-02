package com.deepseek.coder.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.coder.data.FimRepository
import com.deepseek.coder.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val fimRepository: FimRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    data class UiState(
        val code: String = DEFAULT_CODE,
        val language: String = "kotlin",
        val ghostText: String = "",
        val fimLoading: Boolean = false,
        val error: String? = null,
        val fimEnabled: Boolean = true
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var fimJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _state.update { it.copy(fimEnabled = s.fimEnabled) }
            }
        }
    }

    fun onCodeChanged(value: String) {
        _state.update { it.copy(code = value, ghostText = "", error = null) }
    }

    fun onLanguageChanged(lang: String) {
        _state.update { it.copy(language = lang) }
    }

    fun acceptGhost() {
        val s = _state.value
        if (s.ghostText.isBlank()) return
        _state.update { it.copy(code = it.code + it.ghostText, ghostText = "") }
    }

    fun discardGhost() {
        _state.update { it.copy(ghostText = "") }
    }

    fun triggerFim() {
        val s = _state.value
        if (!s.fimEnabled || s.fimLoading) return
        fimJob?.cancel()
        val cursor = s.code.length
        val prefix = s.code.take(cursor)
        val suffix = s.code.drop(cursor)
        if (prefix.isBlank()) return
        _state.update { it.copy(fimLoading = true, ghostText = "", error = null) }
        fimJob = viewModelScope.launch {
            val buf = StringBuilder()
            fimRepository.fimStream(
                FimRepository.FimRequest(prefix = prefix, suffix = suffix)
            ).collect { evt ->
                when (evt) {
                    FimRepository.FimStreamEvent.Start -> Unit
                    is FimRepository.FimStreamEvent.TextDelta -> {
                        buf.append(evt.delta)
                        _state.update { it.copy(ghostText = buf.toString()) }
                    }
                    is FimRepository.FimStreamEvent.Finish -> {
                        if (buf.isEmpty() && evt.fullText.isNotEmpty()) {
                            _state.update { it.copy(ghostText = evt.fullText, fimLoading = false) }
                        } else {
                            _state.update { it.copy(fimLoading = false) }
                        }
                    }
                    is FimRepository.FimStreamEvent.Failure -> {
                        _state.update { it.copy(fimLoading = false, error = "补全失败: ${evt.error.message.take(120)}") }
                    }
                }
            }
        }
    }

    fun cancelFim() {
        fimJob?.cancel(); fimJob = null
        _state.update { it.copy(fimLoading = false) }
    }

    fun clear() {
        cancelFim()
        _state.update { it.copy(code = "", ghostText = "", error = null) }
    }

    companion object {
        const val DEFAULT_CODE =
            "// 在这里写代码，或点击「请求 FIM 补全」让 DeepSeek 帮你继续\n" +
                    "// 支持语言: kotlin, java, python, javascript, typescript, cpp, rust, go...\n" +
                    "\n" +
                    "fun quickSort(arr: IntArray): IntArray {\n" +
                    "    if (arr.size <= 1) return arr\n" +
                    "    "
    }
}
