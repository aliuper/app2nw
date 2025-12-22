package com.alibaba.feature.auto

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.alibaba.domain.model.OutputFormat

private val defaultCountries = listOf(
    "TR", "DE", "AT", "RO", "NL", "FR", "IT", "ES", "UK", "US"
)

@Composable
fun AutoRoute(
    modifier: Modifier = Modifier,
    viewModel: AutoViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    AutoScreen(
        state = state,
        onInputChange = viewModel::onInputChange,
        onExtract = viewModel::extract,
        onToggleCountry = viewModel::toggleCountry,
        onMergeChange = viewModel::setMergeIntoSingle,
        onFormatChange = viewModel::setOutputFormat,
        onRun = viewModel::run,
        modifier = modifier
    )
}

@Composable
fun AutoScreen(
    state: AutoUiState,
    onInputChange: (String) -> Unit,
    onExtract: () -> Unit,
    onToggleCountry: (String, Boolean) -> Unit,
    onMergeChange: (Boolean) -> Unit,
    onFormatChange: (OutputFormat) -> Unit,
    onRun: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Otomatik Mod", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = state.inputText,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = "Metin / Linkler") },
            minLines = 6
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onExtract, enabled = !state.loading) { Text(text = "Linkleri Ayıkla") }
            Button(onClick = onRun, enabled = !state.loading && state.extractedUrls.isNotEmpty()) { Text(text = "Çalıştır") }
        }

        state.progressStep?.let {
            Text(text = it)
            LinearProgressIndicator(
                progress = { (state.progressPercent.coerceIn(0, 100) / 100f) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(text = "%${state.progressPercent}")
            state.etaSeconds?.let { eta -> Text(text = "Kalan: ${eta}s") }
        }

        state.errorMessage?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }

        if (state.extractedUrls.isNotEmpty()) {
            Text(text = "Linkler", style = MaterialTheme.typography.titleMedium)
            state.extractedUrls.forEach { item ->
                Text(text = "- ${item.url}  ${item.status ?: ""}")
            }
        }

        Text(text = "Ülke Seç", style = MaterialTheme.typography.titleMedium)
        defaultCountries.forEach { code ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = code in state.selectedCountries,
                    onCheckedChange = { checked -> onToggleCountry(code, checked) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = code)
            }
        }

        Text(text = "Çıktı", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.mergeIntoSingle, onCheckedChange = { onMergeChange(it) })
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Tüm linkler tek playlist olsun")
        }

        OutputFormat.values().forEach { format ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.outputFormat == format, onClick = { onFormatChange(format) })
                Text(text = when (format) {
                    OutputFormat.M3U -> ".m3u"
                    OutputFormat.M3U8 -> ".m3u8"
                    OutputFormat.M3U8PLUS -> ".m3u8plus"
                })
            }
        }

        if (state.savedFiles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Kaydedilen Dosyalar", style = MaterialTheme.typography.titleMedium)
            state.savedFiles.forEach { f ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = f.displayName, modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            val uri = Uri.parse(f.uriString)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    ) {
                        Text(text = "Paylaş")
                    }
                }
            }
        }

        state.outputPreview?.let { preview ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Önizleme", style = MaterialTheme.typography.titleMedium)
            Text(text = preview, style = MaterialTheme.typography.bodySmall)
        }
    }
}
