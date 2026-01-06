package com.alibaba.feature.auto

import android.os.SystemClock
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.core.common.PlaylistTextFormatter
import com.alibaba.core.common.OutputFormatDetector
import com.alibaba.core.common.isGroupInCountries
import com.alibaba.core.common.extractIptvUrls
import com.alibaba.core.common.isAdultGroup
import com.alibaba.domain.model.OutputDelivery
import com.alibaba.domain.model.OutputFormat
import com.alibaba.domain.model.Playlist
import com.alibaba.domain.model.Channel
import com.alibaba.domain.repo.PlaylistRepository
import com.alibaba.domain.repo.SettingsRepository
import com.alibaba.domain.service.OutputSaver
import com.alibaba.domain.service.StreamTester
import com.alibaba.data.service.StreamTestService
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random
import javax.inject.Inject

@HiltViewModel
class AutoViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
    private val streamTester: StreamTester,
    private val outputSaver: OutputSaver
) : ViewModel() {

    private val _state = MutableStateFlow(AutoUiState())
    val state: StateFlow<AutoUiState> = _state

    private var testService: StreamTestService? = null
    private var progressStartMs: Long? = null
    private val completedUrlDurationsMs = ArrayList<Long>(64)
    private var lastStreamUiUpdateMs: Long = 0L

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _state.update { st ->
                    val updated = st.copy(
                        enableCountryFiltering = s.enableCountryFiltering,
                        outputDelivery = s.outputDelivery
                    )
                    val max = maxStepIndex(updated)
                    updated.copy(step = updated.step.coerceIn(0, max))
                }
            }
        }
    }

    fun onInputChange(value: String) {
        _state.update { it.copy(inputText = value, errorMessage = null) }
    }

    fun setUrls(urls: String) {
        _state.update { it.copy(inputText = urls, errorMessage = null) }
    }

    fun nextStep() {
        val s = state.value
        if (s.loading) return

        if (s.outputDelivery == OutputDelivery.LINKS) {
            val max = maxStepIndex(s)
            val next = (s.step + 1).coerceAtMost(max)
            _state.update { it.copy(step = next, errorMessage = null) }

            val shouldStart = when {
                !s.enableCountryFiltering && next >= 1 -> true
                s.enableCountryFiltering && s.step == 1 && next >= 2 -> true
                else -> false
            }
            if (shouldStart) run()
            return
        }

        _state.update { st ->
            val max = maxStepIndex(st)
            st.copy(step = (st.step + 1).coerceAtMost(max), errorMessage = null)
        }
    }

    fun prevStep() {
        _state.update { s -> s.copy(step = (s.step - 1).coerceAtLeast(0), errorMessage = null) }
    }

    private fun maxStepIndex(s: AutoUiState): Int {
        return if (s.outputDelivery == OutputDelivery.LINKS) {
            // Steps in LINKS mode:
            // 0: Link/Metin
            // 1: (optional) Ülke Seç
            // last: Başlat
            if (s.enableCountryFiltering) 2 else 1
        } else {
            if (s.enableCountryFiltering) 3 else 2
        }
    }

    fun extract() {
        val urls = extractIptvUrls(state.value.inputText)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        _state.update {
            it.copy(
                extractedUrls = urls.map { u -> UrlItem(url = u) },
                errorMessage = if (urls.isEmpty()) "Link bulunamadı" else null,
                loading = false,
                progressPercent = 0,
                progressStep = null,
                etaSeconds = null,
                outputPreview = null,
                workingUrls = emptyList(),
                selectedWorkingUrls = emptySet(),
                failingUrls = emptyList(),
                savedFiles = emptyList()
            )
        }
    }

    fun toggleCountry(code: String, enabled: Boolean) {
        _state.update { s ->
            val updated = if (enabled) s.selectedCountries + code else s.selectedCountries - code
            s.copy(selectedCountries = updated)
        }
    }

    fun setMergeIntoSingle(value: Boolean) {
        _state.update { it.copy(mergeIntoSingle = value) }
    }

    fun setAutoDetectFormat(value: Boolean) {
        _state.update { it.copy(autoDetectFormat = value) }
    }

    fun setOutputFormat(format: OutputFormat) {
        _state.update { it.copy(outputFormat = format) }
    }

    fun setOutputFolder(uriString: String?) {
        _state.update { it.copy(outputFolderUriString = uriString) }
    }

    fun clearAll() {
        _state.update { s ->
            s.copy(
                step = 0,
                inputText = "",
                extractedUrls = emptyList(),
                loading = false,
                progressPercent = 0,
                progressStep = null,
                etaSeconds = null,
                errorMessage = null,
                reportText = null,
                workingUrls = emptyList(),
                selectedWorkingUrls = emptySet(),
                failingUrls = emptyList(),
                lastRunSaved = false,
                outputPreview = null,
                savedFiles = emptyList(),
                mergeRenameWarning = null,
                backgroundWorkId = null
            )
        }
    }

    fun toggleWorkingUrl(url: String, enabled: Boolean) {
        _state.update { s ->
            val updated = if (enabled) s.selectedWorkingUrls + url else s.selectedWorkingUrls - url
            s.copy(selectedWorkingUrls = updated)
        }
    }

    fun run() {
        val urls = state.value.extractedUrls.map { it.url }
        if (urls.isEmpty()) {
            _state.update { it.copy(errorMessage = "Önce linkleri ayıkla") }
            return
        }

        val countries = state.value.selectedCountries

        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            if (settings.enableCountryFiltering && countries.isEmpty()) {
                _state.update { it.copy(errorMessage = "En az 1 ülke seç") }
                return@launch
            }

            StreamTestService.start(appContext)

            progressStartMs = SystemClock.elapsedRealtime()
            completedUrlDurationsMs.clear()
            _state.update { s ->
                s.copy(
                    loading = true,
                    progressPercent = 0,
                    progressStep = "Başlıyor",
                    etaSeconds = null,
                    errorMessage = null,
                    savedFiles = emptyList(),
                    workingUrls = emptyList(),
                    failingUrls = emptyList(),
                    lastRunSaved = false,
                    outputPreview = null,
                    mergeRenameWarning = null,
                    reportText = null,
                    extractedUrls = s.extractedUrls.map { it.copy(status = "Beklemede", success = null, testedStreams = 0) }
                )
            }

            val mergeIntoSingle = state.value.mergeIntoSingle
            val folderUriString = state.value.outputFolderUriString
            val autoDetectFormat = state.value.autoDetectFormat
            val chosenOutputFormat = state.value.outputFormat
            val outputDelivery = settings.outputDelivery

            val mergedChannels = ArrayList<Channel>(16_384)
            var mergedEndDate: String? = null

            val usedGroupNames = linkedMapOf<String, Int>()
            val renameSamples = ArrayList<String>(16)

            // Limit initial capacity to prevent memory bloat
            val working = ArrayList<String>(minOf(urls.size, 200))
            val failing = ArrayList<String>(minOf(urls.size, 200))
            val savedNames = ArrayList<String>(minOf(urls.size + 1, 200))
            val savedUris = ArrayList<String>(minOf(urls.size + 1, 200))

            for ((index, url) in urls.withIndex()) {
                yield() // Prevent ANR by allowing other coroutines to run
                val urlStartMs = SystemClock.elapsedRealtime()
                val header = "${index + 1}/${urls.size}"
                val basePercent = ((index * 100) / maxOf(1, urls.size)).coerceIn(0, 99)
                setProgress(percent = basePercent, step = "$header - İndiriliyor")
                _state.update { s ->
                    val items = s.extractedUrls.toMutableList()
                    if (index in items.indices) {
                        items[index] = items[index].copy(status = "İndiriliyor", success = null, testedStreams = 0)
                    }
                    s.copy(extractedUrls = items)
                }

                try {
                    // Periodic GC hint to prevent memory buildup
                    if ((index + 1) % 5 == 0) {
                        @Suppress("ExplicitGarbageCollectionCall")
                        System.gc()
                        delay(100) // Brief pause for GC
                    }
                    
                    val playlist = playlistRepository.fetchPlaylist(url)
                    
                    // Skip extremely large playlists to prevent OOM
                    if (playlist.channels.size > 50000) {
                        failing += url
                        completedUrlDurationsMs += (SystemClock.elapsedRealtime() - urlStartMs)
                        _state.update { s ->
                            val items = s.extractedUrls.toMutableList()
                            if (index in items.indices) {
                                items[index] = items[index].copy(status = "Playlist çok büyük (>50k)", success = false)
                            }
                            s.copy(extractedUrls = items)
                        }
                        continue
                    }

                    setProgress(percent = (basePercent + 3).coerceAtMost(99), step = "$header - Stream testi")
                    _state.update { s ->
                        val items = s.extractedUrls.toMutableList()
                        if (index in items.indices) {
                            items[index] = items[index].copy(status = "Stream testi", success = null)
                        }
                        s.copy(extractedUrls = items)
                    }

                    val (ok, testedCount, totalCount) = runStreamTestDetailed(playlist) { tested, total ->
                        val now = SystemClock.elapsedRealtime()
                        val shouldUpdate = tested >= total || (now - lastStreamUiUpdateMs) >= 500
                        if (shouldUpdate) {
                            lastStreamUiUpdateMs = now
                            _state.update { s ->
                                val items = s.extractedUrls.toMutableList()
                                if (index in items.indices) {
                                    items[index] = items[index].copy(
                                        status = "Test ${tested}/${total}",
                                        success = null,
                                        testedStreams = tested
                                    )
                                }
                                s.copy(extractedUrls = items)
                            }
                        }
                    }

                    if (!ok) {
                        failing += url
                        completedUrlDurationsMs += (SystemClock.elapsedRealtime() - urlStartMs)
                        _state.update { s ->
                            val items = s.extractedUrls.toMutableList()
                            if (index in items.indices) {
                                items[index] = items[index].copy(status = "Stream testi başarısız", success = false, testedStreams = testedCount)
                            }
                            s.copy(extractedUrls = items)
                        }
                        continue
                    }

                    val filtered = if (settings.enableCountryFiltering) {
                        val p = filterPlaylistByCountries(playlist, countries)
                        if (p.channels.isEmpty()) {
                            failing += url
                            completedUrlDurationsMs += (SystemClock.elapsedRealtime() - urlStartMs)
                            _state.update { s ->
                                val items = s.extractedUrls.toMutableList()
                                if (index in items.indices) {
                                    items[index] = items[index].copy(status = "Seçilen ülke(ler) için kanal yok", success = false, testedStreams = totalCount)
                                }
                                s.copy(extractedUrls = items)
                            }
                            continue
                        }
                        p
                    } else {
                        playlist
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
                        _state.update { s ->
                            val items = s.extractedUrls.toMutableList()
                            if (index in items.indices) {
                                items[index] = items[index].copy(status = "Birleştirildi", success = true, testedStreams = totalCount)
                            }
                            s.copy(extractedUrls = items)
                        }
                        completedUrlDurationsMs += (SystemClock.elapsedRealtime() - urlStartMs)
                    } else {
                        if (outputDelivery == OutputDelivery.FILE) {
                            setProgress(percent = (basePercent + 6).coerceAtMost(99), step = "$header - Kaydediliyor")
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

                            _state.update { s ->
                                val items = s.extractedUrls.toMutableList()
                                if (index in items.indices) {
                                    items[index] = items[index].copy(status = "Kaydedildi", success = true, testedStreams = totalCount)
                                }
                                s.copy(extractedUrls = items)
                            }
                        } else {
                            _state.update { s ->
                                val items = s.extractedUrls.toMutableList()
                                if (index in items.indices) {
                                    items[index] = items[index].copy(status = "Başarılı", success = true, testedStreams = totalCount)
                                }
                                s.copy(extractedUrls = items)
                            }
                        }
                        completedUrlDurationsMs += (SystemClock.elapsedRealtime() - urlStartMs)
                    }
                } catch (e: OutOfMemoryError) {
                    // Critical: Out of memory - force cleanup
                    failing += url
                    completedUrlDurationsMs += (SystemClock.elapsedRealtime() - urlStartMs)
                    _state.update { s ->
                        val items = s.extractedUrls.toMutableList()
                        if (index in items.indices) {
                            items[index] = items[index].copy(status = "Bellek yetersiz - temizleniyor", success = false)
                        }
                        s.copy(extractedUrls = items)
                    }
                    
                    // Aggressive memory cleanup
                    usedGroupNames.clear()
                    renameSamples.clear()
                    @Suppress("ExplicitGarbageCollectionCall")
                    System.gc()
                    delay(2000) // Wait for GC to complete
                    continue
                } catch (e: Exception) {
                    failing += url
                    completedUrlDurationsMs += (SystemClock.elapsedRealtime() - urlStartMs)
                    _state.update { s ->
                        val items = s.extractedUrls.toMutableList()
                        if (index in items.indices) {
                            items[index] = items[index].copy(status = "Hata: ${e.message}", success = false)
                        }
                        s.copy(extractedUrls = items)
                    }
                    continue
                }
            }

            if (mergeIntoSingle && outputDelivery == OutputDelivery.FILE) {
                setProgress(percent = 95, step = "Çıktı hazırlanıyor")
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

            val saved = savedNames.zip(savedUris).map { (n, u) -> SavedFileItem(n, u) }
            val outputPreview = if (outputDelivery == OutputDelivery.LINKS) {
                working.joinToString(separator = "\n")
            } else {
                null
            }

            _state.update {
                it.copy(
                    loading = false,
                    progressPercent = 100,
                    progressStep = null,
                    etaSeconds = null,
                    reportText = report,
                    workingUrls = working,
                    selectedWorkingUrls = if (outputDelivery == OutputDelivery.LINKS) working.toSet() else it.selectedWorkingUrls,
                    failingUrls = failing,
                    savedFiles = saved,
                    lastRunSaved = true,
                    outputPreview = outputPreview,
                    outputFolderUriString = if (outputDelivery == OutputDelivery.LINKS) null else it.outputFolderUriString
                )
            }

            // Stop service and show completion notification
            StreamTestService.stop(appContext)
        }
    }

    private fun filterPlaylistByCountries(playlist: Playlist, countries: Set<String>): Playlist {
        val channels = playlist.channels.filter { c ->
            val group = c.group
            if (isAdultGroup(group)) return@filter false
            isGroupInCountries(group, countries)
        }
        return Playlist(channels = channels, endDate = playlist.endDate)
    }

    private suspend fun runStreamTestDetailed(
        playlist: Playlist,
        onTestUpdate: (tested: Int, total: Int) -> Unit
    ): Triple<Boolean, Int, Int> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()

        val candidates = playlist.channels
            .asSequence()
            .filter { c ->
                if (!settings.skipAdultGroups) return@filter true
                !isAdultGroup(c.group)
            }
            .map { it.url }
            .distinct()
            .toList()

        if (candidates.isEmpty()) return@withContext Triple(false, 0, 0)

        val max = settings.streamTestSampleSize.coerceIn(1, 50)
        val pool = if (settings.shuffleCandidates) candidates.shuffled(Random(System.currentTimeMillis())) else candidates
        val sample = if (pool.size <= max) pool else pool.take(max)

        val total = sample.size
        var tested = 0
        var okCount = 0
        for (url in sample) {
            yield() // Prevent ANR
            tested += 1
            onTestUpdate(tested, total)
            if (streamTester.isPlayable(url, settings.streamTestTimeoutMs)) {
                okCount += 1
                if (okCount >= settings.minPlayableStreamsToPass) {
                    return@withContext Triple(true, tested, total)
                }
            }

            if (settings.delayBetweenStreamTestsMs > 0) {
                delay(settings.delayBetweenStreamTestsMs)
            } else {
                delay(50) // Small delay to prevent ANR even when user sets 0
            }
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
            val originalGroup = c.group ?: "Ungrouped"
            val mapped = groupMapping[originalGroup] ?: originalGroup
            if (mapped == originalGroup) c else c.copy(group = mapped)
        }
    }

    private fun setProgress(percent: Int, step: String?) {
        val now = SystemClock.elapsedRealtime()
        val etaSeconds = if (percent in 1..99 && completedUrlDurationsMs.isNotEmpty()) {
            val avgMs = completedUrlDurationsMs.average().toLong().coerceAtLeast(1)
            val total = state.value.extractedUrls.size
            val remaining = (total - completedUrlDurationsMs.size).coerceAtLeast(0)
            ((avgMs * remaining) / 1000L).coerceAtMost(60 * 60)
        } else {
            val start = progressStartMs
            if (start != null && percent in 1..99) {
                val elapsedMs = (now - start).coerceAtLeast(1)
                val remainingMs = (elapsedMs * (100 - percent)) / percent
                (remainingMs / 1000L).coerceAtMost(60 * 60)
            } else null
        }

        _state.update {
            it.copy(
                progressPercent = percent,
                progressStep = step,
                etaSeconds = etaSeconds
            )
        }

        // Update notification
        try {
            val intent = android.content.Intent(appContext, StreamTestService::class.java)
            intent.putExtra("progress_step", step ?: "")
            intent.putExtra("progress_percent", percent)
            intent.putExtra("progress_eta", etaSeconds?.toInt() ?: -1)
            appContext.startService(intent)
        } catch (_: Exception) {
            // Service might not be running
        }
    }

    override fun onCleared() {
        super.onCleared()
        StreamTestService.stop(appContext)
    }
}
