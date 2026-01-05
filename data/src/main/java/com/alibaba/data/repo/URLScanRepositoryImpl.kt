package com.alibaba.data.repo

import com.alibaba.domain.model.SearchResponse
import com.alibaba.domain.model.SearchResult
import com.alibaba.domain.repo.URLScanRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject

class URLScanRepositoryImpl @Inject constructor(
    private val client: OkHttpClient
) : URLScanRepository {
    
    private val baseUrl = "https://urlscan.io/api/v1/search/"
    
    override suspend fun search(query: String, maxResults: Int): SearchResponse = withContext(Dispatchers.IO) {
        val allResults = mutableListOf<SearchResult>()
        var searchAfter: String? = null
        var totalAvailable = 0
        var hasMore = true
        
        while (allResults.size < maxResults && hasMore) {
            try {
                val url = buildString {
                    append(baseUrl)
                    append("?q=").append(java.net.URLEncoder.encode(query, "UTF-8"))
                    append("&size=100")
                    searchAfter?.let { append("&search_after=").append(it) }
                }
                
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (IPTV Search Tool)")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.code == 429) {
                    delay(30000) // Rate limit
                    continue
                }
                
                if (!response.isSuccessful) {
                    break
                }
                
                val body = response.body?.string() ?: break
                val json = JSONObject(body)
                
                if (totalAvailable == 0) {
                    totalAvailable = json.optInt("total", 0)
                }
                
                val results = json.optJSONArray("results") ?: break
                
                if (results.length() == 0) {
                    hasMore = false
                    break
                }
                
                for (i in 0 until results.length()) {
                    if (allResults.size >= maxResults) break
                    
                    val result = results.getJSONObject(i)
                    val page = result.optJSONObject("page") ?: continue
                    
                    allResults.add(
                        SearchResult(
                            url = page.optString("url", ""),
                            domain = page.optString("domain", ""),
                            ip = page.optString("ip", ""),
                            country = page.optString("country", ""),
                            status = page.optString("status", ""),
                            title = page.optString("title", "")
                        )
                    )
                }
                
                // Get search_after for next page
                if (results.length() > 0) {
                    val lastResult = results.getJSONObject(results.length() - 1)
                    val sortValues = lastResult.optJSONArray("sort")
                    if (sortValues != null && sortValues.length() > 0) {
                        searchAfter = buildString {
                            for (j in 0 until sortValues.length()) {
                                if (j > 0) append(",")
                                append(sortValues.get(j).toString())
                            }
                        }
                    } else {
                        hasMore = false
                    }
                } else {
                    hasMore = false
                }
                
                hasMore = hasMore && json.optBoolean("has_more", false)
                
                delay(500) // Be nice to API
                
            } catch (e: Exception) {
                break
            }
        }
        
        SearchResponse(
            results = allResults,
            total = totalAvailable,
            hasMore = hasMore && allResults.size < maxResults
        )
    }
}
