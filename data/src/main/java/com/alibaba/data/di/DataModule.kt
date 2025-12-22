package com.alibaba.data.di

import com.alibaba.core.network.HttpClientFactory
import com.alibaba.core.network.PlaylistDownloader
import com.alibaba.core.parser.M3uParser
import com.alibaba.data.repo.PlaylistRepositoryImpl
import com.alibaba.data.service.DownloadsOutputSaver
import com.alibaba.data.service.Media3StreamTester
import com.alibaba.domain.repo.PlaylistRepository
import com.alibaba.domain.service.OutputSaver
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
    abstract fun bindStreamTester(impl: Media3StreamTester): StreamTester

    @Binds
    abstract fun bindOutputSaver(impl: DownloadsOutputSaver): OutputSaver
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
