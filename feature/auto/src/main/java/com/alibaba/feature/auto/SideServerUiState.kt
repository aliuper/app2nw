package com.alibaba.feature.auto

data class SideServerUiState(
    val originalLink: String = "",
    val username: String = "",
    val password: String = "",
    val isScanning: Boolean = false,
    val isTesting: Boolean = false,
    val progressPercent: Int = 0,
    val progressText: String = "",
    // Aşama 1: Bulunan domainler (henüz test edilmemiş)
    val discoveredDomains: List<String> = emptyList(),
    val resolvedIP: String = "",
    // Aşama 2: Test edilmiş IPTV sonuçları
    val results: List<SideServerResultItem> = emptyList(),
    val activeCount: Int = 0,
    val errorMessage: String? = null
)

data class SideServerResultItem(
    val serverUrl: String,
    val m3uLink: String,
    val isActive: Boolean,
    val statusText: String,
    val expireDate: String? = null,
    val maxConnections: Int? = null
)
