package com.alibaba.feature.manual

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.alibaba.domain.model.OutputFormat
import android.content.Intent
import android.net.Uri

@Composable
fun ManualRoute(
    modifier: Modifier = Modifier,
    viewModel: ManualViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    ManualScreen(
        state = state,
        onUrlChange = viewModel::onUrlChange,
        onAnalyze = viewModel::analyze,
        onToggleGroup = viewModel::onToggleGroup,
        onAutoDetectFormatChange = viewModel::onAutoDetectFormatChange,
        onOutputFormatChange = viewModel::onOutputFormatChange,
        onGenerate = viewModel::generateOutput,
        onSave = viewModel::saveOutput,
        modifier = modifier
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ManualScreen(
    state: ManualUiState,
    onUrlChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onToggleGroup: (String, Boolean) -> Unit,
    onAutoDetectFormatChange: (Boolean) -> Unit,
    onOutputFormatChange: (OutputFormat) -> Unit,
    onGenerate: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Manuel") }
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

        OutlinedTextField(
            value = state.url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = "IPTV Link") },
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAnalyze, enabled = !state.loading) {
                Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Analiz Et")
            }

            if (state.loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text(text = "İşleniyor...")
                }
            }
        }

        if (state.progressStep != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(imageVector = Icons.Filled.Bolt, contentDescription = null)
                        Text(text = state.progressStep, style = MaterialTheme.typography.titleMedium)
                    }
                    LinearProgressIndicator(
                        progress = { (state.progressPercent.coerceIn(0, 100) / 100f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(text = "%${state.progressPercent}")
                    state.etaSeconds?.let { eta ->
                        Text(text = "Kalan: ${eta}s")
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

        if (state.groups.isNotEmpty()) {
            Text(text = "Gruplar", style = MaterialTheme.typography.titleMedium)

            state.groups.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = item.selected,
                        onCheckedChange = { checked -> onToggleGroup(item.name, checked) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${item.name} (${item.channelCount})",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Format", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = state.autoDetectFormat,
                    onCheckedChange = { onAutoDetectFormatChange(it) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Otomatik belirle")
            }

            OutputFormat.values().forEach { format ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = state.outputFormat == format,
                        onClick = {
                            if (!state.autoDetectFormat) onOutputFormatChange(format)
                        }
                    )
                    Text(text = when (format) {
                        OutputFormat.M3U -> ".m3u"
                        OutputFormat.M3U8 -> ".m3u8"
                        OutputFormat.M3U8PLUS -> ".m3u8plus"
                    })
                }
            }

            Button(
                onClick = onGenerate,
                enabled = !state.loading && state.groups.any { it.selected }
            ) {
                Icon(imageVector = Icons.Filled.Bolt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Çıktı Üret")
            }
        }

        state.outputText?.let { text ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Çıktı", style = MaterialTheme.typography.titleMedium)

            Button(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                Icon(imageVector = Icons.Filled.ContentCopy, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Kopyala")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave, enabled = !state.loading) {
                    Icon(imageVector = Icons.Filled.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Download/IPTV'ye Kaydet")
                }

                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    }
                ) {
                    Icon(imageVector = Icons.Filled.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Paylaş")
                }
            }

            state.savedDisplayName?.let { name ->
                Text(text = "Kaydedildi: ${name}")
            }

            state.savedUriString?.let { uriString ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            val uri = Uri.parse(uriString)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/octet-stream")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        }
                    ) {
                        Icon(imageVector = Icons.Filled.OpenInNew, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Aç")
                    }
                    Button(
                        onClick = {
                            val uri = Uri.parse(uriString)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    ) {
                        Icon(imageVector = Icons.Filled.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Dosyayı Paylaş")
                    }
                }
            }

            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall
            )
        }
        }
    }
}
