package com.alibaba.feature.panelcheck

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelCheckRoute(
    onNavigateBack: () -> Unit,
    viewModel: PanelCheckViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aktiflik Kontrol") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Bilgilendirme kartı
            var showGuide by remember { mutableStateOf(true) }
            if (showGuide) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📖 Aktiflik Kontrol Nedir?",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { showGuide = false }) {
                                Icon(Icons.Default.Close, "Kapat", modifier = Modifier.size(18.dp))
                            }
                        }
                        Text("🔎 Panel adreslerini girin, aktif olup olmadığını kontrol edin", style = MaterialTheme.typography.bodySmall)
                        Text("📡 Port numarası bilmiyorsanız sadece domain girin - otomatik bulunur", style = MaterialTheme.typography.bodySmall)
                        Text("🌐 Yan panel bulma ile aynı IP'deki alternatif panelleri keşfedin", style = MaterialTheme.typography.bodySmall)
                        Text("💡 Örnek: panel.site.com:8080 veya sadece panel.site.com", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Panel giriş alanı
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "🌐 Panel Adresleri",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = state.inputText,
                        onValueChange = { viewModel.setInputText(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Panel adresleri") },
                        placeholder = { Text("panel1.site.com:8080\npanel2.site.com\nveya karışık metin...") },
                        minLines = 3,
                        maxLines = 6,
                        enabled = !state.isChecking
                    )

                    // Butonlar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.isChecking) {
                            Button(
                                onClick = { viewModel.stopCheck() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Durdur")
                            }
                        } else {
                            Button(
                                onClick = { viewModel.startCheck() },
                                enabled = state.inputText.isNotBlank()
                            ) {
                                Icon(Icons.Default.NetworkCheck, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Kontrol Et")
                            }
                        }

                        if (state.results.isNotEmpty() && !state.isChecking) {
                            OutlinedButton(onClick = {
                                clipboardManager.setText(AnnotatedString(viewModel.getResultsText()))
                                Toast.makeText(context, "✅ Sonuçlar kopyalandı!", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Kopyala")
                            }

                            OutlinedButton(onClick = { viewModel.clearResults() }) {
                                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Temizle")
                            }
                        }
                    }
                }
            }

            // İlerleme / Durum
            if (state.isChecking || state.statusMessage.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = state.statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        if (state.isChecking) {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // İstatistikler
                        if (state.totalChecked > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                StatChip("✅ ${state.onlineCount}", MaterialTheme.colorScheme.primary)
                                StatChip("❌ ${state.offlineCount}", MaterialTheme.colorScheme.error)
                                if (state.portFoundCount > 0) {
                                    StatChip("🔍 ${state.portFoundCount} port", MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }
                    }
                }
            }

            // Hata mesajı
            state.errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(error, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(Icons.Default.Close, "Kapat")
                        }
                    }
                }
            }

            // Sonuçlar
            if (state.results.isNotEmpty()) {
                Text(
                    text = "📊 Sonuçlar (${state.results.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                state.results.forEach { result ->
                    PanelCheckResultCard(
                        result = result,
                        onFindRelated = { viewModel.findRelatedPanels(result) },
                        isFindingRelated = state.isFindingRelated,
                        onCopyAddress = { address ->
                            clipboardManager.setText(AnnotatedString(address))
                            Toast.makeText(context, "Kopyalandı: $address", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelCheckResultCard(
    result: PanelCheckResult,
    onFindRelated: () -> Unit,
    isFindingRelated: Boolean,
    onCopyAddress: (String) -> Unit
) {
    val isOnline = result.isOnline
    var showDetails by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isOnline)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header: durum + adres
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isOnline) "✅" else "❌",
                        fontSize = 20.sp
                    )
                    Column {
                        Text(
                            text = result.host,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (result.detectedPort != null) {
                            Text(
                                text = "Port: ${result.detectedPort}" +
                                        if (result.originalInput == result.host) " (otomatik bulundu)" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = "Port bulunamadı",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // Response time badge
                if (isOnline && result.responseTimeMs > 0) {
                    val speedColor = when {
                        result.responseTimeMs < 200 -> MaterialTheme.colorScheme.primary
                        result.responseTimeMs < 500 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                    Text(
                        text = "${result.responseTimeMs}ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = speedColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // IP adresi
            result.ipAddress?.let { ip ->
                Text(
                    text = "IP: $ip",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Sunucu bilgisi
            result.serverInfo?.let { info ->
                Text(
                    text = "🖥 $info",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Hata mesajı
            result.errorMessage?.let { err ->
                Text(
                    text = "⚠️ $err",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Butonlar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Kopyala
                if (result.detectedPort != null) {
                    OutlinedButton(
                        onClick = { onCopyAddress("${result.host}:${result.detectedPort}") },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Kopyala", style = MaterialTheme.typography.labelSmall)
                    }
                }

                // Yan panel bul
                if (isOnline) {
                    OutlinedButton(
                        onClick = onFindRelated,
                        enabled = !isFindingRelated,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        if (isFindingRelated) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.TravelExplore, null, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("Yan Panel Bul", style = MaterialTheme.typography.labelSmall)
                    }
                }

                // Detaylar
                if (result.portsScanned.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showDetails = !showDetails },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Port Detay", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Açık portlar detayı
            AnimatedVisibility(visible = showDetails) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider()
                    Text(
                        text = "🔌 Açık Portlar:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    result.portsScanned.forEach { port ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${if (port.isIptv) "📡" else "🔌"} Port ${port.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (port.isIptv) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (port.isIptv) "IPTV Panel ✅" else "Açık",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (port.isIptv) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // İlişkili paneller
            if (result.relatedDomains.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "🔗 Bulunan İlişkili Paneller (${result.relatedDomains.size}):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                result.relatedDomains.forEach { related ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${if (related.isOnline) "✅" else "⚪"} ${related.domain}${if (related.port != null) ":${related.port}" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (related.isOnline) FontWeight.Bold else FontWeight.Normal,
                                color = if (related.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${related.source} | IP: ${related.ip}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (related.isOnline && related.port != null) {
                            IconButton(
                                onClick = { onCopyAddress("${related.domain}:${related.port}") },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, "Kopyala", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
