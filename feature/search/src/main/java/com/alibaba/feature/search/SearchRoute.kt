package com.alibaba.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
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
import androidx.hilt.navigation.compose.hiltViewModel
import android.widget.Toast

@Composable
fun SearchRoute(
    onNavigateBack: () -> Unit,
    onStartAutoTest: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    SearchScreen(
        state = state,
        onNavigateBack = onNavigateBack,
        onQueryChange = viewModel::setQuery,
        onMaxResultsChange = viewModel::setMaxResults,
        onToggleOnlyTurkey = viewModel::toggleOnlyTurkey,
        onToggleOnlyPanelUrls = viewModel::toggleOnlyPanelUrls,
        onKeywordFilterChange = viewModel::setKeywordFilter,
        onSearch = viewModel::search,
        onToggleUrl = viewModel::toggleUrlSelection,
        onSelectAll = viewModel::selectAll,
        onDeselectAll = viewModel::deselectAll,
        onCopyAll = {
            val urls = viewModel.getSelectedUrlsText()
            if (urls.isNotBlank()) {
                clipboardManager.setText(AnnotatedString(urls))
                Toast.makeText(context, "${state.selectedUrls.size} link kopyalandı", Toast.LENGTH_SHORT).show()
            }
        },
        onStartAutoTest = {
            val urls = viewModel.getSelectedUrlsText()
            onStartAutoTest(urls)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchState,
    onNavigateBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onMaxResultsChange: (Int) -> Unit,
    onToggleOnlyTurkey: () -> Unit,
    onToggleOnlyPanelUrls: () -> Unit,
    onKeywordFilterChange: (String) -> Unit,
    onSearch: () -> Unit,
    onToggleUrl: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onCopyAll: () -> Unit,
    onStartAutoTest: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🔍 Link Ara & Test Et") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D0D0D),
                    titleContentColor = Color(0xFF00FF41)
                )
            )
        },
        containerColor = Color(0xFF0D0D0D)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Search input
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Arama Sorgusu",
                        color = Color(0xFF00FF41),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("filename:\"get.php?username\"") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00FF41),
                            unfocusedBorderColor = Color(0xFF404040),
                            cursorColor = Color(0xFF00FF41)
                        ),
                        enabled = !state.searching
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Filters
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = state.onlyTurkey,
                            onCheckedChange = { onToggleOnlyTurkey() },
                            enabled = !state.searching,
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF00FF41),
                                uncheckedColor = Color(0xFF404040)
                            )
                        )
                        Text(
                            text = "Sadece TR (IP/TR veya Türkçe ipucu)",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = state.onlyPanelUrls,
                            onCheckedChange = { onToggleOnlyPanelUrls() },
                            enabled = !state.searching,
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF00FF41),
                                uncheckedColor = Color(0xFF404040)
                            )
                        )
                        Text(
                            text = "Sadece Panel URL (get.php / player_api.php)",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    OutlinedTextField(
                        value = state.keywordFilter,
                        onValueChange = onKeywordFilterChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Örn: turk türk türkiye") },
                        label = { Text("Kelime Filtresi") },
                        enabled = !state.searching,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00FF41),
                            unfocusedBorderColor = Color(0xFF404040),
                            cursorColor = Color(0xFF00FF41),
                            focusedLabelColor = Color(0xFF00FFFF),
                            unfocusedLabelColor = Color(0xFF00FFFF)
                        )
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Maks:", color = Color(0xFF00FFFF))
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedTextField(
                                value = state.maxResults.toString(),
                                onValueChange = { onMaxResultsChange(it.toIntOrNull() ?: 500) },
                                modifier = Modifier.width(100.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF00FF41),
                                    unfocusedBorderColor = Color(0xFF404040)
                                ),
                                enabled = !state.searching
                            )
                        }
                        
                        Button(
                            onClick = onSearch,
                            enabled = !state.searching,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00FF41),
                                contentColor = Color.Black
                            )
                        ) {
                            Icon(Icons.Default.Search, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (state.searching) "Aranıyor..." else "ARA")
                        }
                    }
                    
                    // Quick examples
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Örnekler:", color = Color(0xFF00FFFF), fontSize = MaterialTheme.typography.bodySmall.fontSize)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            "filename:\"get.php?username\"",
                            "filename:\".m3u\"",
                            "filename:\".m3u8\""
                        ).forEach { example ->
                            TextButton(
                                onClick = { onQueryChange(example) },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF00FFFF))
                            ) {
                                Text(example, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Error message
            state.errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF330000))
                ) {
                    Text(
                        error,
                        modifier = Modifier.padding(16.dp),
                        color = Color(0xFFFF0055)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Loading indicator
            if (state.searching) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF00FF41))
                }
            }
            
            // Results header
            if (state.results.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Bulunan: ${state.results.size} / ${state.totalAvailable}",
                                color = Color(0xFF00FF41),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Seçili: ${state.selectedUrls.size}",
                                color = Color(0xFF00FFFF),
                                fontSize = MaterialTheme.typography.bodySmall.fontSize
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Action buttons - horizontal layout
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onSelectAll,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF00FFFF)
                            )
                        ) {
                            Text("✓ Tümünü Seç")
                        }
                        
                        OutlinedButton(
                            onClick = onDeselectAll,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF00FFFF)
                            )
                        ) {
                            Text("✗ Temizle")
                        }
                        
                        Button(
                            onClick = onCopyAll,
                            modifier = Modifier.weight(1f),
                            enabled = state.selectedUrls.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0066CC),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Kopyala")
                        }
                        
                        Button(
                            onClick = onStartAutoTest,
                            modifier = Modifier.weight(1f),
                            enabled = state.selectedUrls.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00FF41),
                                contentColor = Color.Black
                            )
                        ) {
                            Text("🚀 TEST")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Results list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.results) { result ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleUrl(result.url) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (result.url in state.selectedUrls) {
                                Color(0xFF003300)
                            } else {
                                Color(0xFF1A1A1A)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = result.url in state.selectedUrls,
                                onCheckedChange = { onToggleUrl(result.url) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF00FF41),
                                    uncheckedColor = Color(0xFF404040)
                                )
                            )
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    result.url,
                                    color = Color(0xFF00FF41),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("🌐 ${result.domain}", color = Color(0xFF00FFFF), fontSize = MaterialTheme.typography.bodySmall.fontSize)
                                    Text("📍 ${result.country}", color = Color(0xFF00FFFF), fontSize = MaterialTheme.typography.bodySmall.fontSize)
                                    Text("📊 ${result.status}", color = Color(0xFF00FFFF), fontSize = MaterialTheme.typography.bodySmall.fontSize)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
