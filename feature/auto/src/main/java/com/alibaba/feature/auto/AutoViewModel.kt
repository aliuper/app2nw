package com.alibaba.feature.auto

import android.os.SystemClock
import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.core.common.PlaylistTextFormatter
import com.alibaba.core.common.isGroupInCountries
import com.alibaba.core.common.extractIptvUrls
import com.alibaba.core.common.isAdultGroup
import com.alibaba.domain.model.OutputFormat
import com.alibaba.domain.model.Playlist
import com.alibaba.domain.model.Channel
import com.alibaba.domain.repo.PlaylistRepository
import com.alibaba.domain.service.OutputSaver
import com.alibaba.domain.service.StreamTester
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import java.util.UUID
import kotlin.random.Random
import javax.inject.Inject

@HiltViewModel
class AutoViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val playlistRepository: PlaylistRepository,
    private val streamTester: StreamTester,
    private val outputSaver: OutputSaver
) : ViewModel() {

    private val _state = MutableStateFlow(AutoUiState())
    val state: StateFlow<AutoUiState> = _state

    private var progressStartMs: Long? = null

    fun onInputChange(value: String) {
        _state.update { it.copy(inputText = value, errorMessage = null) }
    }

    fun nextStep() {
        _state.update { s -> s.copy(step = (s.step + 1).coerceAtMost(3), errorMessage = null) }
    }

    fun prevStep() {
        _state.update { s -> s.copy(step = (s.step - 1).coerceAtLeast(0), errorMessage = null) }
    }

    fun extract() {
        val urls = extractIptvUrls(state.value.inputText)
        _state.update {
            it.copy(
                extractedUrls = urls.map { u -> UrlItem(url = u) },
                errorMessage = if (urls.isEmpty()) "Link bulunamadı" else null,
                outputPreview = null,
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

    fun setOutputFolder(uriString: String?) {
        _state.update { it.copy(outputFolderUriString = uriString) }
    }

    fun run() {
        val urls = state.value.extractedUrls.map { it.url }
        if (urls.isEmpty()) {
            _state.update { it.copy(errorMessage = "Önce linkleri ayıkla") }
            return
        }

        val countries = state.value.selectedCountries
        if (countries.isEmpty()) {
            _state.update { it.copy(errorMessage = "En az 1 ülke seç") }
            return
        }

        viewModelScope.launch {
            progressStartMs = SystemClock.elapsedRealtime()
            val id = UUID.randomUUID().toString()

            _state.update { s ->
                s.copy(
                    loading = true,
                    backgroundWorkId = id,
                    progressPercent = 0,
                    progressStep = "Arka plan işi başlatılıyor",
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

            val request = OneTimeWorkRequestBuilder<AutoRunWorker>()
                .addTag("auto_run")
                .setInputData(
                    Data.Builder()
                        .putStringArray(AutoRunWorker.KEY_URLS, urls.toTypedArray())
                        .putStringArray(AutoRunWorker.KEY_COUNTRIES, countries.toTypedArray())
                        .putBoolean(AutoRunWorker.KEY_MERGE_INTO_SINGLE, state.value.mergeIntoSingle)
                        .putString(AutoRunWorker.KEY_FOLDER_URI, state.value.outputFolderUriString)
                        .putBoolean(AutoRunWorker.KEY_AUTO_DETECT_FORMAT, state.value.autoDetectFormat)
                        .putInt(AutoRunWorker.KEY_OUTPUT_FORMAT, state.value.outputFormat.ordinal)
                        .build()
                )
                .build()

            val wm = WorkManager.getInstance(appContext)
            wm.enqueueUniqueWork("auto_run_$id", ExistingWorkPolicy.REPLACE, request)

            callbackFlow {
                val liveData = wm.getWorkInfoByIdLiveData(request.id)
                val observer = Observer<WorkInfo?> { info ->
                    trySend(info)
                }

                liveData.observeForever(observer)
                awaitClose { liveData.removeObserver(observer) }
            }.collectLatest { info ->
                if (info == null) return@collectLatest

                val progress = info.progress
                val output = info.outputData

                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                        val p = info.progress
                        val percent = p.getInt(AutoRunWorker.P_PERCENT, 0)
                        val step = p.getString(AutoRunWorker.P_STEP)
                        val index = p.getInt(AutoRunWorker.P_INDEX, -1)
                        val status = p.getString(AutoRunWorker.P_STATUS)
                        val tested = p.getInt(AutoRunWorker.P_TESTED, 0)
                        val hasSuccess = p.getBoolean(AutoRunWorker.P_HAS_SUCCESS, false)
                        val success = if (hasSuccess) p.getBoolean(AutoRunWorker.P_SUCCESS, false) else null

                        _state.update { s ->
                            val items = s.extractedUrls.toMutableList()
                            if (index in items.indices) {
                                val old = items[index]
                                items[index] = old.copy(
                                    status = status ?: old.status,
                                    success = success,
                                    testedStreams = tested
                                )
                            }
                            s.copy(
                                loading = true,
                                progressPercent = percent,
                                progressStep = step,
                                extractedUrls = items.toList()
                            )
                        }
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        val out = info.outputData
                        val report = out.getString(AutoRunWorker.KEY_REPORT)
                        val workingUrls = out.getStringArray(AutoRunWorker.KEY_WORKING_URLS)?.toList().orEmpty()
                        val failingUrls = out.getStringArray(AutoRunWorker.KEY_FAILING_URLS)?.toList().orEmpty()
                        val savedNames = out.getStringArray(AutoRunWorker.KEY_SAVED_NAMES)?.toList().orEmpty()
                        val savedUris = out.getStringArray(AutoRunWorker.KEY_SAVED_URIS)?.toList().orEmpty()
                        val saved = savedNames.zip(savedUris).map { (n, u) -> SavedFileItem(n, u) }

                        _state.update { s ->
                            s.copy(
                                loading = false,
                                progressPercent = 100,
                                progressStep = null,
                                etaSeconds = null,
                                reportText = report,
                                workingUrls = workingUrls,
                                failingUrls = failingUrls,
                                savedFiles = saved,
                                lastRunSaved = true
                            )
                        }
                    }

                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        val error = info.outputData.getString(AutoRunWorker.KEY_ERROR)
                        _state.update { s ->
                            s.copy(
                                loading = false,
                                progressPercent = 0,
                                progressStep = null,
                                etaSeconds = null,
                                errorMessage = error ?: "İş iptal edildi veya başarısız",
                                lastRunSaved = false
                            )
                        }
                    }

                    else -> Unit
                }
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
        onTestUpdate: (tested: Int, total: Int) -> Unit
    ): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        val candidates = playlist.channels
            .asSequence()
            .map { it.url }
            .distinct()
            .toList()

        if (candidates.isEmpty()) return@withContext false to 0

        val max = 10
        val sample = if (candidates.size <= max) {
            candidates
        } else {
            candidates.shuffled(Random(System.currentTimeMillis())).take(max)
        }

        val total = sample.size
        var tested = 0
        for (url in sample) {
            tested += 1
            onTestUpdate(tested, total)
            if (streamTester.isPlayable(url)) return@withContext true to tested
        }

        false to tested
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

    private fun setProgress(percent: Int, step: String?) {
        val start = progressStartMs
        val now = SystemClock.elapsedRealtime()
        val etaSeconds = if (start != null && percent in 1..99) {
            val elapsedMs = (now - start).coerceAtLeast(1)
            val remainingMs = (elapsedMs * (100 - percent)) / percent
            (remainingMs / 1000L).coerceAtMost(60 * 60)
        } else {
            null
        }

        _state.update {
            it.copy(
                progressPercent = percent,
                progressStep = step,
                etaSeconds = etaSeconds
            )
        }
    }
}
