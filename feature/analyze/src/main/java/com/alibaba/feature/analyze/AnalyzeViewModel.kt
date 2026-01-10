package com.alibaba.feature.analyze

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.core.common.extractIptvUrls
import com.alibaba.domain.model.Channel
import com.alibaba.domain.repo.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AnalyzeViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AnalyzeUiState())
    val state: StateFlow<AnalyzeUiState> = _state

    fun onInputChange(value: String) {
        _state.update { it.copy(inputText = value, errorMessage = null) }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value, errorMessage = null) }
    }

    fun setScope(scope: SearchScope) {
        _state.update { it.copy(scope = scope) }
    }

    fun toggleStopOnFirstMatch() {
        _state.update { it.copy(stopOnFirstMatch = !it.stopOnFirstMatch) }
    }

    fun clearAll() {
        _state.update {
            it.copy(
                inputText = "",
                query = "",
                loading = false,
                progressText = null,
                reportText = null,
                errorMessage = null
            )
        }
    }

    fun runSearch() {
        val urls = extractIptvUrls(state.value.inputText)
        val query = state.value.query.trim()
        if (urls.isEmpty()) {
            _state.update { it.copy(errorMessage = "Link bulunamadı") }
            return
        }
        if (query.isBlank()) {
            _state.update { it.copy(errorMessage = "Arama metni boş") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(loading = true, progressText = "Başlıyor", reportText = null, errorMessage = null) }

            try {
                val report = withContext(Dispatchers.Default) {
                    val sb = StringBuilder()
                    sb.append("Toplam kaynak: ").append(urls.size).append('\n').append('\n')
                    var foundCount = 0
                    var errorCount = 0
                    var lastIndex = 0

                    for ((index, url) in urls.withIndex()) {
                        lastIndex = index
                        _state.update { it.copy(progressText = "${index + 1}/${urls.size} kontrol ediliyor") }

                        var playlist: com.alibaba.domain.model.Playlist? = null
                        try {
                            playlist = withContext(Dispatchers.IO) {
                                playlistRepository.fetchPlaylist(url)
                            }

                            val matchedChannels = filterMatches(playlist.channels, query, state.value.scope)

                            sb.append("Kaynak ${index + 1}: ").append(url).append('\n')
                            if (matchedChannels.isEmpty()) {
                                sb.append("- Bulunamadı\n\n")
                                continue
                            }

                            foundCount++

                            // Basic season/episode extraction from name patterns like S01E02 or 1x02
                            val seriesStats = computeSeriesStats(matchedChannels)

                            sb.append("- Bulunan: ").append(matchedChannels.size).append('\n')
                            if (seriesStats.isNotBlank()) {
                                sb.append(seriesStats)
                            }

                            // List first N matches for copy
                            val preview = matchedChannels.take(50)
                            for (m in preview) {
                                sb.append("  - ").append(m.name).append(" | ").append(m.url).append('\n')
                            }
                            if (matchedChannels.size > preview.size) {
                                sb.append("  ... +").append(matchedChannels.size - preview.size).append(" daha\n")
                            }
                        } catch (e: Exception) {
                            errorCount++
                            sb.append("Kaynak ${index + 1}: ").append(url).append('\n')
                            sb.append("- HATA: ").append(e.message ?: "Bağlantı hatası").append("\n\n")
                        } finally {
                            // Clear playlist reference to allow garbage collection
                            playlist = null
                            
                            // Suggest GC every 10 playlists to prevent memory buildup
                            if (index % 10 == 9) {
                                System.gc()
                                kotlinx.coroutines.delay(50) // Brief pause to allow GC
                            }
                        }
                        sb.append('\n')

                        // Stop on first match if enabled
                        if (state.value.stopOnFirstMatch) {
                            sb.append("\n⚠️ İlk eşleşmede duruldu (${urls.size - index - 1} kaynak kontrol edilmedi)\n")
                            break
                        }
                    }

                    // Add summary
                    sb.append("\n═══════════════════════════\n")
                    sb.append("📊 ÖZET:\n")
                    sb.append("✓ Başarılı: ").append(foundCount).append(" kaynak\n")
                    sb.append("✗ Hatalı: ").append(errorCount).append(" kaynak\n")
                    sb.append("📝 Kontrol edilen: ").append(lastIndex + 1).append("/").append(urls.size).append("\n")

                    sb.toString()
                }

                _state.update { it.copy(loading = false, progressText = null, reportText = report) }
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, progressText = null, errorMessage = t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    private fun filterMatches(channels: List<Channel>, query: String, scope: SearchScope): List<Channel> {
        val q = query.lowercase()
        return channels.asSequence()
            .filter { ch ->
                val name = ch.name.lowercase()
                val group = ch.group?.lowercase()

                val scopeOk = when (scope) {
                    SearchScope.ALL -> true
                    SearchScope.CHANNEL -> group?.contains("live") == true || group?.contains("canal") == true || group?.contains("channel") == true
                    SearchScope.MOVIE -> group?.contains("movie") == true || group?.contains("film") == true
                    SearchScope.SERIES -> group?.contains("series") == true || group?.contains("dizi") == true
                }

                scopeOk && name.contains(q)
            }
            .distinctBy { it.url }
            .toList()
    }

    private fun computeSeriesStats(matches: List<Channel>): String {
        // Try to infer seasons/episodes from names.
        // Patterns supported: S01E02, S1E2, 1x02
        val seasonEpisode = Regex("(?i)(?:s(\\d{1,2})e(\\d{1,3})|(\\d{1,2})x(\\d{1,3}))")

        val seasonToEpisodes = linkedMapOf<Int, MutableSet<Int>>()
        for (m in matches) {
            val hit = seasonEpisode.find(m.name) ?: continue
            val s = hit.groups[1]?.value ?: hit.groups[3]?.value
            val e = hit.groups[2]?.value ?: hit.groups[4]?.value
            val season = s?.toIntOrNull() ?: continue
            val ep = e?.toIntOrNull() ?: continue
            seasonToEpisodes.getOrPut(season) { linkedSetOf() }.add(ep)
        }

        if (seasonToEpisodes.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("- Sezon/Bölüm analizi:\n")
        for ((season, eps) in seasonToEpisodes) {
            sb.append("  - ").append(season).append(". sezon: ").append(eps.size).append(" bölüm\n")
        }
        sb.append('\n')
        return sb.toString()
    }
}
