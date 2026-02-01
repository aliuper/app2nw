package com.alibaba.domain.model

data class AppSettings(
    val streamTestSampleSize: Int = 5,           // Test edilecek kanal sayısı: 5
    val streamTestTimeoutMs: Long = 10_000L,     // Her stream için maksimum 10 saniye
    val minPlayableStreamsToPass: Int = 1,       // Başarılı saymak için 1 stream yeterli
    val delayBetweenStreamTestsMs: Long = 200L,  // Test arası bekleme: 200ms
    val skipAdultGroups: Boolean = true,
    val shuffleCandidates: Boolean = true,
    val enableCountryFiltering: Boolean = false, // Ülke filtreleme varsayılan kapalı
    val outputDelivery: OutputDelivery = OutputDelivery.LINK // Çıktı türü: Link
)
