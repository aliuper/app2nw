package com.alibaba.feature.auto

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.alibaba.domain.model.OutputDelivery
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

    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Throwable) {
                }
                viewModel.setOutputFolder(uri.toString())
            }
        }
    )

    AutoScreen(
        state = state,
        onInputChange = viewModel::onInputChange,
        onExtract = viewModel::extract,
        onToggleCountry = viewModel::toggleCountry,
        onMergeChange = viewModel::setMergeIntoSingle,
        onAutoDetectFormatChange = viewModel::setAutoDetectFormat,
        onFormatChange = viewModel::setOutputFormat,
        onPrev = viewModel::prevStep,
        onNext = viewModel::nextStep,
        onRun = viewModel::run,
        onPickFolder = { folderPicker.launch(null) },
        modifier = modifier
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AutoScreen(
    state: AutoUiState,
    onInputChange: (String) -> Unit,
    onExtract: () -> Unit,
    onToggleCountry: (String, Boolean) -> Unit,
    onMergeChange: (Boolean) -> Unit,
    onAutoDetectFormatChange: (Boolean) -> Unit,
    onFormatChange: (OutputFormat) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRun: () -> Unit,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    val context = LocalContext.current

    val actualStep = state.step.coerceIn(0, 3)
    val stepCount = if (state.enableCountryFiltering) 4 else 3
    val stepIndex = when {
        !state.enableCountryFiltering && actualStep >= 2 -> actualStep - 1
        else -> actualStep
    }.coerceIn(0, stepCount - 1)

    val stepTitle = when (actualStep) {
        0 -> "${stepIndex + 1}/${stepCount} Link / Metin"
        1 -> "${stepIndex + 1}/${stepCount} Ülke Seç"
        2 -> "${stepIndex + 1}/${stepCount} Çıktı Formatı"
        else -> "${stepIndex + 1}/${stepCount} Tek/Çok Dosya"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Otomatik") },
                actions = {
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Ayarlar: Ana menüden açabilirsiniz", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(imageVector = Icons.Filled.Tune, contentDescription = "Ayarlar")
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
            Text(text = stepTitle, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { ((stepIndex + 1) / stepCount.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )

            state.progressStep?.let { step ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(imageVector = Icons.Filled.Bolt, contentDescription = null)
                            Text(text = step, style = MaterialTheme.typography.titleMedium)
                        }
                        LinearProgressIndicator(
                            progress = { (state.progressPercent.coerceIn(0, 100) / 100f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "%${state.progressPercent}")
                            state.etaSeconds?.let { eta -> Text(text = "Kalan: ${eta}s") }
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

            if (state.loading && state.extractedUrls.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(text = "İlerleme", style = MaterialTheme.typography.titleMedium)
                        state.extractedUrls.forEachIndexed { index, item ->
                            val statusColor = when (item.success) {
                                true -> MaterialTheme.colorScheme.primary
                                false -> MaterialTheme.colorScheme.error
                                null -> MaterialTheme.colorScheme.onSurface
                            }
                            val icon = when (item.success) {
                                true -> Icons.Filled.CheckCircle
                                false -> Icons.Filled.Warning
                                null -> Icons.Filled.PlayArrow
                            }
                            val suffix = buildString {
                                item.status?.let { append(" - "); append(it) }
                                if (item.testedStreams > 0) {
                                    append(" (${item.testedStreams} test)")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(imageVector = icon, contentDescription = null, tint = statusColor)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "${index + 1}. ${item.url}", color = statusColor)
                                    if (suffix.isNotBlank()) {
                                        Text(
                                            text = suffix.trim(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = statusColor
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            when (stepIndex) {
                0 -> {
                    OutlinedTextField(
                        value = state.inputText,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        label = { Text(text = "Metin / Linkler") },
                        minLines = 6,
                        maxLines = 10
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onExtract, enabled = !state.loading) {
                            Icon(imageVector = Icons.Filled.Link, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Linkleri Ayıkla")
                        }
                    }

                    if (state.extractedUrls.isNotEmpty()) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(text = "Bulunan Linkler", style = MaterialTheme.typography.titleMedium)
                                state.extractedUrls.forEachIndexed { index, item ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(imageVector = Icons.Filled.Link, contentDescription = null)
                                        Text(text = "${index + 1}. ${item.url}")
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    if (!state.enableCountryFiltering) return@Column
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
                }

                2 -> {
                    Text(text = "Çıktı Formatı", style = MaterialTheme.typography.titleMedium)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.autoDetectFormat, onCheckedChange = { onAutoDetectFormatChange(it) })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Otomatik belirle (önerilen)")
                }

                OutputFormat.values().forEach { format ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = state.outputFormat == format,
                            onClick = { onFormatChange(format) },
                            enabled = !state.autoDetectFormat
                        )
                        Text(text = when (format) {
                            OutputFormat.M3U -> ".m3u"
                            OutputFormat.M3U8 -> ".m3u8"
                            OutputFormat.M3U8PLUS -> ".m3u8plus"
                        })
                    }
                }
            }

                else -> {
                    Text(text = "Tek / Çok Dosya", style = MaterialTheme.typography.titleMedium)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.mergeIntoSingle, onCheckedChange = { onMergeChange(it) })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Tüm linkler tek playlist olsun")
                }

                if (state.mergeIntoSingle) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = "Uyarı", style = MaterialTheme.typography.titleMedium)
                            Text(text = "Tek dosyada birleştirirken aynı isimli gruplar varsa otomatik olarak 'Yedek 1..N' şeklinde yeniden adlandırılır.")
                            Text(text = "Örnek: 'TR Spor' -> 'TR Spor Yedek 1'", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = "Bilgi", style = MaterialTheme.typography.titleMedium)
                            Text(text = "Bu modda her link ayrı dosya olarak kaydedilir.")
                            Text(text = "Dosya adı: başlangıç_tarih + kaynak + v1.. + bitişTarihi (varsa)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                    if (state.outputDelivery == OutputDelivery.FILE) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(text = "Kayıt Klasörü", style = MaterialTheme.typography.titleMedium)
                                Text(text = state.outputFolderUriString ?: "Download/IPTV (varsayılan)", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = onPickFolder, enabled = !state.loading) {
                                    Icon(imageVector = Icons.Filled.FolderOpen, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = "Klasör Seç")
                                }
                            }
                        }
                    }

                state.mergeRenameWarning?.let { warning ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = "Gruplar yeniden adlandırıldı", style = MaterialTheme.typography.titleMedium)
                            Text(text = warning, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val canGoBack = stepIndex > 0 && !state.loading
                Button(onClick = onPrev, enabled = canGoBack) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Geri")
                }

                Box(modifier = Modifier.weight(1f))

                val canGoNext = !state.loading && when (actualStep) {
                    0 -> state.extractedUrls.isNotEmpty()
                    1 -> !state.enableCountryFiltering || state.selectedCountries.isNotEmpty()
                    else -> true
                }

                if (stepIndex < (stepCount - 1)) {
                    Button(onClick = onNext, enabled = canGoNext) {
                        Text(text = "İleri")
                    }
                } else {
                    Button(onClick = onRun, enabled = canGoNext && state.extractedUrls.isNotEmpty()) {
                        Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Başlat")
                    }
                }
            }

            if (state.savedFiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                    Icon(imageVector = Icons.Filled.Share, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = "Paylaş")
                                }
                            }
                        }
                    }
                }
            }

            state.reportText?.let { report ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = "Rapor", style = MaterialTheme.typography.titleMedium)
                        Text(text = report, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            state.outputPreview?.let { preview ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Önizleme", style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text(text = preview, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
