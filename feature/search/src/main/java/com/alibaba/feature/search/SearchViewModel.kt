package com.alibaba.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.domain.model.SearchResult
import com.alibaba.domain.repo.URLScanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchState(
    val query: String = "filename:\"get.php?username\"",
    val maxResults: Int = 500,
    val searching: Boolean = false,
    val allResults: List<SearchResult> = emptyList(),
    val results: List<SearchResult> = emptyList(),
    val selectedUrls: Set<String> = emptySet(),
    val totalAvailable: Int = 0,
    val errorMessage: String? = null,
    val onlyTurkey: Boolean = false,
    val onlyPanelUrls: Boolean = false,
    val keywordFilter: String = ""
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val urlScanRepository: URLScanRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun setMaxResults(max: Int) {
        _state.update { it.copy(maxResults = max.coerceIn(1, 10000)) }
    }

    fun toggleOnlyTurkey() {
        _state.update { s ->
            val next = s.copy(onlyTurkey = !s.onlyTurkey)
            applyFilters(next)
        }
    }

    fun toggleOnlyPanelUrls() {
        _state.update { s ->
            val next = s.copy(onlyPanelUrls = !s.onlyPanelUrls)
            applyFilters(next)
        }
    }

    fun setKeywordFilter(value: String) {
        _state.update { s ->
            val next = s.copy(keywordFilter = value)
            applyFilters(next)
        }
    }

    fun toggleUrlSelection(url: String) {
        _state.update { s ->
            val selected = if (url in s.selectedUrls) {
                s.selectedUrls - url
            } else {
                s.selectedUrls + url
            }
            s.copy(selectedUrls = selected)
        }
    }

    fun selectAll() {
        _state.update { s ->
            s.copy(selectedUrls = s.results.map { it.url }.toSet())
        }
    }

    fun deselectAll() {
        _state.update { it.copy(selectedUrls = emptySet()) }
    }

    fun search() {
        val query = state.value.query.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(errorMessage = "Sorgu boş olamaz") }
            return
        }

        viewModelScope.launch {
            _state.update {
                it.copy(
                    searching = true,
                    errorMessage = null,
                    allResults = emptyList(),
                    results = emptyList(),
                    selectedUrls = emptySet()
                )
            }
            
            try {
                val response = urlScanRepository.search(query, state.value.maxResults)

                _state.update { s ->
                    val next = s.copy(
                        searching = false,
                        allResults = response.results,
                        totalAvailable = response.total
                    )
                    val filtered = filterAndSort(next.allResults, next)
                    next.copy(
                        results = filtered,
                        selectedUrls = filtered.map { it.url }.toSet()
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, errorMessage = "Hata: ${e.message}") }
            }
        }
    }

    fun getSelectedUrlsText(): String {
        return state.value.selectedUrls.joinToString("\n")
    }

    private fun applyFilters(next: SearchState): SearchState {
        val filtered = filterAndSort(next.allResults, next)
        val visibleSet = filtered.map { it.url }.toSet()
        val selected = next.selectedUrls.intersect(visibleSet)
        return next.copy(results = filtered, selectedUrls = selected)
    }

    private fun filterAndSort(all: List<SearchResult>, s: SearchState): List<SearchResult> {
        val keywords = s.keywordFilter
            .trim()
            .lowercase()
            .split(' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return all.asSequence()
            .filter { r ->
                if (!s.onlyTurkey) return@filter true
                r.country.equals("TR", ignoreCase = true) || r.country.equals("TURKEY", ignoreCase = true) ||
                    containsTurkishHint(r)
            }
            .filter { r ->
                if (!s.onlyPanelUrls) return@filter true
                val u = r.url.lowercase()
                u.contains("get.php") || u.contains("player_api.php")
            }
            .filter { r ->
                if (keywords.isEmpty()) return@filter true
                val hay = (r.url + " " + r.domain + " " + r.title).lowercase()
                keywords.all { k -> hay.contains(k) }
            }
            .sortedWith(
                compareByDescending<SearchResult> { it.country.equals("TR", ignoreCase = true) }
                    .thenByDescending { scoreUrl(it.url) }
                    .thenByDescending { statusScore(it.status) }
            )
            .toList()
    }

    private fun scoreUrl(url: String): Int {
        val u = url.lowercase()
        return when {
            u.contains("player_api.php") -> 3
            u.contains("get.php") -> 2
            u.contains(".m3u") || u.contains(".m3u8") -> 1
            else -> 0
        }
    }

    private fun statusScore(status: String): Int {
        val code = status.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: return 0
        return when {
            code in 200..299 -> 3
            code in 300..399 -> 2
            code in 400..499 -> 1
            else -> 0
        }
    }

    private fun containsTurkishHint(r: SearchResult): Boolean {
        val hay = (r.url + " " + r.domain + " " + r.title).lowercase()
        return hay.contains("türk") || hay.contains("türkiye") ||
            Regex("\\btr\\b").containsMatchIn(hay) ||
            Regex("\\bturk\\b").containsMatchIn(hay) ||
            Regex("\\bturkiye\\b").containsMatchIn(hay) ||
            hay.contains("iptvtr") || hay.contains("turkish")
    }
}
