package com.alibaba.feature.auto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    initialUrls: String? = null,
    viewModel: AutoViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    
    LaunchedEffect(initialUrls) {
        if (!initialUrls.isNullOrBlank()) {
            viewModel.setUrls(initialUrls)
        }
    }

    val context = LocalContext.current
    
    // Battery optimization exemption launcher
    var showBatteryDialog by remember { mutableStateOf(false) }
    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // After user returns from settings, start the test
        viewModel.run()
    }
    
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
        onTurboModeChange = viewModel::setTurboMode,
        onParallelModeChange = viewModel::setParallelMode,
        onParallelCountChange = viewModel::setParallelCount,
        onLimitPerServerChange = viewModel::setLimitPerServer,
        onMaxLinksPerServerChange = viewModel::setMaxLinksPerServer,
        onResumeTest = viewModel::resumeTest,
        onDismissInterrupted = viewModel::dismissInterruptedTest,
        onClearRecoveredLinks = viewModel::clearRecoveredLinks,
        onPrev = viewModel::prevStep,
        onNext = viewModel::nextStep,
        onRun = {
            // Check battery optimization before starting test
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                
                if (!isIgnoringBatteryOptimizations) {
                    showBatteryDialog = true
                } else {
                    viewModel.run()
                }
            } else {
                viewModel.run()
            }
        },
        showBatteryDialog = showBatteryDialog,
        onDismissBatteryDialog = { showBatteryDialog = false },
        onRequestBatteryExemption = {
            showBatteryDialog = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                batteryOptimizationLauncher.launch(intent)
            }
        },
        onPickFolder = { folderPicker.launch(null) },
        onSaveText = { suggestedName, text ->
            pendingSaveName = suggestedName
            pendingSaveText = text
            textSaver.launch(suggestedName)
        },
        // Yan panel bulma callback'leri
        onSidePanelUrlChange = viewModel::setSidePanelUrl,
        onSearchSidePanels = viewModel::searchSidePanels,
        onToggleSidePanelSelection = viewModel::toggleSidePanelSelection,
        onSelectAllSidePanels = viewModel::selectAllSidePanels,
        onAddSelectedSidePanelsToTest = viewModel::addSelectedSidePanelsToTest,
        onClearSidePanelResults = viewModel::clearSidePanelResults,
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
    onTurboModeChange: (Boolean) -> Unit,
    onParallelModeChange: (Boolean) -> Unit,
    onParallelCountChange: (Int) -> Unit,
    onLimitPerServerChange: (Boolean) -> Unit,
    onMaxLinksPerServerChange: (Int) -> Unit,
    onResumeTest: () -> Unit,
    onDismissInterrupted: () -> Unit,
    onClearRecoveredLinks: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRun: () -> Unit,
    showBatteryDialog: Boolean,
    onDismissBatteryDialog: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onPickFolder: () -> Unit,
    onSaveText: (suggestedName: String, text: String) -> Unit,
    // Yan panel bulma callback'leri
    onSidePanelUrlChange: (String) -> Unit = {},
    onSearchSidePanels: (fastMode: Boolean) -> Unit = {},
    onToggleSidePanelSelection: (String) -> Unit = {},
    onSelectAllSidePanels: () -> Unit = {},
    onAddSelectedSidePanelsToTest: () -> Unit = {},
    onClearSidePanelResults: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Sıfırlama onay dialogu state'i
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    
    // Sıfırlama onay dialogu
    if (showClearConfirmDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Tüm Verileri Sıfırla") },
            text = {
                Column {
                    Text("Tüm tarama verilerini silmek istediğinize emin misiniz?")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• ${state.extractedUrls.size} adet link\n• ${state.workingUrls.size} çalışan link\n• ${state.recoveredWorkingUrls.size} kurtarılan link",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Bu işlem geri alınamaz!",
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmDialog = false
                        onClear()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Evet, Sıfırla")
                }
            },
            dismissButton = {
                androidx.compose.material3.OutlinedButton(
                    onClick = { showClearConfirmDialog = false }
                ) {
                    Text("İptal")
                }
            }
        )
    }
    
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
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
                    // Yarıda kalan test varsa "Kaldığı Yerden Devam Et" butonu göster
                    if (state.hasInterruptedTest) {
                        Button(
                            onClick = onResumeTest,
                            enabled = !state.loading,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Devam Et")
                        }
                    } else {
                        Button(onClick = onNext, enabled = canGoNext) {
                            Text(text = "İleri")
                        }
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

            // YARIDA KALAN TEST UYARISI
            if (state.hasInterruptedTest && !state.loading) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = "⚠️ Önceki test yarıda kaldı!",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        
                        val testedCount = state.extractedUrls.count { it.success != null }
                        val totalCount = state.extractedUrls.size
                        val remainingCount = totalCount - testedCount
                        
                        Text(
                            text = "Test edilen: $testedCount / $totalCount | Kalan: $remainingCount link",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        
                        if (state.workingUrls.isNotEmpty()) {
                            Text(
                                text = "✅ ${state.workingUrls.size} çalışan link kurtarıldı",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onResumeTest,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = "Kaldığı Yerden Devam Et")
                            }
                            
                            androidx.compose.material3.OutlinedButton(
                                onClick = onDismissInterrupted
                            ) {
                                Text(text = "Kapat")
                            }
                        }
                    }
                }
            }

            // KURTARILAN LİNKLER BÖLÜMÜ
            if (state.recoveredWorkingUrls.isNotEmpty() && !state.loading) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Kurtarılan Linkler (${state.recoveredWorkingUrls.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                androidx.compose.material3.IconButton(
                                    onClick = {
                                        val text = state.recoveredWorkingUrls.joinToString("\n")
                                        clipboard.setText(AnnotatedString(text))
                                        Toast.makeText(context, "${state.recoveredWorkingUrls.size} link kopyalandı", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = "Kopyala",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                
                                androidx.compose.material3.IconButton(onClick = onClearRecoveredLinks) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = "Temizle",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 150.dp)
                        ) {
                            itemsIndexed(state.recoveredWorkingUrls) { index, url ->
                                Text(
                                    text = "${index + 1}. $url",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
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

                        Button(
                            onClick = {
                                // Eğer veri varsa onay dialogu göster
                                if (state.extractedUrls.isNotEmpty() || state.workingUrls.isNotEmpty() || state.recoveredWorkingUrls.isNotEmpty()) {
                                    showClearConfirmDialog = true
                                } else {
                                    onClear()
                                }
                            },
                            enabled = !state.loading,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(text = "Sıfırla")
                        }
                    }

                    // YAN PANEL BULMA BÖLÜMÜ
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(text = "🔍 Yan Panel Bul", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Bir IPTV URL'si girin, aynı sunucudaki diğer panelleri bulalım",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            OutlinedTextField(
                                value = state.sidePanelUrl,
                                onValueChange = { onSidePanelUrlChange(it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("IPTV URL (örn: http://server.com/get.php?username=x&password=y)") },
                                singleLine = true,
                                enabled = !state.sidePanelSearching
                            )
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onSearchSidePanels(true) },
                                    enabled = !state.sidePanelSearching && state.sidePanelUrl.isNotBlank()
                                ) {
                                    Text("⚡ Hızlı Tara")
                                }
                                
                                androidx.compose.material3.OutlinedButton(
                                    onClick = { onSearchSidePanels(false) },
                                    enabled = !state.sidePanelSearching && state.sidePanelUrl.isNotBlank()
                                ) {
                                    Text("🔎 Detaylı Tara")
                                }
                            }
                            
                            // Tarama ilerleme durumu
                            if (state.sidePanelSearching) {
                                LinearProgressIndicator(
                                    progress = { if (state.sidePanelTotal > 0) state.sidePanelProgress.toFloat() / state.sidePanelTotal else 0f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "Taranıyor: ${state.sidePanelProgress}/${state.sidePanelTotal} | Bulunan: ${state.sidePanelFound}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            
                            // Bulunan yan paneller
                            if (state.sidePanelResults.isNotEmpty()) {
                                HorizontalDivider()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "✅ ${state.sidePanelResults.size} Panel Bulundu",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        androidx.compose.material3.TextButton(onClick = { onSelectAllSidePanels() }) {
                                            Text("Tümünü Seç")
                                        }
                                        androidx.compose.material3.TextButton(onClick = { onClearSidePanelResults() }) {
                                            Text("Temizle")
                                        }
                                    }
                                }
                                
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 150.dp)
                                ) {
                                    itemsIndexed(state.sidePanelResults) { index, panel ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Checkbox(
                                                checked = panel.isSelected,
                                                onCheckedChange = { onToggleSidePanelSelection(panel.url) }
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "${index + 1}. ${panel.username}:${panel.password}",
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    text = panel.url,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1
                                                )
                                            }
                                            if (panel.isWorking) {
                                                Icon(
                                                    imageVector = Icons.Filled.CheckCircle,
                                                    contentDescription = "Çalışıyor",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                                
                                Button(
                                    onClick = { onAddSelectedSidePanelsToTest() },
                                    enabled = state.sidePanelResults.any { it.isSelected },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Seçilenleri Test Listesine Ekle (${state.sidePanelResults.count { it.isSelected }})")
                                }
                            }
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

                        // PARALEL MOD - Eşzamanlı indirme (varsayılan açık)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = if (state.parallelMode) {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "🚀 PARALEL MOD",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (state.parallelMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    androidx.compose.material3.Switch(
                                        checked = state.parallelMode,
                                        onCheckedChange = { onParallelModeChange(it) },
                                        enabled = !state.loading
                                    )
                                }
                                
                                if (state.parallelMode) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Aynı anda kaç link indirilsin?",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(3, 5, 8, 10).forEach { count ->
                                            androidx.compose.material3.FilterChip(
                                                selected = state.parallelCount == count,
                                                onClick = { onParallelCountChange(count) },
                                                label = { Text("$count") },
                                                enabled = !state.loading
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "✅ ${state.parallelCount} link aynı anda indirilecek - ${state.parallelCount}x daha hızlı!",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text(
                                        text = "Sıralı indirme (yavaş ama stabil)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // TURBO MOD - Çok sayıda link için hızlı tarama
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = if (state.turboMode) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Bolt,
                                            contentDescription = null,
                                            tint = if (state.turboMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "⚡ TURBO MOD",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (state.turboMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    androidx.compose.material3.Switch(
                                        checked = state.turboMode,
                                        onCheckedChange = { onTurboModeChange(it) },
                                        enabled = !state.loading
                                    )
                                }
                                Text(
                                    text = if (state.turboMode) {
                                        "✅ Aktif: Hızlı tarama (3 kanal, 3sn timeout, bekleme yok)"
                                    } else {
                                        "Çok sayıda link için önerilir (200+ link)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Sunucu Başına Limit Kartı
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = if (state.limitPerServer) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Filled.Tune,
                                            contentDescription = null,
                                            tint = if (state.limitPerServer) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "🎯 Sunucu Başına Limit",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (state.limitPerServer) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    androidx.compose.material3.Switch(
                                        checked = state.limitPerServer,
                                        onCheckedChange = { onLimitPerServerChange(it) },
                                        enabled = !state.loading
                                    )
                                }
                                
                                if (state.limitPerServer) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Her sunucudan maksimum kaç sağlam link?",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(1, 2, 3, 5, 10).forEach { count ->
                                            androidx.compose.material3.FilterChip(
                                                selected = state.maxLinksPerServer == count,
                                                onClick = { onMaxLinksPerServerChange(count) },
                                                label = { Text("$count") },
                                                enabled = !state.loading
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "✅ Aynı sunucudan ${state.maxLinksPerServer} sağlam link bulunca diğerlerini atlar",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text(
                                        text = "Aynı sunucudan çok fazla link varsa kullanışlı",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
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

            // Battery optimization dialog
            if (showBatteryDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissBatteryDialog,
                    title = { Text(text = "Arka Plan İzni Gerekli") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = "Otomatik test uzun sürebilir ve arka planda çalışması gerekir.")
                            Text(text = "Uygulamanın arka planda sorunsuz çalışması için batarya optimizasyonundan muaf tutulması gerekiyor.")
                            Text(text = "Lütfen açılacak ayarlar sayfasında 'İzin Ver' seçeneğini seçin.", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = {
                        Button(onClick = onRequestBatteryExemption) {
                            Text(text = "Ayarlara Git")
                        }
                    },
                    dismissButton = {
                        Button(onClick = {
                            onDismissBatteryDialog()
                            Toast.makeText(context, "İzin verilmeden test başlatıldı - arka planda durabilir", Toast.LENGTH_LONG).show()
                        }) {
                            Text(text = "Şimdi Değil")
                        }
                    }
                )
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
