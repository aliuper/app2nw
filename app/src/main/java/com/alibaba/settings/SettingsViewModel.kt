package com.alibaba.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.domain.model.AppSettings
import com.alibaba.domain.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val saving: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _state.update { it.copy(settings = s) }
            }
        }
    }

    fun setStreamTestSampleSize(value: Int) = launchSave {
        settingsRepository.setStreamTestSampleSize(value)
    }

    fun setStreamTestTimeoutMs(value: Long) = launchSave {
        settingsRepository.setStreamTestTimeoutMs(value)
    }

    fun setMinPlayableStreamsToPass(value: Int) = launchSave {
        settingsRepository.setMinPlayableStreamsToPass(value)
    }

    fun setDelayBetweenStreamTestsMs(value: Long) = launchSave {
        settingsRepository.setDelayBetweenStreamTestsMs(value)
    }

    fun setSkipAdultGroups(value: Boolean) = launchSave {
        settingsRepository.setSkipAdultGroups(value)
    }

    fun setShuffleCandidates(value: Boolean) = launchSave {
        settingsRepository.setShuffleCandidates(value)
    }

    fun setEnableCountryFiltering(value: Boolean) = launchSave {
        settingsRepository.setEnableCountryFiltering(value)
    }

    fun resetToDefaults() = launchSave {
        settingsRepository.resetToDefaults()
    }

    private fun launchSave(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                block()
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }
}
