package com.alibaba.domain.service

import com.alibaba.domain.model.Playlist
import com.alibaba.domain.model.QualityMetrics

interface QualityTester {
    suspend fun testQuality(playlist: Playlist, sampleSize: Int = 5): QualityMetrics
}
