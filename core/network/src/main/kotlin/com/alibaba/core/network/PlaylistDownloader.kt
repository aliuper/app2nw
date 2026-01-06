package com.alibaba.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class PlaylistDownloader(
    private val client: OkHttpClient
) {
    suspend fun downloadLines(url: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${'$'}{response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Empty body")
            val source = body.source()

            // Limit initial capacity to prevent memory bloat for large playlists
            val lines = ArrayList<String>(8192)
            var lineCount = 0
            val maxLines = 500_000 // Allow larger playlists for analysis (500k lines)
            
            while (!source.exhausted() && lineCount < maxLines) {
                val line = source.readUtf8Line() ?: break
                lines.add(line)
                lineCount++
            }
            
            // Don't throw error, just truncate silently for analysis
            if (lineCount >= maxLines) {
                lines.add("#EXTINF:-1,⚠️ Playlist kesildi (>500k satır)")
            }
            
            lines
        }
    }
}
