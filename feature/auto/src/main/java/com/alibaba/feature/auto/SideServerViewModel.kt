package com.alibaba.feature.auto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.data.service.SideServerScannerImpl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SideServerViewModel @Inject constructor(
    private val sideServerScanner: SideServerScannerImpl
) : ViewModel() {

    private val _state = MutableStateFlow(SideServerUiState())
    val state: StateFlow<SideServerUiState> = _state

    private var scanJob: Job? = null

    fun updateOriginalLink(link: String) {
        _state.update { it.copy(originalLink = link, errorMessage = null) }
        
        // Otomatik olarak credentials çıkar
        viewModelScope.launch {
            val credentials = sideServerScanner.extractCredentials(link)
            if (credentials != null) {
                _state.update { 
                    it.copy(
                        username = credentials.username,
                        password = credentials.password
                    )
                }
            }
        }
    }

    fun updateUsername(username: String) {
        _state.update { it.copy(username = username) }
    }

    fun updatePassword(password: String) {
        _state.update { it.copy(password = password) }
    }

    /**
     * Reverse IP Lookup + IPTV Tespiti ile tam tarama başlat
     */
    fun startScan() {
        val currentState = _state.value
        
        if (currentState.originalLink.isBlank()) {
            _state.update { it.copy(errorMessage = "Orijinal IPTV linkini girin") }
            return
        }
        
        if (currentState.username.isBlank() || currentState.password.isBlank()) {
            _state.update { it.copy(errorMessage = "Kullanıcı adı ve şifre gerekli") }
            return
        }
        
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.update { 
                it.copy(
                    isScanning = true, 
                    progressPercent = 0, 
                    progressText = "Başlatılıyor...",
                    results = emptyList(),
                    activeCount = 0,
                    errorMessage = null
                )
            }
            
            try {
                // Yeni fullScan metodu - Reverse IP + IPTV Tespiti
                val results = sideServerScanner.fullScan(
                    originalUrl = currentState.originalLink,
                    username = currentState.username,
                    password = currentState.password
                ) { status, current, total, result ->
                    _state.update { state ->
                        val newResults = if (result != null) {
                            state.results + SideServerResultItem(
                                serverUrl = result.serverUrl,
                                m3uLink = result.m3uLink,
                                isActive = result.isActive,
                                statusText = result.statusText,
                                expireDate = result.expireDate,
                                maxConnections = result.maxConnections
                            )
                        } else {
                            state.results
                        }
                        
                        state.copy(
                            progressPercent = current,
                            progressText = status,
                            results = newResults,
                            activeCount = newResults.count { it.isActive }
                        )
                    }
                }
                
                _state.update { 
                    it.copy(
                        isScanning = false,
                        progressPercent = 100,
                        progressText = "✅ Tamamlandı! ${it.activeCount} aktif sunucu bulundu"
                    )
                }
                
            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        isScanning = false,
                        errorMessage = "Hata: ${e.message}"
                    )
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update { 
            it.copy(
                isScanning = false,
                progressText = "Durduruldu"
            )
        }
    }

    fun clearResults() {
        _state.update { 
            it.copy(
                results = emptyList(),
                activeCount = 0,
                progressPercent = 0,
                progressText = ""
            )
        }
    }

    fun copyActiveLinks(): String {
        return _state.value.results
            .filter { it.isActive }
            .joinToString("\n") { it.m3uLink }
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }
}
