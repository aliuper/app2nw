package com.alibaba.domain.service

interface StreamTester {
    suspend fun isPlayable(url: String, timeoutMs: Long): Boolean
}
