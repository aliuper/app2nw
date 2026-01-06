package com.alibaba.feature.analyze

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Composable
fun AnalyzeRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AnalyzeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    val context = LocalContext.current
    var pendingSaveText by remember { mutableStateOf<String?>(null) }
    val textSaver = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            val text = pendingSaveText
            if (uri != null && text != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(text.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(context, "Kaydedildi", Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    Toast.makeText(context, t.message ?: "Kaydedilemedi", Toast.LENGTH_SHORT).show()
                }
            }
            pendingSaveText = null
        }
    )

    AnalyzeScreen(
        state = state,
        onBack = onBack,
        onInputChange = viewModel::onInputChange,
        onQueryChange = viewModel::onQueryChange,
        onScopeChange = viewModel::setScope,
        onToggleStopOnFirstMatch = viewModel::toggleStopOnFirstMatch,
        onRun = viewModel::runSearch,
        onClear = viewModel::clearAll,
        onSaveText = { name, text ->
            pendingSaveText = text
            textSaver.launch(name)
        },
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
    onToggleStopOnFirstMatch: () -> Unit,
    onRun: () -> Unit,
    onClear: () -> Unit,
    onSaveText: (suggestedName: String, text: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "IPTV Analiz") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
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

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                Button(onClick = onClear, enabled = !state.loading) {
                    Text(text = "Temizle")
                }
            }

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

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.Checkbox(
                    checked = state.stopOnFirstMatch,
                    onCheckedChange = { onToggleStopOnFirstMatch() },
                    enabled = !state.loading
                )
                Text(
                    text = "İlk eşleşmede dur (daha hızlı)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(report))
                            Toast.makeText(context, "Kopyalandı", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(imageVector = Icons.Filled.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Kopyala")
                    }
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, report)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    ) {
                        Text(text = "Paylaş")
                    }
                    Button(
                        onClick = {
                            onSaveText("analiz.txt", report)
                        }
                    ) {
                        Text(text = "Txt")
                    }
                }
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
