package com.alibaba.domain.service

import com.alibaba.domain.model.IptvAnalysisOptions
import com.alibaba.domain.model.IptvGroupAnalysis
import com.alibaba.domain.model.IptvM3uAnalysis
import com.alibaba.domain.model.IptvOverallStreamTest
import com.alibaba.domain.model.IptvUrlLiveness
import com.alibaba.domain.model.Playlist

interface IptvAnalyzer {
    suspend fun checkUrlLiveness(url: String, options: IptvAnalysisOptions = IptvAnalysisOptions()): IptvUrlLiveness

    suspend fun downloadAndAnalyzeM3u(
        url: String,
        options: IptvAnalysisOptions = IptvAnalysisOptions()
    ): IptvM3uAnalysis

    suspend fun analyzeGroups(playlist: Playlist, options: IptvAnalysisOptions = IptvAnalysisOptions()): IptvGroupAnalysis

    suspend fun testStreams(playlist: Playlist, options: IptvAnalysisOptions = IptvAnalysisOptions()): IptvOverallStreamTest
}
