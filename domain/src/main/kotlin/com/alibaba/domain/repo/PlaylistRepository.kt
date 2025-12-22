package com.alibaba.domain.repo

import com.alibaba.domain.model.Playlist

interface PlaylistRepository {
    suspend fun fetchPlaylist(url: String): Playlist
}
