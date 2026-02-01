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
    val customPanelUrl: String = "",  // Elle girilen panel URL
    val selectedPanels: List<PanelInfo> = emptyList(),
    val useEmbeddedPanels: Boolean = false,  // Varsayılan kapalı - kullanıcı kendi panelini girecek
    val scanning: Boolean = false,
    val progress: ScanProgress? = null,
    val results: List<PanelScanResult> = emptyList(),
    val errorMessage: String? = null,
    val comboLineCount: Int = 0,  // Yüklenen satır sayısı
    val showSaveDialog: Boolean = false,  // Kaydetme dialogu
    val savedFilePath: String? = null  // Kaydedilen dosya yolu
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
    
    private var scanJob: kotlinx.coroutines.Job? = null

    fun setComboText(text: String) {
        val lineCount = text.lines().count { it.contains(":") }
        _state.update { it.copy(comboText = text, comboLineCount = lineCount) }
    }
    
    fun setCustomPanelUrl(url: String) {
        _state.update { it.copy(customPanelUrl = url) }
    }
    
    fun parseAndAddCustomPanel() {
        val url = _state.value.customPanelUrl.trim()
        if (url.isBlank()) return
        
        try {
            // URL'den host ve port çıkar
            val cleanUrl = url.removePrefix("http://").removePrefix("https://")
            val parts = cleanUrl.split(":")
            val host = parts[0].split("/")[0]
            val port = if (parts.size > 1) {
                parts[1].split("/")[0].toIntOrNull() ?: 80
            } else 80
            
            val panel = PanelInfo(host, port, isEmbedded = false)
            _state.update { 
                it.copy(
                    selectedPanels = it.selectedPanels + panel,
                    customPanelUrl = ""  // Temizle
                ) 
            }
        } catch (e: Exception) {
            _state.update { it.copy(errorMessage = "Geçersiz panel URL: $url") }
        }
    }
    
    fun clearCustomPanels() {
        _state.update { it.copy(selectedPanels = emptyList()) }
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
    
    fun stopScan() {
        scanJob?.cancel()
        _state.update { 
            it.copy(
                scanning = false,
                showSaveDialog = it.results.isNotEmpty()  // Sonuç varsa kaydetme dialogu göster
            ) 
        }
    }
    
    fun dismissSaveDialog() {
        _state.update { it.copy(showSaveDialog = false) }
    }
    
    fun getResultsAsText(): String {
        val results = _state.value.results
        val sb = StringBuilder()
        sb.appendLine("=== IPTV Panel Tarama Sonuçları ===")
        sb.appendLine("Tarih: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("Bulunan Hesap Sayısı: ${results.size}")
        sb.appendLine()
        
        results.forEach { result ->
            sb.appendLine("─".repeat(50))
            sb.appendLine("Kullanıcı: ${result.account.username}")
            sb.appendLine("Şifre: ${result.account.password}")
            sb.appendLine("Panel: ${result.panel.fullAddress}")
            result.userInfo?.let { info ->
                sb.appendLine("Bitiş: ${info.expDate ?: "Sınırsız"}")
                sb.appendLine("Bağlantı: ${info.activeCons}/${info.maxConnections}")
                sb.appendLine("Durum: ${info.status}")
            }
            sb.appendLine("M3U: http://${result.panel.fullAddress}/get.php?username=${result.account.username}&password=${result.account.password}&type=m3u_plus")
            sb.appendLine()
        }
        
        return sb.toString()
    }

    fun startScan() {
        val currentState = _state.value
        
        if (currentState.comboText.isBlank()) {
            _state.update { it.copy(errorMessage = "Lütfen combo listesi girin") }
            return
        }
        
        // Custom panel veya embedded panel kontrolü
        val hasCustomPanels = currentState.selectedPanels.isNotEmpty()
        val useEmbedded = currentState.useEmbeddedPanels
        
        if (!hasCustomPanels && !useEmbedded) {
            _state.update { it.copy(errorMessage = "Lütfen panel URL'si girin veya gömülü panelleri aktif edin") }
            return
        }

        scanJob = viewModelScope.launch {
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
