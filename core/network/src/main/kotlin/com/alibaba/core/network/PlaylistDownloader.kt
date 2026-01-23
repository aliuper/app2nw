package com.alibaba.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class PlaylistDownloader(
    private val client: OkHttpClient
) {
    suspend fun downloadLines(url: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        val call = client.newCall(request)
        call.timeout().timeout(120, TimeUnit.SECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${'$'}{response.code}")
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
        val request = Request.Builder().url(url).get().build()
        val call = client.newCall(request)
        call.timeout().timeout(20, TimeUnit.SECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${'$'}{response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Empty body")
            body.string()
        }
    }
}
