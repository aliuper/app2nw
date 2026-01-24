package com.alibaba.feature.expirycheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.domain.model.*
import com.alibaba.domain.service.ExpiryChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ExpiryCheckState(
    val linksText: String = "",
    val timeoutSeconds: Int = 10,
    val checkPlayerApi: Boolean = true,
    val checkXmlTv: Boolean = true,
    val checkM3u: Boolean = true,
    val checkChannel: Boolean = true,
    val checking: Boolean = false,
    val progress: ExpiryCheckProgress? = null,
    val results: List<ExpiryCheckResult> = emptyList(),
    val summary: ExpiryCheckSummary? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class ExpiryCheckViewModel @Inject constructor(
    private val expiryChecker: ExpiryChecker
) : ViewModel() {

    private val _state = MutableStateFlow(ExpiryCheckState())
    val state: StateFlow<ExpiryCheckState> = _state.asStateFlow()
    
    private var checkJob: Job? = null

    fun setLinksText(text: String) {
        _state.update { it.copy(linksText = text) }
    }

    fun setTimeoutSeconds(timeout: Int) {
        _state.update { it.copy(timeoutSeconds = timeout) }
    }

    fun toggleCheckPlayerApi() {
        _state.update { it.copy(checkPlayerApi = !it.checkPlayerApi) }
    }

    fun toggleCheckXmlTv() {
        _state.update { it.copy(checkXmlTv = !it.checkXmlTv) }
    }

    fun toggleCheckM3u() {
        _state.update { it.copy(checkM3u = !it.checkM3u) }
    }

    fun toggleCheckChannel() {
        _state.update { it.copy(checkChannel = !it.checkChannel) }
    }

    fun startCheck() {
        val currentState = _state.value
        
        val links = currentState.linksText
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && (it.startsWith("http://") || it.startsWith("https://")) }
        
        if (links.isEmpty()) {
            _state.update { it.copy(errorMessage = "Lütfen en az bir geçerli IPTV linki girin") }
            return
        }

        checkJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    checking = true,
                    errorMessage = null,
                    results = emptyList(),
                    summary = null,
                    progress = null
                )
            }

            try {
                val config = ExpiryCheckConfig(
                    timeoutSeconds = currentState.timeoutSeconds,
                    checkPlayerApi = currentState.checkPlayerApi,
                    checkXmlTv = currentState.checkXmlTv,
                    checkM3u = currentState.checkM3u,
                    checkChannel = currentState.checkChannel
                )

                val summary = withContext(Dispatchers.IO) {
                    expiryChecker.checkMultipleLinks(
                        links = links,
                        config = config,
                        onProgress = { progress ->
                            _state.update { it.copy(progress = progress) }
                        },
                        onResult = { result ->
                            _state.update { it.copy(results = it.results + result) }
                        }
                    )
                }

                _state.update { 
                    it.copy(
                        checking = false,
                        summary = summary
                    ) 
                }

            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        checking = false,
                        errorMessage = "Hata: ${e.message}"
                    )
                }
            }
        }
    }

    fun stopCheck() {
        checkJob?.cancel()
        _state.update { it.copy(checking = false) }
    }

    fun clearResults() {
        _state.update {
            it.copy(
                results = emptyList(),
                summary = null,
                progress = null,
                errorMessage = null
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun getReport(): String {
        val results = _state.value.results
        val summary = _state.value.summary
        
        if (results.isEmpty()) return "Sonuç yok"
        
        return buildString {
            appendLine("╔════════════════════════════════════════╗")
            appendLine("║     IPTV BİTİŞ TARİHİ KONTROL RAPORU   ║")
            appendLine("╚════════════════════════════════════════╝")
            appendLine()
            appendLine("Tarih: ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm").format(java.util.Date())}")
            appendLine()
            
            summary?.let {
                appendLine("📊 ÖZET")
                appendLine("━━━━━━━━━━━━━━━━━━━━")
                appendLine("Toplam: ${it.total}")
                appendLine("✅ Aktif: ${it.active}")
                appendLine("⚠️ Yakında Dolacak: ${it.expiringSoon}")
                appendLine("❌ Süresi Dolmuş: ${it.expired}")
                appendLine("⚡ Hatalı: ${it.errors}")
                appendLine()
            }
            
            appendLine("📋 DETAYLAR")
            appendLine("━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            
            results.forEachIndexed { index, result ->
                appendLine("【 ${index + 1}. Hesap 】")
                appendLine("Kullanıcı: ${result.username}")
                appendLine("Sunucu: ${result.server}")
                appendLine("Durum: ${result.status.displayName}")
                appendLine("Bitiş: ${result.expiryDateFormatted}")
                result.daysRemaining?.let { days ->
                    if (days >= 0) {
                        appendLine("Kalan Gün: $days")
                    } else {
                        appendLine("Dolalı: ${-days} gün")
                    }
                }
                appendLine("Paket: ${result.packageName}")
                result.maxConnections?.let { appendLine("Max Bağlantı: $it") }
                appendLine()
                appendLine("─────────────────────────────────────────")
                appendLine()
            }
        }
    }
}
