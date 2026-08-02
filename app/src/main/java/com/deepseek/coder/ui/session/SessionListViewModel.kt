package com.deepseek.coder.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.coder.data.SessionRepository
import com.deepseek.coder.domain.models.ChatSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    data class UiState(
        val sessions: List<ChatSession> = emptyList(),
        val loading: Boolean = true,
        val searchQuery: String = ""
    ) {
        val filtered: List<ChatSession> get() =
            if (searchQuery.isBlank()) sessions else sessions.filter {
                it.title.contains(searchQuery, ignoreCase = true)
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessionRepository.observeSessions().collect { list ->
                _state.update { it.copy(sessions = list, loading = false) }
            }
        }
    }

    fun onSearchChanged(value: String) = _state.update { it.copy(searchQuery = value) }

    fun createNewSession(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val s = sessionRepository.createSession()
            onCreated(s.id)
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch { sessionRepository.deleteSession(id) }
    }

    fun deleteAll() {
        viewModelScope.launch { sessionRepository.deleteAllSessions() }
    }
}
