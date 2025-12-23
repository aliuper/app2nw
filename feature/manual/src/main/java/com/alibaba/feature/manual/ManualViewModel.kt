package com.alibaba.feature.manual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.core.common.isAdultGroup
import com.alibaba.core.common.PlaylistTextFormatter
import com.alibaba.core.common.OutputFormatDetector
import com.alibaba.domain.model.OutputFormat
import com.alibaba.domain.model.Playlist
import com.alibaba.domain.repo.PlaylistRepository
import com.alibaba.domain.repo.SettingsRepository
import com.alibaba.domain.service.OutputSaver
import com.alibaba.domain.service.StreamTester
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import android.os.SystemClock
import javax.inject.Inject

@HiltViewModel
class ManualViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
    private val streamTester: StreamTester,
    private val outputSaver: OutputSaver
) : ViewModel() {

    private var lastPlaylist: Playlist? = null
    private var progressStartMs: Long? = null

    private val _state = MutableStateFlow(ManualUiState())
    val state: StateFlow<ManualUiState> = _state

    fun onUrlChange(value: String) {
        _state.update { it.copy(url = value, errorMessage = null) }
    }

    fun onOutputFormatChange(format: OutputFormat) {
        _state.update { it.copy(outputFormat = format) }
    }

    fun onAutoDetectFormatChange(enabled: Boolean) {
        _state.update { it.copy(autoDetectFormat = enabled) }
    }

    fun onToggleGroup(groupName: String, selected: Boolean) {
        _state.update { s ->
            s.copy(
                groups = s.groups.map { item ->
                    if (item.name == groupName) item.copy(selected = selected) else item
                }
            )
        }
    }

    fun analyze() {
        val url = state.value.url.trim()
        if (url.isBlank()) {
            _state.update { it.copy(errorMessage = "Link boş olamaz") }
            return
        }

        viewModelScope.launch {
            progressStartMs = SystemClock.elapsedRealtime()
            _state.update {
                it.copy(
                    loading = true,
                    progressPercent = 0,
                    progressStep = "Playlist indiriliyor",
                    etaSeconds = null,
                    errorMessage = null,
                    outputText = null,
                    groups = emptyList(),
                    streamTestPassed = null,
                    savedDisplayName = null,
                    savedUriString = null
                )
            }
            try {
                val playlist = playlistRepository.fetchPlaylist(url)
                lastPlaylist = playlist

                setProgress(percent = 40, step = "Gruplar hazırlanıyor")

                val groups = withContext(Dispatchers.Default) {
                    playlist.channels
                        .map { it.group ?: "Ungrouped" }
                        .filterNot { isAdultGroup(it) }
                        .groupingBy { it }
                        .eachCount()
                        .toList()
                        .sortedBy { it.first.lowercase() }
                        .map { (name, count) -> GroupItem(name = name, channelCount = count, selected = false) }
                }

                setProgress(percent = 70, step = "Stream testi")

                val playable = runStreamTest(playlist)
                if (!playable) {
                    lastPlaylist = null
                    _state.update {
                        it.copy(
                            loading = false,
                            progressPercent = 0,
                            progressStep = null,
                            etaSeconds = null,
                            groups = emptyList(),
                            streamTestPassed = false,
                            errorMessage = "Stream testi başarısız"
                        )
                    }
                    return@launch
                }

                _state.update {
                    it.copy(
                        loading = false,
                        progressPercent = 100,
                        progressStep = null,
                        etaSeconds = null,
                        groups = groups,
                        streamTestPassed = true
                    )
                }
            } catch (t: Throwable) {
                lastPlaylist = null
                _state.update {
                    it.copy(
                        loading = false,
                        progressPercent = 0,
                        progressStep = null,
                        etaSeconds = null,
                        errorMessage = t.message ?: "Hata oluştu"
                    )
                }
            }
        }
    }

    fun generateOutput() {
        val playlist = lastPlaylist
        if (playlist == null) {
            _state.update { it.copy(errorMessage = "Önce analiz yap") }
            return
        }

        viewModelScope.launch {
            progressStartMs = SystemClock.elapsedRealtime()
            _state.update {
                it.copy(
                    loading = true,
                    progressPercent = 0,
                    progressStep = "Çıktı hazırlanıyor",
                    etaSeconds = null,
                    errorMessage = null,
                    savedDisplayName = null,
                    savedUriString = null
                )
            }
            try {
                val selectedGroups = state.value.groups
                    .filter { it.selected }
                    .map { it.name }
                    .toSet()

                val (text, detectedFormat) = withContext(Dispatchers.Default) {
                    val safeGroups = selectedGroups
                        .asSequence()
                        .filterNot { isAdultGroup(it) }
                        .toSet()

                    val filteredChannels = playlist.channels.filter { c ->
                        val g = c.group ?: "Ungrouped"
                        g in safeGroups
                    }
                    val filteredPlaylist = Playlist(channels = filteredChannels, endDate = playlist.endDate)

                    val format = if (state.value.autoDetectFormat) {
                        OutputFormatDetector.detect(filteredPlaylist)
                    } else {
                        state.value.outputFormat
                    }

                    val content = PlaylistTextFormatter.format(filteredPlaylist, safeGroups, format)
                    content to format
                }

                _state.update {
                    it.copy(
                        loading = false,
                        progressPercent = 100,
                        progressStep = null,
                        etaSeconds = null,
                        outputText = text,
                        outputFormat = detectedFormat
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, progressPercent = 0, progressStep = null, etaSeconds = null, errorMessage = t.message ?: "Hata oluştu") }
            }
        }
    }

    fun saveOutput() {
        val playlist = lastPlaylist
        val text = state.value.outputText
        if (playlist == null || text.isNullOrBlank()) {
            _state.update { it.copy(errorMessage = "Kaydetmeden önce çıktı üret") }
            return
        }

        val url = state.value.url.trim()
        val format = state.value.outputFormat

        viewModelScope.launch {
            progressStartMs = SystemClock.elapsedRealtime()
            _state.update { it.copy(loading = true, progressPercent = 0, progressStep = "Dosya kaydediliyor", etaSeconds = null, errorMessage = null) }
            try {
                val saved = outputSaver.saveToDownloads(
                    sourceUrl = url,
                    format = format,
                    content = text,
                    maybeEndDate = playlist.endDate
                )
                _state.update {
                    it.copy(
                        loading = false,
                        progressPercent = 100,
                        progressStep = null,
                        etaSeconds = null,
                        savedDisplayName = saved.displayName,
                        savedUriString = saved.uriString
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, progressPercent = 0, progressStep = null, etaSeconds = null, errorMessage = t.message ?: "Hata oluştu") }
            }
        }
    }

    private suspend fun runStreamTest(playlist: Playlist): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()

        val candidates = playlist.channels
            .asSequence()
            .filter { c ->
                if (!settings.skipAdultGroups) return@filter true
                !isAdultGroup(c.group ?: "")
            }
            .map { it.url }
            .distinct()
            .toList()

        if (candidates.isEmpty()) return@withContext false

        val max = settings.streamTestSampleSize.coerceIn(1, 50)
        val pool = if (settings.shuffleCandidates) candidates.shuffled(Random(System.currentTimeMillis())) else candidates
        val sample = if (pool.size <= max) pool else pool.take(max)

        var okCount = 0
        for ((i, url) in sample.withIndex()) {
            val percent = 70 + ((i + 1) * 10)
            setProgress(percent = percent.coerceAtMost(95), step = "Stream testi")
            if (streamTester.isPlayable(url, settings.streamTestTimeoutMs)) {
                okCount += 1
                if (okCount >= settings.minPlayableStreamsToPass) return@withContext true
            }

            if (settings.delayBetweenStreamTestsMs > 0) {
                kotlinx.coroutines.delay(settings.delayBetweenStreamTestsMs)
            }
        }

        false
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
