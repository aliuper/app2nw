package com.alibaba.feature.analyze

enum class SearchScope {
    ALL,
    CHANNEL,
    MOVIE,
    SERIES
}

data class AnalyzeUiState(
    val inputText: String = "",
    val query: String = "",
    val scope: SearchScope = SearchScope.ALL,
    val stopOnFirstMatch: Boolean = false,
    val loading: Boolean = false,
    val progressText: String? = null,
    val reportText: String? = null,
    val errorMessage: String? = null
)
