package com.alibaba.feature.panelcheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════
// STATE
// ═══════════════════════════════════════════════════════════════

data class PanelCheckResult(
    val originalInput: String,
    val host: String,
    val detectedPort: Int?,
    val isOnline: Boolean,
    val responseTimeMs: Long = 0,
    val serverInfo: String? = null,
    val ipAddress: String? = null,
    val relatedDomains: List<RelatedPanel> = emptyList(),
    val errorMessage: String? = null,
    val portsScanned: List<PortScanResult> = emptyList()
)

data class PortScanResult(
    val port: Int,
    val isOpen: Boolean,
    val isIptv: Boolean = false,
    val responseTimeMs: Long = 0
)

data class RelatedPanel(
    val domain: String,
    val ip: String,
    val port: Int?,
    val isOnline: Boolean = false,
    val source: String = "" // nasıl bulundu
)

data class PanelCheckState(
    val inputText: String = "",
    val isChecking: Boolean = false,
    val isFindingRelated: Boolean = false,
    val statusMessage: String = "",
    val results: List<PanelCheckResult> = emptyList(),
    val progress: Float = 0f,
    val currentPanel: String = "",
    val errorMessage: String? = null,
    // İstatistikler
    val totalChecked: Int = 0,
    val onlineCount: Int = 0,
    val offlineCount: Int = 0,
    val portFoundCount: Int = 0
)

@HiltViewModel
class PanelCheckViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(PanelCheckState())
    val state: StateFlow<PanelCheckState> = _state.asStateFlow()

    private var checkJob: Job? = null

    // Yaygın IPTV panel portları - öncelik sırasına göre
    private val commonIptvPorts = listOf(
        80, 8080, 8880, 8888, 25461, 25462, 25463, 443,
        8000, 8001, 8443, 2082, 2083, 2086, 2087, 2095, 2096,
        8081, 8082, 8083, 8084, 8085, 9090, 9091,
        7777, 1935, 554, 8554, 8181, 8282, 8383, 8484,
        8585, 8686, 8787, 8989, 9000, 9001, 9002, 9999,
        81, 82, 83, 84, 85, 86, 87, 88, 89, 90,
        800, 880, 888, 8008, 8800
    )

    fun setInputText(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun clearResults() {
        _state.update { it.copy(results = emptyList(), totalChecked = 0, onlineCount = 0, offlineCount = 0, portFoundCount = 0) }
    }

    /**
     * Girilen metinden panel adreslerini çıkar
     * host:port veya sadece host destekler
     */
    private fun parseInputPanels(text: String): List<Pair<String, Int?>> {
        val results = mutableListOf<Pair<String, Int?>>()
        val lines = text.split("\n", "\r\n", ",", ";", " ").map { it.trim() }.filter { it.isNotBlank() }

        for (line in lines) {
            val cleaned = line.removePrefix("http://").removePrefix("https://").split("/")[0].trim()
            if (cleaned.isBlank()) continue

            if (cleaned.contains(":")) {
                val parts = cleaned.split(":")
                val host = parts[0].trim()
                val port = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (host.isNotBlank() && host.contains(".")) {
                    results.add(host to port)
                }
            } else {
                if (cleaned.contains(".")) {
                    results.add(cleaned to null) // Port yok, taranacak
                }
            }
        }

        return results.distinctBy { it.first + ":" + (it.second ?: "auto") }
    }

    // ═══════════════════════════════════════════════════════════════
    // AKTİFLİK KONTROL - Ana fonksiyon
    // ═══════════════════════════════════════════════════════════════

    fun startCheck() {
        val text = _state.value.inputText.trim()
        if (text.isBlank()) {
            _state.update { it.copy(errorMessage = "Lütfen panel adresi girin") }
            return
        }

        val panels = parseInputPanels(text)
        if (panels.isEmpty()) {
            _state.update { it.copy(errorMessage = "Geçerli panel adresi bulunamadı") }
            return
        }

        checkJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isChecking = true,
                    errorMessage = null,
                    results = emptyList(),
                    progress = 0f,
                    totalChecked = 0,
                    onlineCount = 0,
                    offlineCount = 0,
                    portFoundCount = 0,
                    statusMessage = "🔍 ${panels.size} panel kontrol ediliyor..."
                )
            }

            val allResults = mutableListOf<PanelCheckResult>()
            var online = 0
            var offline = 0
            var portsFound = 0

            try {
                panels.forEachIndexed { index, (host, port) ->
                    if (!isActive) return@forEachIndexed

                    _state.update {
                        it.copy(
                            currentPanel = host,
                            progress = (index.toFloat()) / panels.size,
                            statusMessage = if (port == null)
                                "🔎 $host - Port taranıyor..."
                            else
                                "🔎 $host:$port - Kontrol ediliyor..."
                        )
                    }

                    val result = checkSinglePanel(host, port)
                    allResults.add(result)

                    if (result.isOnline) online++ else offline++
                    if (result.detectedPort != null && port == null) portsFound++

                    _state.update {
                        it.copy(
                            results = allResults.toList(),
                            totalChecked = allResults.size,
                            onlineCount = online,
                            offlineCount = offline,
                            portFoundCount = portsFound
                        )
                    }
                }

                _state.update {
                    it.copy(
                        isChecking = false,
                        progress = 1f,
                        statusMessage = "✅ Tamamlandı: $online online, $offline offline" +
                                if (portsFound > 0) ", $portsFound port bulundu" else ""
                    )
                }

            } catch (e: CancellationException) {
                _state.update {
                    it.copy(
                        isChecking = false,
                        statusMessage = "⏹ Durduruldu (${allResults.size}/${panels.size})"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isChecking = false,
                        errorMessage = "Hata: ${e.message}"
                    )
                }
            }
        }
    }

    fun stopCheck() {
        checkJob?.cancel()
        _state.update {
            it.copy(isChecking = false, isFindingRelated = false, statusMessage = "⏹ Durduruldu")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TEK PANEL KONTROL
    // ═══════════════════════════════════════════════════════════════

    private suspend fun checkSinglePanel(host: String, givenPort: Int?): PanelCheckResult =
        withContext(Dispatchers.IO) {

            // 1) IP adresini çöz
            val ipAddress = try {
                InetAddress.getByName(host).hostAddress
            } catch (e: Exception) {
                null
            }

            if (ipAddress == null) {
                return@withContext PanelCheckResult(
                    originalInput = if (givenPort != null) "$host:$givenPort" else host,
                    host = host,
                    detectedPort = givenPort,
                    isOnline = false,
                    ipAddress = null,
                    errorMessage = "DNS çözümlenemedi"
                )
            }

            // 2) Port verilmişse direkt kontrol et
            if (givenPort != null) {
                val portResult = testPort(host, givenPort)
                val isIptv = if (portResult.isOpen) testIptvEndpoint(host, givenPort) else false
                val responseTime = portResult.responseTimeMs

                val serverInfo = if (isIptv) {
                    getServerInfo(host, givenPort)
                } else null

                return@withContext PanelCheckResult(
                    originalInput = "$host:$givenPort",
                    host = host,
                    detectedPort = givenPort,
                    isOnline = isIptv || portResult.isOpen,
                    responseTimeMs = responseTime,
                    serverInfo = serverInfo,
                    ipAddress = ipAddress,
                    portsScanned = listOf(portResult.copy(isIptv = isIptv))
                )
            }

            // 3) Port verilmemişse - Akıllı port tarama!
            val scannedPorts = mutableListOf<PortScanResult>()
            var foundPort: Int? = null
            var bestResponseTime = 0L
            var serverInfo: String? = null

            // Paralel port tarama - 15 port aynı anda
            val semaphore = Semaphore(15)
            val portJobs = commonIptvPorts.map { port ->
                async {
                    semaphore.withPermit {
                        val result = testPort(host, port)
                        if (result.isOpen) {
                            val isIptv = testIptvEndpoint(host, port)
                            result.copy(isIptv = isIptv)
                        } else {
                            result
                        }
                    }
                }
            }

            for ((index, job) in portJobs.withIndex()) {
                if (!isActive) break
                val result = job.await()
                scannedPorts.add(result)

                if (result.isIptv && foundPort == null) {
                    foundPort = result.port
                    bestResponseTime = result.responseTimeMs
                    serverInfo = getServerInfo(host, result.port)
                }

                // İlerleme güncelle
                if (index % 5 == 0) {
                    _state.update {
                        it.copy(
                            statusMessage = "🔎 $host - Port taranıyor... (${index + 1}/${commonIptvPorts.size})" +
                                    if (foundPort != null) " ✅ Port $foundPort bulundu!" else ""
                        )
                    }
                }
            }

            // IPTV port bulunamadıysa, açık olan ilk portu al
            if (foundPort == null) {
                val openPort = scannedPorts.firstOrNull { it.isOpen }
                if (openPort != null) {
                    foundPort = openPort.port
                    bestResponseTime = openPort.responseTimeMs
                }
            }

            PanelCheckResult(
                originalInput = host,
                host = host,
                detectedPort = foundPort,
                isOnline = foundPort != null,
                responseTimeMs = bestResponseTime,
                serverInfo = serverInfo,
                ipAddress = ipAddress,
                portsScanned = scannedPorts.filter { it.isOpen || it.isIptv }
            )
        }

    // ═══════════════════════════════════════════════════════════════
    // PORT TEST
    // ═══════════════════════════════════════════════════════════════

    private fun testPort(host: String, port: Int): PortScanResult {
        val start = System.currentTimeMillis()
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 3000)
            val time = System.currentTimeMillis() - start
            socket.close()
            PortScanResult(port = port, isOpen = true, responseTimeMs = time)
        } catch (e: Exception) {
            PortScanResult(port = port, isOpen = false, responseTimeMs = System.currentTimeMillis() - start)
        }
    }

    /**
     * IPTV panel endpoint'i kontrol et
     * player_api.php'ye istek at, anlamlı yanıt alırsa IPTV panel
     */
    private fun testIptvEndpoint(host: String, port: Int): Boolean {
        return try {
            val url = URL("http://$host:$port/player_api.php")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            val code = conn.responseCode
            val contentType = conn.contentType ?: ""
            conn.disconnect()

            // IPTV paneller genelde JSON döner veya 200/403 verir
            code in 200..499 && (contentType.contains("json") || contentType.contains("text") || code == 403 || code == 200)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Panel'den sunucu bilgisi al
     */
    private fun getServerInfo(host: String, port: Int): String? {
        return try {
            val url = URL("http://$host:$port/player_api.php")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"

            val server = conn.getHeaderField("Server")
            val powered = conn.getHeaderField("X-Powered-By")
            conn.disconnect()

            buildString {
                if (server != null) append("Server: $server")
                if (powered != null) {
                    if (isNotEmpty()) append(" | ")
                    append("Powered: $powered")
                }
            }.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // YAN PANEL BULMA - IP tabanlı ilişkili domain keşfi
    // ═══════════════════════════════════════════════════════════════

    /**
     * 🔥 Yan Panel Bulma Sistemi
     * 1. Domain -> IP çözümle
     * 2. Reverse DNS ile aynı IP'deki diğer domainleri bul
     * 3. Bulunan domainlerde IPTV port taraması yap
     * 4. Benzer subdomain pattern'leri dene
     */
    fun findRelatedPanels(result: PanelCheckResult) {
        val ip = result.ipAddress ?: return
        val host = result.host

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isFindingRelated = true,
                    statusMessage = "🔍 $host için yan paneller aranıyor..."
                )
            }

            val relatedPanels = mutableListOf<RelatedPanel>()

            withContext(Dispatchers.IO) {
                // Yöntem 1: Reverse DNS lookup
                try {
                    val reverseName = InetAddress.getByName(ip).canonicalHostName
                    if (reverseName != ip && reverseName != host) {
                        val isIptv = tryFindIptvPort(reverseName)
                        relatedPanels.add(
                            RelatedPanel(
                                domain = reverseName,
                                ip = ip,
                                port = isIptv,
                                isOnline = isIptv != null,
                                source = "Reverse DNS"
                            )
                        )
                    }
                } catch (_: Exception) {}

                // Yöntem 2: Subdomain pattern keşfi
                val baseDomain = extractBaseDomain(host)
                val subdomainPrefixes = listOf(
                    "panel", "iptv", "tv", "stream", "live", "play",
                    "portal", "api", "server", "media", "cdn", "vod",
                    "s1", "s2", "s3", "s4", "s5",
                    "panel1", "panel2", "panel3",
                    "dns", "ns1", "ns2", "proxy",
                    "m3u", "playlist", "epg",
                    "new", "old", "v2", "v3",
                    "main", "backup", "mirror"
                )

                val subdomainSemaphore = Semaphore(10)
                val subJobs = subdomainPrefixes.map { prefix ->
                    async {
                        subdomainSemaphore.withPermit {
                            val testDomain = "$prefix.$baseDomain"
                            if (testDomain != host) {
                                try {
                                    val resolvedIp = InetAddress.getByName(testDomain).hostAddress
                                    val port = tryFindIptvPort(testDomain)
                                    RelatedPanel(
                                        domain = testDomain,
                                        ip = resolvedIp ?: "",
                                        port = port,
                                        isOnline = port != null,
                                        source = if (resolvedIp == ip) "Aynı IP - Subdomain" else "Farklı IP - Subdomain"
                                    )
                                } catch (_: Exception) {
                                    null
                                }
                            } else null
                        }
                    }
                }

                subJobs.forEachIndexed { index, job ->
                    val panel = job.await()
                    if (panel != null) {
                        relatedPanels.add(panel)
                    }
                    if (index % 5 == 0) {
                        _state.update {
                            it.copy(statusMessage = "🔍 Subdomain taranıyor... (${index + 1}/${subdomainPrefixes.size}) - ${relatedPanels.size} bulundu")
                        }
                    }
                }

                // Yöntem 3: Aynı IP'de farklı portlar dene
                val ipPorts = listOf(80, 8080, 8880, 8888, 25461, 443, 8000, 8001)
                val currentPort = result.detectedPort
                ipPorts.filter { it != currentPort }.forEach { port ->
                    try {
                        val isIptv = testIptvEndpoint(ip, port)
                        if (isIptv) {
                            relatedPanels.add(
                                RelatedPanel(
                                    domain = ip,
                                    ip = ip,
                                    port = port,
                                    isOnline = true,
                                    source = "Aynı IP farklı port"
                                )
                            )
                        }
                    } catch (_: Exception) {}
                }
            }

            // Sonucu güncelle
            val updatedResults = _state.value.results.map { r ->
                if (r.host == host) r.copy(relatedDomains = relatedPanels) else r
            }

            _state.update {
                it.copy(
                    isFindingRelated = false,
                    results = updatedResults,
                    statusMessage = "✅ ${relatedPanels.size} ilişkili panel/domain bulundu"
                )
            }
        }
    }

    /**
     * Hızlı IPTV port tespiti - yaygın portları dene
     */
    private fun tryFindIptvPort(host: String): Int? {
        val quickPorts = listOf(80, 8080, 8880, 8888, 25461, 443, 8000)
        for (port in quickPorts) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 2000)
                socket.close()
                if (testIptvEndpoint(host, port)) {
                    return port
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Base domain çıkar: sub.panel.example.com -> example.com
     */
    private fun extractBaseDomain(host: String): String {
        val parts = host.split(".")
        return if (parts.size >= 2) {
            "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
        } else host
    }

    /**
     * Sonuçları metin olarak al (kopyalama/paylaşma için)
     */
    fun getResultsText(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Panel Aktiflik Kontrol Sonuçları ===")
        sb.appendLine("Online: ${_state.value.onlineCount} | Offline: ${_state.value.offlineCount}")
        sb.appendLine()

        _state.value.results.forEach { result ->
            val status = if (result.isOnline) "✅ ONLINE" else "❌ OFFLINE"
            val address = if (result.detectedPort != null) "${result.host}:${result.detectedPort}" else result.host
            sb.appendLine("$status | $address | IP: ${result.ipAddress ?: "?"} | ${result.responseTimeMs}ms")

            if (result.relatedDomains.isNotEmpty()) {
                result.relatedDomains.filter { it.isOnline }.forEach { related ->
                    sb.appendLine("  ↳ ${related.domain}:${related.port} (${related.source})")
                }
            }
        }
        return sb.toString()
    }
}
