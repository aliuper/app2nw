package com.alibaba.feature.manual

import com.alibaba.domain.model.OutputFormat

data class GroupItem(
    val name: String,
    val channelCount: Int,
    val selected: Boolean
)

data class ManualUiState(
    val url: String = "",
    val loading: Boolean = false,
    val progressPercent: Int = 0,
    val progressStep: String? = null,
    val etaSeconds: Long? = null,
    val errorMessage: String? = null,
    val analysisReport: String? = null,
    val groups: List<GroupItem> = emptyList(),
    val autoDetectFormat: Boolean = true,
    val outputFormat: OutputFormat = OutputFormat.M3U8,
    val outputText: String? = null,
    val streamTestPassed: Boolean? = null,
    val savedDisplayName: String? = null,
    val savedUriString: String? = null
)
