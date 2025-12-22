package com.alibaba.data.service

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.alibaba.domain.model.OutputFormat
import com.alibaba.domain.service.OutputSaver
import com.alibaba.domain.service.SavedOutput
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class DownloadsOutputSaver @Inject constructor(
    @ApplicationContext private val context: Context
) : OutputSaver {

    override suspend fun saveToDownloads(
        sourceUrl: String,
        format: OutputFormat,
        content: String,
        maybeEndDate: String?
    ): SavedOutput = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val startDate = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"))
        val sourceName = deriveSourceName(sourceUrl)
        val ext = when (format) {
            OutputFormat.M3U -> "m3u"
            OutputFormat.M3U8 -> "m3u8"
            OutputFormat.M3U8PLUS -> "m3u8plus"
        }

        val version = nextVersion(resolver, startDate, sourceName, ext, maybeEndDate)
        val base = buildString {
            append(startDate)
            append('_')
            append(sourceName)
            append("_v")
            append(version)
            if (!maybeEndDate.isNullOrBlank()) {
                append('_')
                append(maybeEndDate)
            }
        }
        val displayName = "$base.$ext"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/IPTV/")
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Insert failed")

        resolver.openOutputStream(uri, "w")?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Output stream failed")

        SavedOutput(displayName = displayName, uriString = uri.toString())
    }

    override suspend fun saveToFolder(
        folderUriString: String,
        sourceUrl: String,
        format: OutputFormat,
        content: String,
        maybeEndDate: String?
    ): SavedOutput = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val startDate = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"))
        val sourceName = deriveSourceName(sourceUrl)
        val ext = when (format) {
            OutputFormat.M3U -> "m3u"
            OutputFormat.M3U8 -> "m3u8"
            OutputFormat.M3U8PLUS -> "m3u8plus"
        }

        val folderUri = Uri.parse(folderUriString)
        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: throw IllegalStateException("Folder uri not accessible")

        val basePrefix = buildString {
            append(startDate)
            append('_')
            append(sourceName)
            append("_v")
        }

        var version = 1
        while (true) {
            val base = buildString {
                append(basePrefix)
                append(version)
                if (!maybeEndDate.isNullOrBlank()) {
                    append('_')
                    append(maybeEndDate)
                }
            }
            val displayName = "$base.$ext"

            val exists = folder.listFiles().any { it.name == displayName }
            if (!exists) {
                val created = folder.createFile("application/octet-stream", displayName)
                    ?: throw IllegalStateException("Create file failed")
                val outUri = created.uri
                resolver.openOutputStream(outUri, "w")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Output stream failed")
                return@withContext SavedOutput(displayName = displayName, uriString = outUri.toString())
            }

            version += 1
        }
    }

    private fun deriveSourceName(url: String): String {
        return try {
            val host = Uri.parse(url).host.orEmpty()
            val cleaned = host
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
            if (cleaned.isBlank()) "alibaba" else cleaned
        } catch (_: Throwable) {
            "alibaba"
        }
    }

    private fun nextVersion(
        resolver: ContentResolver,
        startDate: String,
        sourceName: String,
        ext: String,
        maybeEndDate: String?
    ): Int {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME
        )

        val prefix = buildString {
            append(startDate)
            append('_')
            append(sourceName)
            append("_v")
        }

        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf("Download/IPTV/")

        var maxVersion = 0
        resolver.query(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                if (!name.startsWith(prefix)) continue
                if (!name.endsWith(".$ext")) continue
                if (!maybeEndDate.isNullOrBlank() && !name.contains("_${maybeEndDate}.$ext")) {
                    continue
                }
                val vPart = name.removePrefix(prefix)
                val vNumber = vPart.takeWhile { it.isDigit() }.toIntOrNull() ?: continue
                if (vNumber > maxVersion) maxVersion = vNumber
            }
        }

        return maxVersion + 1
    }
}
