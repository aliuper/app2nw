package com.alibaba.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class PlaylistDownloader(
    private val client: OkHttpClient
) {
    // Hızlı indirme için optimize edilmiş client
    private val fastClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun downloadLines(url: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Connection", "keep-alive")
            .header("Accept-Encoding", "gzip, deflate")
            .get()
            .build()
        
        val call = fastClient.newCall(request)
        call.timeout().timeout(45, TimeUnit.SECONDS) // 120 -> 45 saniye
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Empty body")
            val source = body.source()

            // Read playlist line by line with reasonable chunk size
            val lines = ArrayList<String>(4096)
            
            // No hard limit - read entire playlist but with memory-efficient approach
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                lines.add(line)
                
                // Yield periodically to prevent blocking for very large files
                if (lines.size % 10000 == 0) {
                    kotlinx.coroutines.yield()
                }
            }
            
            lines
        }
    }

    suspend fun downloadText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Connection", "keep-alive")
            .get()
            .build()
        val call = fastClient.newCall(request)
        call.timeout().timeout(15, TimeUnit.SECONDS) // 20 -> 15 saniye
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Empty body")
            body.string()
        }
    }
}
