package com.alibaba.core.common

private val urlRegex = Regex("""https?://[^\s"']+""", RegexOption.IGNORE_CASE)
private val schemeRegex = Regex("""https?://""", RegexOption.IGNORE_CASE)

// Xtream API URL'lerinden sunucu+kullanıcı+şifre çıkar
private fun extractCredentials(url: String): Triple<String, String, String>? {
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
