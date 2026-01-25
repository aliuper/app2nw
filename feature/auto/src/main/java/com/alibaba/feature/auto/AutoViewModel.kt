package com.alibaba.feature.auto

import android.os.SystemClock
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.core.common.PlaylistTextFormatter
import com.alibaba.core.common.OutputFormatDetector
import com.alibaba.core.common.isGroupInCountries
import com.alibaba.core.common.extractIptvUrls
import com.alibaba.core.common.isAdultGroup
import com.alibaba.domain.model.OutputDelivery
import com.alibaba.domain.model.OutputFormat
import com.alibaba.domain.model.Playlist
import com.alibaba.domain.model.Channel
import com.alibaba.domain.repo.PlaylistRepository
import com.alibaba.domain.repo.SettingsRepository
import com.alibaba.domain.service.OutputSaver
import com.alibaba.domain.service.StreamTester
import com.alibaba.data.service.StreamTestService
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random
import javax.inject.Inject

@HiltViewModel
class AutoViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
    private val streamTester: StreamTester,
    private val outputSaver: OutputSaver
) : ViewModel() {

    private val _state = MutableStateFlow(AutoUiState())
    val state: StateFlow<AutoUiState> = _state

    private var testService: StreamTestService? = null
    private var progressStartMs: Long? = null
    private val completedUrlDurationsMs = ArrayList<Long>(64)
    private var lastStreamUiUpdateMs: Long = 0L
    
    // State persistence için SharedPreferences
    private val prefs: SharedPreferences = appContext.getSharedPreferences("auto_test_state", Context.MODE_PRIVATE)
    private companion object {
        const val KEY_EXTRACTED_URLS = "extracted_urls"
        const val KEY_WORKING_URLS = "working_urls"
        const val KEY_FAILING_URLS = "failing_urls"
        const val KEY_INPUT_TEXT = "input_text"
        const val KEY_IS_TESTING = "is_testing"
        const val KEY_TURBO_MODE = "turbo_mode"
        const val KEY_SELECTED_COUNTRIES = "selected_countries"
    }

    init {
        // Kaydedilmiş state'i geri yükle
        restoreSavedState()
        
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _state.update { st ->
                    val updated = st.copy(
                        enableCountryFiltering = s.enableCountryFiltering,
                        outputDelivery = s.outputDelivery
                    )
                    val max = maxStepIndex(updated)
                    updated.copy(step = updated.step.coerceIn(0, max))
                }
            }
        }
    }
    
    private fun restoreSavedState() {
        try {
            val wasTesting = prefs.getBoolean(KEY_IS_TESTING, false)
            val inputText = prefs.getString(KEY_INPUT_TEXT, "") ?: ""
            val turboMode = prefs.getBoolean(KEY_TURBO_MODE, false)
            val countriesJson = prefs.getString(KEY_SELECTED_COUNTRIES, null)
            
            // Extracted URLs'i geri yükle
            val extractedUrlsJson = prefs.getString(KEY_EXTRACTED_URLS, null)
            val extractedUrls = if (extractedUrlsJson != null) {
                parseUrlItemsFromJson(extractedUrlsJson)
            } else emptyList()
            
            // Working URLs'i geri yükle
            val workingUrlsJson = prefs.getString(KEY_WORKING_URLS, null)
            val workingUrls = if (workingUrlsJson != null) {
                parseStringListFromJson(workingUrlsJson)
            } else emptyList()
            
            // Failing URLs'i geri yükle
            val failingUrlsJson = prefs.getString(KEY_FAILING_URLS, null)
            val failingUrls = if (failingUrlsJson != null) {
                parseStringListFromJson(failingUrlsJson)
            } else emptyList()
            
            // Selected countries'i geri yükle
            val selectedCountries = if (countriesJson != null) {
                parseStringListFromJson(countriesJson).toSet()
            } else setOf("TR")
            
            // Yarıda kalan test var mı kontrol et
            val hasInterrupted = wasTesting && extractedUrls.any { u -> u.success == null }
            
            if (extractedUrls.isNotEmpty() || workingUrls.isNotEmpty()) {
                _state.update { 
                    it.copy(
                        inputText = inputText,
                        extractedUrls = extractedUrls,
                        workingUrls = workingUrls,
                        failingUrls = failingUrls,
                        turboMode = turboMode,
                        selectedCountries = selectedCountries,
                        hasInterruptedTest = hasInterrupted,
                        recoveredWorkingUrls = workingUrls, // Kurtarılan linkler
                        errorMessage = null // Artık UI'da göstereceğiz
                    )
                }
            }
        } catch (e: Exception) {
            // Hata durumunda sessizce devam et
        }
    }
    
    private fun saveCurrentState() {
        try {
            val currentState = _state.value
            prefs.edit().apply {
                putString(KEY_INPUT_TEXT, currentState.inputText)
                putString(KEY_EXTRACTED_URLS, urlItemsToJson(currentState.extractedUrls))
                putString(KEY_WORKING_URLS, stringListToJson(currentState.workingUrls))
                putString(KEY_FAILING_URLS, stringListToJson(currentState.failingUrls))
                putBoolean(KEY_IS_TESTING, currentState.loading)
                putBoolean(KEY_TURBO_MODE, currentState.turboMode)
                putString(KEY_SELECTED_COUNTRIES, stringListToJson(currentState.selectedCountries.toList()))
                apply()
            }
        } catch (e: Exception) {
            // Hata durumunda sessizce devam et
        }
    }
    
    private fun clearSavedState() {
        prefs.edit().clear().apply()
    }
    
    private fun urlItemsToJson(items: List<UrlItem>): String {
        val jsonArray = JSONArray()
        items.forEach { item ->
            val obj = JSONObject().apply {
                put("url", item.url)
                put("status", item.status ?: "")
                put("success", item.success)
                put("testedStreams", item.testedStreams)
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }
    
    private fun parseUrlItemsFromJson(json: String): List<UrlItem> {
        val result = mutableListOf<UrlItem>()
        try {
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                result.add(UrlItem(
                    url = obj.getString("url"),
                    status = obj.optString("status").takeIf { it.isNotEmpty() },
                    success = if (obj.isNull("success")) null else obj.getBoolean("success"),
                    testedStreams = obj.optInt("testedStreams", 0)
                ))
            }
        } catch (e: Exception) {
            // Parse hatası
        }
        return result
    }
    
    private fun stringListToJson(list: List<String>): String {
        val jsonArray = JSONArray()
        list.forEach { jsonArray.put(it) }
        return jsonArray.toString()
    }
    
    private fun parseStringListFromJson(json: String): List<String> {
        val result = mutableListOf<String>()
        try {
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                result.add(jsonArray.getString(i))
            }
        } catch (e: Exception) {
            // Parse hatası
        }
        return result
    }

    fun onInputChange(value: String) {
        _state.update { it.copy(inputText = value, errorMessage = null) }
    }

    fun setUrls(urls: String) {
        _state.update { it.copy(inputText = urls, errorMessage = null) }
    }

    fun nextStep() {
        val s = state.value
        if (s.loading) return

        if (s.outputDelivery == OutputDelivery.LINKS) {
            val max = maxStepIndex(s)
            val next = (s.step + 1).coerceAtMost(max)
            _state.update { it.copy(step = next, errorMessage = null) }

            val shouldStart = when {
                !s.enableCountryFiltering && next >= 1 -> true
                s.enableCountryFiltering && s.step == 1 && next >= 2 -> true
                else -> false
            }
            if (shouldStart) run()
            return
        }

        _state.update { st ->
            val max = maxStepIndex(st)
            st.copy(step = (st.step + 1).coerceAtMost(max), errorMessage = null)
        }
    }

    fun prevStep() {
        _state.update { s -> s.copy(step = (s.step - 1).coerceAtLeast(0), errorMessage = null) }
    }

    private fun maxStepIndex(s: AutoUiState): Int {
        return if (s.outputDelivery == OutputDelivery.LINKS) {
            // Steps in LINKS mode:
            // 0: Link/Metin
            // 1: (optional) Ülke Seç
            // last: Başlat
            if (s.enableCountryFiltering) 2 else 1
        } else {
            if (s.enableCountryFiltering) 3 else 2
        }
    }

    fun extract() {
        val urls = extractIptvUrls(state.value.inputText)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        _state.update {
            it.copy(
                extractedUrls = urls.map { u -> UrlItem(url = u) },
                errorMessage = if (urls.isEmpty()) "Link bulunamadı" else null,
                loading = false,
                progressPercent = 0,
                progressStep = null,
                etaSeconds = null,
                outputPreview = null,
                workingUrls = emptyList(),
                selectedWorkingUrls = emptySet(),
                failingUrls = emptyList(),
                savedFiles = emptyList()
            )
        }
    }

    fun toggleCountry(code: String, enabled: Boolean) {
        _state.update { s ->
            val updated = if (enabled) s.selectedCountries + code else s.selectedCountries - code
            s.copy(selectedCountries = updated)
        }
    }

    fun setMergeIntoSingle(value: Boolean) {
        _state.update { it.copy(mergeIntoSingle = value) }
    }

    fun setAutoDetectFormat(value: Boolean) {
        _state.update { it.copy(autoDetectFormat = value) }
    }

    fun setOutputFormat(format: OutputFormat) {
        _state.update { it.copy(outputFormat = format) }
    }

    fun setTurboMode(enabled: Boolean) {
        _state.update { it.copy(turboMode = enabled) }
    }

    fun setLimitPerServer(enabled: Boolean) {
        _state.update { it.copy(limitPerServer = enabled) }
    }

    fun setMaxLinksPerServer(count: Int) {
        _state.update { it.copy(maxLinksPerServer = count.coerceIn(1, 100)) }
    }

    fun setParallelMode(enabled: Boolean) {
        _state.update { it.copy(parallelMode = enabled) }
    }

    fun setParallelCount(count: Int) {
        _state.update { it.copy(parallelCount = count.coerceIn(1, 10)) }
    }

    fun setOutputFolder(uriString: String?) {
        _state.update { it.copy(outputFolderUriString = uriString) }
    }

    fun clearAll() {
        clearSavedState() // Kaydedilmiş state'i de temizle
        _state.update { s ->
            s.copy(
                step = 0,
                inputText = "",
                extractedUrls = emptyList(),
                loading = false,
                progressPercent = 0,
                progressStep = null,
                etaSeconds = null,
                errorMessage = null,
                reportText = null,
                workingUrls = emptyList(),
                selectedWorkingUrls = emptySet(),
                failingUrls = emptyList(),
                lastRunSaved = false,
                outputPreview = null,
                savedFiles = emptyList(),
                mergeRenameWarning = null,
                backgroundWorkId = null,
                hasInterruptedTest = false,
                recoveredWorkingUrls = emptyList()
            )
        }
    }
    
    fun dismissInterruptedTest() {
        // Yarıda kalan test uyarısını kapat ama kurtarılan linkleri koru
        _state.update { it.copy(hasInterruptedTest = false) }
    }
    
    // ==================== YAN PANEL BULMA ====================
    
    fun setSidePanelUrl(url: String) {
        _state.update { it.copy(sidePanelUrl = url) }
    }
    
    fun searchSidePanels(fastMode: Boolean = true) {
        val url = state.value.sidePanelUrl.trim()
        if (url.isBlank()) {
            _state.update { it.copy(errorMessage = "Lütfen bir IPTV URL'si girin") }
            return
        }
        
        viewModelScope.launch {
            _state.update { 
                it.copy(
                    sidePanelSearching = true,
                    sidePanelProgress = 0,
                    sidePanelTotal = 0,
                    sidePanelFound = 0,
                    sidePanelResults = emptyList(),
                    errorMessage = null
                )
            }
            
            try {
                val results = if (fastMode) {
                    com.alibaba.core.common.findSidePanelsFast(url) { current, total, found ->
                        _state.update { 
                            it.copy(
                                sidePanelProgress = current,
                                sidePanelTotal = total,
                                sidePanelFound = found
                            )
                        }
                    }
                } else {
                    com.alibaba.core.common.findSidePanels(url) { current, total, found ->
                        _state.update { 
                            it.copy(
                                sidePanelProgress = current,
                                sidePanelTotal = total,
                                sidePanelFound = found
                            )
                        }
                    }
                }
                
                _state.update { 
                    it.copy(
                        sidePanelSearching = false,
                        sidePanelResults = results.map { r ->
                            SidePanelItem(
                                url = r.url,
                                username = r.username,
                                password = r.password,
                                isWorking = r.isWorking
                            )
                        },
                        errorMessage = if (results.isEmpty()) "Yan panel bulunamadı" else null
                    )
                }
            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        sidePanelSearching = false,
                        errorMessage = "Hata: ${e.message}"
                    )
                }
            }
        }
    }
    
    fun toggleSidePanelSelection(url: String) {
        _state.update { s ->
            val updated = s.sidePanelResults.map { item ->
                if (item.url == url) item.copy(isSelected = !item.isSelected) else item
            }
            s.copy(sidePanelResults = updated)
        }
    }
    
    fun selectAllSidePanels() {
        _state.update { s ->
            s.copy(sidePanelResults = s.sidePanelResults.map { it.copy(isSelected = true) })
        }
    }
    
    fun addSelectedSidePanelsToTest() {
        val selected = state.value.sidePanelResults.filter { it.isSelected }
        if (selected.isEmpty()) return
        
        val currentUrls = state.value.extractedUrls.map { it.url }.toSet()
        val newUrls = selected.map { it.url }.filter { it !in currentUrls }
        
        if (newUrls.isNotEmpty()) {
            _state.update { s ->
                s.copy(
                    extractedUrls = s.extractedUrls + newUrls.map { UrlItem(url = it) },
                    sidePanelResults = emptyList(),
                    sidePanelUrl = ""
                )
            }
        }
    }
    
    fun clearSidePanelResults() {
        _state.update { 
            it.copy(
                sidePanelResults = emptyList(),
                sidePanelProgress = 0,
                sidePanelTotal = 0,
                sidePanelFound = 0
            )
        }
    }
    
    fun clearRecoveredLinks() {
        // Kurtarılan linkleri temizle
        _state.update { it.copy(recoveredWorkingUrls = emptyList()) }
        // SharedPreferences'tan da temizle
        prefs.edit().remove(KEY_WORKING_URLS).apply()
    }
    
    fun resumeTest() {
        // Yarıda kalan testi devam ettir - sadece test edilmemiş linkleri test et
        val currentState = state.value
        val untestedUrls = currentState.extractedUrls.filter { it.success == null }
        
        if (untestedUrls.isEmpty()) {
            _state.update { it.copy(
                errorMessage = "Devam edilecek link yok - tüm linkler zaten test edilmiş",
                hasInterruptedTest = false
            ) }
            return
        }
        
        // Sadece test edilmemiş linkleri çalıştır
        _state.update { it.copy(hasInterruptedTest = false) }
        runWithUrls(untestedUrls.map { it.url }, resumeMode = true)
    }

    fun toggleWorkingUrl(url: String, enabled: Boolean) {
        _state.update { s ->
            val updated = if (enabled) s.selectedWorkingUrls + url else s.selectedWorkingUrls - url
            s.copy(selectedWorkingUrls = updated)
        }
    }

    fun run() {
        val urls = state.value.extractedUrls.map { it.url }
        if (urls.isEmpty()) {
            _state.update { it.copy(errorMessage = "Önce linkleri ayıkla") }
            return
        }
        runWithUrls(urls, resumeMode = false)
    }
    
    private fun runWithUrls(urls: List<String>, resumeMode: Boolean) {
        if (urls.isEmpty()) {
            _state.update { it.copy(errorMessage = "Test edilecek link yok") }
            return
        }

        val countries = state.value.selectedCountries

        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            if (settings.enableCountryFiltering && countries.isEmpty()) {
                _state.update { it.copy(errorMessage = "En az 1 ülke seç") }
                return@launch
            }

            StreamTestService.start(appContext)
            
            // Foreground Service başlat (arka planda çökmeyi önle)
            try {
                val serviceIntent = android.content.Intent(appContext, Class.forName("com.alibaba.service.AutoTestForegroundService"))
                serviceIntent.action = "com.alibaba.action.START_AUTO_TEST"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(serviceIntent)
                } else {
                    appContext.startService(serviceIntent)
                }
            } catch (e: Exception) {
                // Service bulunamazsa devam et
            }

            progressStartMs = SystemClock.elapsedRealtime()
            completedUrlDurationsMs.clear()
            
            // Resume modunda mevcut working/failing linkleri koru
            val existingWorking = if (resumeMode) state.value.workingUrls else emptyList()
            val existingFailing = if (resumeMode) state.value.failingUrls else emptyList()
            
            _state.update { s ->
                if (resumeMode) {
                    // Resume modunda sadece test edilmemiş linklerin durumunu güncelle
                    s.copy(
                        loading = true,
                        progressPercent = 0,
                        progressStep = "Kaldığı yerden devam ediliyor...",
                        etaSeconds = null,
                        errorMessage = null,
                        lastRunSaved = false,
                        outputPreview = null,
                        mergeRenameWarning = null,
                        reportText = null,
                        extractedUrls = s.extractedUrls.map { 
                            if (it.success == null) it.copy(status = "Beklemede", testedStreams = 0)
                            else it // Zaten test edilmişleri koru
                        }
                    )
                } else {
                    s.copy(
                        loading = true,
                        progressPercent = 0,
                        progressStep = "Başlıyor",
                        etaSeconds = null,
                        errorMessage = null,
                        savedFiles = emptyList(),
                        workingUrls = emptyList(),
                        failingUrls = emptyList(),
                        lastRunSaved = false,
                        outputPreview = null,
                        mergeRenameWarning = null,
                        reportText = null,
                        extractedUrls = s.extractedUrls.map { it.copy(status = "Beklemede", success = null, testedStreams = 0) }
                    )
                }
            }

            val mergeIntoSingle = state.value.mergeIntoSingle
            val folderUriString = state.value.outputFolderUriString
            val autoDetectFormat = state.value.autoDetectFormat
            val chosenOutputFormat = state.value.outputFormat
            val outputDelivery = settings.outputDelivery
            val turboMode = state.value.turboMode
            val parallelMode = state.value.parallelMode
            val parallelCount = state.value.parallelCount
            val limitPerServer = state.value.limitPerServer
            val maxLinksPerServer = state.value.maxLinksPerServer

            val mergedChannels = ArrayList<Channel>(4_096) // Reduced from 16k to prevent OOM
            var mergedEndDate: String? = null

            val usedGroupNames = linkedMapOf<String, Int>()
            val renameSamples = ArrayList<String>(16)
            
            // Sunucu başına sağlam link sayacı (thread-safe)
            val serverWorkingCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

            // Limit initial capacity to prevent memory bloat
            // Resume modunda mevcut linkleri koru
            val working = java.util.Collections.synchronizedList(ArrayList<String>(minOf(urls.size + existingWorking.size, 300)).apply {
                addAll(existingWorking)
            })
            val failing = java.util.Collections.synchronizedList(ArrayList<String>(minOf(urls.size + existingFailing.size, 300)).apply {
                addAll(existingFailing)
            })
            val savedNames = java.util.Collections.synchronizedList(ArrayList<String>(minOf(urls.size + 1, 200)))
            val savedUris = java.util.Collections.synchronizedList(ArrayList<String>(minOf(urls.size + 1, 200)))
            
            // Resume modunda mevcut working linklerden sunucu sayaçlarını başlat
            if (resumeMode && limitPerServer) {
                existingWorking.forEach { workingUrl ->
                    val server = extractServerFromUrl(workingUrl)
                    serverWorkingCount[server] = (serverWorkingCount[server] ?: 0) + 1
                }
            }
            
            // SIRASAL MOD: Stabil ve hızlı (paralel mod kaldırıldı - çökme sorunu)
            for ((loopIndex, url) in urls.withIndex()) {
                yield() // Prevent ANR by allowing other coroutines to run
                
                // URL'nin extractedUrls içindeki GERÇEK index'ini bul
                val realIndex = state.value.extractedUrls.indexOfFirst { it.url == url }
                if (realIndex == -1) continue // URL bulunamadıysa atla
                
                // Sunucu başına limit kontrolü
                if (limitPerServer) {
                    val server = extractServerFromUrl(url)
                    val currentCount = serverWorkingCount[server] ?: 0
                    if (currentCount >= maxLinksPerServer) {
                        // Bu sunucudan yeterli link bulundu, atla
                        _state.update { s ->
                            val items = s.extractedUrls.toMutableList()
                            if (realIndex in items.indices) {
                                items[realIndex] = items[realIndex].copy(
                                    status = "⏭️ Atlandı (sunucu limiti: $maxLinksPerServer)",
                                    success = null
                                )
                            }
                            s.copy(extractedUrls = items)
                        }
                        continue
                    }
                }
                
                val urlStartMs = SystemClock.elapsedRealtime()
                val totalUrls = state.value.extractedUrls.size
                val testedSoFar = state.value.extractedUrls.count { it.success != null }
                val header = "${testedSoFar + 1}/${totalUrls}"
                val basePercent = (((testedSoFar) * 100) / maxOf(1, totalUrls)).coerceIn(0, 99)
                val turboLabel = if (turboMode) "⚡ TURBO - " else ""
                setProgress(percent = basePercent, step = "$turboLabel$header - İndiriliyor")
                _state.update { s ->
                    val items = s.extractedUrls.toMutableList()
                    if (realIndex in items.indices) {
                        items[realIndex] = items[realIndex].copy(status = "İndiriliyor", success = null, testedStreams = 0)
                    }
                    s.copy(extractedUrls = items)
                }

                var playlist: Playlist? = null
                try {
                    playlist = playlistRepository.fetchPlaylist(url)

                    setProgress(percent = (basePercent + 3).coerceAtMost(99), step = "$turboLabel$header - Stream testi")
                    _state.update { s ->
                        val items = s.extractedUrls.toMutableList()
                        if (realIndex in items.indices) {
                            items[realIndex] = items[realIndex].copy(status = if (turboMode) "⚡ Hızlı test" else "Stream testi", success = null)
                        }
                        s.copy(extractedUrls = items)
                    }

                    val (ok, testedCount, totalCount) = runStreamTestDetailed(playlist, turboMode) { tested, total ->
                        val now = SystemClock.elapsedRealtime()
                        val shouldUpdate = tested >= total || (now - lastStreamUiUpdateMs) >= 500
                        if (shouldUpdate) {
                            lastStreamUiUpdateMs = now
                            _state.update { s ->
                                val items = s.extractedUrls.toMutableList()
                                if (realIndex in items.indices) {
                                    items[realIndex] = items[realIndex].copy(
                                        status = "Test ${tested}/${total}",
                                        success = null,
                                        testedStreams = tested
                                    )
                                }
                                s.copy(extractedUrls = items)
                            }
                        }
                    }

                    if (!ok) {
                        failing += url
                        completedUrlDurationsMs += (SystemClock.elapsedRealtime() - urlStartMs)
                        _state.update { s ->
                            val items = s.extractedUrls.toMutableList()
                            if (realIndex in items.indices) {
                                items[realIndex] = items[realIndex].copy(status = "Stream testi başarısız", success = false, testedStreams = testedCount)
                            }
                            s.copy(extractedUrls = items, failingUrls = failing.toList())
                        }
                        saveCurrentState()
                        continue
                    }

                    val filtered = if (settings.enableCountryFiltering) {
                        val p = filterPlaylistByCountries(playlist, countries)
                        if (p.channels.isEmpty()) {
                            failing += url
                            completedUrlDurationsMs += (SystemClock.elapsedRealtime() - urlStartMs)
                            _state.update { s ->
                                val items = s.extractedUrls.toMutableList()
                                if (realIndex in items.indices) {
                                    items[realIndex] = items[realIndex].copy(status = "Seçilen ülke(ler) için kanal yok", success = false, testedStreams = totalCount)
                                }
                                s.copy(extractedUrls = items, failingUrls = failing.toList())
                            }
                            saveCurrentState()
                            continue
                        }
                        p
                    } else {
                        playlist
                    }

                    working += url
                    
                    // Sunucu başına limit için sayacı artır
                    if (limitPerServer) {
                        val server = extractServerFromUrl(url)
                        serverWorkingCount[server] = (serverWorkingCount[server] ?: 0) + 1
                    }

                    if (mergeIntoSingle) {
                        val renamed = mergeWithBackupNames(
                            channels = filtered.channels,
                            usedGroupNames = usedGroupNames,
                            renameSamples = renameSamples
                        )
                        mergedChannels += renamed
                        mergedEndDate = mergedEndDate ?: filtered.endDate
                        _state.update { s ->
                            val items = s.extractedUrls.toMutableList()
                            if (realIndex in items.indices) {
                                items[realIndex] = items[realIndex].copy(status = "Birleştirildi", success = true, testedStreams = totalCount)
                            }
                            s.copy(extractedUrls = items, workingUrls = working.toList())
                        }
                        completedUrlDurationsMs += (SystemClock.elapsedRealtime() - urlStartMs)
                        saveCurrentState()
                    } else {
                        if (outputDelivery == OutputDelivery.FILE) {
                            setProgress(percent = (basePercent + 6).coerceAtMost(99), step = "$header - Kaydediliyor")
                            val format = if (autoDetectFormat) OutputFormatDetector.detect(filtered) else chosenOutputFormat
                            val content = withContext(Dispatchers.Default) {
                                val groups = filtered.channels.map { it.group ?: "Ungrouped" }.toSet()
                                PlaylistTextFormatter.format(filtered, groups, format)
                            }

                            val saved = if (folderUriString.isNullOrBlank()) {
                                outputSaver.saveToDownloads(
                                    sourceUrl = url,
                                    format = format,
                                    content = content,
                                    maybeEndDate = filtered.endDate
                                )
                            } else {
                                outputSaver.saveToFolder(
                                    folderUriString = folderUriString,
                                    sourceUrl = url,
                                    format = format,
                                    content = content,
                                    maybeEndDate = filtered.endDate
                                )
                            }

                            savedNames += saved.displayName
                            savedUris += saved.uriString

                            _state.update { s ->
                                val items = s.extractedUrls.toMutableList()
                                if (realIndex in items.indices) {
                                    items[realIndex] = items[realIndex].copy(status = "Kaydedildi", success = true, testedStreams = totalCount)
                                }
                                s.copy(extractedUrls = items, workingUrls = working.toList())
                            }
                            saveCurrentState()
                        } else {
                            _state.update { s ->
                                val items = s.extractedUrls.toMutableList()
                                if (realIndex in items.indices) {
                                    items[realIndex] = items[realIndex].copy(status = "Başarılı", success = true, testedStreams = totalCount)
                                }
                                s.copy(extractedUrls = items, workingUrls = working.toList())
                            }
                            saveCurrentState()
                        }
                        completedUrlDurationsMs += (SystemClock.elapsedRealtime() - urlStartMs)
                    }
                } catch (e: OutOfMemoryError) {
                    // Critical: Out of memory - force cleanup
                    failing += url
                    completedUrlDurationsMs += (SystemClock.elapsedRealtime() - urlStartMs)
                    _state.update { s ->
                        val items = s.extractedUrls.toMutableList()
                        if (realIndex in items.indices) {
                            items[realIndex] = items[realIndex].copy(status = "Bellek yetersiz - temizleniyor", success = false)
                        }
                        s.copy(extractedUrls = items, failingUrls = failing.toList())
                    }
                    saveCurrentState()
                    
                    // Aggressive memory cleanup
                    mergedChannels.clear()
                    mergedChannels.trimToSize()
                    usedGroupNames.clear()
                    renameSamples.clear()
                    @Suppress("ExplicitGarbageCollectionCall")
                    System.gc()
                    delay(3000)
                    continue
                } catch (e: Exception) {
                    failing += url
                    completedUrlDurationsMs += (SystemClock.elapsedRealtime() - urlStartMs)
                    _state.update { s ->
                        val items = s.extractedUrls.toMutableList()
                        if (realIndex in items.indices) {
                            items[realIndex] = items[realIndex].copy(status = "Hata: ${e.message}", success = false)
                        }
                        s.copy(extractedUrls = items, failingUrls = failing.toList())
                    }
                    saveCurrentState()
                    continue
                } finally {
                    playlist = null
                    @Suppress("ExplicitGarbageCollectionCall")
                    System.gc()
                }
            }

            if (mergeIntoSingle && outputDelivery == OutputDelivery.FILE) {
                setProgress(percent = 95, step = "Çıktı hazırlanıyor")
                val merged = Playlist(channels = mergedChannels, endDate = mergedEndDate)
                val format = if (autoDetectFormat) OutputFormatDetector.detect(merged) else chosenOutputFormat
                val content = withContext(Dispatchers.Default) {
                    val groups = merged.channels.map { it.group ?: "Ungrouped" }.toSet()
                    PlaylistTextFormatter.format(merged, groups, format)
                }

                val saved = if (folderUriString.isNullOrBlank()) {
                    outputSaver.saveToDownloads(
                        sourceUrl = "alibaba",
                        format = format,
                        content = content,
                        maybeEndDate = merged.endDate
                    )
                } else {
                    outputSaver.saveToFolder(
                        folderUriString = folderUriString,
                        sourceUrl = "alibaba",
                        format = format,
                        content = content,
                        maybeEndDate = merged.endDate
                    )
                }

                savedNames += saved.displayName
                savedUris += saved.uriString
            }

            val report = buildString {
                append("Bitti. Çalışan: ")
                append(working.size)
                append(" | Çalışmayan: ")
                append(failing.size)
                if (!folderUriString.isNullOrBlank()) {
                    append(" | Klasör: ")
                    append(folderUriString)
                } else {
                    append(" | Klasör: Download/IPTV")
                }
            }

            val saved = savedNames.zip(savedUris).map { (n, u) -> SavedFileItem(n, u) }
            val outputPreview = if (outputDelivery == OutputDelivery.LINKS) {
                working.joinToString(separator = "\n")
            } else {
                null
            }

            _state.update {
                it.copy(
                    loading = false,
                    progressPercent = 100,
                    progressStep = null,
                    etaSeconds = null,
                    reportText = report,
                    workingUrls = working,
                    selectedWorkingUrls = if (outputDelivery == OutputDelivery.LINKS) working.toSet() else it.selectedWorkingUrls,
                    failingUrls = failing,
                    savedFiles = saved,
                    lastRunSaved = true,
                    outputPreview = outputPreview,
                    outputFolderUriString = if (outputDelivery == OutputDelivery.LINKS) null else it.outputFolderUriString
                )
            }
            
            // Test başarıyla tamamlandı - kaydedilmiş state'i temizle
            clearSavedState()

            // Stop services
            StreamTestService.stop(appContext)
            
            // Foreground Service durdur
            try {
                val serviceIntent = android.content.Intent(appContext, Class.forName("com.alibaba.service.AutoTestForegroundService"))
                serviceIntent.action = "com.alibaba.action.STOP_AUTO_TEST"
                appContext.startService(serviceIntent)
            } catch (e: Exception) {
                // Service bulunamazsa devam et
            }
        }
    }

    private fun filterPlaylistByCountries(playlist: Playlist, countries: Set<String>): Playlist {
        val channels = playlist.channels.filter { c ->
            val group = c.group
            if (isAdultGroup(group)) return@filter false
            isGroupInCountries(group, countries)
        }
        return Playlist(channels = channels, endDate = playlist.endDate)
    }

    private suspend fun runStreamTestDetailed(
        playlist: Playlist,
        turboMode: Boolean = false,
        onTestUpdate: (tested: Int, total: Int) -> Unit
    ): Triple<Boolean, Int, Int> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()

        val candidates = playlist.channels
            .asSequence()
            .filter { c ->
                if (!settings.skipAdultGroups) return@filter true
                !isAdultGroup(c.group)
            }
            .map { it.url }
            .distinct()
            .toList()

        if (candidates.isEmpty()) return@withContext Triple(false, 0, 0)

        // TURBO MOD: Daha az örnek, daha kısa timeout
        val maxSample = if (turboMode) {
            minOf(3, settings.streamTestSampleSize) // Turbo: max 3 kanal test et
        } else {
            settings.streamTestSampleSize.coerceIn(1, 50)
        }
        
        val turboTimeout = if (turboMode) {
            minOf(3000L, settings.streamTestTimeoutMs) // Turbo: max 3 saniye timeout
        } else {
            settings.streamTestTimeoutMs
        }

        val pool = if (settings.shuffleCandidates) candidates.shuffled(Random(System.currentTimeMillis())) else candidates
        val sample = if (pool.size <= maxSample) pool else pool.take(maxSample)

        val total = sample.size
        var tested = 0
        var okCount = 0
        
        // TURBO MOD: 1 çalışan kanal yeterli
        val minRequired = if (turboMode) 1 else settings.minPlayableStreamsToPass
        
        for (url in sample) {
            yield() // Prevent ANR
            tested += 1
            onTestUpdate(tested, total)
            if (streamTester.isPlayable(url, turboTimeout)) {
                okCount += 1
                if (okCount >= minRequired) {
                    return@withContext Triple(true, tested, total)
                }
            }

            // TURBO MOD: Bekleme yok
            if (!turboMode) {
                if (settings.delayBetweenStreamTestsMs > 0) {
                    delay(settings.delayBetweenStreamTestsMs)
                } else {
                    delay(50) // Small delay to prevent ANR even when user sets 0
                }
            }
        }

        Triple(false, tested, total)
    }

    private fun mergeWithBackupNames(
        channels: List<Channel>,
        usedGroupNames: MutableMap<String, Int>,
        renameSamples: MutableList<String>
    ): List<Channel> {
        if (channels.isEmpty()) return emptyList()

        val groupMapping = LinkedHashMap<String, String>()
        for (group in channels.asSequence().map { it.group ?: "Ungrouped" }.distinct()) {
            val currentIndex = usedGroupNames[group]
            if (currentIndex == null) {
                usedGroupNames[group] = 0
                groupMapping[group] = group
            } else {
                val next = currentIndex + 1
                usedGroupNames[group] = next
                val newGroup = "${group} Yedek ${next}"
                if (renameSamples.size < 25) {
                    renameSamples += "- ${group} -> ${newGroup}"
                }
                groupMapping[group] = newGroup
            }
        }

        return channels.map { c ->
            val originalGroup = c.group ?: "Ungrouped"
            val mapped = groupMapping[originalGroup] ?: originalGroup
            if (mapped == originalGroup) c else c.copy(group = mapped)
        }
    }

    private fun extractServerFromUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: ""
            val port = if (uri.port > 0) uri.port else 80
            "$host:$port"
        } catch (e: Exception) {
            // Fallback: basit regex ile sunucu çıkar
            val regex = Regex("https?://([^/:]+)(:\\d+)?")
            val match = regex.find(url)
            match?.groupValues?.get(1) ?: url.take(50)
        }
    }
    
    // Akıllı sunucu erişilebilirlik kontrolü (HEAD request - çok hızlı)
    private suspend fun quickServerCheck(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return@withContext false
            val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
            
            // Socket ile hızlı bağlantı testi (3 saniye timeout)
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 3000)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun setProgress(percent: Int, step: String?) {
        val now = SystemClock.elapsedRealtime()
        
        // Calculate ETA based on actual measured completion times
        val etaSeconds = if (completedUrlDurationsMs.isNotEmpty()) {
            // Use average time per URL from completed URLs
            val avgMs = completedUrlDurationsMs.average().toLong().coerceAtLeast(1)
            val total = state.value.extractedUrls.size
            val completed = completedUrlDurationsMs.size
            val remaining = (total - completed).coerceAtLeast(0)
            
            if (remaining > 0) {
                // Calculate based on actual average time per URL
                val etaMs = avgMs * remaining
                (etaMs / 1000L).coerceAtMost(60 * 60 * 2) // Max 2 hours
            } else {
                0L
            }
        } else {
            // Fallback: estimate based on elapsed time and progress percentage
            val start = progressStartMs
            if (start != null && percent in 1..99) {
                val elapsedMs = (now - start).coerceAtLeast(1)
                val estimatedTotalMs = (elapsedMs * 100) / percent.coerceAtLeast(1)
                val remainingMs = estimatedTotalMs - elapsedMs
                (remainingMs / 1000L).coerceIn(0, 60 * 60 * 2) // Max 2 hours
            } else {
                null
            }
        }

        _state.update {
            it.copy(
                progressPercent = percent,
                progressStep = step,
                etaSeconds = etaSeconds
            )
        }

        // Update notification
        try {
            val intent = android.content.Intent(appContext, StreamTestService::class.java)
            intent.putExtra("progress_step", step ?: "")
            intent.putExtra("progress_percent", percent)
            intent.putExtra("progress_eta", etaSeconds?.toInt() ?: -1)
            appContext.startService(intent)
        } catch (_: Exception) {
            // Service might not be running
        }
    }

    override fun onCleared() {
        super.onCleared()
        StreamTestService.stop(appContext)
    }
}
