package com.alibaba.domain.service

import com.alibaba.domain.model.ExpiryCheckConfig
import com.alibaba.domain.model.ExpiryCheckProgress
import com.alibaba.domain.model.ExpiryCheckResult
import com.alibaba.domain.model.ExpiryCheckSummary
import com.alibaba.domain.model.ParsedCredentials

interface ExpiryChecker {
    
    suspend fun checkExpiry(
        link: String,
        config: ExpiryCheckConfig = ExpiryCheckConfig(),
        onProgress: ((ExpiryCheckProgress) -> Unit)? = null
    ): ExpiryCheckResult
    
    suspend fun checkMultipleLinks(
        links: List<String>,
        config: ExpiryCheckConfig = ExpiryCheckConfig(),
        onProgress: ((ExpiryCheckProgress) -> Unit)? = null,
        onResult: ((ExpiryCheckResult) -> Unit)? = null
    ): ExpiryCheckSummary
    
    fun parseCredentials(link: String): ParsedCredentials
    
    fun maskLink(link: String): String
}
