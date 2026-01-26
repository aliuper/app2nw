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
     * AŞAMA 1: Domain Listele
     * Sadece aynı IP'deki domainleri bul ve listele (IPTV testi yapmadan)
     */
    fun findDomains() {
        val currentState = _state.value
        
        if (currentState.originalLink.isBlank()) {
            _state.update { it.copy(errorMessage = "Domain adresini girin") }
            return
        }
        
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.update { 
                it.copy(
                    isScanning = true, 
                    progressPercent = 0, 
                    progressText = "Domain aranıyor...",
                    discoveredDomains = emptyList(),
                    resolvedIP = "",
                    results = emptyList(),
                    activeCount = 0,
                    errorMessage = null
                )
            }
            
            try {
                // Sadece domain listele - IPTV testi yapma
                val domains = sideServerScanner.findDomainsOnly(
                    originalUrl = currentState.originalLink
                ) { status, current, total, ip, domainList ->
                    _state.update { state ->
                        state.copy(
                            progressPercent = current,
                            progressText = status,
                            resolvedIP = ip,
                            discoveredDomains = domainList
                        )
                    }
                }
                
                _state.update { 
                    it.copy(
                        isScanning = false,
                        progressPercent = 100,
                        progressText = "✅ ${domains.size} domain bulundu! Test etmek için 'IPTV Test Et' butonuna tıklayın.",
                        discoveredDomains = domains
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

    /**
     * AŞAMA 2: IPTV Test Et
     * Bulunan domainleri IPTV için test et
     */
    fun testDomains() {
        val currentState = _state.value
        
        if (currentState.discoveredDomains.isEmpty()) {
            _state.update { it.copy(errorMessage = "Önce domain listeleyin") }
            return
        }
        
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.update { 
                it.copy(
                    isTesting = true, 
                    progressPercent = 0, 
                    progressText = "IPTV testi başlıyor...",
                    results = emptyList(),
                    activeCount = 0,
                    errorMessage = null
                )
            }
            
            try {
                val results = sideServerScanner.testDomainsForIptv(
                    domains = currentState.discoveredDomains,
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
                        isTesting = false,
                        progressPercent = 100,
                        progressText = "✅ Test tamamlandı! ${it.activeCount} IPTV sunucusu bulundu"
                    )
                }
                
            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        isTesting = false,
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
                isTesting = false,
                progressText = "Durduruldu"
            )
        }
    }

    fun clearResults() {
        _state.update { 
            it.copy(
                originalLink = "",
                username = "",
                password = "",
                discoveredDomains = emptyList(),
                resolvedIP = "",
                results = emptyList(),
                activeCount = 0,
                progressPercent = 0,
                progressText = "",
                errorMessage = null
            )
        }
    }

    fun copyActiveLinks(): String {
        return _state.value.results
            .filter { it.isActive }
            .joinToString("\n") { it.m3uLink }
    }
    
    fun copyAllDomains(): String {
        return _state.value.discoveredDomains.joinToString("\n")
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }
}
