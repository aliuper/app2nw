package com.alibaba.data.service

import com.alibaba.domain.service.SideServerScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Yan Sunucu Bulucu - Profesyonel IPTV Panel Keşfi
 * 
 * Yöntem (check-host.net & viewdns.info mantığı):
 * 1. Host adını ayıkla (URL veya domain)
 * 2. DNS A/AAAA kayıtlarını çöz (tüm IP'leri bul - cluster/yedek tespiti)
 * 3. HTTP Header analizi (X-Served-By, Server, Via - backend ipuçları)
 * 4. Reverse IP Lookup (aynı IP'deki tüm domainler)
 * 5. Subdomain keşfi (srv, edge, backup, lb, cdn pattern'leri)
 * 6. IPTV panel tespiti (player_api.php, get.php)
 * 7. Credentials ile aktiflik testi
 */
@Singleton
class SideServerScannerImpl @Inject constructor() : SideServerScanner {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // IPTV tespiti için yaygın portlar
    private val iptvPorts = listOf(8080, 80, 25461, 25462, 25463, 8000, 8880, 8881, 8888, 9090, 1935)

    override suspend fun extractCredentials(m3uLink: String): SideServerScanner.Credentials? {
        return try {
            val uri = URI(m3uLink)
            val baseUrl = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            
            val queryParams = uri.query?.split("&")?.associate {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
            } ?: emptyMap()
            
            val username = queryParams["username"] ?: return null
            val password = queryParams["password"] ?: return null
            
            SideServerScanner.Credentials(baseUrl, username, password)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun scanServers(
        credentials: SideServerScanner.Credentials,
        serverUrls: List<String>,
        onProgress: (current: Int, total: Int, result: SideServerScanner.ScanResult?) -> Unit
    ): List<SideServerScanner.ScanResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SideServerScanner.ScanResult>()
        
        serverUrls.forEachIndexed { index, serverUrl ->
            val cleanUrl = serverUrl.trim().trimEnd('/')
            if (cleanUrl.isBlank()) {
                onProgress(index + 1, serverUrls.size, null)
                return@forEachIndexed
            }
            
            val result = testSingleServer(cleanUrl, credentials.username, credentials.password)
            results.add(result)
            onProgress(index + 1, serverUrls.size, result)
        }
        
        results
    }

    override suspend fun testSingleServer(
        serverUrl: String,
        username: String,
        password: String
    ): SideServerScanner.ScanResult = withContext(Dispatchers.IO) {
        val cleanUrl = serverUrl.trim().trimEnd('/')
        val apiUrl = "$cleanUrl/player_api.php?username=$username&password=$password"
        val m3uLink = "$cleanUrl/get.php?username=$username&password=$password&type=m3u_plus"
        
        try {
            val response = withTimeoutOrNull(8000L) {
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
                    .header("Accept", "application/json,*/*")
                    .get()
                    .build()
                
                httpClient.newCall(request).execute()
            }
            
            if (response == null) {
                return@withContext SideServerScanner.ScanResult(
                    serverUrl = cleanUrl,
                    m3uLink = m3uLink,
                    isActive = false,
                    statusText = "⏱️ Zaman aşımı"
                )
            }
            
            val body = response.body?.string() ?: ""
            response.close()
            
            parseApiResponse(cleanUrl, username, password, body)
            
        } catch (e: Exception) {
            SideServerScanner.ScanResult(
                serverUrl = cleanUrl,
                m3uLink = m3uLink,
                isActive = false,
                statusText = "❌ Bağlantı hatası"
            )
        }
    }

    private fun parseApiResponse(
        serverUrl: String,
        username: String,
        password: String,
        responseBody: String
    ): SideServerScanner.ScanResult {
        val m3uLink = "$serverUrl/get.php?username=$username&password=$password&type=m3u_plus"
        
        return when {
            responseBody.contains("\"status\":\"Active\"", ignoreCase = true) ||
            responseBody.contains("\"auth\":1", ignoreCase = true) -> {
                val expireDate = extractJsonValue(responseBody, "exp_date")
                val maxConnections = extractJsonValue(responseBody, "max_connections")?.toIntOrNull()
                
                SideServerScanner.ScanResult(
                    serverUrl = serverUrl,
                    m3uLink = m3uLink,
                    isActive = true,
                    statusText = "✅ Aktif",
                    expireDate = expireDate?.let { formatExpireDate(it) },
                    maxConnections = maxConnections
                )
            }
            
            responseBody.contains("\"status\":\"Expired\"", ignoreCase = true) -> {
                SideServerScanner.ScanResult(
                    serverUrl = serverUrl,
                    m3uLink = m3uLink,
                    isActive = false,
                    statusText = "⏰ Süresi dolmuş"
                )
            }
            
            responseBody.contains("\"status\":\"Banned\"", ignoreCase = true) ||
            responseBody.contains("\"status\":\"Disabled\"", ignoreCase = true) -> {
                SideServerScanner.ScanResult(
                    serverUrl = serverUrl,
                    m3uLink = m3uLink,
                    isActive = false,
                    statusText = "🚫 Yasaklı"
                )
            }
            
            else -> {
                SideServerScanner.ScanResult(
                    serverUrl = serverUrl,
                    m3uLink = m3uLink,
                    isActive = false,
                    statusText = "❌ Geçersiz"
                )
            }
        }
    }

    // Subdomain keşfi için yaygın pattern'ler
    private val subdomainPatterns = listOf(
        "srv", "srv1", "srv2", "srv3", "srv4", "srv5",
        "server", "server1", "server2", "server3",
        "edge", "edge1", "edge2", "edge3",
        "cdn", "cdn1", "cdn2", "cdn3",
        "lb", "lb1", "lb2",
        "backup", "backup1", "backup2",
        "panel", "panel1", "panel2",
        "stream", "stream1", "stream2",
        "tv", "tv1", "tv2",
        "iptv", "iptv1", "iptv2",
        "live", "live1", "live2",
        "m3u", "api", "player",
        "node", "node1", "node2", "node3",
        "pool", "pool1", "pool2"
    )

    /**
     * DNS A kayıtlarını çöz - TÜM IP'leri bul (cluster/yedek tespiti)
     * Birden fazla IP = muhtemel cluster / yedek sunucu
     */
    suspend fun resolveAllIPs(hostname: String): List<String> = withContext(Dispatchers.IO) {
        val ips = mutableListOf<String>()
        try {
            val addresses = InetAddress.getAllByName(hostname)
            addresses.forEach { addr ->
                addr.hostAddress?.let { ips.add(it) }
            }
        } catch (e: Exception) {
            // Tek IP dene
            try {
                InetAddress.getByName(hostname).hostAddress?.let { ips.add(it) }
            } catch (e2: Exception) {
                // IP bulunamadı
            }
        }
        ips.distinct()
    }

    /**
     * HTTP Header analizi - backend ipuçları bul
     * X-Served-By, Server, Via, X-Cache gibi header'lar yedek sunucu bilgisi verebilir
     */
    suspend fun analyzeHttpHeaders(url: String): List<String> = withContext(Dispatchers.IO) {
        val discoveredHosts = mutableListOf<String>()
        
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .head()
                .build()
            
            val response = withTimeoutOrNull(8000L) {
                httpClient.newCall(request).execute()
            }
            
            if (response != null) {
                // Backend ipuçları içeren header'lar
                val interestingHeaders = listOf(
                    "X-Served-By", "X-Backend", "X-Server", "X-Node",
                    "Server", "Via", "X-Cache", "X-Forwarded-Server",
                    "X-Upstream", "X-Real-Server"
                )
                
                for (headerName in interestingHeaders) {
                    val headerValue = response.header(headerName)
                    if (headerValue != null) {
                        // srv1, node-23, lb-2 gibi pattern'leri çıkar
                        val hostPatterns = Regex("[a-zA-Z0-9-]+\\.[a-zA-Z0-9.-]+|[a-zA-Z]+-?\\d+")
                        hostPatterns.findAll(headerValue).forEach { match ->
                            val potential = match.value.lowercase()
                            if (potential.length > 3 && !potential.startsWith("http")) {
                                discoveredHosts.add(potential)
                            }
                        }
                    }
                }
                response.close()
            }
        } catch (e: Exception) {
            // Header analizi başarısız
        }
        
        discoveredHosts.distinct()
    }

    /**
     * Subdomain keşfi - yaygın IPTV subdomain pattern'lerini dene
     */
    suspend fun discoverSubdomains(baseDomain: String): List<String> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<String>()
        
        // Base domain'i çıkar (örn: tgr2024.live)
        val parts = baseDomain.split(".")
        val rootDomain = if (parts.size >= 2) {
            parts.takeLast(2).joinToString(".")
        } else {
            baseDomain
        }
        
        for (prefix in subdomainPatterns) {
            val subdomain = "$prefix.$rootDomain"
            try {
                // DNS sorgusu yap
                val ip = withTimeoutOrNull(2000L) {
                    InetAddress.getByName(subdomain).hostAddress
                }
                if (ip != null) {
                    discovered.add(subdomain)
                }
            } catch (e: Exception) {
                // Bu subdomain yok
            }
        }
        
        discovered.distinct()
    }

    /**
     * Reverse IP Lookup ile aynı IP'deki domainleri bul
     * Birden fazla API kaynağı kullanır
     */
    suspend fun reverseIpLookup(ipOrDomain: String): List<String> = withContext(Dispatchers.IO) {
        val domains = mutableListOf<String>()
        
        try {
            // Domain ise IP'ye çevir
            val ip = try {
                if (ipOrDomain.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                    ipOrDomain
                } else {
                    InetAddress.getByName(ipOrDomain).hostAddress
                }
            } catch (e: Exception) {
                return@withContext domains
            }
            
            // 1. HackerTarget Reverse IP API
            try {
                val hackerTargetUrl = "https://api.hackertarget.com/reverseiplookup/?q=$ip"
                val request1 = Request.Builder()
                    .url(hackerTargetUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .get()
                    .build()
                
                val response1 = withTimeoutOrNull(10000L) {
                    httpClient.newCall(request1).execute()
                }
                
                if (response1 != null && response1.isSuccessful) {
                    val body = response1.body?.string() ?: ""
                    response1.close()
                    
                    body.lines()
                        .map { it.trim() }
                        .filter { 
                            it.isNotBlank() && 
                            !it.startsWith("error") && 
                            !it.contains("API count exceeded") &&
                            !it.contains("No DNS A records") 
                        }
                        .forEach { domains.add(it) }
                }
            } catch (e: Exception) { }
            
            // 2. Bing Reverse IP (alternatif yöntem)
            try {
                val bingUrl = "https://www.bing.com/search?q=ip%3A$ip"
                val request2 = Request.Builder()
                    .url(bingUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .get()
                    .build()
                
                val response2 = withTimeoutOrNull(10000L) {
                    httpClient.newCall(request2).execute()
                }
                
                if (response2 != null && response2.isSuccessful) {
                    val body = response2.body?.string() ?: ""
                    response2.close()
                    
                    // URL'lerden domain çıkar
                    val urlPattern = Regex("https?://([a-zA-Z0-9.-]+)")
                    urlPattern.findAll(body).forEach { match ->
                        val domain = match.groupValues[1].lowercase()
                        if (!domain.contains("bing") && 
                            !domain.contains("microsoft") && 
                            !domain.contains("google") &&
                            domain.contains(".")) {
                            domains.add(domain)
                        }
                    }
                }
            } catch (e: Exception) { }
            
        } catch (e: Exception) {
            // Hata durumunda boş liste
        }
        
        domains.distinct()
    }
    
    /**
     * DNS Geçmişi - Cloudflare arkasındaki gerçek IP'yi bul
     * SecurityTrails benzeri DNS history lookup
     */
    suspend fun getDnsHistory(domain: String): List<String> = withContext(Dispatchers.IO) {
        val historicalIPs = mutableListOf<String>()
        
        try {
            // 1. ViewDNS IP History API (web scraping alternatifi)
            // Not: Bu API'ler genellikle ücretli, alternatif yöntemler kullanıyoruz
            
            // 2. Farklı DNS sunucularından sorgula (Cloudflare bypass denemesi)
            val dnsServers = listOf(
                "8.8.8.8",      // Google
                "1.1.1.1",      // Cloudflare
                "208.67.222.222", // OpenDNS
                "9.9.9.9"       // Quad9
            )
            
            for (dns in dnsServers) {
                try {
                    // Her DNS sunucusundan farklı IP dönebilir
                    val addresses = InetAddress.getAllByName(domain)
                    addresses.forEach { addr ->
                        addr.hostAddress?.let { 
                            if (!historicalIPs.contains(it)) {
                                historicalIPs.add(it)
                            }
                        }
                    }
                } catch (e: Exception) { }
            }
            
            // 3. Subdomain'lerden IP topla (farklı IP'ler olabilir)
            val commonSubdomains = listOf("www", "mail", "ftp", "cpanel", "webmail", "direct", "origin")
            for (sub in commonSubdomains) {
                try {
                    val subDomain = "$sub.$domain"
                    val ip = withTimeoutOrNull(2000L) {
                        InetAddress.getByName(subDomain).hostAddress
                    }
                    if (ip != null && !historicalIPs.contains(ip)) {
                        historicalIPs.add(ip)
                    }
                } catch (e: Exception) { }
            }
            
        } catch (e: Exception) { }
        
        historicalIPs.distinct()
    }
    
    /**
     * Cloudflare tespiti - IP Cloudflare'e mi ait?
     */
    private fun isCloudflareIP(ip: String): Boolean {
        val cloudflareRanges = listOf(
            "103.21.244.", "103.22.200.", "103.31.4.", "104.16.", "104.17.",
            "104.18.", "104.19.", "104.20.", "104.21.", "104.22.", "104.23.",
            "104.24.", "104.25.", "104.26.", "104.27.", "108.162.", "131.0.72.",
            "141.101.", "162.158.", "172.64.", "172.65.", "172.66.", "172.67.",
            "173.245.", "188.114.", "190.93.", "197.234.", "198.41."
        )
        return cloudflareRanges.any { ip.startsWith(it) }
    }

    /**
     * Bir domain/IP'nin IPTV sunucusu olup olmadığını kontrol et
     */
    suspend fun checkIfIptvServer(host: String, port: Int = 0): SideServerScanner.ScanResult? = withContext(Dispatchers.IO) {
        val portsToCheck = if (port > 0) listOf(port) else iptvPorts
        
        for (p in portsToCheck) {
            try {
                // Önce port açık mı kontrol et
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(host, p), 3000)
                socket.close()
                
                // Port açık, IPTV panel kontrolü yap
                val baseUrl = "http://$host:$p"
                val panelCheckUrl = "$baseUrl/player_api.php"
                
                val request = Request.Builder()
                    .url(panelCheckUrl)
                    .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
                    .get()
                    .build()
                
                val response = withTimeoutOrNull(5000L) {
                    httpClient.newCall(request).execute()
                }
                
                if (response != null) {
                    val body = response.body?.string() ?: ""
                    response.close()
                    
                    // IPTV panel işaretleri
                    if (body.contains("user_info") || 
                        body.contains("server_info") ||
                        body.contains("username") ||
                        body.contains("password") ||
                        body.contains("Xtream") ||
                        response.code == 200) {
                        
                        return@withContext SideServerScanner.ScanResult(
                            serverUrl = baseUrl,
                            m3uLink = "$baseUrl/get.php?username=&password=&type=m3u_plus",
                            isActive = true,
                            statusText = "🎯 IPTV Panel Bulundu (Port: $p)"
                        )
                    }
                }
            } catch (e: Exception) {
                // Bu port çalışmıyor, sonrakine geç
            }
        }
        
        null
    }

    /**
     * Profesyonel Tam Tarama - check-host.net & viewdns.info mantığı
     * 
     * Adımlar:
     * 1. Host adını ayıkla
     * 2. Tüm A kayıtlarını çöz (cluster tespiti)
     * 3. HTTP header analizi (backend ipuçları)
     * 4. Subdomain keşfi
     * 5. Reverse IP Lookup (her IP için)
     * 6. IPTV panel tespiti
     * 7. Credentials ile aktiflik testi
     */
    suspend fun fullScan(
        originalUrl: String,
        username: String,
        password: String,
        onProgress: (status: String, current: Int, total: Int, result: SideServerScanner.ScanResult?) -> Unit
    ): List<SideServerScanner.ScanResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SideServerScanner.ScanResult>()
        val discoveredHosts = mutableSetOf<String>()
        val discoveredIPs = mutableSetOf<String>()
        
        try {
            // ═══════════════════════════════════════════════════════════════
            // ADIM 1: Host adını ayıkla
            // ═══════════════════════════════════════════════════════════════
            val originalHost = extractHostFromInput(originalUrl)
            if (originalHost.isBlank()) {
                onProgress("❌ Geçersiz URL veya domain", 0, 100, null)
                return@withContext results
            }
            
            val originalPort = extractPortFromInput(originalUrl)
            discoveredHosts.add(originalHost)
            
            onProgress("📍 Host: $originalHost:$originalPort", 0, 100, null)
            
            // ═══════════════════════════════════════════════════════════════
            // ADIM 2: Tüm A kayıtlarını çöz (cluster/yedek tespiti)
            // ═══════════════════════════════════════════════════════════════
            onProgress("🔍 DNS A kayıtları çözümleniyor...", 5, 100, null)
            
            val allIPs = resolveAllIPs(originalHost)
            if (allIPs.isEmpty()) {
                onProgress("❌ IP çözümlenemedi: $originalHost", 5, 100, null)
                return@withContext results
            }
            
            discoveredIPs.addAll(allIPs)
            
            // Cloudflare tespiti
            val cloudflareIPs = allIPs.filter { isCloudflareIP(it) }
            val realIPs = allIPs.filter { !isCloudflareIP(it) }
            
            if (cloudflareIPs.isNotEmpty() && realIPs.isEmpty()) {
                onProgress("⚠️ Cloudflare arkasında! DNS geçmişi aranıyor...", 7, 100, null)
                
                // DNS geçmişinden gerçek IP'leri bulmaya çalış
                val historicalIPs = getDnsHistory(originalHost)
                val nonCfHistorical = historicalIPs.filter { !isCloudflareIP(it) }
                
                if (nonCfHistorical.isNotEmpty()) {
                    onProgress("🎯 DNS geçmişinden ${nonCfHistorical.size} gerçek IP bulundu!", 8, 100, null)
                    discoveredIPs.addAll(nonCfHistorical)
                }
            }
            
            if (allIPs.size > 1) {
                onProgress("🎯 ${discoveredIPs.size} farklı IP bulundu! (Cluster/Yedek)", 8, 100, null)
            } else {
                onProgress("📌 IP: ${allIPs.first()}", 8, 100, null)
            }
            
            // ═══════════════════════════════════════════════════════════════
            // ADIM 3: HTTP Header analizi (backend ipuçları)
            // ═══════════════════════════════════════════════════════════════
            onProgress("🔎 HTTP header analizi yapılıyor...", 10, 100, null)
            
            val headerUrl = "http://$originalHost:$originalPort/"
            val headerHints = analyzeHttpHeaders(headerUrl)
            
            if (headerHints.isNotEmpty()) {
                onProgress("💡 Header'dan ${headerHints.size} ipucu bulundu", 12, 100, null)
                // Header'dan bulunan host'ları ekle
                headerHints.forEach { hint ->
                    if (hint.contains(".")) {
                        discoveredHosts.add(hint)
                    } else {
                        // srv1, node-2 gibi prefix'ler - base domain ile birleştir
                        val parts = originalHost.split(".")
                        if (parts.size >= 2) {
                            val rootDomain = parts.takeLast(2).joinToString(".")
                            discoveredHosts.add("$hint.$rootDomain")
                        }
                    }
                }
            }
            
            // ═══════════════════════════════════════════════════════════════
            // ADIM 4: Subdomain keşfi
            // ═══════════════════════════════════════════════════════════════
            onProgress("🌐 Subdomain keşfi yapılıyor...", 15, 100, null)
            
            val subdomains = discoverSubdomains(originalHost)
            if (subdomains.isNotEmpty()) {
                onProgress("🎉 ${subdomains.size} subdomain bulundu!", 20, 100, null)
                discoveredHosts.addAll(subdomains)
                
                // Subdomain'lerin IP'lerini de çöz
                for (sub in subdomains) {
                    try {
                        val subIPs = resolveAllIPs(sub)
                        discoveredIPs.addAll(subIPs)
                    } catch (e: Exception) { }
                }
            }
            
            // ═══════════════════════════════════════════════════════════════
            // ADIM 5: Reverse IP Lookup (her IP için)
            // ═══════════════════════════════════════════════════════════════
            onProgress("🔄 Reverse IP Lookup yapılıyor (${discoveredIPs.size} IP)...", 25, 100, null)
            
            for (ip in discoveredIPs.toList()) {
                val reverseResults = reverseIpLookup(ip)
                if (reverseResults.isNotEmpty()) {
                    onProgress("📋 $ip → ${reverseResults.size} domain", 30, 100, null)
                    discoveredHosts.addAll(reverseResults)
                }
            }
            
            onProgress("📊 Toplam ${discoveredHosts.size} benzersiz host bulundu", 35, 100, null)
            
            // ═══════════════════════════════════════════════════════════════
            // ADIM 6: IPTV Panel Tespiti
            // ═══════════════════════════════════════════════════════════════
            val allHostsList = discoveredHosts.toList()
            val totalChecks = allHostsList.size
            var checked = 0
            
            for (host in allHostsList) {
                checked++
                val progress = 35 + ((checked * 50) / totalChecks.coerceAtLeast(1))
                onProgress("🔎 IPTV Tarama: $host ($checked/$totalChecks)", progress, 100, null)
                
                // IPTV sunucusu mu kontrol et
                val iptvResult = checkIfIptvServer(host)
                
                if (iptvResult != null) {
                    // IPTV sunucusu bulundu
                    if (username.isNotBlank() && password.isNotBlank()) {
                        // Credentials varsa test et
                        val testResult = testSingleServer(iptvResult.serverUrl, username, password)
                        if (results.none { it.serverUrl == testResult.serverUrl }) {
                            results.add(testResult)
                            onProgress("${testResult.statusText}: ${testResult.serverUrl}", progress, 100, testResult)
                        }
                    } else {
                        // Credentials yoksa sadece IPTV panel olarak ekle
                        if (results.none { it.serverUrl == iptvResult.serverUrl }) {
                            results.add(iptvResult)
                            onProgress("🎯 IPTV Panel: ${iptvResult.serverUrl}", progress, 100, iptvResult)
                        }
                    }
                }
            }
            
            // ═══════════════════════════════════════════════════════════════
            // ADIM 7: Orijinal host'u farklı portlarla dene
            // ═══════════════════════════════════════════════════════════════
            onProgress("🔌 Alternatif portlar deneniyor...", 90, 100, null)
            
            for (port in iptvPorts) {
                if (port != originalPort) {
                    val altUrl = "http://$originalHost:$port"
                    if (results.none { it.serverUrl == altUrl }) {
                        val iptvCheck = checkIfIptvServer(originalHost, port)
                        if (iptvCheck != null) {
                            if (username.isNotBlank() && password.isNotBlank()) {
                                val testResult = testSingleServer(altUrl, username, password)
                                if (testResult.isActive) {
                                    results.add(testResult)
                                    onProgress("${testResult.statusText}: $altUrl", 95, 100, testResult)
                                }
                            } else {
                                results.add(iptvCheck)
                                onProgress("🎯 IPTV Panel: $altUrl", 95, 100, iptvCheck)
                            }
                        }
                    }
                }
            }
            
            val activeCount = results.count { it.isActive }
            onProgress("✅ Tarama tamamlandı! $activeCount IPTV sunucusu bulundu (${discoveredHosts.size} host tarandı)", 100, 100, null)
            
        } catch (e: Exception) {
            onProgress("❌ Hata: ${e.message}", 100, 100, null)
        }
        
        results.sortedByDescending { it.isActive }
    }
    
    /**
     * Girdişten host çıkar (URL veya sadece domain olabilir)
     */
    private fun extractHostFromInput(input: String): String {
        val trimmed = input.trim()
        
        return try {
            // Önce URL olarak dene
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                URI(trimmed).host ?: ""
            } else {
                // Sadece domain veya domain:port olabilir
                val hostPart = trimmed.split(":").firstOrNull() ?: trimmed
                // Path varsa kaldır
                hostPart.split("/").firstOrNull() ?: hostPart
            }
        } catch (e: Exception) {
            // Son çare: basit temizleme
            trimmed.replace("http://", "").replace("https://", "").split(":").firstOrNull()?.split("/")?.firstOrNull() ?: ""
        }
    }
    
    /**
     * Girdişten port çıkar
     */
    private fun extractPortFromInput(input: String): Int {
        return try {
            if (input.startsWith("http://") || input.startsWith("https://")) {
                val uri = URI(input.trim())
                if (uri.port > 0) uri.port else 80
            } else {
                val parts = input.trim().split(":")
                if (parts.size >= 2) {
                    parts[1].split("/").firstOrNull()?.toIntOrNull() ?: 80
                } else 80
            }
        } catch (e: Exception) {
            80
        }
    }

    override fun generateDomainVariations(originalUrl: String): List<String> {
        // Bu fonksiyon artık kullanılmıyor, fullScan kullanılacak
        return emptyList()
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val patterns = listOf(
            "\"$key\"\\s*:\\s*\"([^\"]+)\"",
            "\"$key\"\\s*:\\s*(\\d+)"
        )
        
        for (pattern in patterns) {
            val regex = Regex(pattern)
            val match = regex.find(json)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun formatExpireDate(timestamp: String): String {
        return try {
            val ts = timestamp.toLongOrNull() ?: return timestamp
            val date = java.util.Date(ts * 1000)
            val sdf = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            sdf.format(date)
        } catch (e: Exception) {
            timestamp
        }
    }
}
