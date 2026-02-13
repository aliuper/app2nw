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
import java.util.concurrent.atomic.AtomicInteger
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
    val allDiscoveredDomains: List<String> = emptyList(), // TÜM bulunan domainler
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
    val portFoundCount: Int = 0,
    // Yan panel arama log'u
    val scanLog: List<String> = emptyList(),
    val discoveredDomainsCount: Int = 0,
    val iptvFoundCount: Int = 0
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
    // YAN PANEL BULMA - Reverse IP + Domain Varyasyon + Subdomain
    // ═══════════════════════════════════════════════════════════════

    private fun addLog(message: String) {
        _state.update { it.copy(
            scanLog = it.scanLog + message,
            statusMessage = message
        )}
    }

    /**
     * 🔥 Gelişmiş Yan Panel Bulma Sistemi
     * 1. Reverse IP Lookup (hackertarget, rapiddns, host.io) → aynı IP'deki TÜM domainler
     * 2. Domain varyasyon keşfi (numara pattern, prefix/suffix)
     * 3. Reverse DNS
     * 4. Subdomain brute-force
     * 5. Aynı IP farklı port
     * 6. Bulunan her domainde IPTV tespiti
     */
    fun findRelatedPanels(result: PanelCheckResult) {
        val ip = result.ipAddress ?: return
        val host = result.host

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isFindingRelated = true,
                    scanLog = emptyList(),
                    discoveredDomainsCount = 0,
                    iptvFoundCount = 0,
                    statusMessage = "🔍 $host ($ip) için yan paneller aranıyor..."
                )
            }

            val relatedPanels = mutableListOf<RelatedPanel>()
            val allDomains = mutableSetOf<String>()
            allDomains.add(host)

            withContext(Dispatchers.IO) {
                addLog("📍 Hedef: $host → IP: $ip")

                // ══════════════════════════════════════════════════
                // YÖNTEM 1: REVERSE IP LOOKUP API'LERİ
                // ══════════════════════════════════════════════════
                addLog("━━━ ADIM 1: Reverse IP Lookup ━━━")

                val reverseIpDomains = mutableListOf<String>()

                // API 1: HackerTarget
                addLog("🌐 [1/4] HackerTarget API sorgulanıyor...")
                try {
                    val htDomains = reverseIpHackerTarget(ip)
                    reverseIpDomains.addAll(htDomains)
                    addLog("✅ HackerTarget: ${htDomains.size} domain bulundu")
                } catch (e: Exception) {
                    addLog("❌ HackerTarget HATA: ${e.message?.take(80)}")
                }

                // API 2: RapidDNS
                addLog("🌐 [2/4] RapidDNS API sorgulanıyor...")
                try {
                    val rdDomains = reverseIpRapidDns(ip)
                    reverseIpDomains.addAll(rdDomains)
                    addLog("✅ RapidDNS: ${rdDomains.size} domain bulundu")
                } catch (e: Exception) {
                    addLog("❌ RapidDNS HATA: ${e.message?.take(80)}")
                }

                // API 3: Host.io
                addLog("🌐 [3/4] Host.io API sorgulanıyor...")
                try {
                    val hiDomains = reverseIpHostIo(ip)
                    reverseIpDomains.addAll(hiDomains)
                    addLog("✅ Host.io: ${hiDomains.size} domain bulundu")
                } catch (e: Exception) {
                    addLog("❌ Host.io HATA: ${e.message?.take(80)}")
                }

                // API 4: HackerTarget HostSearch
                addLog("🌐 [4/4] HostSearch API sorgulanıyor...")
                try {
                    val hsDomains = reverseIpHostSearch(ip)
                    reverseIpDomains.addAll(hsDomains)
                    addLog("✅ HostSearch: ${hsDomains.size} domain bulundu")
                } catch (e: Exception) {
                    addLog("❌ HostSearch HATA: ${e.message?.take(80)}")
                }

                val uniqueReverse = reverseIpDomains.distinct().filter { it != host && it !in allDomains }
                allDomains.addAll(uniqueReverse)
                addLog("📊 Reverse IP Toplam: ${uniqueReverse.size} benzersiz domain")
                _state.update { it.copy(discoveredDomainsCount = allDomains.size - 1) }

                // IPTV tespiti
                if (uniqueReverse.isNotEmpty()) {
                    addLog("━━━ ADIM 2: ${uniqueReverse.size} domain'de IPTV taranıyor ━━━")
                    val reverseIpSemaphore = Semaphore(15)
                    val reverseJobs = uniqueReverse.map { domain ->
                        async {
                            reverseIpSemaphore.withPermit {
                                testDomainForIptv(domain, ip, "Reverse IP")
                            }
                        }
                    }
                    reverseJobs.forEachIndexed { index, job ->
                        val panel = job.await()
                        if (panel != null) {
                            relatedPanels.add(panel)
                            if (panel.isOnline) {
                                addLog("  📡 IPTV bulundu: ${panel.domain}:${panel.port} (${panel.source})")
                            }
                        }
                        if ((index + 1) % 10 == 0 || index == reverseJobs.size - 1) {
                            _state.update { it.copy(
                                statusMessage = "📡 IPTV tarama: ${index + 1}/${uniqueReverse.size}",
                                iptvFoundCount = relatedPanels.count { it.isOnline }
                            )}
                        }
                    }
                    addLog("📊 Reverse IP IPTV sonuç: ${relatedPanels.count { it.isOnline }} aktif panel")
                } else {
                    addLog("⚠️ Reverse IP'den hiç domain bulunamadı - API limiti olabilir")
                }

                // ══════════════════════════════════════════════════
                // YÖNTEM 2: REVERSE DNS
                // ══════════════════════════════════════════════════
                addLog("━━━ ADIM 3: Reverse DNS ━━━")
                try {
                    val reverseName = InetAddress.getByName(ip).canonicalHostName
                    if (reverseName != ip && reverseName !in allDomains) {
                        allDomains.add(reverseName)
                        val port = tryFindIptvPort(reverseName)
                        relatedPanels.add(RelatedPanel(
                            domain = reverseName, ip = ip, port = port,
                            isOnline = port != null, source = "Reverse DNS"
                        ))
                        addLog("✅ Reverse DNS: $reverseName${if (port != null) " (IPTV port: $port)" else " (IPTV yok)"}")
                    } else {
                        addLog("⚠️ Reverse DNS: ${if (reverseName == ip) "sadece IP döndü" else "zaten listede"}")
                    }
                } catch (e: Exception) {
                    addLog("❌ Reverse DNS HATA: ${e.message?.take(80)}")
                }
                _state.update { it.copy(discoveredDomainsCount = allDomains.size - 1) }

                // ══════════════════════════════════════════════════
                // YÖNTEM 3: SUBDOMAIN BRUTE-FORCE
                // ══════════════════════════════════════════════════
                addLog("━━━ ADIM 4: Subdomain taraması ━━━")
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
                var subFound = 0
                val subdomainSemaphore = Semaphore(10)
                val subJobs = subdomainPrefixes.map { prefix ->
                    async {
                        subdomainSemaphore.withPermit {
                            val testDomain = "$prefix.$baseDomain"
                            if (testDomain !in allDomains) {
                                try {
                                    val resolvedIp = InetAddress.getByName(testDomain).hostAddress
                                    if (resolvedIp != null) {
                                        allDomains.add(testDomain)
                                        val port = tryFindIptvPort(testDomain)
                                        RelatedPanel(
                                            domain = testDomain, ip = resolvedIp ?: "", port = port,
                                            isOnline = port != null,
                                            source = if (resolvedIp == ip) "Aynı IP - Subdomain" else "Farklı IP - Subdomain"
                                        )
                                    } else null
                                } catch (_: Exception) { null }
                            } else null
                        }
                    }
                }
                subJobs.forEach { job ->
                    val panel = job.await()
                    if (panel != null) {
                        relatedPanels.add(panel)
                        subFound++
                        addLog("  🔎 Subdomain: ${panel.domain} → ${panel.ip}${if (panel.isOnline) " (IPTV ✅)" else ""}")
                    }
                }
                addLog("📊 Subdomain sonuç: $subFound bulundu")
                _state.update { it.copy(discoveredDomainsCount = allDomains.size - 1) }

                // ══════════════════════════════════════════════════
                // YÖNTEM 4: DOMAIN VARYASYON KEŞFİ
                // ══════════════════════════════════════════════════
                addLog("━━━ ADIM 5: Domain varyasyonları ━━━")
                val domainVariations = generateDomainVariations(host)
                addLog("🔄 ${domainVariations.size} varyasyon üretildi, DNS sorgulanıyor...")
                var varFound = 0
                val variationSemaphore = Semaphore(20)
                val variationJobs = domainVariations.filter { it !in allDomains }.map { variation ->
                    async {
                        variationSemaphore.withPermit {
                            try {
                                val resolvedIp = InetAddress.getByName(variation).hostAddress
                                if (resolvedIp != null) {
                                    allDomains.add(variation)
                                    val port = tryFindIptvPort(variation)
                                    if (port != null) {
                                        RelatedPanel(
                                            domain = variation, ip = resolvedIp, port = port, isOnline = true,
                                            source = if (resolvedIp == ip) "Aynı IP - Varyasyon" else "Farklı IP - Varyasyon"
                                        )
                                    } else null
                                } else null
                            } catch (_: Exception) { null }
                        }
                    }
                }
                variationJobs.forEachIndexed { index, job ->
                    val panel = job.await()
                    if (panel != null) {
                        relatedPanels.add(panel)
                        varFound++
                        addLog("  🎯 Varyasyon IPTV: ${panel.domain}:${panel.port} (${panel.source})")
                    }
                    if ((index + 1) % 50 == 0) {
                        _state.update { it.copy(
                            statusMessage = "🔄 Varyasyon: ${index + 1}/${variationJobs.size}",
                            discoveredDomainsCount = allDomains.size - 1
                        )}
                    }
                }
                addLog("📊 Varyasyon sonuç: $varFound IPTV panel bulundu")

                // ══════════════════════════════════════════════════
                // YÖNTEM 5: AYNI IP FARKLI PORTLAR
                // ══════════════════════════════════════════════════
                addLog("━━━ ADIM 6: Aynı IP farklı portlar ━━━")
                val ipPorts = listOf(80, 8080, 8880, 8888, 25461, 25462, 443, 8000, 8001, 9090)
                val currentPort = result.detectedPort
                ipPorts.filter { it != currentPort }.forEach { port ->
                    try {
                        if (testIptvEndpoint(ip, port)) {
                            relatedPanels.add(RelatedPanel(
                                domain = ip, ip = ip, port = port,
                                isOnline = true, source = "Aynı IP farklı port"
                            ))
                            addLog("  📡 $ip:$port → IPTV panel ✅")
                        }
                    } catch (_: Exception) {}
                }
            }

            // Sonucu güncelle
            val sortedPanels = relatedPanels.sortedWith(
                compareByDescending<RelatedPanel> { it.isOnline }
                    .thenByDescending { it.source.contains("Reverse IP") }
                    .thenByDescending { it.source.contains("Varyasyon") }
            )
            val allDomainsList = allDomains.filter { it != host }.toList()
            val onlineCount = sortedPanels.count { it.isOnline }

            val updatedResults = _state.value.results.map { r ->
                if (r.host == host) r.copy(
                    relatedDomains = sortedPanels,
                    allDiscoveredDomains = allDomainsList
                ) else r
            }

            addLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            addLog("✅ TAMAMLANDI:")
            addLog("  📋 Toplam domain: ${allDomainsList.size}")
            addLog("  📡 IPTV panel: $onlineCount")
            addLog("  🔗 İlişkili: ${sortedPanels.size}")

            _state.update {
                it.copy(
                    isFindingRelated = false,
                    results = updatedResults,
                    discoveredDomainsCount = allDomainsList.size,
                    iptvFoundCount = onlineCount,
                    statusMessage = "✅ ${allDomainsList.size} domain bulundu, $onlineCount IPTV aktif"
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // REVERSE IP LOOKUP API'LERİ
    // ═══════════════════════════════════════════════════════════════

    private val apiUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * HackerTarget Reverse IP Lookup
     * API: https://api.hackertarget.com/reverseiplookup/?q=IP
     */
    private fun reverseIpHackerTarget(ip: String): List<String> {
        val url = URL("https://api.hackertarget.com/reverseiplookup/?q=$ip")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", apiUserAgent)

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            conn.disconnect()
            throw Exception("HTTP $responseCode")
        }

        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        if (body.contains("error", ignoreCase = true) || body.contains("API count exceeded", ignoreCase = true)) {
            throw Exception("API limit: ${body.take(100)}")
        }

        return body.split("\n")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it.contains(".") && !it.contains(" ") && !it.startsWith("no ") }
    }

    /**
     * RapidDNS Reverse IP Lookup
     * URL: https://rapiddns.io/sameip/IP?full=1
     */
    private fun reverseIpRapidDns(ip: String): List<String> {
        val url = URL("https://rapiddns.io/sameip/$ip?full=1")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", apiUserAgent)
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.5")

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            conn.disconnect()
            throw Exception("HTTP $responseCode")
        }

        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val domains = mutableListOf<String>()
        // Tablo satırlarından domain çıkar
        val domainRegex = Regex("""<td>\s*([a-zA-Z0-9][-a-zA-Z0-9]*(?:\.[a-zA-Z0-9][-a-zA-Z0-9]*)*\.[a-zA-Z]{2,})\s*</td>""")
        domainRegex.findAll(body).forEach { match ->
            val domain = match.groupValues[1].lowercase().trim()
            if (domain.isNotBlank() && domain.contains(".") && !domain.contains("rapiddns")) {
                domains.add(domain)
            }
        }

        // Eğer tablo bulunamazsa, href link'lerinden dene
        if (domains.isEmpty()) {
            val hrefRegex = Regex("""href="[^"]*">([a-zA-Z0-9][-a-zA-Z0-9]*(?:\.[a-zA-Z0-9][-a-zA-Z0-9]*)+)</a>""")
            hrefRegex.findAll(body).forEach { match ->
                val domain = match.groupValues[1].lowercase().trim()
                if (domain.contains(".") && !domain.contains("rapiddns")) {
                    domains.add(domain)
                }
            }
        }

        return domains.distinct()
    }

    /**
     * Host.io Reverse IP Lookup
     * URL: https://host.io/api/domains/ip/IP
     * Alternatif: web sayfasını parse et
     */
    private fun reverseIpHostIo(ip: String): List<String> {
        // Host.io web sayfasından domain bilgisi çek
        val url = URL("https://host.io/ip/$ip")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", apiUserAgent)
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            conn.disconnect()
            throw Exception("HTTP $responseCode")
        }

        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val domains = mutableListOf<String>()

        // JSON verisinden domain çıkar
        val jsonDomainRegex = Regex(""""domain"\s*:\s*"([^"]+)"""")
        jsonDomainRegex.findAll(body).forEach { match ->
            val domain = match.groupValues[1].lowercase().trim()
            if (domain.isNotBlank() && domain.contains(".")) {
                domains.add(domain)
            }
        }

        // HTML href link'lerinden domain çıkar
        val hrefRegex = Regex("""href="/[^"]*">([a-zA-Z0-9][-a-zA-Z0-9]*\.[a-zA-Z0-9][-a-zA-Z0-9]*(?:\.[a-zA-Z]{2,}))</a>""")
        hrefRegex.findAll(body).forEach { match ->
            val domain = match.groupValues[1].lowercase().trim()
            if (domain.contains(".") && !domain.contains("host.io")) {
                domains.add(domain)
            }
        }

        // Düz metin domain pattern'leri
        val textDomainRegex = Regex("""(?<![a-zA-Z0-9./-])([a-zA-Z0-9][-a-zA-Z0-9]{1,60}\.(?:xyz|com|net|live|tv|org|info|me|io|pro|online|site|club|fun|top))(?![a-zA-Z0-9./-])""")
        textDomainRegex.findAll(body).forEach { match ->
            val domain = match.groupValues[1].lowercase().trim()
            if (!domain.contains("host.io") && !domain.contains("google") && !domain.contains("cloudflare")) {
                domains.add(domain)
            }
        }

        return domains.distinct()
    }

    /**
     * HackerTarget HostSearch
     * API: https://api.hackertarget.com/hostsearch/?q=DOMAIN
     */
    private fun reverseIpHostSearch(ip: String): List<String> {
        val url = URL("https://api.hackertarget.com/hostsearch/?q=$ip")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", apiUserAgent)

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            conn.disconnect()
            throw Exception("HTTP $responseCode")
        }

        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        if (body.contains("error", ignoreCase = true)) {
            throw Exception("API error: ${body.take(100)}")
        }

        return body.split("\n")
            .mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size >= 2 && parts[1].trim() == ip) parts[0].trim().lowercase() else null
            }
            .filter { it.isNotBlank() && it.contains(".") }
    }

    // ═══════════════════════════════════════════════════════════════
    // DOMAIN VARYASYON ÜRETME
    // ═══════════════════════════════════════════════════════════════

    /**
     * Domain adı varyasyonları üret
     * maxdigitalandroid.xyz → 000android.xyz, 001android.xyz, newandroid.xyz vb.
     */
    private fun generateDomainVariations(host: String): List<String> {
        val variations = mutableSetOf<String>()
        val parts = host.split(".")
        if (parts.size < 2) return emptyList()

        val tld = parts.last()
        val domainName = parts.dropLast(1).joinToString(".")

        val numberPrefixes = (0..20).map { "%03d".format(it) } +
                (0..9).map { it.toString() } +
                listOf("00", "01", "02", "03", "10", "11", "20", "99", "100", "123", "321", "999")

        val wordPrefixes = listOf(
            "max", "new", "old", "pro", "vip", "best", "top", "my", "the", "super",
            "mega", "ultra", "fast", "speed", "hd", "4k", "premium", "gold", "free",
            "digital", "smart", "plus", "net", "web", "cloud", "fire", "hot", "cool",
            "big", "mini", "global", "world", "star", "king", "royal", "elite", "prime"
        )

        val wordBoundaries = findWordBoundaries(domainName)

        if (wordBoundaries.size > 1) {
            val lastWord = wordBoundaries.last()
            val firstPart = domainName.substringBefore(lastWord)

            numberPrefixes.forEach { num -> variations.add("$num$lastWord.$tld") }
            wordPrefixes.forEach { word ->
                if (word != firstPart.lowercase()) variations.add("$word$lastWord.$tld")
            }

            if (wordBoundaries.size > 2) {
                val firstWord = wordBoundaries.first()
                numberPrefixes.take(10).forEach { num -> variations.add("$firstWord$num.$tld") }
            }
        } else {
            numberPrefixes.forEach { num ->
                variations.add("$num$domainName.$tld")
                variations.add("$domainName$num.$tld")
            }
            wordPrefixes.forEach { word ->
                variations.add("$word$domainName.$tld")
                variations.add("$domainName$word.$tld")
            }
        }

        val altTlds = listOf("xyz", "com", "live", "tv", "net", "org", "info", "me", "co", "io", "pro", "online", "site", "club", "fun", "top")
        altTlds.filter { it != tld }.forEach { altTld -> variations.add("$domainName.$altTld") }

        val leetMap = mapOf('o' to '0', 'i' to '1', 'e' to '3', 'a' to '4', 's' to '5', 'l' to '1')
        leetMap.forEach { (original, replacement) ->
            if (domainName.contains(original)) {
                variations.add("${domainName.replaceFirst(original, replacement)}.$tld")
            }
        }

        return variations.filter { it != host }.toList()
    }

    private fun findWordBoundaries(name: String): List<String> {
        val knownWords = listOf(
            "android", "digital", "stream", "iptv", "panel", "server", "cloud",
            "media", "player", "smart", "mega", "ultra", "super", "max", "pro",
            "premium", "gold", "fire", "live", "online", "net", "web", "tv",
            "hd", "4k", "box", "plus", "star", "king", "royal", "elite", "prime",
            "fast", "speed", "vip", "best", "top", "new", "old", "free", "hot",
            "cool", "big", "mini", "global", "world", "tech", "soft", "hub"
        )

        val words = mutableListOf<String>()
        var remaining = name.lowercase()

        while (remaining.isNotEmpty()) {
            val matched = knownWords
                .filter { remaining.startsWith(it) }
                .maxByOrNull { it.length }

            if (matched != null) {
                words.add(matched)
                remaining = remaining.substring(matched.length)
            } else {
                val numMatch = Regex("^\\d+").find(remaining)
                if (numMatch != null) {
                    words.add(numMatch.value)
                    remaining = remaining.substring(numMatch.value.length)
                } else {
                    words.add(remaining)
                    remaining = ""
                }
            }
        }
        return words
    }

    private fun testDomainForIptv(domain: String, originalIp: String, source: String): RelatedPanel? {
        return try {
            val resolvedIp = InetAddress.getByName(domain).hostAddress ?: return null
            val port = tryFindIptvPort(domain)
            RelatedPanel(
                domain = domain, ip = resolvedIp, port = port,
                isOnline = port != null,
                source = if (resolvedIp == originalIp) "$source (Aynı IP)" else "$source ($resolvedIp)"
            )
        } catch (_: Exception) { null }
    }

    private fun tryFindIptvPort(host: String): Int? {
        val quickPorts = listOf(80, 8080, 8880, 8888, 25461, 443, 8000)
        for (port in quickPorts) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 2000)
                socket.close()
                if (testIptvEndpoint(host, port)) return port
            } catch (_: Exception) {}
        }
        return null
    }

    private fun extractBaseDomain(host: String): String {
        val parts = host.split(".")
        return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else host
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

            if (result.allDiscoveredDomains.isNotEmpty()) {
                sb.appendLine("  📋 Bulunan domainler (${result.allDiscoveredDomains.size}):")
                result.allDiscoveredDomains.forEach { domain ->
                    sb.appendLine("    - $domain")
                }
            }

            if (result.relatedDomains.isNotEmpty()) {
                sb.appendLine("  📡 IPTV Paneller:")
                result.relatedDomains.filter { it.isOnline }.forEach { related ->
                    sb.appendLine("    ↳ ${related.domain}:${related.port} (${related.source})")
                }
            }
        }
        return sb.toString()
    }

    fun getScanLogText(): String {
        return _state.value.scanLog.joinToString("\n")
    }

    // ═══════════════════════════════════════════════════════════════
    // IP RANGE TARAMA - /24 Subnet taraması
    // ═══════════════════════════════════════════════════════════════

    /**
     * Aynı /24 subnet'teki tüm IP'leri tara (x.x.x.1-255)
     * Yaygın IPTV portlarında panel ara
     */
    fun startIpRangeScan(result: PanelCheckResult) {
        val ip = result.ipAddress ?: return
        val parts = ip.split(".")
        if (parts.size != 4) return
        val baseIp = "${parts[0]}.${parts[1]}.${parts[2]}"

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isFindingRelated = true,
                    scanLog = emptyList(),
                    discoveredDomainsCount = 0,
                    iptvFoundCount = 0,
                    statusMessage = "🌐 IP Range Tarama: $baseIp.1-255"
                )
            }

            val relatedPanels = mutableListOf<RelatedPanel>()
            val quickPorts = listOf(80, 8080, 8880, 8888, 25461, 25462, 443, 8000)

            withContext(Dispatchers.IO) {
                addLog("━━━ IP RANGE TARAMA ━━━")
                addLog("📍 Hedef: $baseIp.1 - $baseIp.255")
                addLog("🔌 Portlar: ${quickPorts.joinToString(", ")}")
                addLog("⏳ 255 IP × ${quickPorts.size} port = ${255 * quickPorts.size} bağlantı")

                val semaphore = Semaphore(50)
                val scannedCount = AtomicInteger(0)
                val totalIps = 255

                val jobs = (1..255).map { lastOctet ->
                    async {
                        semaphore.withPermit {
                            val targetIp = "$baseIp.$lastOctet"
                            if (targetIp == ip) {
                                scannedCount.incrementAndGet()
                                return@withPermit
                            }

                            var foundPort: Int? = null
                            for (port in quickPorts) {
                                try {
                                    val socket = Socket()
                                    socket.connect(InetSocketAddress(targetIp, port), 1500)
                                    socket.close()
                                    if (testIptvEndpoint(targetIp, port)) {
                                        foundPort = port
                                        break
                                    }
                                } catch (_: Exception) {}
                            }

                            val count = scannedCount.incrementAndGet()
                            if (count % 20 == 0 || foundPort != null) {
                                _state.update { it.copy(
                                    statusMessage = "🌐 IP Tarama: $count/$totalIps | ${relatedPanels.size} IPTV bulundu",
                                    progress = count.toFloat() / totalIps,
                                    discoveredDomainsCount = count,
                                    iptvFoundCount = relatedPanels.size
                                )}
                            }

                            if (foundPort != null) {
                                val panel = RelatedPanel(
                                    domain = targetIp,
                                    ip = targetIp,
                                    port = foundPort,
                                    isOnline = true,
                                    source = "IP Range Tarama"
                                )
                                synchronized(relatedPanels) {
                                    relatedPanels.add(panel)
                                }
                                addLog("  📡 $targetIp:$foundPort → IPTV Panel ✅")
                            }
                        }
                    }
                }

                jobs.forEach { it.await() }

                addLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                addLog("✅ IP Range Tarama Tamamlandı:")
                addLog("  🔍 Taranan: 255 IP")
                addLog("  📡 Bulunan IPTV: ${relatedPanels.size}")
            }

            // Mevcut sonuçlara ekle
            val existingRelated = _state.value.results.find { it.host == result.host }?.relatedDomains ?: emptyList()
            val combined = (existingRelated + relatedPanels).distinctBy { "${it.domain}:${it.port}" }
            val sorted = combined.sortedWith(
                compareByDescending<RelatedPanel> { it.isOnline }
                    .thenByDescending { it.source.contains("Reverse IP") }
            )

            val updatedResults = _state.value.results.map { r ->
                if (r.host == result.host) r.copy(relatedDomains = sorted) else r
            }

            _state.update {
                it.copy(
                    isFindingRelated = false,
                    results = updatedResults,
                    iptvFoundCount = relatedPanels.size,
                    statusMessage = "✅ IP Range: ${relatedPanels.size} IPTV panel bulundu ($baseIp.1-255)"
                )
            }
        }
    }
}
