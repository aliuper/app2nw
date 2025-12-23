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
import com.alibaba.domain.model.OutputFormat
import com.alibaba.domain.model.Playlist
import com.alibaba.domain.model.Channel
import com.alibaba.domain.repo.PlaylistRepository
import com.alibaba.domain.repo.SettingsRepository
import com.alibaba.domain.service.OutputSaver
import com.alibaba.domain.service.StreamTester
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

    private var progressStartMs: Long? = null

    fun onInputChange(value: String) {
        _state.update { it.copy(inputText = value, errorMessage = null) }
    }

    fun nextStep() {
        _state.update { s -> s.copy(step = (s.step + 1).coerceAtMost(3), errorMessage = null) }
    }

    fun prevStep() {
        _state.update { s -> s.copy(step = (s.step - 1).coerceAtLeast(0), errorMessage = null) }
    }

    fun extract() {
        val urls = extractIptvUrls(state.value.inputText)
        _state.update {
            it.copy(
                extractedUrls = urls.map { u -> UrlItem(url = u) },
                errorMessage = if (urls.isEmpty()) "Link bulunamadı" else null,
                outputPreview = null,
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

    fun run() {
        val urls = state.value.extractedUrls.map { it.url }
        if (urls.isEmpty()) {
            _state.update { it.copy(errorMessage = "Önce linkleri ayıkla") }
            return
        }

        val countries = state.value.selectedCountries
        if (countries.isEmpty()) {
            _state.update { it.copy(errorMessage = "En az 1 ülke seç") }
            return
        }

        viewModelScope.launch {
            progressStartMs = SystemClock.elapsedRealtime()
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
                    val playlist = playlistRepository.fetchPlaylist(url)

                    setProgress(percent = (basePercent + 3).coerceAtMost(99), step = "$header - Stream testi")
                    _state.update { s ->
                        val items = s.extractedUrls.toMutableList()
                        if (index in items.indices) {
                            items[index] = items[index].copy(status = "Stream testi", success = null)
                        }
                        s.copy(extractedUrls = items)
                    }

                    val (ok, testedCount, totalCount) = runStreamTestDetailed(playlist) { tested, total ->
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

                    if (!ok) {
                        failing += url
                        _state.update { s ->
                            val items = s.extractedUrls.toMutableList()
                            if (index in items.indices) {
                                items[index] = items[index].copy(status = "Stream testi başarısız", success = false, testedStreams = testedCount)
                            }
                            s.copy(extractedUrls = items)
                        }
                        continue
                    }

                    val filtered = filterPlaylistByCountries(playlist, countries)
                    if (filtered.channels.isEmpty()) {
                        failing += url
                        _state.update { s ->
                            val items = s.extractedUrls.toMutableList()
                            if (index in items.indices) {
                                items[index] = items[index].copy(status = "Seçilen ülke(ler) için kanal yok", success = false, testedStreams = totalCount)
                            }
                            s.copy(extractedUrls = items)
                        }
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
                        _state.update { s ->
                            val items = s.extractedUrls.toMutableList()
                            if (index in items.indices) {
                                items[index] = items[index].copy(status = "Birleştirildi", success = true, testedStreams = totalCount)
                            }
                            s.copy(extractedUrls = items)
                        }
                    } else {
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
                    }
                } catch (t: Throwable) {
                    failing += url
                    _state.update { s ->
                        val items = s.extractedUrls.toMutableList()
                        if (index in items.indices) {
                            items[index] = items[index].copy(status = t.message ?: "Hata", success = false, testedStreams = 0)
                        }
                        s.copy(extractedUrls = items)
                    }
                }
            }

            if (mergeIntoSingle) {
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

            _state.update {
                it.copy(
                    loading = false,
                    progressPercent = 100,
                    progressStep = null,
                    etaSeconds = null,
                    reportText = report,
                    workingUrls = working,
                    failingUrls = failing,
                    savedFiles = saved,
                    lastRunSaved = true
                )
            }
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
        val start = progressStartMs
        val now = SystemClock.elapsedRealtime()
        val etaSeconds = if (start != null && percent in 1..99) {
            val elapsedMs = (now - start).coerceAtLeast(1)
            val remainingMs = (elapsedMs * (100 - percent)) / percent
            (remainingMs / 1000L).coerceAtMost(60 * 60)
        } else {
            null
        }

        _state.update {
            it.copy(
                progressPercent = percent,
                progressStep = step,
                etaSeconds = etaSeconds
            )
        }
    }
}
