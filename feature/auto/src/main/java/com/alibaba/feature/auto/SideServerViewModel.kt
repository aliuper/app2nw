package com.alibaba.feature.auto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.domain.service.SideServerScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SideServerViewModel @Inject constructor(
    private val sideServerScanner: SideServerScanner
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
                
                // Otomatik varyasyon oluştur
                if (_state.value.autoGenerateEnabled) {
                    generateVariations(credentials.serverUrl)
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

    fun updateServerUrls(urls: String) {
        _state.update { it.copy(serverUrls = urls) }
    }

    fun toggleAutoGenerate(enabled: Boolean) {
        _state.update { it.copy(autoGenerateEnabled = enabled) }
    }

    private fun generateVariations(originalServerUrl: String) {
        viewModelScope.launch {
            val variations = withContext(Dispatchers.Default) {
                sideServerScanner.generateDomainVariations(originalServerUrl)
            }
            
            if (variations.isNotEmpty()) {
                val currentUrls = _state.value.serverUrls
                val newUrls = if (currentUrls.isBlank()) {
                    variations.joinToString("\n")
                } else {
                    currentUrls + "\n" + variations.joinToString("\n")
                }
                _state.update { it.copy(serverUrls = newUrls) }
            }
        }
    }

    fun manualGenerateVariations() {
        val link = _state.value.originalLink
        if (link.isBlank()) {
            _state.update { it.copy(errorMessage = "Önce orijinal linki girin") }
            return
        }
        
        viewModelScope.launch {
            val credentials = sideServerScanner.extractCredentials(link)
            if (credentials != null) {
                generateVariations(credentials.serverUrl)
            } else {
                _state.update { it.copy(errorMessage = "Link'ten sunucu bilgisi çıkarılamadı") }
            }
        }
    }

    fun startScan() {
        val currentState = _state.value
        
        if (currentState.username.isBlank() || currentState.password.isBlank()) {
            _state.update { it.copy(errorMessage = "Kullanıcı adı ve şifre gerekli") }
            return
        }
        
        val serverUrls = currentState.serverUrls
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() && (it.startsWith("http://") || it.startsWith("https://")) }
        
        if (serverUrls.isEmpty()) {
            _state.update { it.copy(errorMessage = "En az bir sunucu URL'si girin") }
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
            
            val credentials = SideServerScanner.Credentials(
                serverUrl = "",
                username = currentState.username,
                password = currentState.password
            )
            
            try {
                val results = sideServerScanner.scanServers(
                    credentials = credentials,
                    serverUrls = serverUrls
                ) { current, total, result ->
                    val percent = ((current * 100) / total).coerceIn(0, 100)
                    val activeCount = _state.value.results.count { it.isActive } + 
                        (if (result?.isActive == true) 1 else 0)
                    
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
                            progressPercent = percent,
                            progressText = "$current / $total | ✅ $activeCount aktif",
                            results = newResults,
                            activeCount = newResults.count { it.isActive }
                        )
                    }
                }
                
                _state.update { 
                    it.copy(
                        isScanning = false,
                        progressPercent = 100,
                        progressText = "Tamamlandı! ${it.activeCount} aktif sunucu bulundu"
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
