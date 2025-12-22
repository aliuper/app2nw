package com.alibaba.feature.auto

import com.alibaba.domain.model.OutputFormat

data class UrlItem(
    val url: String,
    val status: String? = null,
    val success: Boolean? = null,
    val testedStreams: Int = 0
)

data class SavedFileItem(
    val displayName: String,
    val uriString: String
)

data class AutoUiState(
    val step: Int = 0,
    val inputText: String = "",
    val extractedUrls: List<UrlItem> = emptyList(),
    val selectedCountries: Set<String> = setOf("TR"),
    val mergeIntoSingle: Boolean = true,
    val autoDetectFormat: Boolean = true,
    val outputFormat: OutputFormat = OutputFormat.M3U8,
    val mergeRenameWarning: String? = null,
    val outputFolderUriString: String? = null,
    val backgroundWorkId: String? = null,
    val loading: Boolean = false,
    val progressPercent: Int = 0,
    val progressStep: String? = null,
    val etaSeconds: Long? = null,
    val errorMessage: String? = null,
    val reportText: String? = null,
    val workingUrls: List<String> = emptyList(),
    val failingUrls: List<String> = emptyList(),
    val lastRunSaved: Boolean = false,
    val outputPreview: String? = null,
    val savedFiles: List<SavedFileItem> = emptyList()
)
