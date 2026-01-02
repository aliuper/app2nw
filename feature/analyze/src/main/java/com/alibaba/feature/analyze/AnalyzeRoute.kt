package com.alibaba.feature.analyze

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun AnalyzeRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AnalyzeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    AnalyzeScreen(
        state = state,
        onBack = onBack,
        onInputChange = viewModel::onInputChange,
        onQueryChange = viewModel::onQueryChange,
        onScopeChange = viewModel::setScope,
        onRun = viewModel::runSearch,
        modifier = modifier
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AnalyzeScreen(
    state: AnalyzeUiState,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onScopeChange: (SearchScope) -> Unit,
    onRun: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "IPTV Analiz") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Geri")
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
            Text(text = "Kaynak linkleri (alt alta)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.inputText,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                label = { Text(text = "M3U/M3U8 linkleri") },
                minLines = 4,
                maxLines = 10
            )

            Text(text = "Arama türü", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ScopeRadio("Tümü", state.scope == SearchScope.ALL) { onScopeChange(SearchScope.ALL) }
                ScopeRadio("Kanal", state.scope == SearchScope.CHANNEL) { onScopeChange(SearchScope.CHANNEL) }
                ScopeRadio("Film", state.scope == SearchScope.MOVIE) { onScopeChange(SearchScope.MOVIE) }
                ScopeRadio("Dizi", state.scope == SearchScope.SERIES) { onScopeChange(SearchScope.SERIES) }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "Arama metni") }
            )

            Button(onClick = onRun, enabled = !state.loading) {
                Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Başlat")
            }

            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.progressText?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
            }

            state.errorMessage?.let { msg ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = msg, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            state.reportText?.let { report ->
                Text(text = "Sonuç", style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text(text = report, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ScopeRadio(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = text)
    }
}
