package com.alibaba.data.di

import com.alibaba.core.network.HttpClientFactory
import com.alibaba.core.network.PlaylistDownloader
import com.alibaba.core.parser.M3uParser
import com.alibaba.data.repo.PlaylistRepositoryImpl
import com.alibaba.data.repo.SettingsRepositoryImpl
import com.alibaba.data.repo.URLScanRepositoryImpl
import com.alibaba.data.service.DownloadsOutputSaver
import com.alibaba.data.service.Media3StreamTester
import com.alibaba.data.service.OkHttpIptvAnalyzer
import com.alibaba.data.service.ExploitTesterImpl
import com.alibaba.data.service.PanelScannerImpl
import com.alibaba.data.service.QualityTesterImpl
import com.alibaba.domain.repo.PlaylistRepository
import com.alibaba.domain.repo.SettingsRepository
import com.alibaba.domain.repo.URLScanRepository
import com.alibaba.domain.service.IptvAnalyzer
import com.alibaba.domain.service.OutputSaver
import com.alibaba.domain.service.ExploitTester
import com.alibaba.domain.service.PanelScanner
import com.alibaba.domain.service.QualityTester
import com.alibaba.domain.service.StreamTester
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindModule {
    @Binds
    abstract fun bindPlaylistRepository(impl: PlaylistRepositoryImpl): PlaylistRepository

    @Binds
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    abstract fun bindStreamTester(impl: Media3StreamTester): StreamTester

    @Binds
    abstract fun bindIptvAnalyzer(impl: OkHttpIptvAnalyzer): IptvAnalyzer

    @Binds
    abstract fun bindOutputSaver(impl: DownloadsOutputSaver): OutputSaver

    @Binds
    abstract fun bindURLScanRepository(impl: URLScanRepositoryImpl): URLScanRepository

    @Binds
    abstract fun bindQualityTester(impl: QualityTesterImpl): QualityTester

    @Binds
    abstract fun bindPanelScanner(impl: PanelScannerImpl): PanelScanner

    @Binds
    abstract fun bindExploitTester(impl: ExploitTesterImpl): ExploitTester
}

@Module
@InstallIn(SingletonComponent::class)
object DataProvideModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = HttpClientFactory.create()

    @Provides
    @Singleton
    fun provideDownloader(client: OkHttpClient): PlaylistDownloader = PlaylistDownloader(client)

    @Provides
    @Singleton
    fun provideM3uParser(): M3uParser = M3uParser()
}
