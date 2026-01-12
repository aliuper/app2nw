package com.alibaba.feature.panelscan

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.alibaba.domain.model.ScanStatus
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelScanRoute(
    onNavigateBack: () -> Unit,
    viewModel: PanelScanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // File picker for combo files
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val content = inputStream?.bufferedReader()?.use { reader -> reader.readText() }
                content?.let { text ->
                    viewModel.setComboText(text)
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Panel Tarayıcı") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "🎯 IPTV Panel Tarayıcı",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Combo dosyanızı yapıştırın ve panelleri tarayın. Format: kullanici:sifre",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• ${com.alibaba.domain.model.EmbeddedPanels.panels.size} gömülü panel hazır",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // File Picker Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("text/*") },
                    modifier = Modifier.weight(1f),
                    enabled = !state.scanning
                ) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Combo Dosyası Seç")
                }
                
                if (state.comboText.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { viewModel.setComboText("") },
                        enabled = !state.scanning
                    ) {
                        Icon(Icons.Default.Clear, null)
                    }
                }
            }

            // Combo Input
            OutlinedTextField(
                value = state.comboText,
                onValueChange = { viewModel.setComboText(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                label = { Text("Combo Listesi (veya yukarıdan dosya seçin)") },
                placeholder = { 
                    Text("kullanici1:sifre1\nkullanici2:sifre2\n...\n\nveya 'Combo Dosyası Seç' butonuna tıklayın") 
                },
                enabled = !state.scanning,
                maxLines = 10,
                supportingText = {
                    if (state.comboText.isNotEmpty()) {
                        val lineCount = state.comboText.lines().count { it.isNotBlank() }
                        Text("$lineCount satır yüklendi")
                    }
                }
            )

            // Panel Selection
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Panel Seçimi",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = state.useEmbeddedPanels,
                            onCheckedChange = { viewModel.toggleEmbeddedPanels() },
                            enabled = !state.scanning
                        )
                        Text(
                            text = "Gömülü panelleri kullan (${com.alibaba.domain.model.EmbeddedPanels.panels.size} panel)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (state.selectedPanels.isNotEmpty()) {
                        Text(
                            text = "Özel Paneller: ${state.selectedPanels.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            // Start Button
            Button(
                onClick = { viewModel.startScan() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.scanning && state.comboText.isNotBlank()
            ) {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.scanning) "Taranıyor..." else "Taramayı Başlat")
            }

            // Progress
            state.progress?.let { progress ->
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
                            text = "İlerleme: ${progress.current}/${progress.total}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        LinearProgressIndicator(
                            progress = progress.current.toFloat() / progress.total.toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (state.scanning && progress.currentAccount.isNotEmpty()) {
                            Text(
                                text = "Test ediliyor: ${progress.currentAccount}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "✅ Geçerli: ${progress.validCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "❌ Geçersiz: ${progress.invalidCount}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "⚠️ Hata: ${progress.errorCount}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // Error Message
            state.errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(Icons.Default.Close, "Kapat")
                        }
                    }
                }
            }

            // Results
            if (state.results.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🎯 Bulunan Hesaplar (${state.results.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (!state.scanning) {
                        TextButton(onClick = { viewModel.clearResults() }) {
                            Text("Temizle")
                        }
                    }
                }

                state.results.forEach { result ->
                    ResultCard(result)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: com.alibaba.domain.model.PanelScanResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "✅ GEÇERLİ HESAP",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(Date(result.foundAt)),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            HorizontalDivider()

            // Account Info
            InfoRow("👤 Kullanıcı", result.account.username)
            InfoRow("🔑 Şifre", result.account.password)
            InfoRow("🌐 Panel", result.panel.fullAddress)

            // User Info
            result.userInfo?.let { userInfo ->
                HorizontalDivider()
                InfoRow("📅 Bitiş", userInfo.expDate ?: "Sınırsız")
                InfoRow("👥 Bağlantı", "${userInfo.activeCons}/${userInfo.maxConnections}")
                InfoRow("📊 Durum", userInfo.status)
                if (userInfo.isTrial) {
                    InfoRow("🎯 Trial", "Evet")
                }
            }

            // URLs
            HorizontalDivider()
            val m3uUrl = "http://${result.panel.fullAddress}/get.php?username=${result.account.username}&password=${result.account.password}&type=m3u_plus"
            val xstreamUrl = "http://${result.panel.fullAddress}"
            
            Text(
                text = "🔗 M3U URL:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = m3uUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )

            Text(
                text = "📡 Xtream URL:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = xstreamUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
