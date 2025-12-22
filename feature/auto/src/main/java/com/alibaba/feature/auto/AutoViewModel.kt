package com.alibaba.feature.auto

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.core.common.PlaylistTextFormatter
import com.alibaba.core.common.extractIptvUrls
import com.alibaba.core.common.groupCountryCode
import com.alibaba.core.common.isAdultGroup
import com.alibaba.domain.model.OutputFormat
import com.alibaba.domain.model.Playlist
import com.alibaba.domain.model.Channel
import com.alibaba.domain.repo.PlaylistRepository
import com.alibaba.domain.service.OutputSaver
import com.alibaba.domain.service.StreamTester
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import javax.inject.Inject

@HiltViewModel
class AutoViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
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
            _state.update {
                it.copy(
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
                    reportText = null
                )
            }

            try {
                val items = state.value.extractedUrls.toMutableList()
                val mergedChannels = ArrayList<Channel>(16_384)
                var mergedEndDate: String? = null

                val usedGroupNames = linkedMapOf<String, Int>()
                val renameSamples = ArrayList<String>(16)

                val working = ArrayList<String>(urls.size)
                val failing = ArrayList<String>(urls.size)

                val folderUriString = state.value.outputFolderUriString

                for ((index, url) in urls.withIndex()) {
                    val header = "${index + 1}/${urls.size}"
                    setProgress(percent = ((index * 100) / urls.size).coerceIn(0, 99), step = "${header} - Link indiriliyor")
                    items[index] = items[index].copy(status = "İndiriliyor", success = null, testedStreams = 0)
                    _state.update { it.copy(extractedUrls = items.toList()) }

                    try {
                        items[index] = items[index].copy(status = "İndirildi", success = null)
                        _state.update { it.copy(extractedUrls = items.toList()) }

                        setProgress(percent = ((index * 100) / urls.size).coerceIn(0, 99), step = "${header} - Stream testi")
                        val playlist = playlistRepository.fetchPlaylist(url)
                        val test = runStreamTestDetailed(
                            playlist = playlist,
                            onTestUpdate = { tested, total ->
                                items[index] = items[index].copy(status = "Test ${tested}/${total}", success = null, testedStreams = tested)
                                _state.update { it.copy(extractedUrls = items.toList()) }
                            }
                        )
                        val ok = test.first
                        val testedCount = test.second
                        if (!ok) {
                            items[index] = items[index].copy(status = "Stream testi başarısız", success = false, testedStreams = testedCount)
                            failing += url
                            _state.update { it.copy(extractedUrls = items.toList()) }
                            continue
                        }

                        val filtered = filterPlaylistByCountries(playlist, countries)
                        if (filtered.channels.isEmpty()) {
                            items[index] = items[index].copy(
                                status = "Link aktif ama seçilen ülke(ler) için kanal yok",
                                success = false,
                                testedStreams = testedCount
                            )
                            failing += url
                            _state.update { it.copy(extractedUrls = items.toList()) }
                            continue
                        }

                        items[index] = items[index].copy(status = "OK", success = true, testedStreams = testedCount)
                        working += url
                        _state.update { it.copy(extractedUrls = items.toList()) }

                        if (state.value.mergeIntoSingle) {
                            val renamed = mergeWithBackupNames(
                                channels = filtered.channels,
                                usedGroupNames = usedGroupNames,
                                renameSamples = renameSamples
                            )
                            mergedChannels += renamed
                            mergedEndDate = mergedEndDate ?: filtered.endDate
                            items[index] = items[index].copy(status = "Birleştirildi", success = true, testedStreams = testedCount)
                            _state.update { it.copy(extractedUrls = items.toList()) }
                        } else {
                            items[index] = items[index].copy(status = "Kaydediliyor", success = true, testedStreams = testedCount)
                            _state.update { it.copy(extractedUrls = items.toList()) }

                            val content = withContext(Dispatchers.Default) {
                                val groups = filtered.channels.map { it.group ?: "Ungrouped" }.toSet()
                                PlaylistTextFormatter.format(filtered, groups, state.value.outputFormat)
                            }

                            val saved = if (folderUriString.isNullOrBlank()) {
                                outputSaver.saveToDownloads(
                                    sourceUrl = url,
                                    format = state.value.outputFormat,
                                    content = content,
                                    maybeEndDate = filtered.endDate
                                )
                            } else {
                                outputSaver.saveToFolder(
                                    folderUriString = folderUriString,
                                    sourceUrl = url,
                                    format = state.value.outputFormat,
                                    content = content,
                                    maybeEndDate = filtered.endDate
                                )
                            }
                            _state.update { s ->
                                s.copy(savedFiles = s.savedFiles + SavedFileItem(saved.displayName, saved.uriString))
                            }
                            items[index] = items[index].copy(status = "Kaydedildi", success = true, testedStreams = testedCount)
                            _state.update { it.copy(extractedUrls = items.toList()) }
                        }
                    } catch (t: Throwable) {
                        items[index] = items[index].copy(status = t.message ?: "Hata", success = false)
                        failing += url
                    }

                    _state.update { it.copy(extractedUrls = items.toList()) }
                }

                if (state.value.mergeIntoSingle) {
                    if (renameSamples.isNotEmpty()) {
                        val preview = renameSamples.take(10).joinToString("\n")
                        _state.update {
                            it.copy(
                                mergeRenameWarning = "Aynı isimli grup(lar) bulundu. Çakışan gruplar otomatik olarak Yedek 1..N şeklinde adlandırıldı:\n${preview}"
                            )
                        }
                    }

                    setProgress(percent = 95, step = "Çıktı hazırlanıyor")
                    val merged = Playlist(channels = mergedChannels, endDate = mergedEndDate)
                    val content = withContext(Dispatchers.Default) {
                        val groups = merged.channels.map { it.group ?: "Ungrouped" }.toSet()
                        PlaylistTextFormatter.format(merged, groups, state.value.outputFormat)
                    }

                    val saved = if (folderUriString.isNullOrBlank()) {
                        outputSaver.saveToDownloads(
                            sourceUrl = "alibaba",
                            format = state.value.outputFormat,
                            content = content,
                            maybeEndDate = merged.endDate
                        )
                    } else {
                        outputSaver.saveToFolder(
                            folderUriString = folderUriString,
                            sourceUrl = "alibaba",
                            format = state.value.outputFormat,
                            content = content,
                            maybeEndDate = merged.endDate
                        )
                    }

                    _state.update {
                        it.copy(
                            outputPreview = content.take(2000),
                            savedFiles = it.savedFiles + SavedFileItem(saved.displayName, saved.uriString)
                        )
                    }
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

                _state.update {
                    it.copy(
                        loading = false,
                        progressPercent = 100,
                        progressStep = null,
                        etaSeconds = null,
                        reportText = report,
                        workingUrls = working.toList(),
                        failingUrls = failing.toList(),
                        lastRunSaved = true
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        progressPercent = 0,
                        progressStep = null,
                        etaSeconds = null,
                        errorMessage = t.message ?: "Hata oluştu",
                        workingUrls = emptyList(),
                        failingUrls = emptyList(),
                        lastRunSaved = false
                    )
                }
            }
        }
    }

    private fun filterPlaylistByCountries(playlist: Playlist, countries: Set<String>): Playlist {
        val channels = playlist.channels.filter { c ->
            val group = c.group
            if (isAdultGroup(group)) return@filter false
            val code = groupCountryCode(group) ?: return@filter false
            code in countries
        }
        return Playlist(channels = channels, endDate = playlist.endDate)
    }

    private suspend fun runStreamTestDetailed(
        playlist: Playlist,
        onTestUpdate: (tested: Int, total: Int) -> Unit
    ): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        val candidates = playlist.channels
            .asSequence()
            .map { it.url }
            .distinct()
            .toList()

        if (candidates.isEmpty()) return@withContext false to 0

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
            if (streamTester.isPlayable(url)) return@withContext true to tested
        }

        false to tested
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
