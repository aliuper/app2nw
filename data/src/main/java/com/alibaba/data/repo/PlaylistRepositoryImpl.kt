package com.alibaba.data.repo

import com.alibaba.core.common.extractEndDate
import com.alibaba.core.network.PlaylistDownloader
import com.alibaba.core.parser.M3uParser
import com.alibaba.domain.model.Playlist
import com.alibaba.domain.repo.PlaylistRepository
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class PlaylistRepositoryImpl @Inject constructor(
    private val downloader: PlaylistDownloader,
    private val parser: M3uParser
) : PlaylistRepository {
    override suspend fun fetchPlaylist(url: String): Playlist {
        val lines = downloader.downloadLines(url)
        var endDate = extractEndDate(lines)
        if (endDate == null) {
            endDate = fetchXtreamEndDateOrNull(url)
        }
        return parser.parse(lines, endDate = endDate)
    }

    private suspend fun fetchXtreamEndDateOrNull(url: String): String? {
        return try {
            val uri = URI(url)
            val query = uri.rawQuery ?: return null
            val params = parseQueryParams(query)
            val username = params["username"] ?: return null
            val password = params["password"] ?: return null

            val path = uri.path ?: return null
            val lastSlash = path.lastIndexOf('/')
            if (lastSlash < 0) return null
            val basePath = path.substring(0, lastSlash + 1)
            val base = "${uri.scheme}://${uri.authority}$basePath"
            val apiUrl = "${base}player_api.php?username=${encode(username)}&password=${encode(password)}"

            val jsonText = downloader.downloadText(apiUrl)
            val json = JSONObject(jsonText)
            val userInfo = json.optJSONObject("user_info") ?: return null
            val expStr = userInfo.optString("exp_date", "").trim()
            if (expStr.isBlank() || expStr == "0") return null
            val expSeconds = expStr.toLongOrNull() ?: return null

            val date = Instant.ofEpochSecond(expSeconds)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
            date.format(DateTimeFormatter.ofPattern("ddMMyyyy"))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        rawQuery.split('&').forEach { part ->
            if (part.isBlank()) return@forEach
            val idx = part.indexOf('=')
            if (idx <= 0) return@forEach
            val key = URLDecoder.decode(part.substring(0, idx), "UTF-8").lowercase()
            val value = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
            if (key.isNotBlank() && value.isNotBlank()) {
                map[key] = value
            }
        }
        return map
    }

    private fun encode(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8")
    }
}
