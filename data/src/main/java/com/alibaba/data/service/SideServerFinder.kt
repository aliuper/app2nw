package com.alibaba.data.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Yan Sunucu Bulucu - IPTV linklerinin alternatif sunucularını bulur
 * 
 * Mantık:
 * 1. Mevcut m3u linkinden username ve password çıkar
 * 2. Farklı sunucu URL'lerini test et (aynı credentials ile)
 * 3. Aktif olanları döndür
 */
@Singleton
class SideServerFinder @Inject constructor() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class Credentials(
        val serverUrl: String,  // http://example.com:8080
        val username: String,
        val password: String
    )

    data class SideServerResult(
        val serverUrl: String,
        val m3uLink: String,
        val status: ServerStatus,
        val expireDate: String? = null,
        val maxConnections: Int? = null,
        val activeConnections: Int? = null
    )

    enum class ServerStatus {
        ACTIVE,      // Çalışıyor
        EXPIRED,     // Süresi dolmuş
        BANNED,      // Yasaklanmış
        INVALID,     // Geçersiz credentials
        TIMEOUT,     // Zaman aşımı
        ERROR        // Diğer hatalar
    }

    /**
     * M3U linkinden credentials çıkar
     * Örnek: http://example.com:8080/get.php?username=user&password=pass&type=m3u_plus
     */
    fun extractCredentials(m3uLink: String): Credentials? {
        return try {
            val uri = URI(m3uLink)
            val baseUrl = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            
            // Query parametrelerini parse et
            val queryParams = uri.query?.split("&")?.associate {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
            } ?: emptyMap()
            
            val username = queryParams["username"] ?: return null
            val password = queryParams["password"] ?: return null
            
            Credentials(baseUrl, username, password)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Verilen sunucu listesinde credentials'ı test et
     */
    suspend fun findSideServers(
        credentials: Credentials,
        serverUrls: List<String>,
        onProgress: (current: Int, total: Int, result: SideServerResult?) -> Unit
    ): List<SideServerResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SideServerResult>()
        
        serverUrls.forEachIndexed { index, serverUrl ->
            val cleanUrl = serverUrl.trim().trimEnd('/')
            if (cleanUrl.isBlank()) {
                onProgress(index + 1, serverUrls.size, null)
                return@forEachIndexed
            }
            
            val result = testServer(cleanUrl, credentials.username, credentials.password)
            results.add(result)
            onProgress(index + 1, serverUrls.size, result)
        }
        
        results
    }

    /**
     * Tek bir sunucuyu test et
     */
    suspend fun testServer(
        serverUrl: String,
        username: String,
        password: String
    ): SideServerResult = withContext(Dispatchers.IO) {
        val cleanUrl = serverUrl.trim().trimEnd('/')
        
        // player_api.php ile kontrol et (Xtream Codes API)
        val apiUrl = "$cleanUrl/player_api.php?username=$username&password=$password"
        
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
                return@withContext SideServerResult(
                    serverUrl = cleanUrl,
                    m3uLink = buildM3uLink(cleanUrl, username, password),
                    status = ServerStatus.TIMEOUT
                )
            }
            
            val body = response.body?.string() ?: ""
            response.close()
            
            // JSON yanıtını analiz et
            parseApiResponse(cleanUrl, username, password, body)
            
        } catch (e: Exception) {
            SideServerResult(
                serverUrl = cleanUrl,
                m3uLink = buildM3uLink(cleanUrl, username, password),
                status = ServerStatus.ERROR
            )
        }
    }

    private fun parseApiResponse(
        serverUrl: String,
        username: String,
        password: String,
        responseBody: String
    ): SideServerResult {
        val m3uLink = buildM3uLink(serverUrl, username, password)
        
        return when {
            // Aktif hesap kontrolü
            responseBody.contains("\"status\":\"Active\"", ignoreCase = true) ||
            responseBody.contains("\"auth\":1", ignoreCase = true) -> {
                // Ek bilgileri çıkar
                val expireDate = extractJsonValue(responseBody, "exp_date")
                val maxConnections = extractJsonValue(responseBody, "max_connections")?.toIntOrNull()
                val activeConnections = extractJsonValue(responseBody, "active_cons")?.toIntOrNull()
                
                SideServerResult(
                    serverUrl = serverUrl,
                    m3uLink = m3uLink,
                    status = ServerStatus.ACTIVE,
                    expireDate = expireDate?.let { formatExpireDate(it) },
                    maxConnections = maxConnections,
                    activeConnections = activeConnections
                )
            }
            
            // Süresi dolmuş
            responseBody.contains("\"status\":\"Expired\"", ignoreCase = true) -> {
                SideServerResult(
                    serverUrl = serverUrl,
                    m3uLink = m3uLink,
                    status = ServerStatus.EXPIRED
                )
            }
            
            // Yasaklanmış
            responseBody.contains("\"status\":\"Banned\"", ignoreCase = true) ||
            responseBody.contains("\"status\":\"Disabled\"", ignoreCase = true) -> {
                SideServerResult(
                    serverUrl = serverUrl,
                    m3uLink = m3uLink,
                    status = ServerStatus.BANNED
                )
            }
            
            // Geçersiz credentials
            responseBody.contains("\"user_info\":[]", ignoreCase = true) ||
            responseBody.contains("\"auth\":0", ignoreCase = true) ||
            responseBody.isBlank() -> {
                SideServerResult(
                    serverUrl = serverUrl,
                    m3uLink = m3uLink,
                    status = ServerStatus.INVALID
                )
            }
            
            else -> {
                // Bilinmeyen yanıt - muhtemelen aktif değil
                SideServerResult(
                    serverUrl = serverUrl,
                    m3uLink = m3uLink,
                    status = ServerStatus.INVALID
                )
            }
        }
    }

    private fun buildM3uLink(serverUrl: String, username: String, password: String): String {
        return "$serverUrl/get.php?username=$username&password=$password&type=m3u_plus"
    }

    private fun extractJsonValue(json: String, key: String): String? {
        // Basit JSON değer çıkarma (regex ile)
        val patterns = listOf(
            "\"$key\"\\s*:\\s*\"([^\"]+)\"",  // String değer
            "\"$key\"\\s*:\\s*(\\d+)"          // Sayısal değer
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

    /**
     * Popüler IPTV sunucu portları
     */
    fun getCommonPorts(): List<Int> = listOf(
        80, 8080, 8000, 25461, 25462, 25463, 25464, 25465,
        8880, 8881, 8888, 8889, 9090, 9091, 9999,
        1935, 443, 8443, 8001, 8002, 8003
    )

    /**
     * IP aralığından sunucu URL'leri oluştur
     * Örnek: 192.168.1.1 - 192.168.1.255 aralığı
     */
    fun generateServerUrls(
        baseIp: String,
        startRange: Int,
        endRange: Int,
        ports: List<Int>
    ): List<String> {
        val urls = mutableListOf<String>()
        val ipParts = baseIp.split(".")
        
        if (ipParts.size != 4) return urls
        
        val prefix = "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}"
        
        for (i in startRange..endRange) {
            for (port in ports) {
                urls.add("http://$prefix.$i:$port")
            }
        }
        
        return urls
    }

    /**
     * Domain varyasyonları oluştur
     * Örnek: panel1.example.com -> panel2.example.com, panel3.example.com, ...
     */
    fun generateDomainVariations(originalDomain: String): List<String> {
        val variations = mutableListOf<String>()
        
        // Sayı içeren domain'lerde sayıyı değiştir
        val numberPattern = Regex("(\\d+)")
        val match = numberPattern.find(originalDomain)
        
        if (match != null) {
            val originalNumber = match.value.toIntOrNull() ?: return variations
            val prefix = originalDomain.substring(0, match.range.first)
            val suffix = originalDomain.substring(match.range.last + 1)
            
            // -5 ile +10 arasında varyasyonlar
            for (i in (originalNumber - 5)..(originalNumber + 10)) {
                if (i > 0 && i != originalNumber) {
                    variations.add("$prefix$i$suffix")
                }
            }
        }
        
        // Yaygın prefix/suffix varyasyonları
        val prefixes = listOf("panel", "server", "tv", "iptv", "stream", "live", "cdn", "s", "srv")
        val suffixes = listOf("1", "2", "3", "4", "5", "01", "02", "03", "new", "backup", "alt")
        
        val uri = try { URI("http://$originalDomain") } catch (e: Exception) { null }
        val host = uri?.host ?: originalDomain
        val parts = host.split(".")
        
        if (parts.size >= 2) {
            val baseDomain = parts.takeLast(2).joinToString(".")
            
            for (prefix in prefixes) {
                for (suffix in suffixes) {
                    variations.add("$prefix$suffix.$baseDomain")
                }
            }
        }
        
        return variations.distinct()
    }
}
