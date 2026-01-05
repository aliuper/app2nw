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
    val results: List<SearchResult> = emptyList(),
    val selectedUrls: Set<String> = emptySet(),
    val totalAvailable: Int = 0,
    val errorMessage: String? = null
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
            _state.update { it.copy(searching = true, errorMessage = null, results = emptyList(), selectedUrls = emptySet()) }
            
            try {
                val response = urlScanRepository.search(query, state.value.maxResults)
                
                _state.update { s ->
                    s.copy(
                        searching = false,
                        results = response.results,
                        totalAvailable = response.total,
                        selectedUrls = response.results.map { it.url }.toSet() // Auto-select all
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
}
