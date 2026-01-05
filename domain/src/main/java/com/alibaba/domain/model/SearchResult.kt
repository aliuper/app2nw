package com.alibaba.domain.model

data class SearchResult(
    val url: String,
    val domain: String,
    val ip: String,
    val country: String,
    val status: String,
    val title: String
)

data class SearchResponse(
    val results: List<SearchResult>,
    val total: Int,
    val hasMore: Boolean
)
