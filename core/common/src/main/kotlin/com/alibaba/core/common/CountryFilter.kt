package com.alibaba.core.common

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

fun groupCountryCode(group: String?): String? {
    if (group.isNullOrBlank()) return null
    val g = group.trim()

    val match = Regex("""^\[?([A-Za-z]{2})\]?([\s\-_|].*)?$""").find(g)
    return match?.groupValues?.getOrNull(1)?.uppercase()
}

fun isGroupInCountries(group: String?, countries: Set<String>): Boolean {
    if (group.isNullOrBlank()) return false

    val code = groupCountryCode(group)
    if (code != null && code in countries) return true

    val normalized = cachedNormalized(group)
    val tokens = cachedTokens(normalized)
    val aliasMap = countryAliasesNormalized()

    for (c in countries) {
        val aliases = aliasMap[c] ?: continue
        for (alias in aliases) {
            if (alias.contains(' ')) {
                if (containsPhrase(normalized, alias)) return true
            } else {
                if (tokens.contains(alias)) return true
            }
        }
    }

    return false
}

private fun containsPhrase(normalizedGroup: String, normalizedPhrase: String): Boolean {
    val groupPadded = " $normalizedGroup "
    val phrasePadded = " $normalizedPhrase "
    return groupPadded.contains(phrasePadded)
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

private val normalizedCache = ConcurrentHashMap<String, String>()
private val tokensCache = ConcurrentHashMap<String, Set<String>>()

private fun cachedNormalized(rawGroup: String): String {
    normalizedCache[rawGroup]?.let { return it }
    val normalized = normalizeGroup(rawGroup)
    if (normalizedCache.size > 2000) normalizedCache.clear()
    normalizedCache[rawGroup] = normalized
    return normalized
}

private fun cachedTokens(normalized: String): Set<String> {
    tokensCache[normalized]?.let { return it }
    val tokens = normalized.split(' ').filter { it.isNotBlank() }.toSet()
    if (tokensCache.size > 4000) tokensCache.clear()
    tokensCache[normalized] = tokens
    return tokens
}

private val aliasMapNormalized: Map<String, List<String>> by lazy {
    mapOf(
        "TR" to listOf("tr", "turkiye", "turkey", "turk"),
        "DE" to listOf("germany", "deutschland", "almanya", "deutsch"),
        "AT" to listOf("austria", "osterreich", "avusturya"),
        "RO" to listOf("romania", "romanya"),
        "NL" to listOf("netherlands", "holland", "hollanda"),
        "FR" to listOf("france", "fransa"),
        "IT" to listOf("italy", "italia", "italya"),
        "ES" to listOf("spain", "espana", "ispanya"),
        "UK" to listOf("united kingdom", "britain", "england", "ingiltere"),
        "US" to listOf("united states", "usa", "america", "amerika")
    ).mapValues { (_, aliases) -> aliases.map { normalizeGroup(it) }.distinct() }
}

private fun countryAliasesNormalized(): Map<String, List<String>> = aliasMapNormalized
