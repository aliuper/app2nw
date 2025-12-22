package com.alibaba.data.repo

import com.alibaba.core.common.extractEndDate
import com.alibaba.core.network.PlaylistDownloader
import com.alibaba.core.parser.M3uParser
import com.alibaba.domain.model.Playlist
import com.alibaba.domain.repo.PlaylistRepository
import javax.inject.Inject

class PlaylistRepositoryImpl @Inject constructor(
    private val downloader: PlaylistDownloader,
    private val parser: M3uParser
) : PlaylistRepository {
    override suspend fun fetchPlaylist(url: String): Playlist {
        val lines = downloader.downloadLines(url)
        val endDate = extractEndDate(lines)
        return parser.parse(lines, endDate = endDate)
    }
}
