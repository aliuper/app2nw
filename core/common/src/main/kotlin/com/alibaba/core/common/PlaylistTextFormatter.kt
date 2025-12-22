package com.alibaba.core.common

import com.alibaba.domain.model.Channel
import com.alibaba.domain.model.OutputFormat
import com.alibaba.domain.model.Playlist

object PlaylistTextFormatter {
    fun format(
        playlist: Playlist,
        selectedGroups: Set<String>,
        format: OutputFormat
    ): String {
        val channels = playlist.channels
            .filter { channel ->
                val group = channel.group ?: "Ungrouped"
                group in selectedGroups
            }

        val grouped = channels
            .groupBy { it.group ?: "Ungrouped" }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)

        val sb = StringBuilder(maxOf(1024, channels.size * 64))
        sb.append("#EXTM3U\n")

        for ((_, items) in grouped) {
            val sorted = items.sortedBy { it.name.lowercase() }
            for (c in sorted) {
                sb.append(extInfLine(c, format))
                sb.append('\n')
                sb.append(c.url)
                sb.append('\n')
            }
        }

        return sb.toString()
    }

    private fun extInfLine(channel: Channel, format: OutputFormat): String {
        return when (format) {
            OutputFormat.M3U -> {
                "#EXTINF:-1,${channel.name}"
            }

            OutputFormat.M3U8 -> {
                val attrs = buildString {
                    channel.logo?.let { append(" tvg-logo=\"${escape(it)}\"") }
                    channel.group?.let { append(" group-title=\"${escape(it)}\"") }
                }
                "#EXTINF:-1${attrs},${channel.name}"
            }

            OutputFormat.M3U8PLUS -> {
                val attrs = buildString {
                    channel.tvgId?.let { append(" tvg-id=\"${escape(it)}\"") }
                    channel.tvgName?.let { append(" tvg-name=\"${escape(it)}\"") }
                    channel.logo?.let { append(" tvg-logo=\"${escape(it)}\"") }
                    channel.group?.let { append(" group-title=\"${escape(it)}\"") }
                }
                "#EXTINF:-1${attrs},${channel.name}"
            }
        }
    }

    private fun escape(value: String): String = value.replace("\"", "\\\"")
}
