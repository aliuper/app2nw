package com.alibaba.domain.model

data class Channel(
    val name: String,
    val url: String,
    val group: String?,
    val logo: String?,
    val tvgId: String?,
    val tvgName: String?
)
