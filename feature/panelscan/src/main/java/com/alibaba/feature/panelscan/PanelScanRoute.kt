package com.alibaba.feature.panelscan

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.alibaba.domain.model.ScanStatus
import java.io.BufferedReader
import java.io.InputStreamReader
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
    val clipboardManager = LocalClipboardManager.current

    // File picker for combo files - Büyük dosya desteği (streaming ile oku)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                inputStream?.let { stream ->
                    // Büyük dosyalar için satır satır oku (500MB'a kadar)
                    val reader = BufferedReader(InputStreamReader(stream))
                    val lines = StringBuilder()
                    var lineCount = 0
                    val maxLines = 5_000_000 // Maksimum 5 milyon satır
                    
                    reader.useLines { sequence ->
                        sequence.take(maxLines).forEach { line ->
                            if (line.contains(":")) {
                                lines.appendLine(line)
                                lineCount++
                            }
                        }
                    }
                    
                    if (lineCount > 0) {
                        viewModel.setComboText(lines.toString())
                        Toast.makeText(context, "$lineCount hesap yüklendi", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Geçerli hesap bulunamadı (format: user:pass)", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Dosya okuma hatası: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // Hit kaydetme için file saver
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(viewModel.getResultsAsText().toByteArray())
                }
                Toast.makeText(context, "Sonuçlar kaydedildi!", Toast.LENGTH_SHORT).show()
                viewModel.dismissSaveDialog()
            } catch (e: Exception) {
                Toast.makeText(context, "Kaydetme hatası: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // Kaydetme dialogu
    if (state.showSaveDialog && state.results.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSaveDialog() },
            title = { Text("Sonuçları Kaydet") },
            text = { Text("${state.results.size} geçerli hesap bulundu. Kaydetmek ister misiniz?") },
            confirmButton = {
                TextButton(onClick = {
                    saveFileLauncher.launch("iptv_hits_${System.currentTimeMillis()}.txt")
                }) {
                    Text("Kaydet")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSaveDialog() }) {
                    Text("Vazgeç")
                }
            }
        )
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
            // 📚 Bilgilendirme Kartı - Kullanım Kılavuzu
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
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📖 Nasıl Kullanılır?",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { showGuide = false }) {
                                Icon(Icons.Default.Close, "Kapat", modifier = Modifier.size(18.dp))
                            }
                        }
                        
                        Text(
                            text = "📁 Combo Dosyası: kullanici:sifre formatında hesap listesi yükleyin (1GB'a kadar desteklenir)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "🌐 Panel URL: Taramak istediğiniz IPTV panel adresini girin (örn: panel.site.com:8080)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "⚙️ Tarama Hızı: Yavaş=güvenli, Hızlı=normal, Saldırgan=maksimum hız (ban riski)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "🎭 Attack Modu: Farklı IPTV uygulamaları gibi davranarak tespit edilmeyi önler",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "💾 Kaydet: Bulunan geçerli hesapları dosyaya kaydedin",
                            style = MaterialTheme.typography.bodySmall
                        )
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        
                        Text(
                            text = "• ${com.alibaba.domain.model.EmbeddedPanels.panels.size} gömülü panel hazır",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
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

            // ⚙️ Tarama Ayarları - Hız ve Attack Modu
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "⚙️ Tarama Ayarları",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // Tarama Hızı
                    Text(
                        text = "⚡ Tarama Hızı: ${state.scanSpeed.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ScanSpeed.entries.forEach { speed ->
                            FilterChip(
                                selected = state.scanSpeed == speed,
                                onClick = { viewModel.setScanSpeed(speed) },
                                label = { 
                                    Text(
                                        speed.displayName.take(2), 
                                        style = MaterialTheme.typography.labelSmall
                                    ) 
                                },
                                enabled = !state.scanning,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Text(
                        text = "Eşzamanlı: ${state.scanSpeed.concurrency} | Gecikme: ${state.scanSpeed.delayMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    HorizontalDivider()
                    
                    // Attack Modu
                    Text(
                        text = "🎭 Attack Modu: ${state.attackMode.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    
                    var expandedAttackMode by remember { mutableStateOf(false) }
                    
                    ExposedDropdownMenuBox(
                        expanded = expandedAttackMode,
                        onExpandedChange = { if (!state.scanning) expandedAttackMode = it }
                    ) {
                        OutlinedTextField(
                            value = state.attackMode.displayName,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAttackMode) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            enabled = !state.scanning,
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        
                        ExposedDropdownMenu(
                            expanded = expandedAttackMode,
                            onDismissRequest = { expandedAttackMode = false }
                        ) {
                            AttackModeOption.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { 
                                        Column {
                                            Text(mode.displayName, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                mode.description, 
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.setAttackMode(mode)
                                        expandedAttackMode = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Panel Girişi - Elle panel URL yazma
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "🌐 Panel URL'si Girin",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.customPanelUrl,
                            onValueChange = { viewModel.setCustomPanelUrl(it) },
                            modifier = Modifier.weight(1f),
                            label = { Text("Panel URL") },
                            placeholder = { Text("örn: panel.example.com:8080") },
                            singleLine = true,
                            enabled = !state.scanning
                        )
                        
                        IconButton(
                            onClick = { viewModel.parseAndAddCustomPanel() },
                            enabled = !state.scanning && state.customPanelUrl.isNotBlank()
                        ) {
                            Icon(Icons.Default.Add, "Panel Ekle")
                        }
                    }
                    
                    // Eklenen paneller
                    if (state.selectedPanels.isNotEmpty()) {
                        Text(
                            text = "Eklenen Paneller (${state.selectedPanels.size}):",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        state.selectedPanels.forEach { panel ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "• ${panel.fullAddress}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                IconButton(
                                    onClick = { viewModel.removePanel(panel) },
                                    enabled = !state.scanning
                                ) {
                                    Icon(Icons.Default.Close, "Kaldır", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        
                        TextButton(
                            onClick = { viewModel.clearCustomPanels() },
                            enabled = !state.scanning
                        ) {
                            Text("Tümünü Temizle")
                        }
                    }
                    
                    HorizontalDivider()
                    
                    // Gömülü paneller seçeneği
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
                            text = "Gömülü panelleri de kullan (${com.alibaba.domain.model.EmbeddedPanels.panels.size} panel)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Start/Stop Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.scanning) {
                    Button(
                        onClick = { viewModel.stopScan() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Durdur")
                    }
                } else {
                    Button(
                        onClick = { viewModel.startScan() },
                        modifier = Modifier.weight(1f),
                        enabled = state.comboText.isNotBlank() && 
                                 (state.selectedPanels.isNotEmpty() || state.useEmbeddedPanels)
                    ) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Taramayı Başlat")
                    }
                }
                
                // Kaydet butonu
                if (state.results.isNotEmpty() && !state.scanning) {
                    OutlinedButton(
                        onClick = { 
                            saveFileLauncher.launch("iptv_hits_${System.currentTimeMillis()}.txt")
                        }
                    ) {
                        Icon(Icons.Default.Save, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Kaydet")
                    }
                }
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
                    ResultCard(
                        result = result,
                        onCopyM3u = { url ->
                            clipboardManager.setText(AnnotatedString(url))
                            Toast.makeText(context, "M3U linki kopyalandı", Toast.LENGTH_SHORT).show()
                        },
                        onCopyXtream = { info ->
                            clipboardManager.setText(AnnotatedString(info))
                            Toast.makeText(context, "Xtream bilgileri kopyalandı", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: com.alibaba.domain.model.PanelScanResult,
    onCopyM3u: (String) -> Unit = {},
    onCopyXtream: (String) -> Unit = {}
) {
    val m3uUrl = "http://${result.panel.fullAddress}/get.php?username=${result.account.username}&password=${result.account.password}&type=m3u_plus"
    val xstreamUrl = "http://${result.panel.fullAddress}"
    
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

            // URLs with Copy Buttons
            HorizontalDivider()
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🔗 M3U URL:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = m3uUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 2
                    )
                }
                IconButton(onClick = { onCopyM3u(m3uUrl) }) {
                    Icon(Icons.Default.ContentCopy, "M3U Kopyala", tint = MaterialTheme.colorScheme.primary)
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "📡 Xtream URL:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$xstreamUrl | ${result.account.username} | ${result.account.password}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(onClick = { onCopyXtream("$xstreamUrl\n${result.account.username}\n${result.account.password}") }) {
                    Icon(Icons.Default.ContentCopy, "Xtream Kopyala", tint = MaterialTheme.colorScheme.primary)
                }
            }
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
