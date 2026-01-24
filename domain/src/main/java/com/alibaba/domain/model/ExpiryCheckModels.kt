package com.alibaba.domain.model

import java.util.Date

data class ExpiryCheckResult(
    val link: String,
    val status: ExpiryStatus,
    val expiryDate: Date? = null,
    val expiryDateFormatted: String = "Bilinmiyor",
    val daysRemaining: Int? = null,
    val packageName: String = "Bilinmiyor",
    val username: String = "Bilinmiyor",
    val password: String = "",
    val server: String = "",
    val maxConnections: Int? = null,
    val activeConnections: Int? = null,
    val isTrial: Boolean = false,
    val createdAt: String? = null
)

enum class ExpiryStatus(val displayName: String, val colorCode: String) {
    ACTIVE("Aktif", "#00FF00"),
    EXPIRED("Süresi Dolmuş", "#FF0000"),
    EXPIRING_SOON("Yakında Dolacak", "#FFA500"),  // 7 gün içinde
    INVALID_CREDENTIALS("Hatalı Kimlik", "#FF4444"),
    CONNECTION_ERROR("Bağlantı Hatası", "#888888"),
    TIMEOUT("Zaman Aşımı", "#FFAA00"),
    SERVER_NOT_FOUND("Sunucu Bulunamadı", "#666666"),
    INVALID_LINK("Hatalı Link", "#CC0000"),
    UNKNOWN("Bilinmiyor", "#AAAAAA")
}

data class ExpiryCheckProgress(
    val current: Int,
    val total: Int,
    val currentLink: String,
    val phase: String = "Kontrol ediliyor..."
) {
    val percentage: Float
        get() = if (total > 0) (current.toFloat() / total) * 100f else 0f
}

data class ExpiryCheckConfig(
    val timeoutSeconds: Int = 10,
    val checkPlayerApi: Boolean = true,
    val checkXmlTv: Boolean = true,
    val checkM3u: Boolean = true,
    val checkChannel: Boolean = true,
    val delayBetweenChecks: Long = 500L
)

data class ExpiryCheckSummary(
    val total: Int,
    val active: Int,
    val expired: Int,
    val expiringSoon: Int,
    val errors: Int,
    val results: List<ExpiryCheckResult>
) {
    val activePercentage: Float
        get() = if (total > 0) (active.toFloat() / total) * 100f else 0f
}

data class ParsedCredentials(
    val username: String?,
    val password: String?,
    val server: String?,
    val port: Int?
) {
    val isValid: Boolean
        get() = !username.isNullOrBlank() && !server.isNullOrBlank()
}
