package com.alibaba.feature.auto

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.alibaba.core.common.OutputFormatDetector
import com.alibaba.core.common.PlaylistTextFormatter
import com.alibaba.core.common.isGroupInCountries
import com.alibaba.domain.model.Channel
import com.alibaba.domain.model.OutputFormat
import com.alibaba.domain.model.Playlist
import com.alibaba.domain.repo.PlaylistRepository
import com.alibaba.domain.service.OutputSaver
import com.alibaba.domain.service.StreamTester
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

@HiltWorker
class AutoRunWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val playlistRepository: PlaylistRepository,
    private val streamTester: StreamTester,
    private val outputSaver: OutputSaver
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val urls = inputData.getStringArray(KEY_URLS)?.toList().orEmpty()
        val countries = inputData.getStringArray(KEY_COUNTRIES)?.toSet().orEmpty()
        val mergeIntoSingle = inputData.getBoolean(KEY_MERGE_INTO_SINGLE, true)
        val folderUriString = inputData.getString(KEY_FOLDER_URI)
        val autoDetectFormat = inputData.getBoolean(KEY_AUTO_DETECT_FORMAT, true)
        val outputFormatOrdinal = inputData.getInt(KEY_OUTPUT_FORMAT, OutputFormat.M3U8.ordinal)
        val chosenOutputFormat = OutputFormat.values().getOrElse(outputFormatOrdinal) { OutputFormat.M3U8 }

        if (urls.isEmpty() || countries.isEmpty()) {
            return Result.failure(
                Data.Builder().putString(KEY_ERROR, "Link veya ülke listesi boş").build()
            )
        }

        val firstFgOk = runCatching { setForeground(createForegroundInfo(0, "Başlıyor")) }.isSuccess
        if (!firstFgOk) {
            return Result.failure(
                Data.Builder().putString(KEY_ERROR, "Arka plan bildirimi/foreground izni yok (Android 14+ için FOREGROUND_SERVICE_DATA_SYNC gerekebilir)").build()
            )
        }

        val mergedChannels = ArrayList<Channel>(16_384)
        var mergedEndDate: String? = null

        val usedGroupNames = linkedMapOf<String, Int>()
        val renameSamples = ArrayList<String>(16)

        val working = ArrayList<String>(urls.size)
        val failing = ArrayList<String>(urls.size)
        val savedNames = ArrayList<String>(urls.size + 1)
        val savedUris = ArrayList<String>(urls.size + 1)

        for ((index, url) in urls.withIndex()) {
            val header = "${index + 1}/${urls.size}"
            setProgress(header, index, "İndiriliyor", 0, 0, null)
            val fgOk = runCatching {
                setForeground(createForegroundInfo(((index * 100) / urls.size).coerceIn(0, 99), "$header - İndiriliyor"))
            }.isSuccess
            if (!fgOk) {
                return Result.failure(
                    Data.Builder().putString(KEY_ERROR, "Foreground başlatılamadı (bildirim/izin sorunu)").build()
                )
            }

            try {
                val playlist = playlistRepository.fetchPlaylist(url)

                setProgress(header, index, "Stream testi", 0, 0, null)
                val (ok, testedCount, totalCount) = runStreamTestDetailed(playlist) { tested, total ->
                    setProgress(header, index, "Test ${tested}/${total}", tested, total, null)
                }

                if (!ok) {
                    failing += url
                    setProgress(header, index, "Stream testi başarısız", testedCount, totalCount, false)
                    continue
                }

                val filtered = filterPlaylistByCountries(playlist, countries)
                if (filtered.channels.isEmpty()) {
                    failing += url
                    setProgress(header, index, "Link aktif ama seçilen ülke(ler) için kanal yok", testedCount, totalCount, false)
                    continue
                }

                working += url

                if (mergeIntoSingle) {
                    val renamed = mergeWithBackupNames(
                        channels = filtered.channels,
                        usedGroupNames = usedGroupNames,
                        renameSamples = renameSamples
                    )
                    mergedChannels += renamed
                    mergedEndDate = mergedEndDate ?: filtered.endDate
                    setProgress(header, index, "Birleştirildi", testedCount, totalCount, true)
                } else {
                    setProgress(header, index, "Kaydediliyor", testedCount, totalCount, true)
                    val format = if (autoDetectFormat) OutputFormatDetector.detect(filtered) else chosenOutputFormat
                    val content = withContext(Dispatchers.Default) {
                        val groups = filtered.channels.map { it.group ?: "Ungrouped" }.toSet()
                        PlaylistTextFormatter.format(filtered, groups, format)
                    }

                    val saved = if (folderUriString.isNullOrBlank()) {
                        outputSaver.saveToDownloads(
                            sourceUrl = url,
                            format = format,
                            content = content,
                            maybeEndDate = filtered.endDate
                        )
                    } else {
                        outputSaver.saveToFolder(
                            folderUriString = folderUriString,
                            sourceUrl = url,
                            format = format,
                            content = content,
                            maybeEndDate = filtered.endDate
                        )
                    }

                    savedNames += saved.displayName
                    savedUris += saved.uriString
                    setProgress(header, index, "Kaydedildi", testedCount, totalCount, true)
                }
            } catch (t: Throwable) {
                failing += url
                setProgress(header, index, t.message ?: "Hata", 0, 0, false)
            }
        }

        if (mergeIntoSingle) {
            val fgOk = runCatching { setForeground(createForegroundInfo(95, "Çıktı hazırlanıyor")) }.isSuccess
            if (!fgOk) {
                return Result.failure(
                    Data.Builder().putString(KEY_ERROR, "Foreground başlatılamadı (bildirim/izin sorunu)").build()
                )
            }
            val merged = Playlist(channels = mergedChannels, endDate = mergedEndDate)
            val format = if (autoDetectFormat) OutputFormatDetector.detect(merged) else chosenOutputFormat
            val content = withContext(Dispatchers.Default) {
                val groups = merged.channels.map { it.group ?: "Ungrouped" }.toSet()
                PlaylistTextFormatter.format(merged, groups, format)
            }

            val saved = if (folderUriString.isNullOrBlank()) {
                outputSaver.saveToDownloads(
                    sourceUrl = "alibaba",
                    format = format,
                    content = content,
                    maybeEndDate = merged.endDate
                )
            } else {
                outputSaver.saveToFolder(
                    folderUriString = folderUriString,
                    sourceUrl = "alibaba",
                    format = format,
                    content = content,
                    maybeEndDate = merged.endDate
                )
            }

            savedNames += saved.displayName
            savedUris += saved.uriString
        }

        val report = buildString {
            append("Bitti. Çalışan: ")
            append(working.size)
            append(" | Çalışmayan: ")
            append(failing.size)
            if (!folderUriString.isNullOrBlank()) {
                append(" | Klasör: ")
                append(folderUriString)
            } else {
                append(" | Klasör: Download/IPTV")
            }
        }

        val output = Data.Builder()
            .putString(KEY_REPORT, report)
            .putStringArray(KEY_WORKING_URLS, working.toTypedArray())
            .putStringArray(KEY_FAILING_URLS, failing.toTypedArray())
            .putStringArray(KEY_SAVED_NAMES, savedNames.toTypedArray())
            .putStringArray(KEY_SAVED_URIS, savedUris.toTypedArray())
            .build()

        return Result.success(output)
    }

    private fun setProgress(
        header: String,
        index: Int,
        status: String,
        tested: Int,
        total: Int,
        success: Boolean?
    ) {
        val percent = ((index * 100) / maxOf(1, (inputData.getStringArray(KEY_URLS)?.size ?: 1))).coerceIn(0, 99)
        setProgressAsync(
            Data.Builder()
                .putInt(P_PERCENT, percent)
                .putString(P_STEP, "$header - $status")
                .putInt(P_INDEX, index)
                .putString(P_STATUS, status)
                .putInt(P_TESTED, tested)
                .putInt(P_TOTAL, total)
                .putBoolean(P_HAS_SUCCESS, success != null)
                .putBoolean(P_SUCCESS, success == true)
                .build()
        )
    }

    private fun createForegroundInfo(percent: Int, text: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, "auto_run")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Otomatik Test")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .build()

        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(1001, notification)
        }
    }

    private fun filterPlaylistByCountries(playlist: Playlist, countries: Set<String>): Playlist {
        val channels = playlist.channels.filter { c ->
            val group = c.group
            if (group.isNullOrBlank()) return@filter false
            isGroupInCountries(group, countries)
        }
        return Playlist(channels = channels, endDate = playlist.endDate)
    }

    private suspend fun runStreamTestDetailed(
        playlist: Playlist,
        onTestUpdate: (tested: Int, total: Int) -> Unit
    ): Triple<Boolean, Int, Int> = withContext(Dispatchers.IO) {
        val candidates = playlist.channels
            .asSequence()
            .map { it.url }
            .distinct()
            .toList()

        if (candidates.isEmpty()) return@withContext Triple(false, 0, 0)

        val max = 10
        val sample = if (candidates.size <= max) {
            candidates
        } else {
            candidates.shuffled(Random(System.currentTimeMillis())).take(max)
        }

        val total = sample.size
        var tested = 0
        for (url in sample) {
            tested += 1
            onTestUpdate(tested, total)
            if (streamTester.isPlayable(url)) return@withContext Triple(true, tested, total)
        }

        Triple(false, tested, total)
    }

    private fun mergeWithBackupNames(
        channels: List<Channel>,
        usedGroupNames: MutableMap<String, Int>,
        renameSamples: MutableList<String>
    ): List<Channel> {
        if (channels.isEmpty()) return emptyList()

        val groupMapping = LinkedHashMap<String, String>()
        for (group in channels.asSequence().map { it.group ?: "Ungrouped" }.distinct()) {
            val currentIndex = usedGroupNames[group]
            if (currentIndex == null) {
                usedGroupNames[group] = 0
                groupMapping[group] = group
            } else {
                val next = currentIndex + 1
                usedGroupNames[group] = next
                val newGroup = "${group} Yedek ${next}"
                if (renameSamples.size < 25) {
                    renameSamples += "- ${group} -> ${newGroup}"
                }
                groupMapping[group] = newGroup
            }
        }

        return channels.map { c ->
            val g = c.group ?: "Ungrouped"
            val newG = groupMapping[g] ?: g
            if (newG == g) c else c.copy(group = newG)
        }
    }

    companion object {
        const val KEY_URLS = "urls"
        const val KEY_COUNTRIES = "countries"
        const val KEY_MERGE_INTO_SINGLE = "mergeIntoSingle"
        const val KEY_FOLDER_URI = "folderUri"
        const val KEY_AUTO_DETECT_FORMAT = "autoDetectFormat"
        const val KEY_OUTPUT_FORMAT = "outputFormat"

        const val KEY_ERROR = "error"

        const val KEY_REPORT = "report"
        const val KEY_WORKING_URLS = "workingUrls"
        const val KEY_FAILING_URLS = "failingUrls"
        const val KEY_SAVED_NAMES = "savedNames"
        const val KEY_SAVED_URIS = "savedUris"

        const val P_PERCENT = "pPercent"
        const val P_STEP = "pStep"
        const val P_INDEX = "pIndex"
        const val P_STATUS = "pStatus"
        const val P_TESTED = "pTested"
        const val P_TOTAL = "pTotal"
        const val P_HAS_SUCCESS = "pHasSuccess"
        const val P_SUCCESS = "pSuccess"
    }
}
