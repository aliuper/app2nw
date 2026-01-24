package com.alibaba.data.service

import com.alibaba.domain.model.*
import com.alibaba.domain.service.ExpiryChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExpiryCheckerImpl @Inject constructor() : ExpiryChecker {

    private fun createClient(timeoutSeconds: Int): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override suspend fun checkExpiry(
        link: String,
        config: ExpiryCheckConfig,
        onProgress: ((ExpiryCheckProgress) -> Unit)?
    ): ExpiryCheckResult = withContext(Dispatchers.IO) {
        
        val credentials = parseCredentials(link)
        
        if (!credentials.isValid) {
            return@withContext ExpiryCheckResult(
                link = link,
                status = ExpiryStatus.INVALID_LINK,
                username = credentials.username ?: "Bilinmiyor"
            )
        }
        
        val username = credentials.username!!
        val password = credentials.password ?: ""
        val server = credentials.server!!
        val port = credentials.port ?: 8080
        
        val client = createClient(config.timeoutSeconds)
        
        // 1. Player API kontrolü (en güvenilir yöntem)
        if (config.checkPlayerApi) {
            onProgress?.invoke(ExpiryCheckProgress(1, 4, link, "Player API sorgulanıyor..."))
            val playerApiResult = checkPlayerApi(client, server, port, username, password)
            if (playerApiResult != null) {
                return@withContext playerApiResult.copy(link = link)
            }
        }
        
        // 2. XMLTV kontrolü
        if (config.checkXmlTv) {
            onProgress?.invoke(ExpiryCheckProgress(2, 4, link, "XMLTV sorgulanıyor..."))
            val xmlTvResult = checkXmlTv(client, server, port, username, password)
            if (xmlTvResult != null) {
                return@withContext xmlTvResult.copy(link = link)
            }
        }
        
        // 3. M3U kontrolü
        if (config.checkM3u) {
            onProgress?.invoke(ExpiryCheckProgress(3, 4, link, "M3U dosyası sorgulanıyor..."))
            val m3uResult = checkM3u(client, server, port, username, password)
            if (m3uResult != null) {
                return@withContext m3uResult.copy(link = link)
            }
        }
        
        // 4. Kanal testi
        if (config.checkChannel) {
            onProgress?.invoke(ExpiryCheckProgress(4, 4, link, "Test kanalı sorgulanıyor..."))
            val channelResult = checkChannel(client, server, port, username, password)
            if (channelResult != null) {
                return@withContext channelResult.copy(link = link)
            }
        }
        
        // Hiçbir kontrol başarılı olmadı
        ExpiryCheckResult(
            link = link,
            status = ExpiryStatus.UNKNOWN,
            username = username,
            password = password,
            server = server
        )
    }

    private fun checkPlayerApi(
        client: OkHttpClient,
        server: String,
        port: Int,
        username: String,
        password: String
    ): ExpiryCheckResult? {
        return try {
            val url = "http://$server:$port/player_api.php?username=$username&password=$password"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                
                try {
                    val json = JSONObject(body)
                    
                    if (json.has("user_info")) {
                        val userInfo = json.getJSONObject("user_info")
                        
                        // Bitiş tarihi kontrolü
                        val expDateStr = userInfo.optString("exp_date", "")
                        val status = userInfo.optString("status", "")
                        val packageName = userInfo.optString("package", "Bilinmiyor")
                        val maxConnections = userInfo.optInt("max_connections", 1)
                        val activeConnections = userInfo.optInt("active_cons", 0)
                        val isTrial = userInfo.optString("is_trial", "0") == "1"
                        val createdAt = userInfo.optString("created_at", null)
                        
                        if (expDateStr.isNotBlank() && expDateStr != "null") {
                            val expTimestamp = expDateStr.toLongOrNull()
                            if (expTimestamp != null) {
                                val expiryDate = Date(expTimestamp * 1000)
                                val now = Date()
                                val diffDays = ((expiryDate.time - now.time) / (1000 * 60 * 60 * 24)).toInt()
                                
                                val expiryStatus = when {
                                    expiryDate.after(now) && diffDays <= 7 -> ExpiryStatus.EXPIRING_SOON
                                    expiryDate.after(now) -> ExpiryStatus.ACTIVE
                                    else -> ExpiryStatus.EXPIRED
                                }
                                
                                val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                                val formattedDate = if (diffDays >= 0) {
                                    "${dateFormat.format(expiryDate)} ($diffDays gün kaldı)"
                                } else {
                                    "${dateFormat.format(expiryDate)} (${-diffDays} gün önce doldu)"
                                }
                                
                                return ExpiryCheckResult(
                                    link = "",
                                    status = expiryStatus,
                                    expiryDate = expiryDate,
                                    expiryDateFormatted = formattedDate,
                                    daysRemaining = diffDays,
                                    packageName = packageName,
                                    username = username,
                                    password = password,
                                    server = server,
                                    maxConnections = maxConnections,
                                    activeConnections = activeConnections,
                                    isTrial = isTrial,
                                    createdAt = createdAt
                                )
                            }
                        }
                        
                        // Durum kontrolü (exp_date yoksa)
                        val expiryStatus = when (status.lowercase()) {
                            "active", "aktif" -> ExpiryStatus.ACTIVE
                            "expired", "süresi dolmuş" -> ExpiryStatus.EXPIRED
                            "banned", "disabled" -> ExpiryStatus.INVALID_CREDENTIALS
                            else -> ExpiryStatus.UNKNOWN
                        }
                        
                        return ExpiryCheckResult(
                            link = "",
                            status = expiryStatus,
                            expiryDateFormatted = expDateStr.ifBlank { "Bilinmiyor" },
                            packageName = packageName,
                            username = username,
                            password = password,
                            server = server,
                            maxConnections = maxConnections,
                            activeConnections = activeConnections,
                            isTrial = isTrial,
                            createdAt = createdAt
                        )
                    }
                } catch (e: Exception) {
                    // JSON parse hatası - devam et
                }
            } else if (response.code == 401 || response.code == 403) {
                return ExpiryCheckResult(
                    link = "",
                    status = ExpiryStatus.INVALID_CREDENTIALS,
                    username = username,
                    password = password,
                    server = server
                )
            }
            null
        } catch (e: java.net.SocketTimeoutException) {
            ExpiryCheckResult(
                link = "",
                status = ExpiryStatus.TIMEOUT,
                username = username,
                password = password,
                server = server
            )
        } catch (e: java.net.ConnectException) {
            ExpiryCheckResult(
                link = "",
                status = ExpiryStatus.CONNECTION_ERROR,
                username = username,
                password = password,
                server = server
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun checkXmlTv(
        client: OkHttpClient,
        server: String,
        port: Int,
        username: String,
        password: String
    ): ExpiryCheckResult? {
        return try {
            val url = "http://$server:$port/xmltv.php?username=$username&password=$password"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                
                // XML içeriğinde bitiş tarihi bilgisini ara
                val expiryPattern = Pattern.compile("expiry=\"([^\"]+)\"|exp_date=\"([^\"]+)\"")
                val matcher = expiryPattern.matcher(body)
                
                if (matcher.find()) {
                    val expiryStr = matcher.group(1) ?: matcher.group(2) ?: return null
                    
                    // Unix timestamp kontrolü
                    if (expiryStr.matches(Regex("\\d{10}"))) {
                        val expTimestamp = expiryStr.toLongOrNull() ?: return null
                        val expiryDate = Date(expTimestamp * 1000)
                        val now = Date()
                        val diffDays = ((expiryDate.time - now.time) / (1000 * 60 * 60 * 24)).toInt()
                        
                        val expiryStatus = when {
                            expiryDate.after(now) && diffDays <= 7 -> ExpiryStatus.EXPIRING_SOON
                            expiryDate.after(now) -> ExpiryStatus.ACTIVE
                            else -> ExpiryStatus.EXPIRED
                        }
                        
                        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        val formattedDate = if (diffDays >= 0) {
                            "${dateFormat.format(expiryDate)} ($diffDays gün kaldı)"
                        } else {
                            "${dateFormat.format(expiryDate)} (${-diffDays} gün önce doldu)"
                        }
                        
                        return ExpiryCheckResult(
                            link = "",
                            status = expiryStatus,
                            expiryDate = expiryDate,
                            expiryDateFormatted = formattedDate,
                            daysRemaining = diffDays,
                            username = username,
                            password = password,
                            server = server
                        )
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun checkM3u(
        client: OkHttpClient,
        server: String,
        port: Int,
        username: String,
        password: String
    ): ExpiryCheckResult? {
        return try {
            val url = "http://$server:$port/get.php?username=$username&password=$password&type=m3u_plus"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                
                when {
                    body.contains("#EXTINF") -> {
                        // M3U dosyası düzgün geldi, aktif hesap
                        return ExpiryCheckResult(
                            link = "",
                            status = ExpiryStatus.ACTIVE,
                            expiryDateFormatted = "Bilinmiyor (M3U erişilebilir)",
                            username = username,
                            password = password,
                            server = server
                        )
                    }
                    body.lowercase().contains("incorrect") || 
                    body.lowercase().contains("wrong") ||
                    body.lowercase().contains("invalid") -> {
                        return ExpiryCheckResult(
                            link = "",
                            status = ExpiryStatus.INVALID_CREDENTIALS,
                            username = username,
                            password = password,
                            server = server
                        )
                    }
                    body.lowercase().contains("expired") -> {
                        return ExpiryCheckResult(
                            link = "",
                            status = ExpiryStatus.EXPIRED,
                            username = username,
                            password = password,
                            server = server
                        )
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun checkChannel(
        client: OkHttpClient,
        server: String,
        port: Int,
        username: String,
        password: String
    ): ExpiryCheckResult? {
        return try {
            val url = "http://$server:$port/live/$username/$password/1.ts"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()?.take(1024)?.toByteArray()
                if (bytes != null) {
                    val content = String(bytes, Charsets.ISO_8859_1)
                    if (content.contains("#EXT") || content.contains("MPEG") || bytes.size > 100) {
                        return ExpiryCheckResult(
                            link = "",
                            status = ExpiryStatus.ACTIVE,
                            expiryDateFormatted = "Bilinmiyor (Kanal erişilebilir)",
                            username = username,
                            password = password,
                            server = server
                        )
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun checkMultipleLinks(
        links: List<String>,
        config: ExpiryCheckConfig,
        onProgress: ((ExpiryCheckProgress) -> Unit)?,
        onResult: ((ExpiryCheckResult) -> Unit)?
    ): ExpiryCheckSummary = withContext(Dispatchers.IO) {
        val results = mutableListOf<ExpiryCheckResult>()
        var active = 0
        var expired = 0
        var expiringSoon = 0
        var errors = 0
        
        links.forEachIndexed { index, link ->
            onProgress?.invoke(ExpiryCheckProgress(
                current = index + 1,
                total = links.size,
                currentLink = maskLink(link),
                phase = "Kontrol ediliyor..."
            ))
            
            val result = checkExpiry(link, config)
            results.add(result)
            onResult?.invoke(result)
            
            when (result.status) {
                ExpiryStatus.ACTIVE -> active++
                ExpiryStatus.EXPIRED -> expired++
                ExpiryStatus.EXPIRING_SOON -> {
                    expiringSoon++
                    active++ // Expiring soon da aktif sayılır
                }
                else -> errors++
            }
            
            if (config.delayBetweenChecks > 0) {
                delay(config.delayBetweenChecks)
            }
        }
        
        ExpiryCheckSummary(
            total = links.size,
            active = active,
            expired = expired,
            expiringSoon = expiringSoon,
            errors = errors,
            results = results
        )
    }

    override fun parseCredentials(link: String): ParsedCredentials {
        try {
            val uri = URI(link)
            val host = uri.host
            val port = if (uri.port > 0) uri.port else 8080
            
            // Format 1: username:password@server.com
            if (uri.userInfo != null && uri.userInfo.contains(":")) {
                val parts = uri.userInfo.split(":")
                return ParsedCredentials(
                    username = parts[0],
                    password = parts.getOrNull(1),
                    server = host,
                    port = port
                )
            }
            
            // Format 2: /live/username/password/...
            val pathParts = uri.path.trim('/').split("/")
            if (pathParts.size >= 3 && pathParts[0] in listOf("live", "playlist", "player", "get")) {
                return ParsedCredentials(
                    username = pathParts[1],
                    password = pathParts[2],
                    server = host,
                    port = port
                )
            }
            
            // Format 3: ?username=xxx&password=yyy
            val query = uri.query ?: ""
            val usernameMatch = Regex("username=([^&]+)").find(query)
            val passwordMatch = Regex("password=([^&]+)").find(query)
            
            if (usernameMatch != null) {
                return ParsedCredentials(
                    username = usernameMatch.groupValues[1],
                    password = passwordMatch?.groupValues?.get(1),
                    server = host,
                    port = port
                )
            }
            
            return ParsedCredentials(null, null, host, port)
        } catch (e: Exception) {
            return ParsedCredentials(null, null, null, null)
        }
    }

    override fun maskLink(link: String): String {
        return try {
            val credentials = parseCredentials(link)
            
            if (credentials.isValid) {
                val maskedUser = credentials.username?.let {
                    if (it.length > 2) it.take(2) + "*".repeat(it.length - 2) else it
                } ?: ""
                val maskedPass = credentials.password?.let { "*".repeat(it.length) } ?: ""
                
                var masked = link
                credentials.username?.let { masked = masked.replace(it, maskedUser) }
                credentials.password?.let { masked = masked.replace(it, maskedPass) }
                masked
            } else {
                if (link.length > 20) {
                    link.take(10) + "..." + link.takeLast(10)
                } else {
                    link
                }
            }
        } catch (e: Exception) {
            if (link.length > 20) {
                link.take(10) + "..." + link.takeLast(10)
            } else {
                link
            }
        }
    }
}
