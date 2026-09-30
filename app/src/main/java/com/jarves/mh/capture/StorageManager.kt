package com.jarves.mh.capture

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CaptureRecord(
    val file: File,
    val title: String,
    val timestampMs: Long,
    val formattedDate: String,
    val sizeBytes: Long,
    val previewSnippet: String,
    val elementCount: Int
)

object StorageManager {

    private val _recordsFlow = MutableStateFlow<List<CaptureRecord>>(emptyList())
    val recordsFlow: StateFlow<List<CaptureRecord>> = _recordsFlow.asStateFlow()

    private val FILE_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val DISPLAY_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun getCapturesDir(context: Context): File {
        val publicDir = File(Environment.getExternalStorageDirectory(), "ScreenSpoof/captures")
        if (publicDir.exists() || publicDir.mkdirs()) {
            return publicDir
        }
        val appDir = File(context.getExternalFilesDir(null), "captures")
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        return appDir
    }

    fun saveCapture(context: Context, content: String, date: Date = Date()): File {
        val dir = getCapturesDir(context)
        val timestamp = FILE_DATE_FORMAT.format(date)
        val file = File(dir, "capture_$timestamp.txt")
        file.writeText(content, Charsets.UTF_8)
        refresh(context)
        return file
    }

    fun refresh(context: Context) {
        val dir = getCapturesDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: emptyArray()
        val list = files.map { file ->
            val text = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
            val count = extractElementCount(text)
            val snippet = extractSnippet(text)
            CaptureRecord(
                file = file,
                title = file.nameWithoutExtension,
                timestampMs = file.lastModified(),
                formattedDate = DISPLAY_DATE_FORMAT.format(Date(file.lastModified())),
                sizeBytes = file.length(),
                previewSnippet = snippet,
                elementCount = count
            )
        }.sortedByDescending { it.timestampMs }

        _recordsFlow.value = list
    }

    fun delete(context: Context, file: File): Boolean {
        val deleted = file.delete()
        refresh(context)
        return deleted
    }

    fun clearAll(context: Context): Int {
        val dir = getCapturesDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: emptyArray()
        var count = 0
        for (f in files) {
            if (f.delete()) count++
        }
        refresh(context)
        return count
    }

    private fun extractElementCount(content: String): Int {
        val regex = Regex("""Total Interactive Elements:\s*(\d+)""")
        val match = regex.find(content)
        if (match != null) {
            return match.groupValues[1].toIntOrNull() ?: 0
        }
        return content.lines().count {
            it.trimStart().startsWith("[BUTTON]") || it.trimStart().startsWith("[INPUT]") ||
            it.trimStart().startsWith("[TOGGLE]") || it.trimStart().startsWith("[IMAGE/ICON]")
        }
    }

    private fun extractSnippet(content: String): String {
        val summaryIndex = content.indexOf("[UI BRIEF SUMMARY]")
        if (summaryIndex != -1) {
            val after = content.substring(summaryIndex + "[UI BRIEF SUMMARY]".length).trimStart()
            val nextHeader = after.indexOf("\n[")
            val summary = if (nextHeader != -1) after.substring(0, nextHeader).trim() else after.take(200).trim()
            if (summary.isNotBlank()) return summary.replace("\n", " ").take(160)
        }
        return content.lines().firstOrNull { it.isNotBlank() && !it.startsWith("=") }?.take(140) ?: "CLI Screen Spoof File"
    }
}
