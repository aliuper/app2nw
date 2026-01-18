package com.alibaba.feature.compare

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.alibaba.domain.model.PlaylistQuality
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun CompareRoute(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CompareViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    CompareScreen(
        state = state,
        onInputChange = viewModel::onInputChange,
        onExtract = viewModel::extractUrls,
        onStartComparison = viewModel::startComparison,
        onClear = viewModel::clearAll,
        onNavigateBack = onNavigateBack,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    state: CompareUiState,
    onInputChange: (String) -> Unit,
    onExtract: () -> Unit,
    onStartComparison: () -> Unit,
    onClear: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "IPTV Karşılaştır") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Geri"
                        )
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "IPTV linklerini karşılaştırın ve en kalitelisini bulun",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = state.inputText,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                label = { Text(text = "IPTV Linkleri (Her satıra bir link)") },
                minLines = 6,
                maxLines = 10,
                enabled = !state.loading
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onExtract,
                    enabled = !state.loading && state.inputText.isNotBlank()
                ) {
                    Icon(imageVector = Icons.Filled.Link, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Linkleri Ayıkla")
                }

                Button(
                    onClick = {
                        val clip = clipboard.getText()?.text?.trim()
                        if (clip.isNullOrBlank()) {
                            Toast.makeText(context, "Pano boş", Toast.LENGTH_SHORT).show()
                        } else {
                            val next = if (state.inputText.isBlank()) clip else state.inputText.trimEnd() + "\n" + clip
                            onInputChange(next)
                        }
                    },
                    enabled = !state.loading
                ) {
                    Text(text = "Panodan Yapıştır")
                }

                Button(
                    onClick = onClear,
                    enabled = !state.loading
                ) {
                    Text(text = "Temizle")
                }
            }

            if (state.extractedUrls.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Bulunan Linkler (${state.extractedUrls.size})",
                            style = MaterialTheme.typography.titleMedium
                        )
                        state.extractedUrls.forEachIndexed { index, url ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Link,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(text = "${index + 1}. $url", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Button(
                            onClick = onStartComparison,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Karşılaştırmayı Başlat")
                        }
                    }
                }
            }

            state.errorMessage?.let { msg ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(text = msg, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (state.loading) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(imageVector = Icons.Filled.Bolt, contentDescription = null)
                            Text(
                                text = state.progressMessage ?: "İşleniyor...",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (state.progressPercent.coerceIn(0, 100) / 100f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(text = "%${state.progressPercent}")
                    }
                }
            }

            if (state.results.isNotEmpty()) {
                Text(
                    text = "Sonuçlar (Kaliteye Göre Sıralı)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                state.results.forEach { result ->
                    PlaylistQualityCard(result)
                }
            }
        }
    }
}

@Composable
fun PlaylistQualityCard(quality: PlaylistQuality) {
    val rankColor = when (quality.rank) {
        1 -> MaterialTheme.colorScheme.primary
        2 -> MaterialTheme.colorScheme.secondary
        3 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }

    val rankIcon = when (quality.rank) {
        1 -> Icons.Filled.Star
        2 -> Icons.Filled.Star
        3 -> Icons.Filled.Star
        else -> Icons.Filled.CheckCircle
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (quality.rank == 1) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = rankIcon,
                        contentDescription = null,
                        tint = rankColor
                    )
                    Text(
                        text = "#${quality.rank}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = rankColor
                    )
                }
                
                Text(
                    text = "%.1f/10".format(quality.qualityScore),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        quality.qualityScore >= 8.0f -> MaterialTheme.colorScheme.primary
                        quality.qualityScore >= 6.0f -> MaterialTheme.colorScheme.secondary
                        quality.qualityScore >= 4.0f -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }

            HorizontalDivider()

            Text(
                text = quality.url,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "Bitiş Tarihi:", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = quality.endDate ?: "Bilinmiyor",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    // Kalan gün hesaplama
                    quality.endDate?.let { endDateStr ->
                        val daysRemaining = calculateDaysRemaining(endDateStr)
                        if (daysRemaining != null) {
                            val (text, color) = when {
                                daysRemaining < 0 -> "⛔ Süresi dolmuş" to MaterialTheme.colorScheme.error
                                daysRemaining == 0L -> "⚠️ Bugün bitiyor!" to MaterialTheme.colorScheme.error
                                daysRemaining <= 7 -> "⚠️ $daysRemaining gün kaldı" to MaterialTheme.colorScheme.error
                                daysRemaining <= 30 -> "📅 $daysRemaining gün kaldı" to MaterialTheme.colorScheme.tertiary
                                daysRemaining <= 90 -> "📅 $daysRemaining gün kaldı" to MaterialTheme.colorScheme.secondary
                                else -> "✅ $daysRemaining gün kaldı" to MaterialTheme.colorScheme.primary
                            }
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = color
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "Kanallar:", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = "${quality.workingChannelCount}/${quality.channelCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = "Kalite Metrikleri",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            MetricRow(
                icon = Icons.Filled.Speed,
                label = "Açılma Hızı",
                value = "${quality.metrics.avgOpeningSpeed}ms",
                score = when {
                    quality.metrics.avgOpeningSpeed < 500 -> "Mükemmel"
                    quality.metrics.avgOpeningSpeed < 1000 -> "İyi"
                    quality.metrics.avgOpeningSpeed < 2000 -> "Orta"
                    else -> "Yavaş"
                }
            )

            MetricRow(
                icon = Icons.Filled.Download,
                label = "Yükleme Hızı",
                value = "${quality.metrics.avgLoadingSpeed}ms",
                score = when {
                    quality.metrics.avgLoadingSpeed < 1000 -> "Mükemmel"
                    quality.metrics.avgLoadingSpeed < 2000 -> "İyi"
                    quality.metrics.avgLoadingSpeed < 3000 -> "Orta"
                    else -> "Yavaş"
                }
            )

            MetricRow(
                icon = Icons.Filled.Pause,
                label = "Buffering",
                value = "%.0f%%".format(quality.metrics.bufferingRate * 100),
                score = when {
                    quality.metrics.bufferingRate < 0.1f -> "Mükemmel"
                    quality.metrics.bufferingRate < 0.3f -> "İyi"
                    quality.metrics.bufferingRate < 0.5f -> "Orta"
                    else -> "Kötü"
                }
            )

            MetricRow(
                icon = Icons.Filled.HighQuality,
                label = "Bitrate",
                value = "%.1f Mbps".format(quality.metrics.avgBitrate / 1_000_000f),
                score = when {
                    quality.metrics.avgBitrate > 5_000_000 -> "Mükemmel"
                    quality.metrics.avgBitrate > 3_000_000 -> "İyi"
                    quality.metrics.avgBitrate > 1_500_000 -> "Orta"
                    else -> "Düşük"
                }
            )

            MetricRow(
                icon = Icons.Filled.CheckCircle,
                label = "Başarı Oranı",
                value = "%.0f%%".format(quality.metrics.successRate * 100),
                score = when {
                    quality.metrics.successRate > 0.8f -> "Mükemmel"
                    quality.metrics.successRate > 0.6f -> "İyi"
                    quality.metrics.successRate > 0.4f -> "Orta"
                    else -> "Kötü"
                }
            )
        }
    }
}

@Composable
fun MetricRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    score: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Text(text = label, style = MaterialTheme.typography.bodySmall)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = score,
                style = MaterialTheme.typography.labelSmall,
                color = when (score) {
                    "Mükemmel" -> MaterialTheme.colorScheme.primary
                    "İyi" -> MaterialTheme.colorScheme.secondary
                    "Orta" -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

// Bitiş tarihinden kalan günü hesapla
private fun calculateDaysRemaining(endDateStr: String): Long? {
    return try {
        val now = System.currentTimeMillis()
        
        // Farklı tarih formatlarını dene
        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "dd-MM-yyyy HH:mm:ss",
            "dd-MM-yyyy",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy",
            "MM/dd/yyyy",
            "yyyy/MM/dd"
        )
        
        var endDate: Date? = null
        for (format in formats) {
            try {
                endDate = SimpleDateFormat(format, Locale.getDefault()).parse(endDateStr)
                if (endDate != null) break
            } catch (_: Exception) {
                continue
            }
        }
        
        // Unix timestamp olabilir (saniye cinsinden)
        if (endDate == null) {
            try {
                val timestamp = endDateStr.toLongOrNull()
                if (timestamp != null) {
                    // 10 haneli ise saniye, 13 haneli ise milisaniye
                    endDate = if (timestamp > 1_000_000_000_000L) {
                        Date(timestamp)
                    } else {
                        Date(timestamp * 1000)
                    }
                }
            } catch (_: Exception) {
                // Ignore
            }
        }
        
        if (endDate == null) return null
        
        val diffMs = endDate.time - now
        TimeUnit.MILLISECONDS.toDays(diffMs)
    } catch (_: Exception) {
        null
    }
}
