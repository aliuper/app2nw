package com.alibaba.core.parser

import com.alibaba.domain.model.Channel
import com.alibaba.domain.model.Playlist

class M3uParser {
    fun parse(lines: List<String>, endDate: String? = null): Playlist {
        val channels = ArrayList<Channel>(lines.size / 2)

        var pendingInf: String? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                pendingInf = line
                continue
            }

            if (!line.startsWith("#")) {
                val meta = pendingInf.orEmpty()

                fun attr(key: String): String? {
                    val regex = Regex("""${'$'}key=\"([^\"]*)\"""", RegexOption.IGNORE_CASE)
                    return regex.find(meta)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                }

                val group = attr("group-title")
                val logo = attr("tvg-logo")
                val tvgId = attr("tvg-id")
                val tvgName = attr("tvg-name")

                val name = meta.substringAfterLast(",").trim().ifBlank { "Unknown" }

                channels += Channel(
                    name = name,
                    url = line,
                    group = group,
                    logo = logo,
                    tvgId = tvgId,
                    tvgName = tvgName
                )

                pendingInf = null
            }
        }

        return Playlist(channels = channels, endDate = endDate)
    }
}
