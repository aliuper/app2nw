package com.alibaba.domain.repo

import com.alibaba.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setStreamTestSampleSize(value: Int)
    suspend fun setStreamTestTimeoutMs(value: Long)
    suspend fun setMinPlayableStreamsToPass(value: Int)
    suspend fun setDelayBetweenStreamTestsMs(value: Long)
    suspend fun setSkipAdultGroups(value: Boolean)
    suspend fun setShuffleCandidates(value: Boolean)

    suspend fun resetToDefaults()
}
