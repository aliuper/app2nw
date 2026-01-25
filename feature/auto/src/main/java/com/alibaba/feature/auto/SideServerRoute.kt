package com.alibaba.feature.auto

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SideServerRoute(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: SideServerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🌐 Yan Sunucu Bulucu") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    if (state.activeCount > 0) {
                        IconButton(onClick = {
                            val links = viewModel.copyActiveLinks()
                            clipboardManager.setText(AnnotatedString(links))
                            Toast.makeText(context, "${state.activeCount} aktif link kopyalandı", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Kopyala")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Açıklama
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "🔍 Reverse IP Lookup + IPTV Tespiti",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Kapanan IPTV linkinizin alternatif sunucularını bulur. Aynı IP'deki tüm domainleri tarar ve IPTV panellerini tespit eder.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Orijinal Link Girişi
            OutlinedTextField(
                value = state.originalLink,
                onValueChange = { viewModel.updateOriginalLink(it) },
                label = { Text("IPTV M3U Linki") },
                placeholder = { Text("http://example.com:8080/get.php?username=...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !state.isScanning
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Credentials
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = { viewModel.updateUsername(it) },
                    label = { Text("Kullanıcı Adı") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !state.isScanning
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { viewModel.updatePassword(it) },
                    label = { Text("Şifre") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !state.isScanning
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Hata Mesajı
            state.errorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Kontrol Butonları
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.isScanning) {
                    Button(
                        onClick = { viewModel.stopScan() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Durdur")
                    }
                } else {
                    Button(
                        onClick = { viewModel.startScan() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Yan Sunucu Ara")
                    }
                }
                
                OutlinedButton(
                    onClick = { viewModel.clearResults() },
                    enabled = !state.isScanning && state.results.isNotEmpty()
                ) {
                    Text("Temizle")
                }
            }

            // Progress
            if (state.isScanning || state.progressText.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                
                if (state.isScanning) {
                    LinearProgressIndicator(
                        progress = { state.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                Text(
                    text = state.progressText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sonuçlar
            if (state.results.isNotEmpty()) {
                Text(
                    text = "🎯 Bulunan Sunucular (${state.activeCount} aktif / ${state.results.size} toplam)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Önce aktif olanları göster
                    val sortedResults = state.results.sortedByDescending { it.isActive }
                    
                    items(sortedResults) { result ->
                        SideServerResultCard(
                            result = result,
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(result.m3uLink))
                                Toast.makeText(context, "Link kopyalandı", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SideServerResultCard(
    result: SideServerResultItem,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (result.isActive) 
                Color(0xFF1B5E20).copy(alpha = 0.2f) 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Icon
            Icon(
                imageVector = if (result.isActive) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (result.isActive) Color(0xFF4CAF50) else Color(0xFFE57373),
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.serverUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Text(
                    text = result.statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.isActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (result.isActive) {
                    Row {
                        result.expireDate?.let {
                            Text(
                                text = "📅 $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        result.maxConnections?.let {
                            Text(
                                text = "👥 $it bağlantı",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            // Copy Button (sadece aktif olanlar için)
            if (result.isActive) {
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Kopyala",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
