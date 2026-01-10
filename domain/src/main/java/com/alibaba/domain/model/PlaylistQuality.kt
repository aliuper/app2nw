package com.alibaba.domain.model

data class PlaylistQuality(
    val url: String,
    val endDate: String?,
    val channelCount: Int,
    val workingChannelCount: Int,
    val qualityScore: Float, // 0-10
    val metrics: QualityMetrics,
    val rank: Int = 0
)

data class QualityMetrics(
    val avgOpeningSpeed: Long, // milliseconds to first byte
    val avgLoadingSpeed: Long, // milliseconds to connect
    val bufferingRate: Float, // 0-1 (0 = no buffering, 1 = constant buffering)
    val avgBitrate: Long, // bits per second
    val responseTime: Long, // milliseconds
    val successRate: Float // 0-1 (working channels / total channels)
)

fun QualityMetrics.calculateScore(): Float {
    // Opening speed score (0-2 points): faster is better
    val openingScore = when {
        avgOpeningSpeed < 500 -> 2.0f
        avgOpeningSpeed < 1000 -> 1.5f
        avgOpeningSpeed < 2000 -> 1.0f
        avgOpeningSpeed < 3000 -> 0.5f
        else -> 0.0f
    }
    
    // Loading speed score (0-2 points): faster is better
    val loadingScore = when {
        avgLoadingSpeed < 1000 -> 2.0f
        avgLoadingSpeed < 2000 -> 1.5f
        avgLoadingSpeed < 3000 -> 1.0f
        avgLoadingSpeed < 5000 -> 0.5f
        else -> 0.0f
    }
    
    // Buffering score (0-2 points): less buffering is better
    val bufferingScore = (1.0f - bufferingRate) * 2.0f
    
    // Bitrate score (0-2 points): higher is better
    val bitrateScore = when {
        avgBitrate > 5_000_000 -> 2.0f // > 5 Mbps
        avgBitrate > 3_000_000 -> 1.5f // > 3 Mbps
        avgBitrate > 1_500_000 -> 1.0f // > 1.5 Mbps
        avgBitrate > 500_000 -> 0.5f   // > 500 Kbps
        else -> 0.0f
    }
    
    // Success rate score (0-2 points): more working channels is better
    val successScore = successRate * 2.0f
    
    return (openingScore + loadingScore + bufferingScore + bitrateScore + successScore).coerceIn(0f, 10f)
}
