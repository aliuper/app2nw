package com.alibaba.data.service

import com.alibaba.domain.service.SideServerScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SideServerScannerImpl @Inject constructor() : SideServerScanner {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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

    override fun generateDomainVariations(originalUrl: String): List<String> {
        val variations = mutableListOf<String>()
        
        try {
            val uri = URI(originalUrl)
            val host = uri.host ?: return variations
            val port = if (uri.port > 0) uri.port else 80
            val scheme = uri.scheme ?: "http"
            
            // 1. Port varyasyonları
            val commonPorts = listOf(80, 8080, 8000, 25461, 25462, 25463, 25464, 25465, 8880, 8881, 8888, 9090)
            for (p in commonPorts) {
                if (p != port) {
                    variations.add("$scheme://$host:$p")
                }
            }
            
            // 2. Sayı varyasyonları (panel1 -> panel2, panel3, ...)
            val numberPattern = Regex("(\\d+)")
            val match = numberPattern.find(host)
            if (match != null) {
                val originalNumber = match.value.toIntOrNull() ?: 0
                val prefix = host.substring(0, match.range.first)
                val suffix = host.substring(match.range.last + 1)
                
                for (i in 1..10) {
                    if (i != originalNumber) {
                        val newHost = "$prefix$i$suffix"
                        variations.add("$scheme://$newHost:$port")
                        // Yaygın portlarla da dene
                        for (p in listOf(8080, 25461, 80)) {
                            if (p != port) {
                                variations.add("$scheme://$newHost:$p")
                            }
                        }
                    }
                }
            }
            
            // 3. Subdomain varyasyonları
            val parts = host.split(".")
            if (parts.size >= 2) {
                val baseDomain = parts.takeLast(2).joinToString(".")
                val prefixes = listOf("panel", "server", "tv", "iptv", "stream", "live", "cdn", "s", "srv", "m3u")
                val suffixes = listOf("1", "2", "3", "4", "5", "01", "02", "03")
                
                for (prefix in prefixes) {
                    for (suffix in suffixes) {
                        val newHost = "$prefix$suffix.$baseDomain"
                        if (newHost != host) {
                            variations.add("$scheme://$newHost:$port")
                            variations.add("$scheme://$newHost:8080")
                            variations.add("$scheme://$newHost:25461")
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            // Hata durumunda boş liste döndür
        }
        
        return variations.distinct().take(100) // Max 100 varyasyon
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
