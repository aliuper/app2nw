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
 * Yan Sunucu Bulucu - Reverse IP Lookup + IPTV Tespiti
 * 
 * Yöntem:
 * 1. Domain'in IP adresini çöz
 * 2. HackerTarget API ile aynı IP'deki tüm domainleri bul
 * 3. Bulunan domainleri IPTV portları için tara
 * 4. IPTV panel tespiti yap (player_api.php, get.php varlığı)
 * 5. Credentials ile test et
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

    /**
     * Reverse IP Lookup ile aynı IP'deki domainleri bul
     * HackerTarget API kullanır (ücretsiz, günlük 50 sorgu)
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
            
            // HackerTarget Reverse IP API
            val apiUrl = "https://api.hackertarget.com/reverseiplookup/?q=$ip"
            
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()
            
            val response = withTimeoutOrNull(15000L) {
                httpClient.newCall(request).execute()
            }
            
            if (response != null && response.isSuccessful) {
                val body = response.body?.string() ?: ""
                response.close()
                
                // Her satır bir domain
                body.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("error") && !it.contains("API count exceeded") }
                    .forEach { domains.add(it) }
            }
            
        } catch (e: Exception) {
            // Hata durumunda boş liste
        }
        
        domains.distinct()
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
     * Tam tarama: Reverse IP + IPTV Tespiti
     * Kullanıcı adı/şifre opsiyonel - sadece domain ile de çalışır
     */
    suspend fun fullScan(
        originalUrl: String,
        username: String,
        password: String,
        onProgress: (status: String, current: Int, total: Int, result: SideServerScanner.ScanResult?) -> Unit
    ): List<SideServerScanner.ScanResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SideServerScanner.ScanResult>()
        
        try {
            // 1. Orijinal URL'den host çıkar
            val originalHost = extractHostFromInput(originalUrl)
            if (originalHost.isBlank()) {
                onProgress("❌ Geçersiz URL veya domain", 0, 100, null)
                return@withContext results
            }
            
            val originalPort = extractPortFromInput(originalUrl)
            
            onProgress("🔍 IP adresi çözümleniyor: $originalHost", 0, 100, null)
            
            // 2. IP adresini çöz
            val ip = try {
                InetAddress.getByName(originalHost).hostAddress
            } catch (e: Exception) {
                onProgress("❌ IP çözümlenemedi: $originalHost", 0, 100, null)
                return@withContext results
            }
            
            onProgress("🌐 Reverse IP Lookup yapılıyor: $ip", 5, 100, null)
            
            // 3. Reverse IP Lookup
            val domains = reverseIpLookup(ip)
            
            if (domains.isEmpty()) {
                onProgress("⚠️ Aynı IP'de başka domain bulunamadı, port taraması yapılıyor...", 10, 100, null)
            } else {
                onProgress("🎉 ${domains.size} domain bulundu! IPTV taraması başlıyor...", 10, 100, null)
            }
            
            // 4. Her domain için IPTV kontrolü
            val allHosts = (domains + originalHost).distinct()
            val totalChecks = allHosts.size
            var checked = 0
            
            for (host in allHosts) {
                checked++
                val progress = 10 + ((checked * 70) / totalChecks.coerceAtLeast(1))
                onProgress("🔎 Taraniyor: $host ($checked/$totalChecks)", progress, 100, null)
                
                // IPTV sunucusu mu kontrol et
                val iptvResult = checkIfIptvServer(host)
                
                if (iptvResult != null) {
                    // IPTV sunucusu bulundu
                    if (username.isNotBlank() && password.isNotBlank()) {
                        // Credentials varsa test et
                        val testResult = testSingleServer(iptvResult.serverUrl, username, password)
                        results.add(testResult)
                        onProgress("${testResult.statusText}: ${testResult.serverUrl}", progress, 100, testResult)
                    } else {
                        // Credentials yoksa sadece IPTV panel olarak ekle
                        results.add(iptvResult)
                        onProgress("🎯 IPTV Panel: ${iptvResult.serverUrl}", progress, 100, iptvResult)
                    }
                }
            }
            
            // 5. Orijinal sunucuyu farklı portlarla da dene
            onProgress("🔌 Alternatif portlar deneniyor...", 85, 100, null)
            
            for (port in iptvPorts) {
                if (port != originalPort) {
                    val altUrl = "http://$originalHost:$port"
                    // Zaten taranmış mı kontrol et
                    if (results.none { it.serverUrl == altUrl }) {
                        val iptvCheck = checkIfIptvServer(originalHost, port)
                        if (iptvCheck != null) {
                            if (username.isNotBlank() && password.isNotBlank()) {
                                val testResult = testSingleServer(altUrl, username, password)
                                if (testResult.isActive) {
                                    results.add(testResult)
                                    onProgress("${testResult.statusText}: $altUrl", 90, 100, testResult)
                                }
                            } else {
                                results.add(iptvCheck)
                                onProgress("🎯 IPTV Panel: $altUrl", 90, 100, iptvCheck)
                            }
                        }
                    }
                }
            }
            
            val activeCount = results.count { it.isActive }
            onProgress("✅ Tarama tamamlandı! $activeCount IPTV sunucusu bulundu", 100, 100, null)
            
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
