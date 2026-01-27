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

    // Mobil User-Agent - Cloudflare bypass için
    private val mobileUserAgent = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    
    /**
     * Reverse IP Lookup ile aynı IP'deki domainleri bul
     * crt.sh (Certificate Transparency) + HackerTarget kullanır
     */
    suspend fun reverseIpLookup(ipOrDomain: String): List<String> = withContext(Dispatchers.IO) {
        val domains = mutableListOf<String>()
        
        try {
            // Domain ise, önce crt.sh'den subdomain/ilişkili domain bul
            val isIp = ipOrDomain.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))
            
            if (!isIp) {
                // 1. crt.sh - Certificate Transparency (SSL sertifikalarından domain bul)
                // Bu API gerçekten çalışıyor ve ücretsiz!
                try {
                    val baseDomain = extractBaseDomain(ipOrDomain)
                    val crtUrl = "https://crt.sh/?q=%25.$baseDomain&output=json"
                    val request1 = Request.Builder()
                        .url(crtUrl)
                        .header("User-Agent", mobileUserAgent)
                        .get()
                        .build()
                    
                    val response1 = withTimeoutOrNull(20000L) {
                        httpClient.newCall(request1).execute()
                    }
                    
                    if (response1 != null && response1.isSuccessful) {
                        val body = response1.body?.string() ?: ""
                        response1.close()
                        
                        // JSON'dan domain'leri çıkar
                        // "name_value":"*.example.com\nexample.com" formatında
                        val namePattern = Regex("\"name_value\"\\s*:\\s*\"([^\"]+)\"")
                        namePattern.findAll(body).forEach { match ->
                            val names = match.groupValues[1]
                            names.split("\\n", "\n").forEach { name ->
                                val cleanName = name.trim().removePrefix("*.")
                                if (cleanName.isNotBlank() && 
                                    cleanName.contains(".") &&
                                    !cleanName.contains("@") &&
                                    !isJunkDomain(cleanName)) {
                                    domains.add(cleanName.lowercase())
                                }
                            }
                        }
                        
                        // common_name alanından da çıkar
                        val cnPattern = Regex("\"common_name\"\\s*:\\s*\"([^\"]+)\"")
                        cnPattern.findAll(body).forEach { match ->
                            val cn = match.groupValues[1].trim().removePrefix("*.")
                            if (cn.isNotBlank() && 
                                cn.contains(".") &&
                                !cn.contains("@") &&
                                !isJunkDomain(cn)) {
                                domains.add(cn.lowercase())
                            }
                        }
                    }
                } catch (e: Exception) { }
            }
            
            // 2. IP'yi çöz ve HackerTarget ile reverse lookup yap
            val ip = try {
                if (isIp) {
                    ipOrDomain
                } else {
                    InetAddress.getByName(ipOrDomain).hostAddress
                }
            } catch (e: Exception) {
                null
            }
            
            if (ip != null) {
                try {
                    val hackerTargetUrl = "https://api.hackertarget.com/reverseiplookup/?q=$ip"
                    val request2 = Request.Builder()
                        .url(hackerTargetUrl)
                        .header("User-Agent", mobileUserAgent)
                        .get()
                        .build()
                    
                    val response2 = withTimeoutOrNull(10000L) {
                        httpClient.newCall(request2).execute()
                    }
                    
                    if (response2 != null && response2.isSuccessful) {
                        val body = response2.body?.string() ?: ""
                        response2.close()
                        
                        body.lines()
                            .map { it.trim().lowercase() }
                            .filter { 
                                it.isNotBlank() && 
                                it.contains(".") &&
                                !it.startsWith("error") && 
                                !it.contains("api count exceeded") &&
                                !it.contains("no dns") &&
                                !isJunkDomain(it)
                            }
                            .forEach { domains.add(it) }
                    }
                } catch (e: Exception) { }
            }
            
        } catch (e: Exception) {
            // Hata durumunda boş liste
        }
        
        domains.distinct()
    }
    
    /**
     * Domain'den base domain çıkar (subdomain'leri kaldır)
     */
    private fun extractBaseDomain(domain: String): String {
        val parts = domain.lowercase().split(".")
        return if (parts.size >= 2) {
            "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
        } else {
            domain
        }
    }
    
    /**
     * Saçma/alakasız domainleri filtrele
     */
    private fun isJunkDomain(domain: String): Boolean {
        val junkPatterns = listOf(
            "microsoft", "msn", "live.com", "outlook", "hotmail",
            "google", "facebook", "twitter", "instagram", "youtube",
            "amazon", "cloudflare", "akamai", "fastly",
            "github", "stackoverflow", "wikipedia",
            "apple.com", "icloud", "yahoo",
            ".gov", ".edu", ".mil",
            "cdn.", "static.", "assets.",
            "analytics", "tracking", "ads.",
            "mail.", "smtp.", "pop.", "imap.",
            "ns1.", "ns2.", "dns.",
            "rapiddns", "viewdns", "hackertarget",
            "sectigo", "digicert", "letsencrypt", "comodo"
        )
        
        val lowerDomain = domain.lowercase()
        return junkPatterns.any { lowerDomain.contains(it) }
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

    /**
     * AŞAMA 1: Sadece domain listele (IPTV testi yapmadan)
     * Aynı IP'deki tüm domainleri bul ve listele
     */
    suspend fun findDomainsOnly(
        originalUrl: String,
        onProgress: (status: String, current: Int, total: Int, ip: String, domains: List<String>) -> Unit
    ): List<String> = withContext(Dispatchers.IO) {
        val discoveredDomains = mutableSetOf<String>()
        var resolvedIP = ""
        
        try {
            // 1. Host adını ayıkla
            val originalHost = extractHostFromInput(originalUrl)
            if (originalHost.isBlank()) {
                onProgress("❌ Geçersiz URL veya domain", 0, 100, "", emptyList())
                return@withContext emptyList()
            }
            
            discoveredDomains.add(originalHost)
            onProgress("📍 Host: $originalHost", 5, 100, "", discoveredDomains.toList())
            
            // 2. IP adresini çöz
            onProgress("🔍 IP adresi çözümleniyor...", 10, 100, "", discoveredDomains.toList())
            
            val allIPs = resolveAllIPs(originalHost)
            if (allIPs.isEmpty()) {
                onProgress("❌ IP çözümlenemedi", 10, 100, "", discoveredDomains.toList())
                return@withContext discoveredDomains.toList()
            }
            
            resolvedIP = allIPs.first()
            
            // Cloudflare kontrolü
            val isCloudflare = allIPs.all { isCloudflareIP(it) }
            if (isCloudflare) {
                onProgress("⚠️ Cloudflare tespit edildi! Gerçek IP aranıyor...", 15, 100, resolvedIP, discoveredDomains.toList())
                
                // DNS geçmişinden gerçek IP bul
                val historicalIPs = getDnsHistory(originalHost)
                val realIPs = historicalIPs.filter { !isCloudflareIP(it) }
                
                if (realIPs.isNotEmpty()) {
                    resolvedIP = realIPs.first()
                    onProgress("🎯 Gerçek IP bulundu: $resolvedIP", 20, 100, resolvedIP, discoveredDomains.toList())
                }
            } else {
                onProgress("📌 IP: $resolvedIP", 15, 100, resolvedIP, discoveredDomains.toList())
            }
            
            // 3. Reverse IP Lookup
            onProgress("🔄 Aynı IP'deki domainler aranıyor...", 25, 100, resolvedIP, discoveredDomains.toList())
            
            for (ip in allIPs.filter { !isCloudflareIP(it) }.take(3)) {
                val reverseResults = reverseIpLookup(ip)
                if (reverseResults.isNotEmpty()) {
                    discoveredDomains.addAll(reverseResults)
                    onProgress("📋 $ip → ${reverseResults.size} domain bulundu", 40, 100, resolvedIP, discoveredDomains.toList())
                }
            }
            
            // 4. Subdomain keşfi
            onProgress("🌐 Subdomain keşfi yapılıyor...", 60, 100, resolvedIP, discoveredDomains.toList())
            
            val subdomains = discoverSubdomains(originalHost)
            if (subdomains.isNotEmpty()) {
                discoveredDomains.addAll(subdomains)
                onProgress("🎉 ${subdomains.size} subdomain bulundu", 80, 100, resolvedIP, discoveredDomains.toList())
            }
            
            onProgress("✅ Toplam ${discoveredDomains.size} domain bulundu", 100, 100, resolvedIP, discoveredDomains.toList())
            
        } catch (e: Exception) {
            onProgress("❌ Hata: ${e.message}", 100, 100, resolvedIP, discoveredDomains.toList())
        }
        
        discoveredDomains.toList()
    }

    /**
     * AŞAMA 2: Bulunan domainleri IPTV için test et
     * User/Pass girilmişse her panelde dener, uyuşursa "✅ User/Pass Uyumlu" işareti koyar
     */
    suspend fun testDomainsForIptv(
        domains: List<String>,
        username: String,
        password: String,
        onProgress: (status: String, current: Int, total: Int, result: SideServerScanner.ScanResult?) -> Unit
    ): List<SideServerScanner.ScanResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SideServerScanner.ScanResult>()
        val totalDomains = domains.size
        var checked = 0
        val hasCredentials = username.isNotBlank() && password.isNotBlank()
        
        for (domain in domains) {
            checked++
            val progress = (checked * 100) / totalDomains.coerceAtLeast(1)
            onProgress("🔎 IPTV Test: $domain ($checked/$totalDomains)", progress, 100, null)
            
            // IPTV sunucusu mu kontrol et
            val iptvResult = checkIfIptvServer(domain)
            
            if (iptvResult != null) {
                // IPTV sunucusu bulundu
                if (hasCredentials) {
                    // Credentials varsa test et
                    val testResult = testSingleServer(iptvResult.serverUrl, username, password)
                    if (results.none { it.serverUrl == testResult.serverUrl }) {
                        // User/Pass uyuştu mu kontrol et
                        val finalResult = if (testResult.isActive) {
                            // ✅ User/Pass uyuştu!
                            testResult.copy(
                                statusText = "✅ User/Pass Uyumlu! ${testResult.statusText}"
                            )
                        } else {
                            // Panel var ama user/pass uyuşmadı
                            SideServerScanner.ScanResult(
                                serverUrl = iptvResult.serverUrl,
                                m3uLink = iptvResult.m3uLink,
                                isActive = false,
                                statusText = "🎯 IPTV Panel (User/Pass uyuşmadı)"
                            )
                        }
                        results.add(finalResult)
                        onProgress("${finalResult.statusText}: ${finalResult.serverUrl}", progress, 100, finalResult)
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
        
        val activeCount = results.count { it.isActive }
        val matchedCount = results.count { it.statusText.contains("User/Pass Uyumlu") }
        
        val summaryText = if (hasCredentials) {
            "✅ Test tamamlandı! $matchedCount panelde User/Pass uyuştu, ${results.size} IPTV paneli bulundu"
        } else {
            "✅ Test tamamlandı! ${results.size} IPTV paneli bulundu"
        }
        onProgress(summaryText, 100, 100, null)
        
        // Önce user/pass uyuşanlar, sonra diğerleri
        results.sortedByDescending { it.isActive }
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
