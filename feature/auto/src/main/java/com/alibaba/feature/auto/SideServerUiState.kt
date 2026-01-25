package com.alibaba.feature.auto

data class SideServerUiState(
    val originalLink: String = "",
    val username: String = "",
    val password: String = "",
    val serverUrls: String = "", // Her satırda bir URL
    val isScanning: Boolean = false,
    val progressPercent: Int = 0,
    val progressText: String = "",
    val results: List<SideServerResultItem> = emptyList(),
    val activeCount: Int = 0,
    val errorMessage: String? = null,
    val autoGenerateEnabled: Boolean = true // Otomatik varyasyon oluşturma
)

data class SideServerResultItem(
    val serverUrl: String,
    val m3uLink: String,
    val isActive: Boolean,
    val statusText: String,
    val expireDate: String? = null,
    val maxConnections: Int? = null
)
