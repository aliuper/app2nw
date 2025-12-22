package com.alibaba.core.common

import com.alibaba.domain.model.OutputFormat
import com.alibaba.domain.model.Playlist

object OutputFormatDetector {
    fun detect(playlist: Playlist): OutputFormat {
        var hasPlus = false
        var hasAttrs = false

        for (c in playlist.channels) {
            if (!c.tvgId.isNullOrBlank() || !c.tvgName.isNullOrBlank()) {
                hasPlus = true
                break
            }
            if (!c.logo.isNullOrBlank() || !c.group.isNullOrBlank()) {
                hasAttrs = true
            }
        }

        return when {
            hasPlus -> OutputFormat.M3U8PLUS
            hasAttrs -> OutputFormat.M3U8
            else -> OutputFormat.M3U
        }
    }

    fun maxOf(a: OutputFormat, b: OutputFormat): OutputFormat {
        val rank = mapOf(
            OutputFormat.M3U to 0,
            OutputFormat.M3U8 to 1,
            OutputFormat.M3U8PLUS to 2
        )
        return if ((rank[a] ?: 0) >= (rank[b] ?: 0)) a else b
    }
}
