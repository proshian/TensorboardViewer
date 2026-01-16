package com.example.tensorboardviewer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tensorboardviewer.data.model.ScalarSequence
import com.example.tensorboardviewer.data.repository.LogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class Success(val sequences: List<ScalarSequence>) : UiState()
    data class Error(val message: String) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LogRepository(application)

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    fun loadLogs(directoryUri: Uri) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val sequences = repository.loadLogs(directoryUri)
                if (sequences.isEmpty()) {
                    _uiState.value = UiState.Error("No valid logs found in directory.")
                } else {
                    _uiState.value = UiState.Success(sequences)
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.localizedMessage ?: "Unknown error")
            }
        }
    }
}
