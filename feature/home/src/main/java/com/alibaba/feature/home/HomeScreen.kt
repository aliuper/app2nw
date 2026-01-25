package com.alibaba.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MenuItem(
    val emoji: String,
    val title: String,
    val subtitle: String,
    val color: Color,
    val onClick: () -> Unit
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HomeScreen(
    onManualClick: () -> Unit,
    onAutoClick: () -> Unit,
    onAnalyzeClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit = {},
    onCompareClick: () -> Unit = {},
    onPanelScanClick: () -> Unit = {},
    onExploitTestClick: () -> Unit = {},
    onExpiryCheckClick: () -> Unit = {},
    onSideServerClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val menuItems = listOf(
        MenuItem("✏️", "Manuel", "Tek link düzenle", Color(0xFF2196F3), onManualClick),
        MenuItem("⚡", "Otomatik Test", "Toplu link testi", Color(0xFF4CAF50), onAutoClick),
        MenuItem("🔍", "Link Ara", "Web'den link bul", Color(0xFF9C27B0), onSearchClick),
        MenuItem("📊", "IPTV Analiz", "Link detay analizi", Color(0xFFFF9800), onAnalyzeClick),
        MenuItem("🌐", "Yan Sunucu", "Alternatif sunucu bul", Color(0xFFE91E63), onSideServerClick),
        MenuItem("📅", "Bitiş Tarihi", "Abonelik kontrolü", Color(0xFF00BCD4), onExpiryCheckClick),
        MenuItem("🔄", "Karşılaştır", "İki listeyi karşılaştır", Color(0xFF795548), onCompareClick),
        MenuItem("📡", "Panel Tara", "IPTV panel tarayıcı", Color(0xFF607D8B), onPanelScanClick),
        MenuItem("🔓", "Güvenlik", "Panel güvenlik testi", Color(0xFFF44336), onExploitTestClick)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "📺",
                            fontSize = 24.sp
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Column {
                            Text(
                                text = "Alibaba IPTV",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "IPTV Editör & Test Aracı",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = "Ayarlar")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Grid menü
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(menuItems) { item ->
                    MenuCard(item = item)
                }
            }
        }
    }
}

@Composable
private fun MenuCard(item: MenuItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clickable { item.onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = item.color.copy(alpha = 0.15f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = item.emoji,
                fontSize = 32.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = item.color
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
