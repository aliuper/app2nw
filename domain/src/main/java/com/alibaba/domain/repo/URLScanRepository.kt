package com.alibaba.domain.repo

import com.alibaba.domain.model.SearchResponse

interface URLScanRepository {
    suspend fun search(query: String, maxResults: Int): SearchResponse
}
