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
            // Test 1: Initial connection speed (HEAD request)
            val headStartTime = System.currentTimeMillis()
            val headRequest = Request.Builder()
                .url(url)
                .head()
                .build()

            val headResponse = withTimeoutOrNull(8_000) {
                okHttpClient.newCall(headRequest).execute()
            }

            if (headResponse == null || !headResponse.isSuccessful) {
                return@withContext ChannelTestResult(
                    success = false,
                    openingSpeed = 8_000,
                    loadingSpeed = 8_000,
                    hasBuffering = true,
                    bitrate = 0,
                    responseTime = 8_000
                )
            }

            val headResponseTime = System.currentTimeMillis() - headStartTime
            headResponse.close()

            // Test 2: Download actual data to measure real bitrate (first 512KB)
            val dataStartTime = System.currentTimeMillis()
            val dataRequest = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-524287") // Request first 512KB
                .build()

            var actualBitrate = 0L
            var downloadTime = 0L
            var dataSuccess = false

            val dataResponse = withTimeoutOrNull(10_000) {
                okHttpClient.newCall(dataRequest).execute()
            }

            if (dataResponse != null && (dataResponse.isSuccessful || dataResponse.code == 206)) {
                dataResponse.use {
                    val body = it.body
                    if (body != null) {
                        try {
                            // Read data to measure actual download speed
                            val buffer = ByteArray(8192)
                            val inputStream = body.byteStream()
                            var totalBytes = 0L
                            
                            var readBytes = inputStream.read(buffer)
                            while (totalBytes < 131072 && readBytes != -1) { // Read up to 128KB
                                totalBytes += readBytes
                                readBytes = inputStream.read(buffer)
                            }
                            
                            downloadTime = System.currentTimeMillis() - dataStartTime
                            if (downloadTime > 0 && totalBytes > 0) {
                                // Calculate actual bitrate from downloaded data
                                actualBitrate = (totalBytes * 8 * 1000) / downloadTime
                                dataSuccess = true
                            }
                        } catch (e: Exception) {
                            // Download test failed, use fallback
                        }
                    }
                }
            }

            // Test 3: Connection stability - try second connection
            val stabilityStartTime = System.currentTimeMillis()
            val stabilityRequest = Request.Builder()
                .url(url)
                .head()
                .build()

            val stabilityResponse = withTimeoutOrNull(5_000) {
                okHttpClient.newCall(stabilityRequest).execute()
            }

            val stabilityTime = System.currentTimeMillis() - stabilityStartTime
            val isStable = stabilityResponse?.isSuccessful == true
            stabilityResponse?.close()

            // Calculate metrics
            val openingSpeed = headResponseTime
            val loadingSpeed = if (dataSuccess) downloadTime else headResponseTime
            
            // Buffering detection: unstable connection or slow response
            val hasBuffering = !isStable || headResponseTime > 3000 || stabilityTime > 3000
            
            // Use actual bitrate if available, otherwise estimate
            val finalBitrate = if (dataSuccess && actualBitrate > 0) {
                actualBitrate
            } else {
                // Fallback estimation based on response time
                when {
                    headResponseTime < 500 -> 5_000_000L  // Fast = assume HD
                    headResponseTime < 1500 -> 3_000_000L // Medium = assume SD+
                    headResponseTime < 3000 -> 1_500_000L // Slow = assume SD
                    else -> 500_000L // Very slow = low quality
                }
            }

            val avgResponseTime = (headResponseTime + stabilityTime) / 2

            ChannelTestResult(
                success = true,
                openingSpeed = openingSpeed,
                loadingSpeed = loadingSpeed,
                hasBuffering = hasBuffering,
                bitrate = finalBitrate,
                responseTime = avgResponseTime
            )
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
