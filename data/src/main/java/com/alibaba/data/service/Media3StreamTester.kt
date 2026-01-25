package com.alibaba.data.service

import android.content.Context
import com.alibaba.domain.service.StreamTester
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class Media3StreamTester @Inject constructor(
    @ApplicationContext private val context: Context
) : StreamTester {

    // HTTP-based hızlı stream tester (paralel güvenli, çökmez)
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override suspend fun isPlayable(url: String, timeoutMs: Long): Boolean {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                try {
                    // HEAD request ile hızlı kontrol
                    val headRequest = Request.Builder()
                        .url(url)
                        .head()
                        .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
                        .build()
                    
                    val headResponse = httpClient.newCall(headRequest).execute()
                    val headSuccess = headResponse.isSuccessful
                    val contentType = headResponse.header("Content-Type", "") ?: ""
                    headResponse.close()
                    
                    if (headSuccess) {
                        // Content-Type video/audio ise başarılı
                        val isMedia = contentType.contains("video", ignoreCase = true) ||
                                     contentType.contains("audio", ignoreCase = true) ||
                                     contentType.contains("mpegurl", ignoreCase = true) ||
                                     contentType.contains("octet-stream", ignoreCase = true) ||
                                     contentType.contains("application/x-mpegurl", ignoreCase = true)
                        
                        if (isMedia) return@withTimeoutOrNull true
                    }
                    
                    // HEAD başarısız veya content-type belirsiz ise GET ile küçük veri oku
                    val getRequest = Request.Builder()
                        .url(url)
                        .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
                        .header("Range", "bytes=0-1024") // Sadece ilk 1KB
                        .build()
                    
                    val getResponse = httpClient.newCall(getRequest).execute()
                    val success = getResponse.isSuccessful || getResponse.code == 206
                    val bodySize = getResponse.body?.bytes()?.size ?: 0
                    getResponse.close()
                    
                    // Veri geliyorsa çalışıyor demektir
                    success && bodySize > 0
                } catch (e: Exception) {
                    false
                }
            } ?: false
        }
    }
}
