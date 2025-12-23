package com.alibaba.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Restore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState = viewModel.state.collectAsState().value
    val settings = uiState.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Ayarlar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { padding ->
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(imageVector = Icons.Filled.Tune, contentDescription = null)
                        Text(text = "Otomatik Test Ayarları", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "Buradaki ayarlar otomatik modda linkleri test ederken kullanılan hız/kalite dengesini belirler.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "Test edilecek kanal sayısı", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Her linkte rastgele seçilen en fazla ${settings.streamTestSampleSize} stream denenir. Büyük değer daha güvenilir ama daha yavaştır.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = settings.streamTestSampleSize.toFloat(),
                        onValueChange = { viewModel.setStreamTestSampleSize(it.toInt()) },
                        valueRange = 1f..50f,
                        steps = 48
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "Stream timeout", style = MaterialTheme.typography.titleMedium)
                    val seconds = (settings.streamTestTimeoutMs / 1000L).coerceAtLeast(1)
                    Text(
                        text = "Her stream için maksimum ${seconds}s beklenir. Düşük değer hızlı, yüksek değer daha toleranslıdır.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = seconds.toFloat(),
                        onValueChange = { viewModel.setStreamTestTimeoutMs(it.toLong() * 1000L) },
                        valueRange = 1f..30f,
                        steps = 28
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "Başarılı saymak için kaç stream yeter?", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "En az ${settings.minPlayableStreamsToPass} stream çalışırsa link başarılı sayılır.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = settings.minPlayableStreamsToPass.toFloat(),
                        onValueChange = { viewModel.setMinPlayableStreamsToPass(it.toInt()) },
                        valueRange = 1f..5f,
                        steps = 3
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "Testler arası bekleme", style = MaterialTheme.typography.titleMedium)
                    val delayMs = settings.delayBetweenStreamTestsMs
                    Text(
                        text = "Ağ/cihazı yormamak için her test arasında ${delayMs}ms beklenir.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = delayMs.toFloat(),
                        onValueChange = { viewModel.setDelayBetweenStreamTestsMs(it.toLong()) },
                        valueRange = 0f..5000f,
                        steps = 49
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Ülke/grup filtreleme", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Kapalı olursa ülke seçimi dikkate alınmaz; stream testi geçen linkler direkt işlenir. Açık olursa seçtiğin ülkelere göre filtrelenir.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = settings.enableCountryFiltering,
                            onCheckedChange = viewModel::setEnableCountryFiltering
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Adult/XXX grupları atla", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Açık olursa +18 içerik grupları otomatik olarak filtrelenir.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = settings.skipAdultGroups,
                            onCheckedChange = viewModel::setSkipAdultGroups
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Rastgele örnekle (önerilen)", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Açık olursa streamler karıştırılarak denenir. Kapalı olursa ilk N stream denenir.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = settings.shuffleCandidates,
                            onCheckedChange = viewModel::setShuffleCandidates
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = viewModel::resetToDefaults,
                    enabled = !uiState.saving
                ) {
                    Icon(imageVector = Icons.Filled.Restore, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Varsayılanlara dön")
                }
            }
        }
    }
}
