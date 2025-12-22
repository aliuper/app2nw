package com.alibaba.core.common

fun groupCountryCode(group: String?): String? {
    if (group.isNullOrBlank()) return null
    val g = group.trim()

    val match = Regex("""^\[?([A-Z]{2})\]?([\s\-_|].*)?$""").find(g)
    return match?.groupValues?.getOrNull(1)
}

fun isGroupInCountries(group: String?, countries: Set<String>): Boolean {
    val code = groupCountryCode(group) ?: return false
    return code in countries
}
