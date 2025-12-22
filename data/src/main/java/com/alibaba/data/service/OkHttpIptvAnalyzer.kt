package com.alibaba.data.service

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.alibaba.core.common.isAdultGroup
import com.alibaba.core.parser.M3uParser
import com.alibaba.domain.model.IptvAnalysisOptions
import com.alibaba.domain.model.IptvGroupAnalysis
import com.alibaba.domain.model.IptvGroupStat
import com.alibaba.domain.model.IptvM3uAnalysis
import com.alibaba.domain.model.IptvOverallStreamTest
import com.alibaba.domain.model.IptvQuality
import com.alibaba.domain.model.IptvStreamChannelTest
import com.alibaba.domain.model.IptvStreamGroupTest
import com.alibaba.domain.model.IptvUrlLiveness
import com.alibaba.domain.model.Playlist
import com.alibaba.domain.service.IptvAnalyzer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class OkHttpIptvAnalyzer @Inject constructor(
    private val client: OkHttpClient,
    private val parser: M3uParser,
    @ApplicationContext private val context: Context
) : IptvAnalyzer {

    override suspend fun checkUrlLiveness(url: String, options: IptvAnalysisOptions): IptvUrlLiveness =
        withContext(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            val tuned = tunedClient(options.urlTimeoutMs)

            fun buildRequest(method: String): Request {
                return Request.Builder()
                    .url(url)
                    .method(method, null)
                    .header("User-Agent", options.userAgent)
                    .build()
            }

            fun toResult(resp: Response, ok: Boolean): IptvUrlLiveness {
                val elapsed = SystemClock.elapsedRealtime() - start
                return IptvUrlLiveness(
                    url = url,
                    ok = ok,
                    httpCode = resp.code,
                    finalUrl = resp.request.url.toString(),
                    elapsedMs = elapsed,
                    error = null
                )
            }

            try {
                tuned.newCall(buildRequest("HEAD")).execute().use { resp ->
                    val code = resp.code
                    if (code == 405) {
                        // HEAD not allowed; fall back to lightweight GET
                    } else {
                        val ok = code in 200..399
                        return@withContext toResult(resp, ok)
                    }
                }

                tuned.newCall(
                    Request.Builder()
                        .url(url)
                        .get()
                        .header("User-Agent", options.userAgent)
                        .header("Range", "bytes=0-1023")
                        .build()
                ).execute().use { resp ->
                    val ok = resp.code in 200..399
                    toResult(resp, ok)
                }
            } catch (t: Throwable) {
                val elapsed = SystemClock.elapsedRealtime() - start
                IptvUrlLiveness(
                    url = url,
                    ok = false,
                    httpCode = null,
                    finalUrl = null,
                    elapsedMs = elapsed,
                    error = t.message ?: t::class.java.simpleName
                )
            }
        }

    override suspend fun downloadAndAnalyzeM3u(url: String, options: IptvAnalysisOptions): IptvM3uAnalysis {
        val liveness = checkUrlLiveness(url, options)
        if (!liveness.ok) {
            return IptvM3uAnalysis(
                url = url,
                liveness = liveness,
                startsWithExtM3u = false,
                extInfCount = 0,
                channelCount = 0,
                playlist = null,
                warning = null,
                error = liveness.error ?: (liveness.httpCode?.let { "HTTP $it" } ?: "URL canlı değil")
            )
        }

        return withContext(Dispatchers.IO) {
            val tuned = tunedClient(options.urlTimeoutMs)
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", options.userAgent)
                    .build()

                tuned.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext IptvM3uAnalysis(
                            url = url,
                            liveness = liveness.copy(ok = false, httpCode = resp.code, finalUrl = resp.request.url.toString()),
                            startsWithExtM3u = false,
                            extInfCount = 0,
                            channelCount = 0,
                            playlist = null,
                            warning = null,
                            error = "HTTP ${resp.code}"
                        )
                    }

                    val body = resp.body ?: return@withContext IptvM3uAnalysis(
                        url = url,
                        liveness = liveness,
                        startsWithExtM3u = false,
                        extInfCount = 0,
                        channelCount = 0,
                        playlist = null,
                        warning = null,
                        error = "Empty body"
                    )

                    val source = body.source()
                    val lines = ArrayList<String>(8192)
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        lines.add(line)
                    }

                    val firstNonEmpty = lines.firstOrNull { it.trim().isNotEmpty() }?.trim().orEmpty()
                    val startsWithExtM3u = firstNonEmpty.startsWith("#EXTM3U", ignoreCase = true)
                    val extInfCount = lines.count { it.trim().startsWith("#EXTINF", ignoreCase = true) }

                    val playlist = parser.parse(lines)
                    val channelCount = playlist.channels.size

                    val warning = when {
                        !startsWithExtM3u -> "#EXTM3U yok (format bozuk olabilir)"
                        channelCount in 1..9 -> "Kanal sayısı düşük ($channelCount)"
                        else -> null
                    }

                    val error = when {
                        channelCount == 0 -> "Kanal sayısı 0 (link çöptür)"
                        else -> null
                    }

                    IptvM3uAnalysis(
                        url = url,
                        liveness = liveness.copy(httpCode = resp.code, finalUrl = resp.request.url.toString()),
                        startsWithExtM3u = startsWithExtM3u,
                        extInfCount = extInfCount,
                        channelCount = channelCount,
                        playlist = if (channelCount == 0) null else playlist,
                        warning = warning,
                        error = error
                    )
                }
            } catch (t: Throwable) {
                IptvM3uAnalysis(
                    url = url,
                    liveness = liveness.copy(ok = false),
                    startsWithExtM3u = false,
                    extInfCount = 0,
                    channelCount = 0,
                    playlist = null,
                    warning = null,
                    error = t.message ?: t::class.java.simpleName
                )
            }
        }
    }

    override suspend fun analyzeGroups(playlist: Playlist, options: IptvAnalysisOptions): IptvGroupAnalysis =
        withContext(Dispatchers.Default) {
            val all = playlist.channels
                .asSequence()
                .filter { !isAdultGroup(it.group ?: "") }
                .map { it.group?.trim().takeUnless { g -> g.isNullOrBlank() } ?: "Ungrouped" }
                .groupingBy { it }
                .eachCount()
                .map { (k, v) -> IptvGroupStat(name = k, channelCount = v) }
                .sortedByDescending { it.channelCount }

            val filtered = all.filter { it.channelCount > 1 }

            val largest = filtered.maxByOrNull { it.channelCount }
            val smallest = filtered.minByOrNull { it.channelCount }

            IptvGroupAnalysis(
                totalGroups = all.size,
                totalChannels = playlist.channels.size,
                filteredGroups = filtered.size,
                filteredChannels = filtered.sumOf { it.channelCount },
                largestGroup = largest,
                smallestGroup = smallest,
                groups = filtered
            )
        }

    override suspend fun testStreams(playlist: Playlist, options: IptvAnalysisOptions): IptvOverallStreamTest = coroutineScope {
        val groupMap = playlist.channels
            .asSequence()
            .filter { !isAdultGroup(it.group ?: "") }
            .groupBy { it.group?.trim().takeUnless { g -> g.isNullOrBlank() } ?: "Ungrouped" }

        val eligibleGroups = groupMap
            .map { (name, ch) -> name to ch.distinctBy { it.url } }
            .filter { (_, ch) -> ch.size > 1 }
            .shuffled()

        val groupsToTest = eligibleGroups.take(options.maxGroupsToTest)
        val groupsSkipped = (eligibleGroups.size - groupsToTest.size).coerceAtLeast(0)

        if (groupsToTest.isEmpty()) {
            return@coroutineScope IptvOverallStreamTest(
                groupsTested = 0,
                groupsSkipped = groupsSkipped,
                totalChannelsTested = 0,
                totalChannelsPassed = 0,
                quality = IptvQuality.INVALID,
                groupResults = emptyList()
            )
        }

        val semaphore = Semaphore(options.maxConcurrentStreamTests)

        val groupResults = groupsToTest.map { (groupName, channels) ->
            async(Dispatchers.IO) {
                val sample = channels.shuffled().take(options.streamsPerGroup)

                val tests = sample.map { ch ->
                    async {
                        semaphore.withPermit {
                            testSingleStream(ch.url, options)
                        }
                    }
                }.awaitAll()

                val passed = tests.count { it.ok }
                val quality = when {
                    passed >= 2 -> IptvQuality.ACTIVE
                    passed == 1 -> IptvQuality.WEAK
                    else -> IptvQuality.DEAD
                }

                IptvStreamGroupTest(
                    groupName = groupName,
                    tested = tests.size,
                    passed = passed,
                    quality = quality,
                    channelTests = tests
                )
            }
        }.awaitAll()

        val totalChannelsTested = groupResults.sumOf { it.tested }
        val totalChannelsPassed = groupResults.sumOf { it.passed }

        val overall = when {
            groupResults.any { it.quality == IptvQuality.ACTIVE } -> IptvQuality.ACTIVE
            groupResults.any { it.quality == IptvQuality.WEAK } -> IptvQuality.WEAK
            else -> IptvQuality.DEAD
        }

        IptvOverallStreamTest(
            groupsTested = groupResults.size,
            groupsSkipped = groupsSkipped,
            totalChannelsTested = totalChannelsTested,
            totalChannelsPassed = totalChannelsPassed,
            quality = overall,
            groupResults = groupResults
        )
    }

    private fun tunedClient(timeoutMs: Long): OkHttpClient {
        return client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMs.coerceAtMost(10_000L), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.coerceAtMost(15_000L), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.coerceAtMost(15_000L), TimeUnit.MILLISECONDS)
            .build()
    }

    private fun normalizeMime(raw: String?): String? {
        return raw?.substringBefore(';')?.trim()?.lowercase(Locale.US)
    }

    private fun mimeLooksLikeVideoOrPlaylist(mime: String?, url: String): Boolean {
        val m = mime.orEmpty()
        if (m.contains("mpegurl")) return true
        if (m.contains("application/vnd.apple.mpegurl")) return true
        if (m.contains("video/mp2t")) return true
        if (m.contains("video/")) return true
        if (m.contains("audio/")) return true
        if (url.lowercase(Locale.US).contains(".m3u8")) return true
        return false
    }

    private suspend fun testSingleStream(url: String, options: IptvAnalysisOptions): IptvStreamChannelTest {
        val start = SystemClock.elapsedRealtime()
        val tuned = tunedClient(options.urlTimeoutMs)

        try {
            val rangeEnd = (options.partialDownloadBytes - 1).coerceAtLeast(1023L)
            val req = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", options.userAgent)
                .header("Range", "bytes=0-$rangeEnd")
                .build()

            val (httpCode, mime) = tuned.newCall(req).execute().use { resp ->
                val code = resp.code
                val mime = normalizeMime(resp.header("Content-Type"))
                if (code !in 200..399) {
                    val elapsed = SystemClock.elapsedRealtime() - start
                    return IptvStreamChannelTest(
                        url = url,
                        ok = false,
                        httpCode = code,
                        mime = mime,
                        elapsedMs = elapsed,
                        reason = "HTTP $code"
                    )
                }

                if (!mimeLooksLikeVideoOrPlaylist(mime, url)) {
                    val elapsed = SystemClock.elapsedRealtime() - start
                    return IptvStreamChannelTest(
                        url = url,
                        ok = false,
                        httpCode = code,
                        mime = mime,
                        elapsedMs = elapsed,
                        reason = "MIME uyumsuz: ${mime ?: "(yok)"}"
                    )
                }

                code to mime
            }

            val exoOk = testFirstFrameExo(url, options.exoFirstFrameTimeoutMs)
            val elapsed = SystemClock.elapsedRealtime() - start
            if (!exoOk) {
                return IptvStreamChannelTest(
                    url = url,
                    ok = false,
                    httpCode = httpCode,
                    mime = mime,
                    elapsedMs = elapsed,
                    reason = "ExoPlayer frame timeout"
                )
            }

            return IptvStreamChannelTest(
                url = url,
                ok = true,
                httpCode = httpCode,
                mime = mime,
                elapsedMs = elapsed,
                reason = null
            )
        } catch (t: Throwable) {
            val elapsed = SystemClock.elapsedRealtime() - start
            return IptvStreamChannelTest(
                url = url,
                ok = false,
                httpCode = null,
                mime = null,
                elapsedMs = elapsed,
                reason = t.message ?: t::class.java.simpleName
            )
        }
    }

    private suspend fun testFirstFrameExo(url: String, timeoutMs: Long): Boolean {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                val player = ExoPlayer.Builder(context).build()
                try {
                    val deferred = CompletableDeferred<Boolean>()
                    val listener = object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            if (!deferred.isCompleted) deferred.complete(true)
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY && !deferred.isCompleted) {
                                deferred.complete(true)
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (!deferred.isCompleted) deferred.complete(false)
                        }
                    }
                    player.addListener(listener)
                    player.setMediaItem(MediaItem.fromUri(url))
                    player.prepare()
                    player.playWhenReady = true
                    deferred.await()
                } finally {
                    player.release()
                }
            } ?: false
        }
    }
}
