package com.alibaba.core.common

private val urlRegex = Regex("""https?://[^\s"']+""", RegexOption.IGNORE_CASE)

fun extractIptvUrls(text: String): List<String> {
    return urlRegex.findAll(text)
        .map { it.value.trim().trimEnd(',', ';') }
        .filter { it.contains("m3u", ignoreCase = true) || it.contains("m3u8", ignoreCase = true) }
        .distinct()
        .toList()
}
