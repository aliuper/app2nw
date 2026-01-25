package com.alibaba.domain.service

/**
 * Yan sunucu tarama servisi interface
 */
interface SideServerScanner {
    
    data class Credentials(
        val serverUrl: String,
        val username: String,
        val password: String
    )
    
    data class ScanResult(
        val serverUrl: String,
        val m3uLink: String,
        val isActive: Boolean,
        val statusText: String,
        val expireDate: String? = null,
        val maxConnections: Int? = null
    )
    
    suspend fun extractCredentials(m3uLink: String): Credentials?
    
    suspend fun scanServers(
        credentials: Credentials,
        serverUrls: List<String>,
        onProgress: (current: Int, total: Int, result: ScanResult?) -> Unit
    ): List<ScanResult>
    
    suspend fun testSingleServer(
        serverUrl: String,
        username: String,
        password: String
    ): ScanResult
    
    fun generateDomainVariations(originalUrl: String): List<String>
}
