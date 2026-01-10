package com.alibaba.data.service

import com.alibaba.domain.model.Playlist
import com.alibaba.domain.model.QualityMetrics
import com.alibaba.domain.service.QualityTester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.random.Random

class QualityTesterImpl @Inject constructor(
    private val okHttpClient: OkHttpClient
) : QualityTester {

    override suspend fun testQuality(playlist: Playlist, sampleSize: Int): QualityMetrics = withContext(Dispatchers.IO) {
        val channels = playlist.channels
        if (channels.isEmpty()) {
            return@withContext QualityMetrics(
                avgOpeningSpeed = Long.MAX_VALUE,
                avgLoadingSpeed = Long.MAX_VALUE,
                bufferingRate = 1.0f,
                avgBitrate = 0L,
                responseTime = Long.MAX_VALUE,
                successRate = 0.0f
            )
        }

        // Sample random channels
        val sample = if (channels.size <= sampleSize) {
            channels
        } else {
            channels.shuffled(Random(System.currentTimeMillis())).take(sampleSize)
        }

        val results = mutableListOf<ChannelTestResult>()
        
        for (channel in sample) {
            val result = testChannel(channel.url)
            results.add(result)
        }

        val successfulResults = results.filter { it.success }
        val successCount = successfulResults.size
        val totalCount = results.size

        if (successfulResults.isEmpty()) {
            return@withContext QualityMetrics(
                avgOpeningSpeed = Long.MAX_VALUE,
                avgLoadingSpeed = Long.MAX_VALUE,
                bufferingRate = 1.0f,
                avgBitrate = 0L,
                responseTime = Long.MAX_VALUE,
                successRate = 0.0f
            )
        }

        QualityMetrics(
            avgOpeningSpeed = successfulResults.map { it.openingSpeed }.average().toLong(),
            avgLoadingSpeed = successfulResults.map { it.loadingSpeed }.average().toLong(),
            bufferingRate = results.count { it.hasBuffering }.toFloat() / totalCount.toFloat(),
            avgBitrate = successfulResults.map { it.bitrate }.average().toLong(),
            responseTime = successfulResults.map { it.responseTime }.average().toLong(),
            successRate = successCount.toFloat() / totalCount.toFloat()
        )
    }

    private suspend fun testChannel(url: String): ChannelTestResult = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            
            val request = Request.Builder()
                .url(url)
                .head() // Use HEAD request for faster response
                .build()

            val response = withTimeoutOrNull(10_000) {
                okHttpClient.newCall(request).execute()
            }

            if (response == null) {
                return@withContext ChannelTestResult(
                    success = false,
                    openingSpeed = 10_000,
                    loadingSpeed = 10_000,
                    hasBuffering = true,
                    bitrate = 0,
                    responseTime = 10_000
                )
            }

            response.use {
                val responseTime = System.currentTimeMillis() - startTime
                val isSuccess = it.isSuccessful

                if (!isSuccess) {
                    return@withContext ChannelTestResult(
                        success = false,
                        openingSpeed = responseTime,
                        loadingSpeed = responseTime,
                        hasBuffering = true,
                        bitrate = 0,
                        responseTime = responseTime
                    )
                }

                // Estimate bitrate from content-length header
                val contentLength = it.header("Content-Length")?.toLongOrNull() ?: 0L
                val estimatedBitrate = if (contentLength > 0 && responseTime > 0) {
                    (contentLength * 8 * 1000) / responseTime // bits per second
                } else {
                    1_500_000L // Default 1.5 Mbps if unknown
                }

                // Opening speed = response time for HEAD request
                val openingSpeed = responseTime
                
                // Loading speed = same as opening for HEAD request
                val loadingSpeed = responseTime

                // Estimate buffering based on response time
                val hasBuffering = responseTime > 3000

                ChannelTestResult(
                    success = true,
                    openingSpeed = openingSpeed,
                    loadingSpeed = loadingSpeed,
                    hasBuffering = hasBuffering,
                    bitrate = estimatedBitrate,
                    responseTime = responseTime
                )
            }
        } catch (e: Exception) {
            ChannelTestResult(
                success = false,
                openingSpeed = 10_000,
                loadingSpeed = 10_000,
                hasBuffering = true,
                bitrate = 0,
                responseTime = 10_000
            )
        }
    }

    private data class ChannelTestResult(
        val success: Boolean,
        val openingSpeed: Long,
        val loadingSpeed: Long,
        val hasBuffering: Boolean,
        val bitrate: Long,
        val responseTime: Long
    )
}
