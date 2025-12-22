package com.alibaba.core.common

private val ddMMyyyyInline = Regex("""\b(\d{2})(\d{2})(\d{4})\b""")
private val yyyyMmDd = Regex("""\b(\d{4})[-./](\d{2})[-./](\d{2})\b""")

fun extractEndDate(lines: List<String>): String? {
    val candidates = lines
        .asSequence()
        .filter { line ->
            val l = line.lowercase()
            l.contains("exp") || l.contains("expire") || l.contains("end") || l.contains("valid")
        }
        .take(200)
        .toList()

    for (line in candidates) {
        ddMMyyyyInline.find(line)?.let { m ->
            val dd = m.groupValues[1]
            val mm = m.groupValues[2]
            val yyyy = m.groupValues[3]
            return "$dd$mm$yyyy"
        }

        yyyyMmDd.find(line)?.let { m ->
            val yyyy = m.groupValues[1]
            val mm = m.groupValues[2]
            val dd = m.groupValues[3]
            return "$dd$mm$yyyy"
        }
    }

    return null
}
