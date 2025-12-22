package com.alibaba.domain.service

import com.alibaba.domain.model.OutputFormat

data class SavedOutput(
    val displayName: String,
    val uriString: String
)

interface OutputSaver {
    suspend fun saveToDownloads(
        sourceUrl: String,
        format: OutputFormat,
        content: String,
        maybeEndDate: String?
    ): SavedOutput

    suspend fun saveToFolder(
        folderUriString: String,
        sourceUrl: String,
        format: OutputFormat,
        content: String,
        maybeEndDate: String?
    ): SavedOutput
}
