package com.alibaba.core.common

private val adultTokens = listOf(
    "adult",
    "xxx",
    "porn",
    "18+",
    "+18",
    "erotik",
    "sex"
)

fun isAdultGroup(group: String?): Boolean {
    val g = group?.trim()?.lowercase() ?: return false
    return adultTokens.any { token -> g.contains(token) }
}
