package com.alibaba.feature.panelscan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.domain.model.*
import com.alibaba.domain.service.PanelScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PanelScanState(
    val comboText: String = "",
    val selectedPanels: List<PanelInfo> = emptyList(),
    val useEmbeddedPanels: Boolean = true,
    val scanning: Boolean = false,
    val progress: ScanProgress? = null,
    val results: List<PanelScanResult> = emptyList(),
    val errorMessage: String? = null
)

data class ScanProgress(
    val current: Int,
    val total: Int,
    val currentAccount: String = "",
    val validCount: Int = 0,
    val invalidCount: Int = 0,
    val errorCount: Int = 0
)

@HiltViewModel
class PanelScanViewModel @Inject constructor(
    private val panelScanner: PanelScanner
) : ViewModel() {

    private val _state = MutableStateFlow(PanelScanState())
    val state: StateFlow<PanelScanState> = _state.asStateFlow()

    fun setComboText(text: String) {
        _state.update { it.copy(comboText = text) }
    }

    fun toggleEmbeddedPanels() {
        _state.update { it.copy(useEmbeddedPanels = !it.useEmbeddedPanels) }
    }

    fun addCustomPanel(host: String, port: Int) {
        val panel = PanelInfo(host, port, isEmbedded = false)
        _state.update { 
            it.copy(selectedPanels = it.selectedPanels + panel) 
        }
    }

    fun removePanel(panel: PanelInfo) {
        _state.update { 
            it.copy(selectedPanels = it.selectedPanels.filter { p -> p != panel }) 
        }
    }

    fun startScan() {
        val currentState = _state.value
        
        if (currentState.comboText.isBlank()) {
            _state.update { it.copy(errorMessage = "Lütfen combo listesi girin") }
            return
        }

        viewModelScope.launch {
            _state.update { 
                it.copy(
                    scanning = true, 
                    errorMessage = null,
                    results = emptyList(),
                    progress = null
                ) 
            }

            try {
                // Parse combo file
                val accounts = panelScanner.parseComboFile(currentState.comboText)
                
                if (accounts.isEmpty()) {
                    _state.update { 
                        it.copy(
                            scanning = false,
                            errorMessage = "Geçerli hesap bulunamadı. Format: kullanici:sifre"
                        ) 
                    }
                    return@launch
                }

                // Get panels to scan
                val panelsToScan = mutableListOf<PanelInfo>()
                
                if (currentState.useEmbeddedPanels) {
                    panelsToScan.addAll(
                        EmbeddedPanels.panels.map { 
                            PanelInfo(it.host, it.port, isEmbedded = true) 
                        }
                    )
                }
                
                panelsToScan.addAll(currentState.selectedPanels)

                if (panelsToScan.isEmpty()) {
                    _state.update { 
                        it.copy(
                            scanning = false,
                            errorMessage = "Lütfen en az bir panel seçin"
                        ) 
                    }
                    return@launch
                }

                // Calculate total scans
                val totalScans = accounts.size * panelsToScan.size
                var currentScan = 0
                var validCount = 0
                var invalidCount = 0
                var errorCount = 0
                val results = mutableListOf<PanelScanResult>()

                // Scan each account on each panel
                withContext(Dispatchers.IO) {
                    for (account in accounts) {
                        for (panel in panelsToScan) {
                            currentScan++
                            
                            // Update progress
                            _state.update { 
                                it.copy(
                                    progress = ScanProgress(
                                        current = currentScan,
                                        total = totalScans,
                                        currentAccount = "${account.username}:${account.password}",
                                        validCount = validCount,
                                        invalidCount = invalidCount,
                                        errorCount = errorCount
                                    )
                                ) 
                            }

                            // Scan account
                            val result = panelScanner.scanAccount(account, panel)
                            
                            // Update counts
                            when (result.status) {
                                is ScanStatus.Valid -> {
                                    validCount++
                                    results.add(result)
                                }
                                is ScanStatus.Invalid -> invalidCount++
                                is ScanStatus.Error -> errorCount++
                                is ScanStatus.Banned -> errorCount++
                                else -> {}
                            }

                            // Update results if valid
                            if (result.status is ScanStatus.Valid) {
                                _state.update { 
                                    it.copy(results = results.toList()) 
                                }
                            }

                            // Small delay to prevent rate limiting
                            kotlinx.coroutines.delay(100)
                        }
                    }
                }

                // Final update
                _state.update { 
                    it.copy(
                        scanning = false,
                        progress = ScanProgress(
                            current = totalScans,
                            total = totalScans,
                            validCount = validCount,
                            invalidCount = invalidCount,
                            errorCount = errorCount
                        )
                    ) 
                }

            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        scanning = false,
                        errorMessage = "Hata: ${e.message}"
                    ) 
                }
            }
        }
    }

    fun clearResults() {
        _state.update { 
            it.copy(
                results = emptyList(),
                progress = null,
                errorMessage = null
            ) 
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
