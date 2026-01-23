package com.alibaba.core.common

private val urlRegex = Regex("""https?://[^\s"']+""", RegexOption.IGNORE_CASE)
 private val schemeRegex = Regex("""https?://""", RegexOption.IGNORE_CASE)

fun extractIptvUrls(text: String): List<String> {
    return urlRegex.findAll(text)
        .flatMap { m -> splitConcatenatedUrls(m.value).asSequence() }
        .map { it.trim().trimEnd(',', ';') }
        .filter { it.contains("m3u", ignoreCase = true) || it.contains("m3u8", ignoreCase = true) }
        .distinct()
        .toList()
}

 private fun splitConcatenatedUrls(raw: String): List<String> {
    val matches = schemeRegex.findAll(raw).toList()
    if (matches.size <= 1) return listOf(raw)

    val splitPoints = ArrayList<Int>(matches.size)
    splitPoints.add(0)
    for (i in 1 until matches.size) {
        val pos = matches[i].range.first
        if (pos <= 0) continue
        val prev = raw[pos - 1]
        if (!prev.isWhitespace() && prev != '=' && prev != '&' && prev != '?' && prev != '#') {
            splitPoints.add(pos)
        }
    }
    if (splitPoints.size == 1) return listOf(raw)

    val out = ArrayList<String>(splitPoints.size)
    for (i in splitPoints.indices) {
        val start = splitPoints[i]
        val end = if (i + 1 < splitPoints.size) splitPoints[i + 1] else raw.length
        if (start in 0..end && end <= raw.length) {
            out.add(raw.substring(start, end))
        }
    }
    return out
 }
