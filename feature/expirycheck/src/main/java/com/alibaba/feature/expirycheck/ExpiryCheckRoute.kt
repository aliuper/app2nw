package com.alibaba.feature.expirycheck

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.alibaba.domain.model.*

@Composable
fun ExpiryCheckRoute(
    onNavigateBack: () -> Unit,
    viewModel: ExpiryCheckViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    ExpiryCheckScreen(
        state = state,
        onNavigateBack = onNavigateBack,
        onLinksTextChange = viewModel::setLinksText,
        onTogglePlayerApi = viewModel::toggleCheckPlayerApi,
        onToggleXmlTv = viewModel::toggleCheckXmlTv,
        onToggleM3u = viewModel::toggleCheckM3u,
        onToggleChannel = viewModel::toggleCheckChannel,
        onStartCheck = viewModel::startCheck,
        onStopCheck = viewModel::stopCheck,
        onClearResults = viewModel::clearResults,
        onCopyReport = {
            val report = viewModel.getReport()
            clipboardManager.setText(AnnotatedString(report))
            Toast.makeText(context, "Rapor kopyalandı", Toast.LENGTH_SHORT).show()
        }
    )
}

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpiryCheckScreen(
    state: ExpiryCheckState,
    onNavigateBack: () -> Unit,
    onLinksTextChange: (String) -> Unit,
    onTogglePlayerApi: () -> Unit,
    onToggleXmlTv: () -> Unit,
    onToggleM3u: () -> Unit,
    onToggleChannel: () -> Unit,
    onStartCheck: () -> Unit,
    onStopCheck: () -> Unit,
    onClearResults: () -> Unit,
    onCopyReport: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.DateRange,
                            contentDescription = null,
                            tint = Color(0xFF00FFFF),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Bitiş Tarihi Kontrolü",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D0D1A)
                )
            )
        },
        containerColor = Color(0xFF0D0D1A)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Progress veya Results
            if (state.checking) {
                ProgressView(progress = state.progress)
            } else if (state.results.isNotEmpty()) {
                ResultsView(
                    results = state.results,
                    summary = state.summary,
                    onClearResults = onClearResults,
                    onCopyReport = onCopyReport
                )
            } else {
                ConfigView(
                    state = state,
                    onLinksTextChange = onLinksTextChange,
                    onTogglePlayerApi = onTogglePlayerApi,
                    onToggleXmlTv = onToggleXmlTv,
                    onToggleM3u = onToggleM3u,
                    onToggleChannel = onToggleChannel,
                    onStartCheck = onStartCheck
                )
            }

            // Error Message
            state.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF330000)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error,
                        color = Color(0xFFFF4444),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ConfigView(
    state: ExpiryCheckState,
    onLinksTextChange: (String) -> Unit,
    onTogglePlayerApi: () -> Unit,
    onToggleXmlTv: () -> Unit,
    onToggleM3u: () -> Unit,
    onToggleChannel: () -> Unit,
    onStartCheck: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Links Input
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "📋 IPTV Linkleri",
                    color = Color(0xFF00FFFF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Her satıra bir link girin (M3U, Xtream Codes vb.)",
                    color = Color(0xFF888888),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.linksText,
                    onValueChange = onLinksTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    placeholder = { 
                        Text(
                            "http://server.com:8080/get.php?username=xxx&password=yyy\n" +
                            "http://server.com/live/user/pass/1.ts",
                            color = Color(0xFF555555)
                        ) 
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00FFFF),
                        unfocusedBorderColor = Color(0xFF404040),
                        cursorColor = Color(0xFF00FFFF)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Check Options
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "🔧 Kontrol Yöntemleri",
                    color = Color(0xFF00FFFF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                CheckOptionRow(
                    title = "Player API",
                    description = "En güvenilir yöntem (player_api.php)",
                    checked = state.checkPlayerApi,
                    onToggle = onTogglePlayerApi
                )

                CheckOptionRow(
                    title = "XMLTV",
                    description = "EPG bilgisi üzerinden kontrol",
                    checked = state.checkXmlTv,
                    onToggle = onToggleXmlTv
                )

                CheckOptionRow(
                    title = "M3U Dosyası",
                    description = "Playlist erişilebilirlik kontrolü",
                    checked = state.checkM3u,
                    onToggle = onToggleM3u
                )

                CheckOptionRow(
                    title = "Kanal Testi",
                    description = "Canlı yayın akışı kontrolü",
                    checked = state.checkChannel,
                    onToggle = onToggleChannel
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Start Button
        Button(
            onClick = onStartCheck,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFFF)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Kontrol Başlat",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
fun CheckOptionRow(
    title: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFF00FFFF),
                uncheckedColor = Color(0xFF666666)
            )
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Medium)
            Text(description, color = Color(0xFF888888), fontSize = 12.sp)
        }
    }
}

@Composable
fun ProgressView(progress: ExpiryCheckProgress?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                color = Color(0xFF00FFFF),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            progress?.let {
                Text(
                    "${it.current} / ${it.total}",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                LinearProgressIndicator(
                    progress = { it.percentage / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = Color(0xFF00FFFF),
                    trackColor = Color(0xFF333333)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    it.phase,
                    color = Color(0xFF888888),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    it.currentLink,
                    color = Color(0xFF666666),
                    fontSize = 12.sp,
                    maxLines = 1
                )
            } ?: Text(
                "Başlatılıyor...",
                color = Color(0xFF888888),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun ResultsView(
    results: List<ExpiryCheckResult>,
    summary: ExpiryCheckSummary?,
    onClearResults: () -> Unit,
    onCopyReport: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Summary Card
        summary?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "📊 Özet",
                        color = Color(0xFF00FFFF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatItem("Toplam", it.total.toString(), Color.White)
                        StatItem("Aktif", it.active.toString(), Color(0xFF00FF00))
                        StatItem("Yakında", it.expiringSoon.toString(), Color(0xFFFFA500))
                        StatItem("Dolmuş", it.expired.toString(), Color(0xFFFF0000))
                        StatItem("Hata", it.errors.toString(), Color(0xFF888888))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onClearResults,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Temizle")
            }
            Button(
                onClick = onCopyReport,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFFF))
            ) {
                Icon(Icons.Default.Share, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Rapor Kopyala", color = Color.Black)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Results List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(results) { result ->
                ResultCard(result = result)
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = color,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            color = Color(0xFF888888),
            fontSize = 12.sp
        )
    }
}

@Composable
fun ResultCard(result: ExpiryCheckResult) {
    val statusColor = when (result.status) {
        ExpiryStatus.ACTIVE -> Color(0xFF00FF00)
        ExpiryStatus.EXPIRING_SOON -> Color(0xFFFFA500)
        ExpiryStatus.EXPIRED -> Color(0xFFFF0000)
        ExpiryStatus.INVALID_CREDENTIALS -> Color(0xFFFF4444)
        else -> Color(0xFF888888)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(statusColor, RoundedCornerShape(6.dp))
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.username,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    result.server,
                    color = Color(0xFF888888),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    result.expiryDateFormatted,
                    color = statusColor,
                    fontSize = 12.sp
                )
            }
            
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    result.status.displayName,
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                result.daysRemaining?.let { days ->
                    Text(
                        if (days >= 0) "$days gün" else "${-days} gün önce",
                        color = Color(0xFF888888),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
