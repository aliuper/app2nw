package com.alibaba.domain.model

data class Playlist(
    val channels: List<Channel>,
    val endDate: String? = null
)
