package com.alibaba.core.common

import java.text.Normalizer

fun groupCountryCode(group: String?): String? {
    if (group.isNullOrBlank()) return null
    val g = group.trim()

    val match = Regex("""^\[?([A-Z]{2})\]?([\s\-_|].*)?$""").find(g)
    return match?.groupValues?.getOrNull(1)
}

fun isGroupInCountries(group: String?, countries: Set<String>): Boolean {
    if (group.isNullOrBlank()) return false

    val code = groupCountryCode(group)
    if (code != null && code in countries) return true

    val normalized = normalizeGroup(group)
    val map = countryAliases()
    for (c in countries) {
        val aliases = map[c] ?: continue
        if (aliases.any { a -> normalized.contains(a) }) return true
    }

    return false
}

private fun normalizeGroup(value: String): String {
    val lower = value.lowercase()
    val noDiacritics = Normalizer.normalize(lower, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    return noDiacritics
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

private fun countryAliases(): Map<String, List<String>> {
    return mapOf(
        "TR" to listOf("tr", "turkiye", "turkiye", "turkey", "turkay", "t urkiye", "turk"),
        "DE" to listOf("de", "germany", "deutschland", "almanya", "deutsch"),
        "AT" to listOf("at", "austria", "osterreich", "osterreich", "avusturya"),
        "RO" to listOf("ro", "romania", "romanya"),
        "NL" to listOf("nl", "netherlands", "holland", "hollanda"),
        "FR" to listOf("fr", "france", "fransa"),
        "IT" to listOf("it", "italy", "italia", "italya"),
        "ES" to listOf("es", "spain", "espana", "ispanya"),
        "UK" to listOf("uk", "united kingdom", "britain", "england", "ingiltere"),
        "US" to listOf("us", "usa", "united states", "america", "amerika")
    )
}
