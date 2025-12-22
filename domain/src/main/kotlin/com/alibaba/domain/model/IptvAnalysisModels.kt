package com.alibaba.domain.model

enum class IptvQuality {
    ACTIVE,
    WEAK,
    DEAD,
    INVALID
}

data class IptvAnalysisOptions(
    val userAgent: String = "VLC/3.0.0 LibVLC/3.0.0",
    val urlTimeoutMs: Long = 10_000L,
    val partialDownloadBytes: Long = 2L * 1024L * 1024L,
    val exoFirstFrameTimeoutMs: Long = 8_000L,
    val streamsPerGroup: Int = 3,
    val maxGroupsToTest: Int = 10,
    val maxConcurrentStreamTests: Int = 3
)

data class IptvUrlLiveness(
    val url: String,
    val ok: Boolean,
    val httpCode: Int? = null,
    val finalUrl: String? = null,
    val elapsedMs: Long? = null,
    val error: String? = null
)

data class IptvM3uAnalysis(
    val url: String,
    val liveness: IptvUrlLiveness,
    val startsWithExtM3u: Boolean,
    val extInfCount: Int,
    val channelCount: Int,
    val playlist: Playlist?,
    val warning: String? = null,
    val error: String? = null
)

data class IptvGroupStat(
    val name: String,
    val channelCount: Int
)

data class IptvGroupAnalysis(
    val totalGroups: Int,
    val totalChannels: Int,
    val filteredGroups: Int,
    val filteredChannels: Int,
    val largestGroup: IptvGroupStat?,
    val smallestGroup: IptvGroupStat?,
    val groups: List<IptvGroupStat>
)

data class IptvStreamChannelTest(
    val url: String,
    val ok: Boolean,
    val httpCode: Int? = null,
    val mime: String? = null,
    val elapsedMs: Long? = null,
    val reason: String? = null
)

data class IptvStreamGroupTest(
    val groupName: String,
    val tested: Int,
    val passed: Int,
    val quality: IptvQuality,
    val channelTests: List<IptvStreamChannelTest>
)

data class IptvOverallStreamTest(
    val groupsTested: Int,
    val groupsSkipped: Int,
    val totalChannelsTested: Int,
    val totalChannelsPassed: Int,
    val quality: IptvQuality,
    val groupResults: List<IptvStreamGroupTest>
) {
    fun toShortReport(): String {
        return buildString {
            append("Stream testi: ")
            append(quality.name)
            append(" | Grup: ")
            append(groupsTested)
            if (groupsSkipped > 0) {
                append(" (+")
                append(groupsSkipped)
                append(" atlandı)")
            }
            append(" | Kanal: ")
            append(totalChannelsPassed)
            append("/")
            append(totalChannelsTested)
        }
    }
}
