package com.alibaba.feature.compare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.core.common.extractIptvUrls
import com.alibaba.domain.model.PlaylistQuality
import com.alibaba.domain.model.QualityMetrics
import com.alibaba.domain.model.calculateScore
import com.alibaba.domain.repo.PlaylistRepository
import com.alibaba.domain.service.QualityTester
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import javax.inject.Inject

@HiltViewModel
class CompareViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val qualityTester: QualityTester
) : ViewModel() {

    private val _state = MutableStateFlow(CompareUiState())
    val state: StateFlow<CompareUiState> = _state

    fun onInputChange(value: String) {
        _state.update { it.copy(inputText = value, errorMessage = null) }
    }

    fun extractUrls() {
        val urls = extractIptvUrls(state.value.inputText)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        
        _state.update {
            it.copy(
                extractedUrls = urls,
                errorMessage = if (urls.isEmpty()) "Link bulunamadı" else null
            )
        }
    }

    fun startComparison() {
        val urls = state.value.extractedUrls
        if (urls.isEmpty()) {
            _state.update { it.copy(errorMessage = "Önce linkleri ayıkla") }
            return
        }

        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    progressPercent = 0,
                    progressMessage = "Başlıyor...",
                    results = emptyList(),
                    errorMessage = null
                )
            }

            val results = mutableListOf<PlaylistQuality>()

            for ((index, url) in urls.withIndex()) {
                yield()
                val progress = ((index * 100) / urls.size).coerceIn(0, 99)
                _state.update {
                    it.copy(
                        progressPercent = progress,
                        progressMessage = "${index + 1}/${urls.size} - Test ediliyor: $url"
                    )
                }

                // Her link için maksimum 2 dakika (120 saniye) timeout
                val result = withTimeoutOrNull(120_000L) {
                    try {
                        // Fetch playlist
                        val playlist = playlistRepository.fetchPlaylist(url)
                        
                        // Test quality
                        val metrics = qualityTester.testQuality(playlist, sampleSize = 5)
                        val score = metrics.calculateScore()
                        
                        // Count working channels (estimate based on success rate)
                        val workingCount = (playlist.channels.size * metrics.successRate).toInt()

                        PlaylistQuality(
                            url = url,
                            endDate = playlist.endDate,
                            channelCount = playlist.channels.size,
                            workingChannelCount = workingCount,
                            qualityScore = score,
                            metrics = metrics
                        )
                    } catch (e: Exception) {
                        // Test failed
                        null
                    }
                }
                
                // Timeout veya hata durumunda başarısız sonuç ekle
                if (result != null) {
                    results.add(result)
                } else {
                    // Timeout veya hata - başarısız sonuç ekle
                    results.add(
                        PlaylistQuality(
                            url = url,
                            endDate = null,
                            channelCount = 0,
                            workingChannelCount = 0,
                            qualityScore = 0f,
                            metrics = QualityMetrics(
                                avgOpeningSpeed = Long.MAX_VALUE,
                                avgLoadingSpeed = Long.MAX_VALUE,
                                bufferingRate = 1.0f,
                                avgBitrate = 0L,
                                responseTime = Long.MAX_VALUE,
                                successRate = 0.0f
                            )
                        )
                    )
                }
            }

            // Sort by quality score (descending) and assign ranks
            val sortedResults = results
                .sortedByDescending { it.qualityScore }
                .mapIndexed { index, quality ->
                    quality.copy(rank = index + 1)
                }

            _state.update {
                it.copy(
                    loading = false,
                    progressPercent = 100,
                    progressMessage = "Tamamlandı",
                    results = sortedResults
                )
            }
        }
    }

    fun clearAll() {
        _state.update { CompareUiState() }
    }
}

data class CompareUiState(
    val inputText: String = "",
    val extractedUrls: List<String> = emptyList(),
    val loading: Boolean = false,
    val progressPercent: Int = 0,
    val progressMessage: String? = null,
    val results: List<PlaylistQuality> = emptyList(),
    val errorMessage: String? = null
)
