package com.alibaba.domain.model

data class AppSettings(
    val streamTestSampleSize: Int = 10,
    val streamTestTimeoutMs: Long = 6_000L,
    val minPlayableStreamsToPass: Int = 1,
    val delayBetweenStreamTestsMs: Long = 0L,
    val skipAdultGroups: Boolean = true,
    val shuffleCandidates: Boolean = true
)
