package com.alibaba.feature.auto

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.ContentCopy
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

    var pendingSaveText by remember { mutableStateOf<String?>(null) }
    var pendingSaveName by remember { mutableStateOf<String?>(null) }
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
            pendingSaveName = null
        }
    )

    AutoScreen(
        state = state,
        onInputChange = viewModel::onInputChange,
        onExtract = viewModel::extract,
        onClear = viewModel::clearAll,
        onToggleCountry = viewModel::toggleCountry,
        onToggleWorkingUrl = viewModel::toggleWorkingUrl,
        onMergeChange = viewModel::setMergeIntoSingle,
        onAutoDetectFormatChange = viewModel::setAutoDetectFormat,
        onFormatChange = viewModel::setOutputFormat,
        onPrev = viewModel::prevStep,
        onNext = viewModel::nextStep,
        onRun = viewModel::run,
        onPickFolder = { folderPicker.launch(null) },
        onSaveText = { suggestedName, text ->
            pendingSaveName = suggestedName
            pendingSaveText = text
            textSaver.launch(suggestedName)
        },
        modifier = modifier
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AutoScreen(
    state: AutoUiState,
    onInputChange: (String) -> Unit,
    onExtract: () -> Unit,
    onClear: () -> Unit,
    onToggleCountry: (String, Boolean) -> Unit,
    onToggleWorkingUrl: (String, Boolean) -> Unit,
    onMergeChange: (Boolean) -> Unit,
    onAutoDetectFormatChange: (Boolean) -> Unit,
    onFormatChange: (OutputFormat) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRun: () -> Unit,
    onPickFolder: () -> Unit,
    onSaveText: (suggestedName: String, text: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val maxStep = if (state.outputDelivery == OutputDelivery.LINKS) {
        if (state.enableCountryFiltering) 2 else 1
    } else {
        if (state.enableCountryFiltering) 3 else 2
    }
    val actualStep = state.step.coerceIn(0, maxStep)

    val visibleSteps = buildList {
        add("Link / Metin")
        if (state.enableCountryFiltering) add("Ülke Seç")
        if (state.outputDelivery == OutputDelivery.FILE) {
            add("Çıktı Formatı")
            add("Tek/Çok Dosya")
        } else {
            add("Başlat")
        }
    }
    val stepCount = visibleSteps.size
    val stepIndex = actualStep.coerceIn(0, stepCount - 1)
    val stepTitle = "${stepIndex + 1}/${stepCount} ${visibleSteps[stepIndex]}"

    val inputStepIndex = 0
    val countryStepIndex = if (state.enableCountryFiltering) 1 else null
    val outputFormatStepIndex = if (state.outputDelivery == OutputDelivery.FILE) {
        if (state.enableCountryFiltering) 2 else 1
    } else null
    val mergeStepIndex = if (state.outputDelivery == OutputDelivery.FILE) {
        if (state.enableCountryFiltering) 3 else 2
    } else null
    val startStepIndex = if (state.outputDelivery == OutputDelivery.LINKS) {
        if (state.enableCountryFiltering) 2 else 1
    } else null

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
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val canGoBack = stepIndex > 0 && !state.loading
                Button(onClick = onPrev, enabled = canGoBack) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Geri")
                }

                Box(modifier = Modifier.weight(1f))

                val canGoNext = !state.loading && when {
                    stepIndex == inputStepIndex -> state.extractedUrls.isNotEmpty()
                    countryStepIndex != null && stepIndex == countryStepIndex -> state.selectedCountries.isNotEmpty()
                    else -> true
                }

                if (stepIndex < (stepCount - 1)) {
                    Button(onClick = onNext, enabled = canGoNext) {
                        Text(text = "İleri")
                    }
                } else {
                    // LINKS modunda test otomatik başlar; FILE modunda burada Başlat butonu kalır.
                    if (state.outputDelivery == OutputDelivery.FILE) {
                        Button(onClick = onRun, enabled = canGoNext && state.extractedUrls.isNotEmpty()) {
                            Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Başlat")
                        }
                    } else {
                        Button(onClick = {}, enabled = false) {
                            Text(text = if (state.loading) "Test ediliyor" else "Bitti")
                        }
                    }
                }
            }
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
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                        ) {
                            itemsIndexed(state.extractedUrls, key = { _, item -> item.url }) { index, item ->
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
                                        append(" (").append(item.testedStreams).append(" test)")
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
            }

            when {
                stepIndex == inputStepIndex -> {
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

                    if (state.extractedUrls.isNotEmpty()) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(text = "Bulunan Linkler (${state.extractedUrls.size})", style = MaterialTheme.typography.titleMedium)
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                ) {
                                    itemsIndexed(state.extractedUrls, key = { _, item -> item.url }) { index, item ->
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
                }

                countryStepIndex != null && stepIndex == countryStepIndex -> {
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

                outputFormatStepIndex != null && stepIndex == outputFormatStepIndex -> {
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

                startStepIndex != null && stepIndex == startStepIndex -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(text = "Link modu", style = MaterialTheme.typography.titleMedium)
                                Text(text = "Bu modda dosya kaydı yapılmaz. Test bittiğinde çalışan linkler aşağıda listelenir.")
                            }
                        }

                        if (state.loading) {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(text = "Test devam ediyor...", style = MaterialTheme.typography.titleMedium)
                                    Text(text = state.progressStep ?: "")
                                }
                            }
                        }

                        if (!state.loading && state.workingUrls.isNotEmpty()) {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(text = "Çalışan Linkler (${state.workingUrls.size})", style = MaterialTheme.typography.titleMedium)

                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        val selectedText = state.workingUrls
                                            .asSequence()
                                            .filter { it in state.selectedWorkingUrls }
                                            .joinToString("\n")

                                        Button(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(selectedText))
                                                Toast.makeText(context, "Kopyalandı", Toast.LENGTH_SHORT).show()
                                            },
                                            enabled = selectedText.isNotBlank()
                                        ) {
                                            Icon(imageVector = Icons.Filled.ContentCopy, contentDescription = null)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(text = "Seçileni Kopyala")
                                        }

                                        Button(
                                            onClick = {
                                                onSaveText("secili_linkler.txt", selectedText)
                                            },
                                            enabled = selectedText.isNotBlank()
                                        ) {
                                            Text(text = "Txt")
                                        }
                                    }

                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 320.dp)
                                    ) {
                                        itemsIndexed(state.workingUrls, key = { _, u -> u }) { _, url ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Checkbox(
                                                    checked = url in state.selectedWorkingUrls,
                                                    onCheckedChange = { checked -> onToggleWorkingUrl(url, checked) }
                                                )
                                                Text(text = url, modifier = Modifier.weight(1f))
                                            }
                                            HorizontalDivider()
                                        }
                                    }
                                }
                            }
                        }
                }

                mergeStepIndex != null && stepIndex == mergeStepIndex -> {
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

                else -> Unit
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
                                Icon(imageVector = Icons.Filled.Share, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = "Paylaş")
                            }
                            Button(
                                onClick = {
                                    onSaveText("rapor.txt", report)
                                }
                            ) {
                                Text(text = "Txt")
                            }
                        }
                        Text(text = report, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            state.outputPreview?.let { preview ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Önizleme", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(preview))
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
                                putExtra(Intent.EXTRA_TEXT, preview)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    ) {
                        Icon(imageVector = Icons.Filled.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Paylaş")
                    }
                    Button(
                        onClick = {
                            onSaveText("linkler.txt", preview)
                        }
                    ) {
                        Text(text = "Txt")
                    }
                }
                SelectionContainer {
                    Text(text = preview, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
