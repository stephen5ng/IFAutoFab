package com.ifautofab

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.*
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Manages downloading Sherpa-ONNX Kokoro TTS model files (125 MB compressed).
 * Uses Android DownloadManager for reliable background downloads.
 *
 * Downloads: kokoro-int8-multi-lang-v1_0.tar.bz2 from k2-fsa/sherpa-onnx releases
 * Extracts to: context.filesDir/tts/sherpa/
 */
class SherpaModelDownloader(private val context: Context) {

    sealed class DownloadState {
        object NotStarted : DownloadState()
        data class InProgress(val progress: Int, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
        object Extracting : DownloadState()
        object Completed : DownloadState()
        data class Failed(val error: String) : DownloadState()
    }

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private var downloadId: Long? = null
    private var isExtracting = false

    companion object {
        // Official Sherpa-ONNX releases
        private const val BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
        const val ARCHIVE_URL = "$BASE_URL/kokoro-int8-multi-lang-v1_0.tar.bz2"

        // Expected file sizes
        private const val ARCHIVE_SIZE_BYTES = 125L * 1024 * 1024 // 125 MB compressed
        private const val EXTRACTED_SIZE_BYTES = 280L * 1024 * 1024 // ~280 MB extracted

        // Model directory and files
        private const val MODEL_DIR = "tts/sherpa"
        private const val ARCHIVE_NAME = "kokoro-int8-multi-lang-v1_0.tar.bz2"
        private const val EXTRACTED_PREFIX = "kokoro-int8-multi-lang-v1_0/"

        // Required files (relative to model directory)
        val REQUIRED_FILES = listOf(
            "model.int8.onnx",      // 76 MB - ONNX model
            "voices.bin",           // 27 MB - Voice embeddings
            "tokens.txt",           // Token vocabulary
            "espeak-ng-data/",      // Phoneme data directory (398 files)
            "lexicon-us-en.txt"     // English pronunciation lexicon
        )
    }

    /**
     * Start downloading and extracting model files.
     */
    fun startDownload(
        onProgress: (Int) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        // Check available storage (add 50 MB buffer)
        val requiredBytes = EXTRACTED_SIZE_BYTES + (50 * 1024 * 1024)
        if (context.filesDir.usableSpace < requiredBytes) {
            onComplete(false)
            return
        }

        // Create model directory
        val modelDir = File(context.filesDir, MODEL_DIR)
        modelDir.mkdirs()

        // Download archive
        val archiveFile = File(context.filesDir, "downloads/$ARCHIVE_NAME")
        archiveFile.parentFile?.mkdirs()

        val request = DownloadManager.Request(Uri.parse(ARCHIVE_URL)).apply {
            setDestinationUri(android.net.Uri.fromFile(archiveFile))
            setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            setTitle("Sherpa-ONNX Kokoro TTS Model")
            setDescription("Downloading speech model (125 MB)")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        }
        downloadId = downloadManager.enqueue(request)

        // Monitor download and extract
        CoroutineScope(Dispatchers.IO).launch {
            monitorDownloadAndExtract(archiveFile, onProgress, onComplete)
        }
    }

    private suspend fun monitorDownloadAndExtract(
        archiveFile: File,
        onProgress: (Int) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        // Download phase (0-90%)
        while (downloadId != null && !isExtracting) {
            delay(500)

            val progress = getDownloadProgress(downloadId!!)
            if (progress != null) {
                // Map 0-100% download to 0-90% overall progress
                val overallProgress = (progress * 0.9).toInt()
                onProgress(overallProgress)

                if (progress >= 100) {
                    downloadId = null
                    break
                }
            }
        }

        // Extraction phase (90-100%)
        isExtracting = true
        onProgress(90)

        try {
            extractArchive(archiveFile, File(context.filesDir, MODEL_DIR))
            onProgress(100)

            // Verify files
            val success = verifyRequiredFiles()
            onComplete(success)

            // Clean up archive
            archiveFile.delete()
        } catch (e: Exception) {
            onComplete(false)
        } finally {
            isExtracting = false
        }
    }

    private fun extractArchive(archiveFile: File, targetDir: File) {
        // Extract tar.bz2
        FileInputStream(archiveFile).use { fis ->
            BZip2CompressorInputStream(fis).use { bzis ->
                TarArchiveInputStream(bzis).use { taris ->
                    var entry = taris.nextTarEntry
                    while (entry != null) {
                        val name = entry.name

                        // Skip directory prefix
                        if (name.startsWith(EXTRACTED_PREFIX)) {
                            val relativeName = name.substring(EXTRACTED_PREFIX.length)
                            val outputFile = File(targetDir, relativeName)

                            if (entry.isFile) {
                                // Create parent directories
                                outputFile.parentFile?.mkdirs()

                                // Extract file
                                FileOutputStream(outputFile).use { fos ->
                                    taris.copyTo(fos)
                                }
                            }
                        }
                        entry = taris.nextTarEntry
                    }
                }
            }
        }
    }

    private fun getDownloadProgress(downloadId: Long): Int? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor: Cursor? = downloadManager.query(query)

        cursor?.use {
            if (it.moveToFirst()) {
                val bytesDownloadedIndex = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalBytesIndex = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                if (bytesDownloadedIndex >= 0 && totalBytesIndex >= 0) {
                    val downloaded = it.getLong(bytesDownloadedIndex)
                    val total = it.getLong(totalBytesIndex)
                    return if (total > 0) ((downloaded * 100) / total).toInt() else null
                }
            }
        }
        return null
    }

    private fun verifyRequiredFiles(): Boolean {
        val modelDir = File(context.filesDir, MODEL_DIR)

        // Check model file
        val modelFile = File(modelDir, "model.int8.onnx")
        if (!modelFile.exists() || modelFile.length() < 70 * 1024 * 1024) return false

        // Check voices file
        val voicesFile = File(modelDir, "voices.bin")
        if (!voicesFile.exists() || voicesFile.length() < 25 * 1024 * 1024) return false

        // Check tokens file
        val tokensFile = File(modelDir, "tokens.txt")
        if (!tokensFile.exists()) return false

        // Check espeak-ng-data directory
        val espeakDir = File(modelDir, "espeak-ng-data")
        if (!espeakDir.exists() || !espeakDir.isDirectory) return false

        // Check lexicon file
        val lexiconFile = File(modelDir, "lexicon-us-en.txt")
        if (!lexiconFile.exists()) return false

        return true
    }

    /**
     * Cancel active download.
     */
    fun cancelDownload() {
        downloadId?.let { downloadManager.remove(it) }
        downloadId = null
    }

    /**
     * Check if model files are already downloaded and extracted.
     */
    fun isModelAvailable(): Boolean = verifyRequiredFiles()

    /**
     * Get total size of downloaded model files in bytes.
     */
    fun getModelSize(): Long {
        val modelDir = File(context.filesDir, MODEL_DIR)
        if (!modelDir.exists()) return 0L

        return modelDir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }

    /**
     * Delete all downloaded model files to free up space.
     */
    fun deleteModels(): Boolean {
        return try {
            val modelDir = File(context.filesDir, MODEL_DIR)
            modelDir.deleteRecursively()
            true
        } catch (e: Exception) {
            false
        }
    }
}
